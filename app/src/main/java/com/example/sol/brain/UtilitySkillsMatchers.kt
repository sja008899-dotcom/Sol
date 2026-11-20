package com.example.sol.brain

import com.example.sol.calendar.JalaliCalendar
import com.example.sol.text.PersianTextNormalizer

/**
 * Rule-based token/phrase matchers for Milestone M5 offline utility skills:
 * - Timer & Stopwatch (B8.1)
 * - Flashlight torch control (B8.2)
 * - Calendar Event insertion (B8.3)
 * - Safe Math Calculator (B8.4)
 * - Notes & To-Do / Shopping Lists (B8.5)
 * - Hardware Media Keys (B8.8)
 */
internal class TimerStopwatchMatcher : IntentMatcher {
    override val id = "matcher_timer_stopwatch"
    override val action = SolAction.TIMER_STOPWATCH
    override val priority = 87

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasAnyToken("خرید", "قیمت", "فروش")) return null

        val isStopwatch = ctx.hasAnyToken("کرنومتر", "کرونومتر") ||
            ctx.hasAnyPhrase("زمان سنج", "زمان‌سنج")
        val isTimer = ctx.hasToken("تایمر") || ctx.hasAnyPhrase("شمارش معکوس", "تایم بگیر")

        if (!isStopwatch && !isTimer) return null

        val durationSeconds = parseDurationSeconds(ctx)
        val mode = if (isStopwatch) "STOPWATCH" else "TIMER"
        val op = when {
            isStopwatch -> when {
                ctx.hasAnyToken("ریست", "صفر", "مجدد") || ctx.hasPhrase("پاک کن") -> "RESET"
                ctx.hasAnyToken("توقف", "متوقف", "نگهدار", "استاپ", "خاموش", "ببند", "قطع") -> "STOP"
                ctx.hasAnyToken("وضعیت", "چقدر", "چنده", "بگو", "نشان", "نشون") -> "STATUS"
                else -> "START"
            }
            else -> when {
                ctx.hasAnyToken("لغو", "کنسل", "خاموش", "قطع", "حذف", "ببند") -> "CANCEL"
                ctx.hasAnyToken("وضعیت", "چقدر", "مانده", "باقی", "چنده") && durationSeconds <= 0L -> "STATUS"
                else -> "START"
            }
        }

        val label = extractTimerLabel(ctx.cleaned)
        val slots = buildMap<String, SlotValue> {
            put("timer_mode", SlotValue.Text(mode))
            put("timer_op", SlotValue.Text(op))
            if (durationSeconds > 0L) {
                put("duration_seconds", SlotValue.Number(durationSeconds.toDouble()))
            }
            if (!label.isNullOrBlank()) {
                put("message", SlotValue.Text(label))
            }
        }

        return MatchResult(
            score = 0.94f,
            slots = slots,
            evidence = listOf("timer:$mode:$op"),
        ) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                message = label,
                delayMinutes = (durationSeconds / 60L).coerceAtLeast(1L),
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }

    private fun parseDurationSeconds(ctx: MatchContext): Long {
        val normNum = ctx.numberConvertedNormalized
        var totalSeconds = 0L
        for (m in COMPOUND_DURATION_REGEX.findAll(normNum)) {
            val qty = m.groupValues[1].toDoubleOrNull() ?: continue
            val unit = m.groupValues[2]
            val sec = when (unit) {
                "ساعت" -> (qty * 3600.0).toLong()
                "دقیقه", "دقیق" -> (qty * 60.0).toLong()
                "ثانیه" -> qty.toLong()
                else -> 0L
            }
            totalSeconds += sec.coerceAtLeast(0L)
        }
        if (totalSeconds > 0L) return totalSeconds

        return when {
            ctx.hasAnyPhrase("نیم ساعت", "نصف ساعت") -> 1800L
            ctx.hasAnyPhrase("یک ربع", "یه ربع", "ربع ساعت") -> 900L
            else -> 0L
        }
    }

    private fun extractTimerLabel(cleaned: String): String? {
        val m = TIMER_LABEL_REGEX.find(cleaned)?.groupValues?.getOrNull(1)?.trim()
        return m?.takeIf { it.isNotBlank() }
    }

    companion object {
        private val COMPOUND_DURATION_REGEX = Regex("(\\d+(?:\\.\\d+)?)\\s*(ساعت|دقیقه|دقیق|ثانیه)")
        private val TIMER_LABEL_REGEX = Regex("(?:برای|بابت|با نام|با عنوان)\\s+(.+)$")
    }
}

internal class FlashlightMatcher : IntentMatcher {
    override val id = "matcher_flashlight"
    override val action = SolAction.FLASHLIGHT
    override val priority = 85

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasAnyToken("خرید", "قیمت", "باتری", "مموری", "کامپیوتر")) return null

        val hit = ctx.hasAnyToken("چراغقوه") ||
            ctx.hasAnyPhrase("چراغ قوه", "فلش گوشی", "فلاش گوشی", "فلاش دوربین", "نور فلش", "چراغ گوشی")

        if (!hit) return null

        val explicitEnable: Boolean? = when {
            ctx.hasAnyToken("خاموش", "ببند", "قطع", "غیرفعال", "بستن") -> false
            ctx.hasAnyToken("روشن", "باز", "بزن", "فعال") -> true
            else -> null
        }

        val slots = buildMap<String, SlotValue> {
            explicitEnable?.let { put("torch_enabled", SlotValue.Flag(it)) }
        }

        return MatchResult(
            score = 0.94f,
            slots = slots,
            evidence = listOf("flashlight:${explicitEnable ?: "toggle"}"),
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

internal class CalendarEventMatcher : IntentMatcher {
    override val id = "matcher_calendar_event"
    override val action = SolAction.CREATE_CALENDAR_EVENT
    override val priority = 85

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasAnyToken("چندم", "امروز", "کبیسه", "برنامه", "اپ")) return null

        val hit = ctx.hasAnyPhrase(
            "به تقویم اضافه کن",
            "در تقویم ثبت کن",
            "تو تقویم بذار",
            "توی تقویم بذار",
            "در تقویم بنویس",
            "تو تقویم ثبت کن",
            "رویداد تقویم",
            "رویداد جدید",
            "ثبت رویداد",
            "ایجاد رویداد",
            "اضافه به تقویم",
        ) || (
            ctx.hasToken("تقویم") &&
                ctx.hasAnyToken("اضافه", "ثبت", "بذار", "بگذار", "بساز", "بنویس", "ایجاد") &&
                ctx.hasAnyToken("رویداد", "جلسه", "قرار", "ملاقات", "تولد", "مراسم", "امتحان", "سفر", "برنامه")
            )

        if (!hit) return null

        val title = extractEventTitle(ctx.cleaned)
        val parsedRange = JalaliCalendar.parseEventDateTimeRange(ctx.cleaned)

        val slots = buildMap<String, SlotValue> {
            put("event_title", SlotValue.Text(title))
            parsedRange?.first?.let { put("event_begin_ms", SlotValue.Number(it.toDouble())) }
        }

        return MatchResult(
            score = 0.93f,
            slots = slots,
            evidence = listOf("calendar_event"),
        ) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                message = title,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }

    private fun extractEventTitle(cleaned: String): String {
        val quoted = Regex("[«\"](.+?)[»\"]").find(cleaned)?.groupValues?.getOrNull(1)?.trim()
        if (!quoted.isNullOrBlank()) return quoted

        val stripped = cleaned
            .replace(Regex("(?:به|در|تو|توی)\\s+تقویم\\s+(?:من\\s+)?(?:اضافه کن|ثبت کن|بذار|بگذار|بنویس|بساز|ایجاد کن)"), " ")
            .replace(Regex("(?:اضافه کن|ثبت کن|ایجاد کن)\\s+(?:به|در|تو|توی)\\s+تقویم"), " ")
            .replace(Regex("(?:ثبت|ایجاد)\\s+رویداد(?:\\s+جدید)?"), " ")
            .replace(Regex("رویداد(?:\\s+جدید)?"), " ")
            .replace(Regex("\\b(?:را|رو|برام|برای من|در تقویم|به تقویم)\\b"), " ")
            .replace(Regex("(?:امروز|فردا|پس‌فردا|پس فردا|\\d{4}[/-]\\d{1,2}[/-]\\d{1,2})"), " ")
            .replace(Regex("ساعت\\s*\\d{1,2}(?:[:٫.]\\d{1,2})?(?:\\s*(?:صبح|ظهر|عصر|شب|بعدازظهر))?"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return stripped.ifBlank { "رویداد Sol" }
    }
}

internal class CalculatorMatcher : IntentMatcher {
    override val id = "matcher_calculator"
    override val action = SolAction.CALCULATOR
    override val priority = 89

    override fun match(ctx: MatchContext): MatchResult? {
        if (URL_REGEX.containsMatchIn(ctx.cleaned)) return null
        if (ctx.hasAnyPhrase("ماشین حساب")) return null
        if (ctx.hasAnyToken(
                "فراموش", "یادآوری", "یادم", "تماس", "زنگ", "پیام", "پیامک",
                "تقویم", "کبیسه", "تایمر", "کرنومتر", "کرونومتر", "شمسی", "میلادی",
                "شماره", "موبایل", "پلاک", "بیمه", "کد",
            )
        ) {
            return null
        }

        val rawHasBinaryOp = BINARY_SYMBOL_OP_REGEX.containsMatchIn(ctx.cleaned)
        val hasSlashDatePattern = SLASH_DATE_REGEX.containsMatchIn(ctx.cleaned) && !rawHasBinaryOp
        if (hasSlashDatePattern && !ctx.hasAnyPhrase("حساب کن", "محاسبه کن", "تقسیم بر")) return null

        val hasDigit = DIGIT_REGEX.containsMatchIn(ctx.numberConvertedCleaned)
        val hasPersianOpPhrase = ctx.hasAnyPhrase(
            "ضرب در", "ضربدر", "تقسیم بر", "به علاوه", "بعلاوه", "به اضافه", "به توان", "اضافه بر"
        )
        val hasRootKeyword = ctx.hasAnyToken("جذر", "رادیکال") && hasDigit
        val hasPercentOfExpr = (ctx.hasToken("درصد") || ctx.cleaned.contains('%') || ctx.cleaned.contains('٪')) &&
            hasDigit && !ctx.hasAnyToken("باتری", "شارژ", "بدن", "هوا", "اب", "آب", "اکسیژن")
        val hasMinusWordWithDigit = ctx.hasAnyToken("منهای", "منها") && hasDigit
        val hasCalculateVerbWithDigit = ctx.hasAnyPhrase("حساب کن", "محاسبه کن") &&
            (hasDigit || ctx.hasAnyToken("ضرب", "تقسیم", "جمع", "تفریق", "توان", "جذر", "درصد"))

        val hit = (rawHasBinaryOp && !hasSlashDatePattern) ||
            hasPersianOpPhrase ||
            hasRootKeyword ||
            hasPercentOfExpr ||
            hasMinusWordWithDigit ||
            hasCalculateVerbWithDigit

        if (!hit) return null

        val expr = ctx.cleaned
        val slots = mapOf("expression" to SlotValue.Text(expr), "message" to SlotValue.Text(expr))
        return MatchResult(
            score = 0.93f,
            slots = slots,
            evidence = listOf("calculator_expr"),
        ) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                message = expr,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }

    companion object {
        private val URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
        private val DIGIT_REGEX = Regex("\\d")
        private val BINARY_SYMBOL_OP_REGEX = Regex("\\d\\s*[+*×÷^]\\s*\\d|\\d\\s+[-/]\\s+\\d|\\(\\s*\\d")
        private val SLASH_DATE_REGEX = Regex("\\b(?:13|14|19|20)\\d{2}/\\d{1,2}/\\d{1,2}\\b")
    }
}

internal class NotesTodoMatcher : IntentMatcher {
    override val id = "matcher_notes_todo"
    override val action = SolAction.NOTES_TODO
    override val priority = 81

    override fun match(ctx: MatchContext): MatchResult? {
        // Keep existing FORGET_MEMORY phrases routed cleanly to FORGET_MEMORY
        if (ctx.hasPhrase("فراموش کن") || ctx.hasPhrase("از حافظه") || ctx.hasPhrase("یادداشت های حافظه")) return null
        if (ctx.hasAnyToken("یادداشتها", "یادداشت") && ctx.hasPhrase("باز کن") && !ctx.hasAnyToken("بخوان", "بخون", "اضافه")) return null

        val isShopping = ctx.hasAnyPhrase("لیست خرید", "فهرست خرید", "سبد خرید")
        val isTodo = ctx.hasAnyPhrase(
            "کارهای امروز", "کارهای روزانه", "لیست کار", "لیست کارها",
            "فهرست کارها", "فهرست کار", "لیست انجام کار", "برنامه کارها",
        ) || ctx.hasAnyToken("تودو", "کارهام", "کارهامو", "تودولیست")
        val isNoteVerb = ctx.hasAnyPhrase(
            "یادداشت کن", "یادداشت بردار", "یادداشت جدید",
            "به یادداشت اضافه کن", "تو یادداشت بنویس", "دفترچه یادداشت",
        ) || (ctx.hasAnyToken("یادداشت", "یادداشتها", "یادداشتهام", "یادداشتهامو") &&
            ctx.hasAnyToken("بخوان", "بخون", "نشون", "نشان", "فهرست", "لیست", "اضافه", "ثبت", "بنویس"))

        if (!isShopping && !isTodo && !isNoteVerb) return null

        val kind = when {
            isShopping -> "SHOPPING"
            isTodo -> "TODO"
            else -> "NOTE"
        }
        val op = when {
            ctx.hasAnyToken("حذف", "پاک") -> "DELETE"
            ctx.hasAnyPhrase("انجام شد", "تیک بزن", "تمام شد", "تکمیل شد") ||
                (ctx.hasToken("انجام") && ctx.hasAnyToken("شده", "دادم", "بزن")) -> "DONE"
            ctx.hasAnyToken("بخوان", "بخون", "چیه", "چیست", "چیا", "نشان", "نشون", "دارم", "نمایش", "فهرست", "لیست") &&
                extractNoteItemBody(ctx.cleaned).isBlank() -> "LIST"
            ctx.hasAnyPhrase("لیست را بخوان", "لیست رو بخون", "لیست را نشان بده", "چیزهایی در لیست") -> "LIST"
            extractNoteItemBody(ctx.cleaned).isBlank() && (isShopping || isTodo) -> "LIST"
            else -> "ADD"
        }

        val content = extractNoteItemBody(ctx.cleaned)
        val slots = buildMap<String, SlotValue> {
            put("note_kind", SlotValue.Text(kind))
            put("note_op", SlotValue.Text(op))
            if (content.isNotBlank()) put("message", SlotValue.Text(content))
        }

        return MatchResult(
            score = 0.92f,
            slots = slots,
            evidence = listOf("notes:$kind:$op"),
        ) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                message = content.ifBlank { null },
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }

    private fun extractNoteItemBody(cleaned: String): String {
        val afterColon = Regex("[:：]\\s*(.+)$").find(cleaned)?.groupValues?.getOrNull(1)?.trim()
        if (!afterColon.isNullOrBlank()) return afterColon

        return cleaned
            .replace(Regex("^(?:به|در|توی|تو)?\\s*(?:لیست خرید|فهرست خرید|سبد خرید|کارهای امروز|کارهای روزانه|لیست کارها|فهرست کارها|دفترچه یادداشت|یادداشت‌ها|یادداشت ها|یادداشت)\\s*"), "")
            .replace(Regex("^(?:یادداشت کن|یادداشت بردار|یادداشت جدید|اضافه کن|بنویس|ثبت کن)\\s*(?:که|به)?\\s*"), "")
            .replace(Regex("(?:را|رو)?\\s*(?:به|در|توی|تو)\\s*(?:لیست خرید|فهرست خرید|لیست کارها|کارهای امروز|یادداشت‌ها|یادداشت)\\s*(?:اضافه کن|بذار|بگذار|ثبت کن|بنویس|حذف کن|پاک کن)$"), "")
            .replace(Regex("\\s*(?:انجام شد|تکمیل شد|تیک بزن|حذف کن|پاک کن|را بخوان|رو بخون|را نشان بده|چیه|چیست|دارم)$"), "")
            .replace(Regex("^(?:که|رو|را|برام|برای من|لطفاً|لطفا)\\s+"), "")
            .replace(Regex("\\s+(?:را|رو|برام|برای من)$"), "")
            .trim()
    }
}

internal class MediaControlMatcher : IntentMatcher {
    override val id = "matcher_media_control"
    override val action = SolAction.MEDIA_CONTROL
    override val priority = 79

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasPhrase("پس زمینه") || ctx.hasPhrase("سرویس صوتی") || ctx.hasPhrase("روخوانی")) return null
        if (ctx.hasToken("باز") && !ctx.hasAnyToken("بعدی", "قبلی", "توقف", "پاز")) return null

        val cmd = when {
            ctx.hasAnyPhrase("آهنگ بعدی", "موزیک بعدی", "موسیقی بعدی", "ترک بعدی", "قطعه بعدی", "پخش بعدی") ||
                ctx.hasAnyToken("نکست", "بعدی") && ctx.hasAnyToken("آهنگ", "موزیک", "موسیقی", "ترک", "پخش") -> "NEXT"
            ctx.hasAnyPhrase("آهنگ قبلی", "موزیک قبلی", "موسیقی قبلی", "ترک قبلی", "قطعه قبلی") ||
                ctx.hasAnyToken("قبلی") && ctx.hasAnyToken("آهنگ", "موزیک", "موسیقی", "ترک", "پخش") -> "PREVIOUS"
            ctx.hasAnyPhrase("توقف موسیقی", "توقف آهنگ", "توقف موزیک", "توقف پخش", "پاز کن", "موزیک رو قطع کن", "آهنگ رو قطع کن", "موسیقی رو قطع کن", "آهنگ را متوقف کن", "موزیک را متوقف کن", "موسیقی را متوقف کن", "پخش را متوقف کن") -> "PAUSE"
            ctx.hasAnyPhrase("پخش موسیقی", "پخش آهنگ", "پخش موزیک", "آهنگ را پخش کن", "موسیقی را پخش کن", "موزیک را پخش کن", "ادامه پخش", "پلی کن") -> "PLAY"
            else -> null
        } ?: return null

        val slots = mapOf("media_cmd" to SlotValue.Text(cmd))
        return MatchResult(
            score = 0.91f,
            slots = slots,
            evidence = listOf("media:$cmd"),
        ) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                message = cmd,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }
}
