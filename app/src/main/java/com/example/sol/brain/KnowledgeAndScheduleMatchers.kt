package com.example.sol.brain

import com.example.sol.calendar.JalaliCalendar
import com.example.sol.text.PersianNumbers
import com.example.sol.text.PersianTextNormalizer

internal class ShowCapabilitiesMatcher : IntentMatcher {
    override val id = "matcher_show_capabilities"
    override val action = SolAction.SHOW_CAPABILITIES
    override val priority = 100

    override fun match(ctx: MatchContext): MatchResult? {
        val hit = ctx.hasAnyPhrase(
            "20 قابلیت",
            "بیست قابلیت",
            "فهرست قابلیت",
            "فهرست امکانات",
            "لیست قابلیت",
            "لیست امکانات",
            "امکانات پس زمینه",
            "قابلیت های پس زمینه",
            "کل قابلیت",
            "همه قابلیت",
        ) || (ctx.hasAnyToken("فهرست", "لیست") && ctx.hasStem("قابلیت")) ||
            (ctx.tokens == listOf("قابلیت", "ها") || ctx.tokens == listOf("قابلیتها"))

        if (!hit) return null
        return MatchResult(
            score = 0.98f,
            evidence = listOf("capabilities_catalog_phrase"),
        ) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }
}

internal class WebBookLearnRoleMatcher : IntentMatcher {
    override val id = "matcher_web_book_learn_role"
    override val action = SolAction.WEB_BOOK_LEARN_ROLE
    override val priority = 95

    override fun match(ctx: MatchContext): MatchResult? {
        val hasUrl = URL_REGEX.containsMatchIn(ctx.cleaned)
        val hasBookWord = ctx.hasExactRawToken("کتاب") || ctx.hasToken("کتاب")
        val hasInternetWord = ctx.hasAnyToken(
            "اینترنت", "انلاین", "وب", "وبسایت", "سایت",
            "گوگل", "لینک", "مقاله", "ویکیپدیا", "نت",
        ) || hasUrl

        val hasFetchVerb = ctx.hasAnyToken(
            "دانلود", "پیدا", "بیاور", "بیار", "جستجو", "جستوجو",
            "بگیر", "بخوان", "بخون", "دریافت",
        ) || ctx.hasAnyPhrase("پیدا کن", "جستجو کن", "دانلود کن", "دریافت کن", "باز کن") ||
            (ctx.hasExactRawToken("برو") && (hasInternetWord || hasBookWord))

        val hasRoleOrVoiceLearn = ctx.hasAnyToken("نقش", "نقشت", "حافظه") ||
            ctx.hasAnyPhrase("توضیحات صوتی", "توضیح صوتی", "یاد بگیر", "اضافه کن")

        val isOpenSettingsNotDownload = ctx.hasAnyToken("وایفای", "wifi", "تنظیمات") &&
            !ctx.hasToken("دانلود") && !hasBookWord

        val isConsultOrForget = ctx.startsWithAnyPhrase("فراموش کن") ||
            ctx.hasAnyPhrase("طبق نقش", "بر اساس کتاب", "با توجه به کتاب")

        val isVoskModelNote = ctx.hasPhrase("مدل vosk")

        val isPureWebSearchPhrase = ctx.startsWithAnyPhrase(
            "جستجوی وب", "جست‌وجوی وب", "جستجو در وب", "جست‌وجو در وب",
            "سرچ وب", "داک داک گو", "جستجوی اینترنتی", "سرچ اینترنتی",
        ) || ctx.hasAnyPhrase("وب را بگرد", "جستجوی وب")

        val isDownloadRequest = ctx.hasToken("دانلود") && !isVoskModelNote
        val shouldTrigger = !isOpenSettingsNotDownload && !isConsultOrForget && !isPureWebSearchPhrase && (
            hasUrl ||
                isDownloadRequest ||
                (hasBookWord && (hasInternetWord || hasRoleOrVoiceLearn || hasFetchVerb)) ||
                (hasInternetWord && hasFetchVerb)
            )

        if (!shouldTrigger) return null

        val extractedQuery = extractBookOrWebQuery(ctx.cleaned).ifBlank { null }
        val roleHint = ROLE_HINT_REGEX.find(ctx.cleaned)?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { null }
        val bgId = if (ctx.hasPhrase("پس زمینه")) "bg_book_fetch_worker" else null

        val slots = buildMap<String, SlotValue> {
            extractedQuery?.let { put("book_query", SlotValue.Text(it)) }
            roleHint?.let { put("role_name", SlotValue.Text(it)) }
            bgId?.let { put("background_job", SlotValue.Text(it)) }
            put("has_url", SlotValue.Flag(hasUrl))
        }

        val score = when {
            hasUrl -> 0.99f
            hasBookWord && (hasInternetWord || hasRoleOrVoiceLearn) -> 0.97f
            ctx.hasToken("دانلود") || (hasInternetWord && hasFetchVerb) -> 0.94f
            else -> 0.90f
        }

        return MatchResult(
            score = score,
            slots = slots,
            evidence = listOfNotNull(
                if (hasUrl) "url" else null,
                if (hasBookWord) "book_token" else null,
                if (hasInternetWord) "internet_token" else null,
                if (hasFetchVerb) "fetch_verb" else null,
            ),
        ) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                bookQuery = extractedQuery,
                roleName = roleHint,
                backgroundJobId = bgId,
                requireConfirmation = true,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }

    private fun extractBookOrWebQuery(cleaned: String): String {
        URL_REGEX.find(cleaned)?.let { return it.groupValues[1].trim() }

        AFTER_BOOK_REGEX.find(cleaned)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() }?.let { return it }

        ABOUT_REGEX.find(cleaned)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() && !isGenericPlaceholder(it) }?.let { return it }

        val candidate = cleaned
            .replace(STRIP_GO_NET_REGEX, "")
            .replace(STRIP_FROM_NET_REGEX, "")
            .replace(STRIP_FILLERS_REGEX, "")
            .replace(STRIP_VERBS_REGEX, "")
            .replace(STRIP_TAIL_REGEX, "")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()

        return if (isGenericPlaceholder(candidate)) "" else candidate
    }

    private fun isGenericPlaceholder(txt: String): Boolean {
        val n = PersianTextNormalizer.normalizeForMatching(txt)
        return n.isBlank() || n in GENERIC_PLACEHOLDERS
    }

    companion object {
        private val URL_REGEX = Regex("(https?://\\S+)", RegexOption.IGNORE_CASE)
        private val ROLE_HINT_REGEX = Regex("(?:به عنوان|نقش)\\s+([آ-یa-zA-Z0-9\\s]{2,35})")
        private val AFTER_BOOK_REGEX = Regex("کتاب\\s+[«\"]?(.+?)[»\"]?(?:\\s+(?:را|رو)\\s+|\\s+(?:پیدا|دانلود|دریافت|بیار|بیاور|بخوان|بخون|وانمود|و\\s+با)|$)")
        private val ABOUT_REGEX = Regex("(?:درباره|در مورد|راجب|راجع به)\\s+(.+?)(?:\\s+(?:از اینترنت|تو اینترنت|در اینترنت|پیدا کن|دانلود کن|دریافت کن|بیار|بیاور|جستجو کن|جست‌وجو کن|سرچ کن|بگو|بخوان|بخون)|$)")
        private val STRIP_GO_NET_REGEX = Regex("(?:برو\\s+(?:به|تو|توی)?\\s*(?:اینترنت|وب|گوگل))")
        private val STRIP_FROM_NET_REGEX = Regex("(?:از|تو|توی|در)\\s*(?:اینترنت|وب|گوگل|پس\\s*زمینه|پس‌زمینه)")
        private val STRIP_FILLERS_REGEX = Regex("(?:برام|برای من|لطفاً|لطفا|میشه|می‌تونی|می تونی|یه|یک)")
        private val STRIP_VERBS_REGEX = Regex("(?:دانلود کن|دریافت کن|پیدا کن|جستجو کن|جست‌وجو کن|سرچ کن|بیار|بیاور|بگیر|بخوان|بخون|باز کن)")
        private val STRIP_TAIL_REGEX = Regex("(?:را|رو|به حافظه|و نقشت اضافه کن)$")
        private val MULTI_SPACE_REGEX = Regex("\\s+")
        private val GENERIC_PLACEHOLDERS = setOf(
            "چیز", "چیزی", "یک چیز", "یه چیز", "یه چیزی", "یک چیزی",
            "مطلب", "مطلبی", "فایل", "فایلی", "کتاب", "صفحه", "مورد",
        )
    }
}

internal class SearchWebMatcher : IntentMatcher {
    override val id = "matcher_search_web"
    override val action = SolAction.SEARCH_WEB
    override val priority = 93

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasAnyToken("کتاب", "دانلود", "مقاله", "ویکیپدیا", "نقش")) return null
        if (ctx.hasAnyPhrase("حافظه", "مخاطب")) return null

        val explicitWebSearchPrefix = ctx.startsWithAnyPhrase(
            "جستجوی وب", "جست‌وجوی وب", "جستجو در وب", "جست‌وجو در وب",
            "سرچ وب", "جستجوی اینترنتی", "سرچ اینترنتی", "داک داک گو",
        ) || ctx.hasAnyPhrase("وب را بگرد", "جستجوی وب", "جست‌وجوی وب", "سرچ وب", "جستجو در وب")

        val genericSearchWithWeb = ctx.hasAnyPhrase("جستجو کن", "جست‌وجو کن", "سرچ کن") &&
            !ctx.hasAnyToken("آنلاین", "گوگل", "درباره") &&
            !ctx.hasAnyPhrase("در مورد", "راجع به", "راجب")

        val browseNetPhrase = ctx.hasAnyPhrase("تو وب بگرد", "در وب بگرد", "تو اینترنت بگرد", "در اینترنت بگرد")

        if (!explicitWebSearchPrefix && !genericSearchWithWeb && !browseNetPhrase) return null

        val query = extractSearchQuery(ctx.cleaned).ifBlank { null }
        val slots = buildMap<String, SlotValue> {
            query?.let { put("search_query", SlotValue.Text(it)) }
        }
        val score = if (explicitWebSearchPrefix) 0.96f else 0.91f
        return MatchResult(
            score = score,
            slots = slots,
            evidence = listOf("n1_web_search"),
        ) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                searchQuery = query,
                requireConfirmation = true,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }

    private fun extractSearchQuery(cleaned: String): String {
        val stripped = cleaned
            .replace(PREFIX_REGEX, "")
            .replace(IN_WEB_REGEX, " ")
            .replace(ACTION_TAIL_REGEX, "")
            .replace(LEADING_PREPOSITION_REGEX, "")
            .replace(TRAILING_OBJECT_REGEX, "")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
        return stripped
    }

    companion object {
        private val PREFIX_REGEX = Regex("^(?:جست‌?وجوی\\s+وب|جست‌?وجو\\s+در\\s+وب|سرچ\\s+وب|جست‌?وجوی\\s+اینترنتی|سرچ\\s+اینترنتی|داک\\s*داک\\s*گو|در\\s+وب\\s+جست‌?وجو\\s+کن|جست‌?وجو\\s+کن|سرچ\\s+کن)\\s*(?:دربارهٔ?|در\\s+مورد|برای)?\\s*")
        private val IN_WEB_REGEX = Regex("(?:در|تو|توی)\\s*(?:وب|اینترنت)\\s*(?:برای|دربارهٔ?|در\\s+مورد)?")
        private val ACTION_TAIL_REGEX = Regex("(?:را|رو)?\\s*(?:در\\s+وب|تو\\s+وب|در\\s+اینترنت|تو\\s+اینترنت)?\\s*(?:جست‌?وجو\\s+کن|سرچ\\s+کن|بگرد|پیدا\\s+کن)$")
        private val LEADING_PREPOSITION_REGEX = Regex("^(?:دربارهٔ?|در\\s+مورد|برای|موضوع)\\s+")
        private val TRAILING_OBJECT_REGEX = Regex("\\s+(?:را|رو)$")
        private val MULTI_SPACE_REGEX = Regex("\\s+")
    }
}

internal class TeachRoleVoiceMatcher : IntentMatcher {
    override val id = "matcher_teach_role_voice"
    override val action = SolAction.TEACH_ROLE_VOICE
    override val priority = 90

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasAnyPhrase("طبق نقش", "بر اساس کتاب") || ctx.hasAnyToken("دانلود", "اینترنت")) return null
        val m = TEACH_ROLE_REGEX.find(ctx.cleaned) ?: return null
        val role = m.groupValues[1].trim()
        if (role.isBlank()) return null

        val slots = mapOf("role_name" to SlotValue.Text(role))
        return MatchResult(score = 0.92f, slots = slots, evidence = listOf("teach_role_pattern")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                roleName = role,
                message = c.cleaned,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }

    companion object {
        private val TEACH_ROLE_REGEX = Regex("(?:از این به بعد\\s+)?(?:نقش تو|نقشت|نقش جدیدت|به عنوان)\\s+(.+?)(?:\\s+(?:است|هست|باش|عمل کن|پاسخ بده))?$")
    }
}

internal class ConsultRoleBookMatcher : IntentMatcher {
    override val id = "matcher_consult_role_book"
    override val action = SolAction.CONSULT_ROLE_BOOK
    override val priority = 88

    override fun match(ctx: MatchContext): MatchResult? {
        val hit = ctx.hasAnyPhrase(
            "طبق نقش",
            "طبق نقشت",
            "بر اساس نقش",
            "بر اساس کتاب",
            "با توجه به کتاب",
            "از روی کتاب",
            "بر پایه کتاب",
            "راهنماییم کن",
        ) || (ctx.hasAnyToken("مشاوره", "مشورت", "پیشنهاد") && ctx.hasAnyToken("نقش", "نقشت", "کتاب", "مربی"))

        if (!hit) return null
        return MatchResult(score = 0.93f, evidence = listOf("consult_role_or_book")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                message = c.cleaned,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }
}

internal class ScheduleReminderMatcher : IntentMatcher {
    override val id = "matcher_schedule_reminder"
    override val action = SolAction.SCHEDULE_REMINDER
    override val priority = 86

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasPhrase("یادآور تایید موقعیت") || ctx.hasPhrase("یادآور تأیید موقعیت")) return null

        val hasExplicitSchedulingCue = ctx.hasAnyPhrase("یادآوری کن", "یادم بنداز", "یادم بیار", "آلارم بذار", "آلارم بگذار", "زنگ هشدار") ||
            ctx.hasAnyToken(
                "یادآور", "یادآوری", "آلارم", "دیگر", "دیگه", "بعد",
                "فردا", "پسفردا", "شنبه", "یکشنبه", "دوشنبه", "چهارشنبه", "پنجشنبه", "جمعه",
            ) || ctx.hasAnyPhrase("پس فردا", "سه شنبه")

        val isImmediateComm = ctx.hasAnyPhrase("پیام بده", "پیام بفرست", "پیامک بده", "پیامک بفرست", "زنگ بزن", "تماس بگیر")
        val hasReminderWord = ctx.hasAnyToken("یادآور", "یادآوری", "یادم", "آلارم", "هشدار")
        if (isImmediateComm && !hasReminderWord) return null

        val numSource = ctx.numberConvertedCleaned
        val spokenMinutes = PersianNumbers.parseSpokenDurationMinutes(ctx.cleaned)
        val reminderMatch = TIMED_REMINDER_REGEX.find(numSource)
        val parsedDelayMinutes = when {
            spokenMinutes != null && ctx.hasAnyToken("نیم", "ربع") &&
                ctx.hasAnyPhrase("یادآوری کن", "یادم بنداز", "به من بگو", "خبرم کن", "آلارم بذار", "آلارم بگذار") -> {
                spokenMinutes
            }
            reminderMatch != null -> {
                val num = reminderMatch.groupValues[1].toDoubleOrNull() ?: 1.0
                val unit = reminderMatch.groupValues[2]
                when (unit) {
                    "ساعت" -> (num * 60.0).toLong().coerceAtLeast(1L)
                    "ثانیه" -> 1L
                    else -> num.toLong().coerceAtLeast(1L)
                }
            }
            spokenMinutes != null && ctx.hasAnyPhrase("یادآوری کن", "یادم بنداز", "به من بگو", "خبرم کن", "آلارم بذار", "آلارم بگذار") -> {
                spokenMinutes
            }
            else -> 0L
        }

        val jalaliParsed = if (hasExplicitSchedulingCue) {
            JalaliCalendar.parsePersianDateTimeCommand(ctx.cleaned)
        } else {
            null
        }
        if (jalaliParsed != null) {
            val slots = buildMap<String, SlotValue> {
                put("schedule", SlotValue.Schedule(jalaliParsed))
                put("delay_minutes", SlotValue.DurationMinutes(parsedDelayMinutes))
                put("message", SlotValue.Text(jalaliParsed.reminderMessage))
                jalaliParsed.contactHint?.let { put("contact", SlotValue.Contact(it)) }
            }
            return MatchResult(score = 0.96f, slots = slots, evidence = listOf("jalali_datetime_command")) { c, r ->
                ParsedCommand(
                    action = action,
                    rawText = c.rawText,
                    normalizedText = c.normalized,
                    delayMinutes = parsedDelayMinutes,
                    message = jalaliParsed.reminderMessage,
                    contact = jalaliParsed.contactHint,
                    jalaliSchedule = jalaliParsed,
                    backgroundJobId = "bg_exact_alarm",
                    requireConfirmation = false,
                    slots = r.slots,
                    confidence = r.score,
                    matchedRuleId = id,
                )
            }
        }

        if (reminderMatch != null || (spokenMinutes != null && ctx.hasAnyPhrase("یادآوری کن", "یادم بنداز", "خبرم کن", "آلارم بذار", "آلارم بگذار"))) {
            val mins = parsedDelayMinutes.coerceAtLeast(1L)
            val msg = reminderMatch?.groupValues?.getOrNull(3)?.trim()
                ?.ifBlank { null }
                ?: extractQuickBody(ctx.cleaned)
                ?: "یادآور زمان‌بندی‌شده Sol"

            val slots = mapOf(
                "delay_minutes" to SlotValue.DurationMinutes(mins),
                "message" to SlotValue.Text(msg),
            )
            return MatchResult(score = 0.94f, slots = slots, evidence = listOf("relative_duration_reminder")) { c, r ->
                ParsedCommand(
                    action = action,
                    rawText = c.rawText,
                    normalizedText = c.normalized,
                    delayMinutes = mins,
                    message = msg,
                    backgroundJobId = "bg_reminder",
                    requireConfirmation = false,
                    slots = r.slots,
                    confidence = r.score,
                    matchedRuleId = id,
                )
            }
        }

        val quickMatch = QUICK_REMINDER_REGEX.find(ctx.cleaned)
        if (quickMatch != null) {
            val msg = quickMatch.groupValues[1].trim().ifBlank { "یادآور Sol" }
            val slots = mapOf(
                "delay_minutes" to SlotValue.DurationMinutes(15L),
                "message" to SlotValue.Text(msg),
            )
            return MatchResult(score = 0.89f, slots = slots, evidence = listOf("quick_reminder")) { c, r ->
                ParsedCommand(
                    action = action,
                    rawText = c.rawText,
                    normalizedText = c.normalized,
                    delayMinutes = 15L,
                    message = msg,
                    backgroundJobId = "bg_reminder",
                    requireConfirmation = false,
                    slots = r.slots,
                    confidence = r.score,
                    matchedRuleId = id,
                    hasExplicitTimeSlot = false,
                )
            }
        }

        return null
    }

    private fun extractQuickBody(cleaned: String): String? {
        return QUICK_REMINDER_REGEX.find(cleaned)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    companion object {
        private val TIMED_REMINDER_REGEX = Regex(
            "(\\d+(?:\\.\\d+)?)\\s*(دقیقه|ثانیه|ساعت)(?:\\s+و\\s+نیم)?\\s*(?:دیگر|دیگه|بعد)\\s*(?:یادآوری کن|یادم بنداز|به من بگو|خبرم کن)\\s*(.*)$"
        )
        private val QUICK_REMINDER_REGEX = Regex("(?:یادم بنداز|یادآوری کن|آلارم بذار|آلارم بگذار|زنگ هشدار بذار)\\s*(?:که\\s+|برای\\s+)?(.+)$")
    }
}

internal class BackgroundControlMatcher : IntentMatcher {
    override val id = "matcher_background_control"
    override val action = SolAction.BACKGROUND_CONTROL
    override val priority = 84

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasToken("کتاب") && ctx.hasAnyToken("دریافت", "دانلود", "پیدا", "بخوان")) return null

        val hasBgCue = ctx.hasPhrase("پس زمینه") ||
            ctx.hasPhrase("سرویس صوتی") ||
            ctx.hasPhrase("یادآور تایید موقعیت") ||
            ctx.hasPhrase("یادآور تأیید موقعیت") ||
            (ctx.hasAnyToken("پشتیبان", "بکاپ") && ctx.hasAnyToken("حافظه", "اطلاعات", "بگیر", "تهیه", "اجرا", "فعال")) ||
            (ctx.hasToken("روخوانی") && ctx.hasAnyToken("سرویس", "حافظه", "گزارش", "کتاب"))

        if (!hasBgCue) return null

        val jobId = when {
            ctx.hasAnyToken("پشتیبان", "بکاپ") -> "bg_memory_backup"
            ctx.hasToken("باتری") -> "bg_battery_guard"
            ctx.hasAnyToken("صبحگاهی", "روتین") -> "bg_daily_briefing"
            ctx.hasAnyToken("مرتب", "نمایه", "نمایه‌سازی", "مرتبسازی") -> "bg_memory_indexer"
            ctx.hasToken("موقعیت") -> "bg_location_checkin"
            ctx.hasAnyToken("سوابق", "بهینه", "پاکسازی", "هرس") -> "bg_history_cleanup"
            ctx.hasPhrase("سرویس صوتی") || ctx.hasToken("روخوانی") -> "bg_tts_reader_service"
            else -> "bg_memory_backup"
        }

        val slots = mapOf(
            "background_job" to SlotValue.Text(jobId),
            "message" to SlotValue.Text(ctx.cleaned),
        )
        return MatchResult(score = 0.90f, slots = slots, evidence = listOf("background_job:$jobId")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                backgroundJobId = jobId,
                message = c.cleaned,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }
}
