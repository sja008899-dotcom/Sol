package com.example.sol.brain

import com.example.sol.calendar.JalaliCalendar
import com.example.sol.memory.PersianNormalizer
import com.example.sol.text.ColloquialLexicon
import com.example.sol.text.PersianNumbers
import com.example.sol.text.PersianTextNormalizer

data class PhoneValidationResult(
    val isValid: Boolean,
    val e164: String? = null,
    val rawMatched: String = "",
    val rejectionReasonFa: String? = null,
)

class EntityExtractor {

    fun extractPhone(text: String): String? {
        return extractValidatedPhone(text)?.e164
    }

    fun extractValidatedPhone(text: String): PhoneValidationResult? {
        val cleaned = PersianTextNormalizer.cleanDisplayText(text)
        val candidate = Regex("(?:\\+98|0098|98|0)?9\\d{7,11}|0\\d{2,3}[-\\s]?\\d{6,9}").find(cleaned)?.value
            ?: return null
        return normalizeIranPhoneToE164(candidate)
    }

    fun normalizeIranPhoneToE164(raw: String): PhoneValidationResult {
        val normalizedDigits = PersianTextNormalizer.cleanDisplayText(raw)
        val compact = normalizedDigits.replace(Regex("[\\s\\-()]"), "")
        val hasPlus = compact.startsWith("+")
        val digitsOnly = compact.filter { it.isDigit() }
        if (digitsOnly.length < 10 || digitsOnly.length > 14) {
            return PhoneValidationResult(
                isValid = false,
                rawMatched = raw,
                rejectionReasonFa = "طول شمارهٔ تلفن معتبر نیست.",
            )
        }

        // Convert Iran formats to national digits without leading country code
        val national: String = when {
            compact.startsWith("+98") -> digitsOnly.removePrefix("98")
            compact.startsWith("0098") -> digitsOnly.removePrefix("0098")
            digitsOnly.startsWith("98") && digitsOnly.length == 12 -> digitsOnly.removePrefix("98")
            digitsOnly.startsWith("0") -> digitsOnly.removePrefix("0")
            !hasPlus && digitsOnly.startsWith("9") && digitsOnly.length == 10 -> digitsOnly
            else -> return PhoneValidationResult(
                isValid = false,
                rawMatched = raw,
                rejectionReasonFa = "پیش‌شمارهٔ معتبر ایران شناسایی نشد.",
            )
        }

        // Iran mobile: 10 national digits starting with 9
        if (national.startsWith("9")) {
            if (national.length != 10) {
                return PhoneValidationResult(
                    isValid = false,
                    rawMatched = raw,
                    rejectionReasonFa = "شماره موبایل ایران باید ۱۰ رقم بعد از پیش‌شماره داشته باشد.",
                )
            }
            val subscriber = national.substring(1)
            // Reject dummy repeated digits like 09000000000, 09111111111, 09999999999
            if (subscriber.all { it == subscriber.first() } || national.drop(2).all { it == '0' }) {
                return PhoneValidationResult(
                    isValid = false,
                    rawMatched = raw,
                    rejectionReasonFa = "این شماره تکراری یا نامعتبر است.",
                )
            }
            return PhoneValidationResult(
                isValid = true,
                e164 = "+98$national",
                rawMatched = raw,
            )
        }

        // Iran landline: 10 national digits (2-3 digit city code [1-8] + subscriber)
        if (national.length == 10 && national.first() in '1'..'8') {
            if (national.drop(3).all { it == '0' }) {
                return PhoneValidationResult(
                    isValid = false,
                    rawMatched = raw,
                    rejectionReasonFa = "شماره ثابت نامعتبر است.",
                )
            }
            return PhoneValidationResult(
                isValid = true,
                e164 = "+98$national",
                rawMatched = raw,
            )
        }

        return PhoneValidationResult(
            isValid = false,
            rawMatched = raw,
            rejectionReasonFa = "ساختار شماره تلفن معتبر نیست.",
        )
    }

    fun extractLocationReference(normalized: String): String? {
        return when {
            normalized.contains("شرکت") -> "WORK"
            normalized.contains("دفتر") -> "OFFICE"
            normalized.contains("خانه") || normalized.contains("خونه") || normalized.contains("منزل") -> "HOME"
            else -> null
        }
    }

    fun extractMessageAfterSay(clean: String): String? {
        val patterns = listOf(
            Regex("(?:بگو|بگم|بنویس|اطلاع بده|خبر بده|بفرست)\\s+(?:که\\s+)?(.+)$"),
            Regex("پیام(?:ک)?\\s+بده\\s+(?:به\\s+.+?\\s+)?(?:که\\s+)?(.+)$"),
        )
        for (p in patterns) {
            val found = p.find(clean)?.groupValues?.getOrNull(1)?.trim()
            if (!found.isNullOrBlank()) return found
        }
        return null
    }

    fun extractContactForCall(clean: String): String? {
        val byWith = Regex("(?:با|به)\\s+(.+?)\\s+(?:یه\\s+)?(?:تماس|زنگ)").find(clean)
            ?.groupValues?.getOrNull(1)?.trim()
        if (!byWith.isNullOrBlank()) return cleanContactStopWords(byWith)

        val dialNumber = Regex("شماره\\s+(.+?)\\s+(?:را|رو)\\s+بگیر").find(clean)
            ?.groupValues?.getOrNull(1)?.trim()
        if (!dialNumber.isNullOrBlank()) return cleanContactStopWords(dialNumber)

        val afterCall = Regex("(?:زنگ بزن به|تماس بگیر با|تماس با|بگیر به|شماره بگیر به)\\s+(.+?)(?:\\s+و\\s+بگو|\\s+بگو|$)")
            .find(clean)?.groupValues?.getOrNull(1)?.trim()
        return afterCall?.let(::cleanContactStopWords)?.takeIf { it.isNotBlank() }
    }

    fun extractContactForMessage(clean: String): String? {
        val p1 = Regex("(?:به|برای)\\s+(.+?)\\s+(?:یه\\s+)?(?:پیامک|پیام|اس ام اس|اس‌ام‌اس|اطلاع|خبر)")
            .find(clean)?.groupValues?.getOrNull(1)?.trim()
        if (!p1.isNullOrBlank()) return cleanContactStopWords(p1)

        val p2 = Regex("(?:پیامک|پیام|اس ام اس)\\s+(?:به|برای)\\s+(.+?)(?:\\s+(?:بده|بفرست|بگو|که)|$)")
            .find(clean)?.groupValues?.getOrNull(1)?.trim()
        return p2?.let(::cleanContactStopWords)?.takeIf { it.isNotBlank() }
    }

    fun fillSlotsForAction(
        action: SolAction,
        raw: String,
        lexicon: ColloquialLexicon = ColloquialLexicon.loadDefault(),
        confidence: Float = 0.8f,
        matchedRuleId: String = "STATISTICAL",
    ): ParsedCommand {
        val ctx = MatchContext.create(raw, lexicon)
        val phoneRes = extractValidatedPhone(ctx.cleaned)
        val validPhone = phoneRes?.takeIf { it.isValid }?.e164
        val locRef = extractLocationReference(ctx.normalized)

        return when (action) {
            SolAction.CALL -> {
                val contact = extractContactForCall(ctx.cleaned) ?: validPhone
                val msg = extractMessageAfterSay(ctx.cleaned)
                ParsedCommand(
                    action = SolAction.CALL,
                    rawText = raw,
                    normalizedText = ctx.normalized,
                    contact = contact,
                    phone = validPhone,
                    message = msg,
                    locationReference = locRef,
                    requireConfirmation = true,
                    confidence = confidence,
                    matchedRuleId = matchedRuleId,
                )
            }
            SolAction.SEND_SMS -> {
                val contact = extractContactForMessage(ctx.cleaned) ?: validPhone
                val msg = extractMessageAfterSay(ctx.cleaned)
                ParsedCommand(
                    action = SolAction.SEND_SMS,
                    rawText = raw,
                    normalizedText = ctx.normalized,
                    contact = contact,
                    phone = validPhone,
                    message = msg,
                    locationReference = locRef,
                    requireConfirmation = true,
                    confidence = confidence,
                    matchedRuleId = matchedRuleId,
                )
            }
            SolAction.MULTI_STEP_NOTIFY -> {
                val contact = extractContactForMessage(ctx.cleaned) ?: extractContactForCall(ctx.cleaned)
                val msg = extractMessageAfterSay(ctx.cleaned)
                ParsedCommand(
                    action = SolAction.MULTI_STEP_NOTIFY,
                    rawText = raw,
                    normalizedText = ctx.normalized,
                    contact = contact,
                    message = msg,
                    locationReference = locRef,
                    requireConfirmation = true,
                    confidence = confidence,
                    matchedRuleId = matchedRuleId,
                )
            }
            SolAction.SCHEDULE_REMINDER -> {
                val schedule = JalaliCalendar.parsePersianDateTimeCommand(raw)
                    ?: JalaliCalendar.parseDateTimeSlot(raw)
                val delayMatch = Regex("(\\d+)\\s*(?:دقیقه|ساعت|ثانیه|min)").find(ctx.numberConvertedNormalized)
                val delayMin = delayMatch?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 1L
                val hasTime = schedule != null || delayMatch != null
                ParsedCommand(
                    action = SolAction.SCHEDULE_REMINDER,
                    rawText = raw,
                    normalizedText = ctx.normalized,
                    message = schedule?.reminderMessage ?: ctx.cleaned,
                    delayMinutes = delayMin,
                    jalaliSchedule = schedule,
                    requireConfirmation = false,
                    confidence = confidence,
                    matchedRuleId = matchedRuleId,
                    hasExplicitTimeSlot = hasTime,
                )
            }
            SolAction.ADJUST_VOLUME -> {
                val delta = if (ctx.hasAnyToken("کم", "پایین", "اروم", "آروم", "کاهش", "ضعیف")) -2 else 2
                ParsedCommand(
                    action = SolAction.ADJUST_VOLUME,
                    rawText = raw,
                    normalizedText = ctx.normalized,
                    volumeDelta = delta,
                    requireConfirmation = false,
                    confidence = confidence,
                    matchedRuleId = matchedRuleId,
                )
            }
            SolAction.OPEN_SETTINGS -> {
                val settingTarget = when {
                    ctx.hasAnyToken("هواپیما", "پرواز", "airplane") -> "AIRPLANE"
                    ctx.hasAnyToken("دیتا", "دیتای", "داده") || ctx.hasPhrase("اینترنت همراه") -> "DATA"
                    ctx.hasAnyToken("وای", "وایفای", "اینترنت", "wifi") -> "WIFI"
                    ctx.hasAnyToken("بلوتوث", "bluetooth") -> "BLUETOOTH"
                    ctx.hasAnyToken("مکان", "جی", "gps", "لوکیشن") -> "LOCATION"
                    ctx.hasAnyToken("صدا", "آهنگ", "ویبره", "sound") -> "SOUND"
                    else -> "BATTERY"
                }
                val toggleRequested = ctx.hasAnyToken("روشن", "خاموش", "وصل", "قطع", "فعال", "غیرفعال")
                ParsedCommand(
                    action = SolAction.OPEN_SETTINGS,
                    rawText = raw,
                    normalizedText = ctx.normalized,
                    settingTarget = settingTarget,
                    slots = mapOf(
                        "setting_target" to SlotValue.Text(settingTarget),
                        "toggle_requested" to SlotValue.Flag(toggleRequested),
                    ),
                    requireConfirmation = false,
                    confidence = confidence,
                    matchedRuleId = matchedRuleId,
                )
            }
            SolAction.TIMER_STOPWATCH -> {
                val isStopwatch = ctx.hasAnyToken("کرنومتر", "کرونومتر") || ctx.hasPhrase("زمان سنج")
                val durationSec = if (!isStopwatch) TimerStopwatchMatcher.extractDurationSeconds(ctx) else 0L
                val mode = if (isStopwatch) "STOPWATCH" else "TIMER"
                val op = when {
                    isStopwatch && ctx.hasAnyToken("ریست", "صفر") -> "RESET"
                    isStopwatch && ctx.hasAnyToken("توقف", "نگهدار", "استاپ") -> "STOP"
                    !isStopwatch && ctx.hasAnyToken("لغو", "کنسل", "حذف") -> "CANCEL"
                    ctx.hasAnyToken("وضعیت", "چقدر", "مانده") && durationSec <= 0L -> "STATUS"
                    else -> "START"
                }
                val slots = buildMap<String, SlotValue> {
                    put("timer_mode", SlotValue.Text(mode))
                    put("timer_op", SlotValue.Text(op))
                    if (durationSec > 0L) put("duration_seconds", SlotValue.Number(durationSec.toDouble()))
                }
                ParsedCommand(
                    action = SolAction.TIMER_STOPWATCH,
                    rawText = raw,
                    normalizedText = ctx.normalized,
                    delayMinutes = (durationSec / 60L).coerceAtLeast(1L),
                    slots = slots,
                    requireConfirmation = false,
                    confidence = confidence,
                    matchedRuleId = matchedRuleId,
                )
            }
            SolAction.FLASHLIGHT -> {
                val torchFlag = when {
                    ctx.hasAnyToken("خاموش", "ببند", "قطع") -> false
                    ctx.hasAnyToken("روشن", "باز", "بزن") -> true
                    else -> null
                }
                ParsedCommand(
                    action = SolAction.FLASHLIGHT,
                    rawText = raw,
                    normalizedText = ctx.normalized,
                    slots = torchFlag?.let { mapOf("torch_enabled" to SlotValue.Flag(it)) } ?: emptyMap(),
                    requireConfirmation = false,
                    confidence = confidence,
                    matchedRuleId = matchedRuleId,
                )
            }
            SolAction.CREATE_CALENDAR_EVENT -> {
                val title = CalendarEventMatcher.extractEventTitle(ctx.cleaned)
                val range = JalaliCalendar.parseEventDateTimeRange(ctx.cleaned)
                val slots = buildMap<String, SlotValue> {
                    put("message", SlotValue.Text(title))
                    range?.let { put("event_begin_ms", SlotValue.Number(it.first.toDouble())) }
                }
                ParsedCommand(
                    action = SolAction.CREATE_CALENDAR_EVENT,
                    rawText = raw,
                    normalizedText = ctx.normalized,
                    message = title,
                    slots = slots,
                    requireConfirmation = false,
                    confidence = confidence,
                    matchedRuleId = matchedRuleId,
                )
            }
            SolAction.CALCULATOR -> ParsedCommand(
                action = SolAction.CALCULATOR,
                rawText = raw,
                normalizedText = ctx.normalized,
                message = ctx.cleaned,
                slots = mapOf("message" to SlotValue.Text(ctx.cleaned)),
                requireConfirmation = false,
                confidence = confidence,
                matchedRuleId = matchedRuleId,
            )
            SolAction.NOTES_TODO -> {
                val kind = when {
                    ctx.hasAnyPhrase("لیست خرید", "فهرست خرید", "سبد خرید") -> "SHOPPING"
                    ctx.hasAnyPhrase("کارهای امروز", "فهرست کار", "لیست کار", "کارهای روزانه") ||
                        ctx.hasAnyToken("کارهام", "تودو") -> "TODO"
                    else -> "NOTE"
                }
                val op = when {
                    ctx.hasAnyToken("حذف", "پاک") -> "DELETE"
                    ctx.hasAnyPhrase("انجام شد", "تیک بزن", "خریدم") || ctx.hasToken("تیک") -> "DONE"
                    ctx.hasAnyToken("چیه", "چیست", "بخوان", "بخون", "بگو", "نشان", "نشون") -> "LIST"
                    else -> "ADD"
                }
                val content = NotesTodoMatcher.extractNoteItemContent(ctx.cleaned)
                ParsedCommand(
                    action = SolAction.NOTES_TODO,
                    rawText = raw,
                    normalizedText = ctx.normalized,
                    message = content.ifBlank { null },
                    slots = buildMap {
                        put("note_kind", SlotValue.Text(kind))
                        put("note_op", SlotValue.Text(op))
                        if (content.isNotBlank()) put("message", SlotValue.Text(content))
                    },
                    requireConfirmation = false,
                    confidence = confidence,
                    matchedRuleId = matchedRuleId,
                )
            }
            SolAction.MEDIA_CONTROL -> {
                val op = when {
                    ctx.hasToken("بعدی") || ctx.hasToken("نکست") -> "NEXT"
                    ctx.hasToken("قبلی") -> "PREVIOUS"
                    ctx.hasAnyToken("توقف", "قطع", "متوقف", "پاز", "مکث") -> "PAUSE"
                    else -> "PLAY"
                }
                ParsedCommand(
                    action = SolAction.MEDIA_CONTROL,
                    rawText = raw,
                    normalizedText = ctx.normalized,
                    slots = mapOf("media_op" to SlotValue.Text(op)),
                    requireConfirmation = false,
                    confidence = confidence,
                    matchedRuleId = matchedRuleId,
                )
            }
            SolAction.OPEN_APP -> {
                val target = ctx.cleaned
                    .replace(Regex("^(?:برنامه|اپ|اپلیکیشن)\\s+"), "")
                    .replace(Regex("\\s*(?:رو|را)?\\s*(?:باز کن|اجرا کن|بیار بالا)$"), "")
                    .trim()
                ParsedCommand(
                    action = SolAction.OPEN_APP,
                    rawText = raw,
                    normalizedText = ctx.normalized,
                    targetApp = target.takeIf { it.isNotBlank() },
                    requireConfirmation = false,
                    confidence = confidence,
                    matchedRuleId = matchedRuleId,
                )
            }
            SolAction.FORGET_MEMORY -> {
                val target = ctx.cleaned
                    .replace(Regex("(?:را|رو)?\\s*(?:از حافظه)?\\s*(?:فراموش کن|پاک کن|حذف کن|یادت بره).*$"), "")
                    .trim()
                ParsedCommand(
                    action = SolAction.FORGET_MEMORY,
                    rawText = raw,
                    normalizedText = ctx.normalized,
                    aliasKey = target.takeIf { it.isNotBlank() },
                    requireConfirmation = true,
                    confidence = confidence,
                    matchedRuleId = matchedRuleId,
                )
            }
            SolAction.SET_CURRENT_LOCATION -> {
                val locText = ctx.cleaned
                    .replace(Regex("^(?:من\\s+)?(?:الان|در حال حاضر)\\s*(?:در)?\\s*"), "")
                    .replace(Regex("\\s*(?:هستم|هستیم)$"), "")
                    .trim()
                ParsedCommand(
                    action = SolAction.SET_CURRENT_LOCATION,
                    rawText = raw,
                    normalizedText = ctx.normalized,
                    locationReference = locText.ifBlank { ctx.cleaned },
                    requireConfirmation = false,
                    confidence = confidence,
                    matchedRuleId = matchedRuleId,
                )
            }
            SolAction.LEARN_FACT -> ParsedCommand(
                action = SolAction.LEARN_FACT,
                rawText = raw,
                normalizedText = ctx.normalized,
                message = ctx.cleaned,
                requireConfirmation = false,
                confidence = confidence,
                matchedRuleId = matchedRuleId,
            )
            SolAction.WEB_BOOK_LEARN_ROLE -> {
                val url = Regex("https?://\\S+").find(raw)?.value
                val cleanTopic = url ?: ctx.cleaned
                    .replace(Regex("(?:به اینترنت برو|از اینترنت|کتاب|مقاله|پیدا کن|دانلود کن|دریافت کن|بخون)"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .takeIf { it.length >= 2 }
                ParsedCommand(
                    action = SolAction.WEB_BOOK_LEARN_ROLE,
                    rawText = raw,
                    normalizedText = ctx.normalized,
                    bookQuery = cleanTopic,
                    requireConfirmation = true,
                    confidence = confidence,
                    matchedRuleId = matchedRuleId,
                )
            }
            SolAction.SEARCH_WEB -> {
                val cleanSearch = ctx.cleaned
                    .replace(Regex("(?:جست‌?وجوی وب|جست‌?وجو در وب|سرچ وب|جست‌?وجو کن|سرچ کن|تو وب|در وب|بگرد)"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .takeIf { it.length >= 2 }
                ParsedCommand(
                    action = SolAction.SEARCH_WEB,
                    rawText = raw,
                    normalizedText = ctx.normalized,
                    searchQuery = cleanSearch,
                    requireConfirmation = true,
                    confidence = confidence,
                    matchedRuleId = matchedRuleId,
                )
            }
            else -> ParsedCommand(
                action = action,
                rawText = raw,
                normalizedText = ctx.normalized,
                locationReference = locRef,
                phone = validPhone,
                requireConfirmation = action in setOf(SolAction.CALL, SolAction.SEND_SMS, SolAction.MULTI_STEP_NOTIFY, SolAction.FORGET_MEMORY),
                confidence = confidence,
                matchedRuleId = matchedRuleId,
            )
        }
    }

    private fun cleanContactStopWords(raw: String): String {
        return raw
            .removePrefix("آقای ")
            .removePrefix("خانم ")
            .removeSuffix(" رو")
            .removeSuffix(" را")
            .trim()
    }
}

