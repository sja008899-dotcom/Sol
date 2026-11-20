package com.example.sol.brain

import com.example.sol.text.ColloquialLexicon

enum class ConfidenceLevel(val labelFa: String) {
    HIGH("اطمینان بالا"),
    MEDIUM("نیاز به شفاف‌سازی"),
    LOW("اطمینان پایین"),
}

enum class NluSource {
    RULE,
    STATISTICAL,
    HYBRID,
}

data class ClarifyIntentOption(
    val action: SolAction,
    val labelFa: String,
    val score: Float,
    val command: ParsedCommand,
)

sealed interface NluDecision {
    val confidence: ConfidenceLevel
    val topScore: Float

    data class Execute(
        val command: ParsedCommand,
        override val confidence: ConfidenceLevel,
        val source: NluSource,
        override val topScore: Float,
    ) : NluDecision

    data class ClarifyTopTwo(
        val rawText: String,
        val options: List<ClarifyIntentOption>,
        override val topScore: Float,
        override val confidence: ConfidenceLevel = ConfidenceLevel.MEDIUM,
    ) : NluDecision

    data class LowConfidence(
        val rawText: String,
        val normalizedText: String,
        override val topScore: Float,
        override val confidence: ConfidenceLevel = ConfidenceLevel.LOW,
    ) : NluDecision
}

class HybridNluEngine(
    private val intentParser: IntentParser,
    private val statisticalClassifier: StatisticalIntentClassifier,
    private val extractor: EntityExtractor = EntityExtractor(),
    private val lexicon: ColloquialLexicon = ColloquialLexicon.loadDefault(),
    private val config: NluConfig = NluConfig(),
) {

    suspend fun evaluate(raw: String): NluDecision {
        statisticalClassifier.ensureInitialized()
        return evaluateSync(raw)
    }

    fun evaluateSync(raw: String): NluDecision {
        val ruleCandidates = intentParser.parseRanked(raw)
        val statAll = statisticalClassifier.classify(raw, topK = 5)
        val outOfScopeStat = statAll.firstOrNull { it.action == SolAction.UNKNOWN }?.score ?: 0f
        val statCommands = statAll.filter { it.action != SolAction.UNKNOWN }

        val topRule = ruleCandidates.firstOrNull()
        val secondRule = ruleCandidates.getOrNull(1)
        val topStat = statCommands.firstOrNull()
        val secondStat = statCommands.getOrNull(1)

        // 1. Check exact user-taught high-similarity statistical match overriding weak/missing rule
        if (topRule != null && topRule.score >= config.ruleConfidenceHigh) {
            // Two conflicting rule matchers virtually tied? Clarify only when close & stat supports secondRule.
            if (secondRule != null &&
                secondRule.action != topRule.action &&
                (topRule.score - secondRule.score) < 0.04f &&
                topStat?.action == secondRule.action &&
                topStat.score >= config.highScoreThreshold
            ) {
                return NluDecision.ClarifyTopTwo(
                    rawText = raw,
                    options = listOf(
                        ClarifyIntentOption(topRule.action, actionLabelFa(topRule.action), topRule.score, topRule.command),
                        ClarifyIntentOption(secondRule.action, actionLabelFa(secondRule.action), secondRule.score, secondRule.command),
                    ),
                    topScore = topRule.score,
                )
            }
            val src = if (topStat?.action == topRule.action) NluSource.HYBRID else NluSource.RULE
            return NluDecision.Execute(
                command = topRule.command.copy(confidence = topRule.score),
                confidence = ConfidenceLevel.HIGH,
                source = src,
                topScore = topRule.score,
            )
        }

        // 2. Medium-confidence rule path: combine with statistical classifier
        if (topRule != null && topRule.score >= config.ruleConfidenceMedium) {
            if (topStat != null && topStat.action != topRule.action && topStat.score >= config.highScoreThreshold) {
                val statCmd = buildStatisticalCommand(topStat.action, raw, topStat.score)
                return NluDecision.ClarifyTopTwo(
                    rawText = raw,
                    options = listOf(
                        ClarifyIntentOption(topRule.action, actionLabelFa(topRule.action), topRule.score, topRule.command),
                        ClarifyIntentOption(topStat.action, actionLabelFa(topStat.action), topStat.score, statCmd),
                    ),
                    topScore = maxOf(topRule.score, topStat.score),
                )
            }
            val combinedScore = if (topStat?.action == topRule.action) {
                maxOf(topRule.score, (topRule.score + topStat.score) * 0.55f).coerceAtMost(0.98f)
            } else {
                topRule.score
            }
            return NluDecision.Execute(
                command = topRule.command.copy(confidence = combinedScore),
                confidence = ConfidenceLevel.HIGH,
                source = if (topStat?.action == topRule.action) NluSource.HYBRID else NluSource.RULE,
                topScore = combinedScore,
            )
        }

        // 3. Pure statistical rescue / ambiguity / out-of-scope gate when rules did not fire
        if (topStat == null || (outOfScopeStat >= topStat.score - 0.02f && topStat.score < config.highScoreThreshold)) {
            val ctx = MatchContext.create(raw, lexicon)
            return NluDecision.LowConfidence(
                rawText = raw,
                normalizedText = ctx.normalized,
                topScore = topStat?.score ?: 0f,
            )
        }

        val secondScore = maxOf(secondStat?.score ?: 0f, outOfScopeStat)
        val margin = topStat.score - secondScore

        if (topStat.score >= config.highScoreThreshold && margin >= config.highMarginThreshold) {
            val cmd = buildStatisticalCommand(topStat.action, raw, topStat.score)
            return NluDecision.Execute(
                command = cmd,
                confidence = ConfidenceLevel.HIGH,
                source = NluSource.STATISTICAL,
                topScore = topStat.score,
            )
        }

        if (topStat.score >= config.mediumScoreThreshold && statCommands.size >= 2) {
            val opt1Cmd = buildStatisticalCommand(topStat.action, raw, topStat.score)
            val opt2 = statCommands[1]
            val opt2Cmd = buildStatisticalCommand(opt2.action, raw, opt2.score)
            return NluDecision.ClarifyTopTwo(
                rawText = raw,
                options = listOf(
                    ClarifyIntentOption(topStat.action, actionLabelFa(topStat.action), topStat.score, opt1Cmd),
                    ClarifyIntentOption(opt2.action, actionLabelFa(opt2.action), opt2.score, opt2Cmd),
                ),
                topScore = topStat.score,
            )
        }

        val ctx = MatchContext.create(raw, lexicon)
        return NluDecision.LowConfidence(
            rawText = raw,
            normalizedText = ctx.normalized,
            topScore = topStat.score,
        )
    }

    fun buildStatisticalCommand(action: SolAction, raw: String, score: Float): ParsedCommand {
        // Prefer matcher slot extraction if any weaker candidate exists for this action
        val ruleCandidate = intentParser.parseRanked(raw).firstOrNull { it.action == action }
        if (ruleCandidate != null) {
            return ruleCandidate.command.copy(confidence = score, matchedRuleId = "HYBRID_${ruleCandidate.matcherId}")
        }
        return extractor.fillSlotsForAction(
            action = action,
            raw = raw,
            lexicon = lexicon,
            confidence = score,
            matchedRuleId = "STATISTICAL_${action.name}",
        )
    }

    companion object {
        fun actionLabelFa(action: SolAction): String = when (action) {
            SolAction.GREETING -> "احوال‌پرسی"
            SolAction.IDENTITY -> "معرفی Sol"
            SolAction.TIME_DATE -> "ساعت و تقویم شمسی"
            SolAction.HELP -> "راهنما و نمونه فرمان‌ها"
            SolAction.LEARN_ALIAS -> "آموزش مترادف جدید"
            SolAction.LEARN_FACT -> "ذخیرهٔ نکته در حافظه"
            SolAction.FORGET_MEMORY -> "حذف مورد از حافظه"
            SolAction.LIST_MEMORY -> "نمایش حافظهٔ شخصی"
            SolAction.SAVE_PERSON -> "ثبت شخص و شماره تماس"
            SolAction.SAVE_LOCATION -> "ذخیرهٔ مکان جدید"
            SolAction.SET_CURRENT_LOCATION -> "ثبت موقعیت فعلی"
            SolAction.GET_CURRENT_LOCATION -> "پرسش موقعیت فعلی"
            SolAction.CALL -> "تماس تلفنی"
            SolAction.SEND_SMS -> "ارسال پیامک"
            SolAction.MULTI_STEP_NOTIFY -> "اطلاع‌رسانی چندمرحله‌ای"
            SolAction.OPEN_APP -> "باز کردن برنامه"
            SolAction.OPEN_SETTINGS -> "باز کردن تنظیمات گوشی"
            SolAction.DEVICE_STATUS -> "وضعیت باتری و دستگاه"
            SolAction.ADJUST_VOLUME -> "تنظیم صدای رسانه"
            SolAction.RUN_ROUTINE -> "اجرای روتین روزانه"
            SolAction.SCHEDULE_REMINDER -> "تنظیم یادآور یا آلارم"
            SolAction.BACKGROUND_CONTROL -> "اجرای کار پس‌زمینه"
            SolAction.WEB_BOOK_LEARN_ROLE -> "دریافت کتاب یا صفحه از اینترنت"
            SolAction.TEACH_ROLE_VOICE -> "آموزش نقش جدید به Sol"
            SolAction.CONSULT_ROLE_BOOK -> "مشورت با نقش و کتاب‌ها"
            SolAction.SHOW_CAPABILITIES -> "نمایش ۲۰ قابلیت Sol"
            SolAction.UNKNOWN -> "گفت‌وگوی آزاد / پرسش عمومی"
        }

        fun resolveIntentFromUserText(text: String): SolAction? {
            val norm = com.example.sol.text.PersianTextNormalizer.normalizeForMatching(text)
            val directPairs = listOf(
                listOf("تماس", "زنگ", "تلفن") to SolAction.CALL,
                listOf("پیامک", "پیام", "اس ام اس") to SolAction.SEND_SMS,
                listOf("یاداور", "یادآور", "الارم", "آلارم") to SolAction.SCHEDULE_REMINDER,
                listOf("تقویم", "تاریخ", "ساعت", "چندم") to SolAction.TIME_DATE,
                listOf("باتری", "دستگاه", "وضعیت گوشی") to SolAction.DEVICE_STATUS,
                listOf("صدا", "ولوم") to SolAction.ADJUST_VOLUME,
                listOf("اپ", "برنامه") to SolAction.OPEN_APP,
                listOf("تنظیمات", "وایفای", "بلوتوث") to SolAction.OPEN_SETTINGS,
                listOf("فراموش", "حذف حافظه", "پاک کن") to SolAction.FORGET_MEMORY,
                listOf("حافظه", "فهرست حافظه") to SolAction.LIST_MEMORY,
                listOf("مکان فعلی", "کجام", "کجا هستم") to SolAction.SET_CURRENT_LOCATION,
                listOf("ذخیره مکان", "مکان جدید") to SolAction.SAVE_LOCATION,
                listOf("ذخیره شخص", "مخاطب جدید") to SolAction.SAVE_PERSON,
                listOf("کتاب", "ویکی", "اینترنت", "دانلود") to SolAction.WEB_BOOK_LEARN_ROLE,
                listOf("نقش", "شخصیت") to SolAction.TEACH_ROLE_VOICE,
                listOf("مشورت", "مشاوره", "طبق کتاب") to SolAction.CONSULT_ROLE_BOOK,
                listOf("روتین") to SolAction.RUN_ROUTINE,
                listOf("پس زمینه") to SolAction.BACKGROUND_CONTROL,
                listOf("قابلیت") to SolAction.SHOW_CAPABILITIES,
                listOf("راهنما", "کمک") to SolAction.HELP,
                listOf("مترادف", "هر وقت میگم") to SolAction.LEARN_ALIAS,
                listOf("نکته", "یادداشت") to SolAction.LEARN_FACT,
            )
            for ((keywords, action) in directPairs) {
                if (keywords.any { norm.contains(it) }) return action
            }
            return null
        }
    }
}
