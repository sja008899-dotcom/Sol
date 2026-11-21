package com.example.sol.brain

import com.example.sol.calendar.JalaliCalendar
import com.example.sol.text.PersianNumbers
import com.example.sol.text.PersianTextNormalizer

internal class TimerStopwatchMatcher : IntentMatcher {
    override val id = "matcher_timer_stopwatch"
    override val action = SolAction.TIMER_STOPWATCH
    override val priority = 87

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasAnyToken("قیمت", "خرید", "فروش")) return null

        val isStopwatch = ctx.hasAnyToken("کرنومتر", "کرونومتر") ||
            ctx.hasAnyPhrase("زمان سنج", "زمان‌سنج")
        val isTimer = ctx.hasToken("تایمر") || ctx.hasPhrase("شمارش معکوس")
        if (!isStopwatch && !isTimer) return null

        val mode = if (isStopwatch) "STOPWATCH" else "TIMER"
        val durationSec = if (!isStopwatch) extractDurationSeconds(ctx) else 0L

        val op = if (isStopwatch) {
            when {
                ctx.hasAnyToken("ریست", "صفر", "مجدد") || ctx.hasPhrase("پاک کن") -> "RESET"
                ctx.hasAnyToken("توقف", "نگهدار", "استاپ", "خاموش", "ببند", "متوقف", "قطع") -> "STOP"
                ctx.hasAnyToken("وضعیت", "چقدر", "چنده", "بگو", "نشان", "نشون") -> "STATUS"
                else -> "START"
            }
        } else {
            when {
                ctx.hasAnyToken("لغو", "کنسل", "خاموش", "قطع", "حذف", "پاک", "ببند") -> "CANCEL"
                durationSec <= 0L && ctx.hasAnyToken("وضعیت", "چقدر", "مانده", "باقی", "چنده", "بگو") -> "STATUS"
                else -> "START"
            }
        }

        val label = extractTimerLabel(ctx.cleaned)
        val slots = buildMap<String, SlotValue> {
            put("timer_mode", SlotValue.Text(mode))
            put("timer_op", SlotValue.Text(op))
            if (durationSec > 0L) {
                put("duration_seconds", SlotValue.Number(durationSec.toDouble()))
            }
            if (label.isNotBlank()) {
                put("message", SlotValue.Text(label))
            }
        }

        val score = if (durationSec > 0L || isStopwatch || op != "START") 0.95f else 0.91f
        return MatchResult(score = score, slots = slots, evidence = listOf("timer_stopwatch:$mode:$op")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                message = label.ifBlank { null },
                delayMinutes = (durationSec / 60L).coerceAtLeast(1L),
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }

    companion object {
        private val DURATION_PART_REGEX = Regex("(\\d+(?:\\.\\d+)?)\\s*(ساعت|دقیقه|ثانیه)")

        internal fun extractDurationSeconds(ctx: MatchContext): Long {
            val numSource = ctx.numberConvertedCleaned
            var totalSeconds = 0.0
            for (m in DURATION_PART_REGEX.findAll(numSource)) {
                val amount = m.groupValues[1].toDoubleOrNull() ?: continue
                totalSeconds += when (m.groupValues[2]) {
                    "ساعت" -> amount * 3600.0
                    "دقیقه" -> amount * 60.0
                    "ثانیه" -> amount
                    else -> 0.0
                }
            }
            if (totalSeconds <= 0.0) {
                if (ctx.hasPhrase("نیم ساعت")) {
                    totalSeconds = 1800.0
                } else if (ctx.hasAnyPhrase("ربع ساعت", "یک ربع", "یه ربع")) {
                    totalSeconds = 900.0
                } else if (numSource.contains("و نیم") && numSource.contains("دقیقه")) {
                    totalSeconds += 30.0
                }
            } else if (numSource.contains("و نیم") && numSource.contains("ساعت") && !numSource.contains("دقیقه")) {
                totalSeconds += 1800.0
            } else if (numSource.contains("و نیم") && numSource.contains("دقیقه") && !numSource.contains("ثانیه")) {
                totalSeconds += 30.0
            }
            return totalSeconds.toLong().coerceAtLeast(0L)
        }

        private fun extractTimerLabel(cleaned: String): String {
            val m = Regex("(?:برای|واسه)\\s+(.+?)$").find(cleaned)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            return m.replace(Regex("^(?:تایمر|کرنومتر)\\s*"), "").trim()
        }
    }
}

internal class FlashlightMatcher : IntentMatcher {
    override val id = "matcher_flashlight"
    override val action = SolAction.FLASHLIGHT
    override val priority = 85

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasAnyToken("خرید", "قیمت", "باتری", "مموری", "کامپیوتر")) return null

        val hit = ctx.hasAnyToken("چراغ‌قوه", "چراغقوه") ||
            ctx.hasAnyPhrase(
                "چراغ قوه", "فلش گوشی", "فلاش گوشی",
                "فلاش دوربین", "نور فلش", "چراغ گوشی",
            )
        if (!hit) return null

        val targetFlag: Boolean? = when {
            ctx.hasAnyToken("خاموش", "ببند", "قطع", "غیرفعال") -> false
            ctx.hasAnyToken("روشن", "باز", "بزن", "فعال", "روشنش") -> true
            else -> null
        }

        val slots = buildMap<String, SlotValue> {
            targetFlag?.let { put("torch_enabled", SlotValue.Flag(it)) }
        }
        return MatchResult(score = 0.94f, slots = slots, evidence = listOf("flashlight")) { c, r ->
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
        if (ctx.hasAnyToken("خرید", "فراموش", "حذف")) return null

        val hasExplicitPhrase = ctx.hasAnyPhrase(
            "به تقویم اضافه کن",
            "به تقویم اضافه",
            "در تقویم ثبت کن",
            "در تقویم اضافه کن",
            "تو تقویم بذار",
            "توی تقویم بذار",
            "تو تقویم ثبت کن",
            "توی تقویم ثبت کن",
            "رویداد تقویم",
            "ثبت رویداد",
            "ایجاد رویداد",
            "اضافه به تقویم",
        )
        val hasCalendarPlusEvent = ctx.hasToken("تقویم") &&
            ctx.hasAnyToken("اضافه", "ثبت", "بذار", "بگذار", "بساز", "بنویس", "ایجاد") &&
            ctx.hasAnyToken("رویداد", "جلسه", "قرار", "ملاقات", "تولد", "مراسم", "امتحان", "سفر", "برنامه")
        val hasEventAndCalendar = ctx.hasToken("تقویم") && ctx.hasAnyToken("رویداد", "قرار", "ملاقات")

        if (!hasExplicitPhrase && !hasCalendarPlusEvent && !hasEventAndCalendar) return null

        val title = extractEventTitle(ctx.cleaned)
        val parsedRange = JalaliCalendar.parseEventDateTimeRange(ctx.cleaned)

        val slots = buildMap<String, SlotValue> {
            put("message", SlotValue.Text(title))
            parsedRange?.let { (beginMs, _) ->
                put("event_begin_ms", SlotValue.Number(beginMs.toDouble()))
            }
        }
        return MatchResult(score = 0.93f, slots = slots, evidence = listOf("calendar_event")) { c, r ->
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

    companion object {
        private val STRIP_BOILERPLATE_REGEX = Regex(
            "(?:به تقویم اضافه کن|در تقویم ثبت کن|در تقویم اضافه کن|تو تقویم بذار|توی تقویم بذار|تو تقویم ثبت کن|توی تقویم ثبت کن|به تقویم|در تقویم|توی تقویم|تو تقویم|اضافه کن|ثبت کن|بذار|بگذار|ایجاد کن|بساز|رویداد جدید|رویداد)\\s*"
        )
        private val STRIP_TIME_NOISE_REGEX = Regex(
            "(?:امروز|فردا|پس‌فردا|پس فردا|ساعت\\s*[\\d۰-۹]{1,2}(?:[:٫.][\\d۰-۹]{1,2}|\\s*و\\s*نیم)?(?:\\s*(?:صبح|ظهر|عصر|شب|بعدازظهر))?|\\b1[2-5]\\d{2}[/-]\\d{1,2}[/-]\\d{1,2}\\b)"
        )

        internal fun extractEventTitle(cleaned: String): String {
            val noBoilerplate = STRIP_BOILERPLATE_REGEX.replace(cleaned, " ")
            val noDigits = PersianTextNormalizer.cleanDisplayText(noBoilerplate)
            val stripped = STRIP_TIME_NOISE_REGEX.replace(noDigits, " ")
                .replace(Regex("^(?:را|رو|یک|یه)\\s+"), "")
                .replace(Regex("\\s+(?:را|رو)$"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            return stripped.ifBlank { "رویداد Sol" }
        }
    }
}

internal class CalculatorMatcher : IntentMatcher {
    override val id = "matcher_calculator"
    override val action = SolAction.CALCULATOR
    override val priority = 89

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasPhrase("ماشین حساب") && ctx.hasAnyToken("باز", "اجرا")) return null
        if (ctx.hasAnyToken(
                "فراموش", "یادآور", "یادآوری", "یادم", "آلارم", "تماس", "زنگ", "پیام", "پیامک",
                "تقویم", "کبیسه", "تایمر", "کرنومتر", "کرونومتر", "شمسی", "میلادی", "چندشنبه", "امروز",
            )
        ) return null
        if (Regex("https?://\\S+", RegexOption.IGNORE_CASE).containsMatchIn(ctx.cleaned)) return null

        val converted = ctx.numberConvertedCleaned
        val hasDigits = DIGIT_REGEX.containsMatchIn(converted)
        val hasSymbolicMath = SYMBOLIC_MATH_REGEX.containsMatchIn(converted)
        val hasPersianMathPhrase = ctx.hasAnyPhrase(
            "ضرب در", "ضربدر", "تقسیم بر", "به علاوه", "بعلاوه", "به اضافه", "به توان",
        )
        val hasSqrt = ctx.hasAnyToken("جذر", "رادیکال") && hasDigits
        val hasPercentOf = ctx.hasToken("درصد") && hasDigits && !ctx.hasAnyToken("باتری", "شارژ")
        val hasMinusWithDigits = ctx.hasToken("منهای") && hasDigits
        val hasCalcVerbWithDigits = ctx.hasAnyPhrase("حساب کن", "محاسبه کن") &&
            (hasDigits || ctx.hasAnyToken("جمع", "تفریق", "ضرب", "تقسیم", "درصد", "جذر"))

        if (!hasSymbolicMath && !hasPersianMathPhrase && !hasSqrt && !hasPercentOf && !hasMinusWithDigits && !hasCalcVerbWithDigits) {
            return null
        }

        val slots = mapOf("message" to SlotValue.Text(ctx.cleaned))
        return MatchResult(score = 0.94f, slots = slots, evidence = listOf("calculator_expression")) { c, r ->
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

    companion object {
        private val DIGIT_REGEX = Regex("\\d")
        private val SYMBOLIC_MATH_REGEX = Regex("\\d\\s*(?:[+*^×÷]|\\s/\\s|\\s-\\s|\\s%\\s)\\s*\\d")
    }
}

internal class NotesTodoMatcher : IntentMatcher {
    override val id = "matcher_notes_todo"
    override val action = SolAction.NOTES_TODO
    override val priority = 81

    override fun match(ctx: MatchContext): MatchResult? {
        // Never steal explicit memory-forget commands ("از حافظه پاک کن", "فراموش کن") or list-memory queries
        if (ctx.hasAnyPhrase("فراموش کن", "یادت بره", "از حافظه", "در حافظه", "توی حافظه")) return null
        if (ctx.hasAnyToken("توضیح", "یادگیری", "نقش", "نکته", "زنگ", "تماس", "پیام", "پیامک")) return null
        if (ctx.hasAnyToken("حافظه") && ctx.hasAnyToken("بگو", "بخوان", "فهرست", "لیست")) return null
        if (ctx.hasPhrase("یادداشت") && ctx.hasToken("باز") && !ctx.hasAnyToken("بخوان", "اضافه", "لیست")) return null

        val isShopping = ctx.hasAnyPhrase("لیست خرید", "فهرست خرید", "سبد خرید")
        val isTodo = ctx.hasAnyPhrase("کارهای امروز", "فهرست کار", "لیست کار", "کارهای روزانه", "برنامه امروز", "کارهای من") ||
            ctx.hasAnyToken("کارهام", "کارهامو", "کارها", "تودو")
        val isExplicitNoteRead = ctx.hasAnyPhrase(
            "یادداشت ها را بخوان", "یادداشت هام چیه", "یادداشت هامو بخون",
            "یادداشت هام را بگو", "یادداشت ها را نشان بده", "دفترچه یادداشت",
            "لیست یادداشت", "فهرست یادداشت",
        )
        val isExplicitNoteAdd = ctx.hasAnyPhrase("یادداشت کن", "یادداشت بردار", "یادداشت جدید", "به یادداشت ها اضافه کن", "توی یادداشت ها بنویس")
        val isExplicitNoteCompleteOrDelete = (isShopping || isTodo || isExplicitNoteRead || ctx.hasPhrase("یادداشت ها")) &&
            (ctx.hasAnyToken("تیک", "حذف", "پاک") || ctx.hasAnyPhrase("انجام شد", "خریدم", "تموم شد", "تمام شد"))
        val isMarkTodoDone = ctx.hasAnyPhrase("انجام شد", "تیک بزن") &&
            ctx.hasAnyToken("کارهای", "کارهام", "کارهامو", "کارها", "لیست", "فهرست", "خرید", "سبد", "یادداشت", "تودو")

        if (!isShopping && !isTodo && !isExplicitNoteRead && !isExplicitNoteAdd && !isExplicitNoteCompleteOrDelete && !isMarkTodoDone) {
            return null
        }

        // Preserve existing M3 FORGET_MEMORY golden phrases containing "یادداشت ... حذف کن / پاک کن" when there is no shopping-list cue
        if (ctx.hasToken("یادداشت") && ctx.hasAnyToken("حذف", "پاک") && !isShopping && !isTodo) {
            return null
        }

        val kind = when {
            isShopping -> "SHOPPING"
            isTodo -> "TODO"
            else -> "NOTE"
        }

        val op = when {
            ctx.hasAnyToken("حذف", "پاک") -> "DELETE"
            ctx.hasAnyPhrase("انجام شد", "تیک بزن", "خریدم", "تموم شد", "تمام شد") || ctx.hasToken("تیک") -> "DONE"
            ctx.hasAnyToken("چیه", "چیست", "چیا", "بخوان", "بخون", "بگو", "نشان", "نشون", "نمایش", "مرور") &&
                !ctx.hasAnyPhrase("اضافه کن", "بنویس", "بذار", "ثبت کن", "یادداشت کن", "یادداشت بردار") -> "LIST"
            !isExplicitNoteAdd && extractNoteItemContent(ctx.cleaned).isBlank() -> "LIST"
            else -> "ADD"
        }

        val content = extractNoteItemContent(ctx.cleaned)
        val slots = buildMap<String, SlotValue> {
            put("note_kind", SlotValue.Text(kind))
            put("note_op", SlotValue.Text(op))
            if (content.isNotBlank()) {
                put("message", SlotValue.Text(content))
            }
        }

        return MatchResult(score = 0.93f, slots = slots, evidence = listOf("notes_todo:$kind:$op")) { c, r ->
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

    companion object {
        private val NOTE_PREFIX_REGEX = Regex(
            "^(?:لطفاً\\s+|لطفا\\s+)?(?:به\\s+(?:لیست خرید|فهرست خرید|کارهای امروز|لیست کارها|فهرست کارها|یادداشت‌ها|یادداشت ها)\\s+اضافه کن(?:\\s+که)?|یادداشت کن(?:\\s+که)?|یادداشت بردار(?:\\s+که)?|یادداشت جدید(?:\\s*[:\\-])?|توی\\s+یادداشت‌ها\\s+بنویس(?:\\s+که)?|در\\s+لیست خرید\\s+بنویس(?:\\s+که)?|در\\s+کارهای امروز\\s+بنویس(?:\\s+که)?|تیک\\s+|حذف\\s+)\\s*"
        )
        private val NOTE_SUFFIX_REGEX = Regex(
            "\\s*(?:را\\s+|رو\\s+)?(?:به\\s+لیست خرید|در\\s+لیست خرید|به\\s+کارهای امروز|به\\s+لیست کارها|به\\s+یادداشت‌ها|از\\s+لیست خرید|از\\s+کارهای امروز|از\\s+یادداشت‌ها)?\\s*(?:اضافه کن|بنویس|بذار|ثبت کن|حذف کن|پاک کن|انجام شد|تیک بزن|بخوان|بخون|بگو|نشان بده|نشون بده|چیه|چیست)\\s*$"
        )

        internal fun extractNoteItemContent(cleaned: String): String {
            var s = PersianTextNormalizer.cleanDisplayText(cleaned)
            s = NOTE_PREFIX_REGEX.replace(s, "")
            s = NOTE_SUFFIX_REGEX.replace(s, "")
            s = s.replace(Regex("^(?:لیست خرید|فهرست خرید|کارهای امروز|لیست کارها|فهرست کارها|یادداشت‌ها|یادداشت ها)\\s*[:\\-]?\\s*"), "")
            return s.replace(Regex("\\s+"), " ").trim()
        }
    }
}

internal class MediaControlMatcher : IntentMatcher {
    override val id = "matcher_media_control"
    override val action = SolAction.MEDIA_CONTROL
    override val priority = 79

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasAnyPhrase("سرویس صوتی", "روخوانی", "باز کن", "مدل", "آهنگ شاد", "موسیقی کلاسیک")) return null
        if (ctx.hasToken("اینترنت") || ctx.hasToken("دانلود")) return null

        val mediaOp = when {
            ctx.hasAnyPhrase("آهنگ بعدی", "ترک بعدی", "موسیقی بعدی", "موزیک بعدی", "بعدی پخش کن", "برو آهنگ بعدی", "نکست") -> "NEXT"
            ctx.hasAnyPhrase("آهنگ قبلی", "ترک قبلی", "موسیقی قبلی", "موزیک قبلی", "برگرد آهنگ قبلی") -> "PREVIOUS"
            ctx.hasAnyPhrase(
                "توقف موسیقی", "توقف آهنگ", "توقف موزیک",
                "موسیقی را متوقف کن", "آهنگ را متوقف کن", "موسیقی را قطع کن",
                "آهنگ را قطع کن", "پخش موسیقی را متوقف کن", "پخش را متوقف کن",
                "پاز کن", "مکث موسیقی", "مکث پخش",
            ) -> "PAUSE"
            ctx.hasAnyPhrase(
                "پخش موسیقی", "پخش آهنگ", "پخش موزیک",
                "موسیقی را پخش کن", "آهنگ را پخش کن", "موزیک را پخش کن",
                "ادامه پخش", "ادامه موسیقی", "ادامه آهنگ", "پلی کن",
            ) -> "PLAY"
            else -> null
        } ?: return null

        val slots = mapOf("media_op" to SlotValue.Text(mediaOp))
        return MatchResult(score = 0.93f, slots = slots, evidence = listOf("media:$mediaOp")) { c, r ->
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
