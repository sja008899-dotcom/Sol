package com.example.sol.brain

import com.example.sol.calendar.JalaliCalendar
import com.example.sol.memory.PersianNormalizer

class IntentParser(
    private val extractor: EntityExtractor = EntityExtractor(),
) {

    fun parse(raw: String): ParsedCommand {
        val strippedWake = stripWakePrefix(PersianNormalizer.cleanText(raw))
        val cleaned = stripPoliteFiller(strippedWake)
        val norm = PersianNormalizer.normalize(cleaned)
        val rawNorm = PersianNormalizer.normalize(strippedWake)

        // 0A. Show 20 capabilities catalog
        if (norm.contains("۲۰ قابلیت") || norm.contains("20 قابلیت") || norm.contains("فهرست قابلیت") || norm.contains("امکانات پس زمینه")) {
            return ParsedCommand(
                action = SolAction.SHOW_CAPABILITIES,
                rawText = raw,
                normalizedText = norm,
                requireConfirmation = false,
            )
        }

        // 0B. Advanced Internet Fetch / Download / Search + Book & Role Learning
        // Supports BOTH formal book commands AND natural spoken download/search requests:
        // e.g. «از اینترنت درباره هوش مصنوعی دانلود کن», «یک چیزی از اینترنت دانلود کن», «برو تو گوگل ... پیدا کن»
        val hasUrl = cleaned.contains("http://", ignoreCase = true) || cleaned.contains("https://", ignoreCase = true)
        val hasBookWord = norm.contains("کتاب")
        val hasInternetWord = norm.contains("اینترنت") || norm.contains("آنلاین") || norm.contains("وب") ||
            norm.contains("سایت") || norm.contains("گوگل") || norm.contains("لینک") || norm.contains("مقاله") || hasUrl
        val hasFetchVerb = norm.contains("دانلود") || norm.contains("پیدا") || norm.contains("بیار") ||
            norm.contains("جستجو") || norm.contains("جست جو") || norm.contains("سرچ") || norm.contains("بگیر") ||
            norm.contains("بخوان") || norm.contains("بخون") || norm.contains("برو")
        val hasRoleOrVoiceLearn = norm.contains("نقش") || norm.contains("توضیحات صوتی") ||
            norm.contains("توضیح صوتی") || norm.contains("یاد بگیر") || norm.contains("حافظه")

        val isOpenSettingsNotDownload = (norm.contains("وای فای") || norm.contains("وایفای") || norm.contains("تنظیمات")) &&
            !norm.contains("دانلود") && !hasBookWord

        if (!isOpenSettingsNotDownload &&
            !norm.startsWith("فراموش") &&
            !norm.contains("طبق نقش") &&
            (hasUrl ||
                (norm.contains("دانلود") && !norm.contains("مدل vosk")) ||
                (hasBookWord && (hasInternetWord || hasRoleOrVoiceLearn)) ||
                (hasInternetWord && hasFetchVerb))
        ) {
            val extractedQuery = extractBookOrWebQuery(cleaned)
            val roleHint = Regex("(?:به عنوان|نقش)\\s+([آ-یa-zA-Z0-9\\s]{2,35})").find(cleaned)
                ?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val runBg = norm.contains("پس زمینه") || norm.contains("پس‌زمینه")
            return ParsedCommand(
                action = SolAction.WEB_BOOK_LEARN_ROLE,
                rawText = raw,
                normalizedText = norm,
                bookQuery = extractedQuery.ifBlank { null },
                roleName = roleHint.ifBlank { null },
                backgroundJobId = if (runBg) "bg_book_fetch_worker" else null,
                requireConfirmation = true,
            )
        }

        // 0C. Teach a Role directly by voice/text: «به عنوان مربی ...» / «نقش تو ... است»
        val teachRoleMatch = Regex("(?:از این به بعد\\s+)?(?:نقش تو|نقشت|به عنوان)\\s+(.+?)(?:\\s+(?:است|باش|عمل کن))?$").find(cleaned)
        if (teachRoleMatch != null && !norm.contains("طبق نقش")) {
            val role = teachRoleMatch.groupValues[1].trim()
            return ParsedCommand(
                action = SolAction.TEACH_ROLE_VOICE,
                rawText = raw,
                normalizedText = norm,
                roleName = role,
                message = cleaned,
                requireConfirmation = false,
            )
        }

        // 0D. Consult learned book/role: «طبق نقشت ...» / «بر اساس کتاب ...»
        if (norm.contains("طبق نقش") || norm.contains("بر اساس کتاب") || norm.contains("با توجه به کتاب") || norm.contains("راهنماییم کن")) {
            return ParsedCommand(
                action = SolAction.CONSULT_ROLE_BOOK,
                rawText = raw,
                normalizedText = norm,
                message = cleaned,
                requireConfirmation = false,
            )
        }

        // 0E. Exact Jalali Scheduled Reminder or Timed Message
        val reminderMatch = Regex("(\\d+)\\s*(?:دقیقه|ثانیه|ساعت)\\s*(?:دیگر|دیگه|بعد)\\s*(?:یادآوری کن|یادم بنداز|به من بگو)\\s*(.*)$").find(cleaned)
        val parsedDelayMinutes = if (reminderMatch != null) {
            val num = reminderMatch.groupValues[1].toLongOrNull() ?: 1L
            when {
                norm.contains("ساعت") -> num * 60L
                norm.contains("ثانیه") -> 1L
                else -> num.coerceAtLeast(1L)
            }
        } else {
            0L
        }

        val jalaliParsed = JalaliCalendar.parsePersianDateTimeCommand(cleaned)
        if (jalaliParsed != null) {
            return ParsedCommand(
                action = SolAction.SCHEDULE_REMINDER,
                rawText = raw,
                normalizedText = norm,
                delayMinutes = parsedDelayMinutes,
                message = jalaliParsed.reminderMessage,
                contact = jalaliParsed.contactHint,
                jalaliSchedule = jalaliParsed,
                backgroundJobId = "bg_exact_alarm",
                requireConfirmation = false,
            )
        }
        if (reminderMatch != null) {
            val num = reminderMatch.groupValues[1].toLongOrNull() ?: 1L
            val minutes = when {
                norm.contains("ساعت") -> num * 60L
                norm.contains("ثانیه") -> 1L
                else -> num.coerceAtLeast(1L)
            }
            val msg = reminderMatch.groupValues[2].trim().ifBlank { "یادآور زمان‌بندی‌شده Sol" }
            return ParsedCommand(
                action = SolAction.SCHEDULE_REMINDER,
                rawText = raw,
                normalizedText = norm,
                delayMinutes = minutes,
                message = msg,
                backgroundJobId = "bg_reminder",
                requireConfirmation = false,
            )
        }
        // Timeless spoken reminder: «یادم بنداز دارو بخورم»
        val quickReminderMatch = Regex("(?:یادم بنداز|یادآوری کن|آلارم بذار)\\s*(?:که\\s+)?(.+)$").find(cleaned)
        if (quickReminderMatch != null) {
            val msg = quickReminderMatch.groupValues[1].trim().ifBlank { "یادآور Sol" }
            return ParsedCommand(
                action = SolAction.SCHEDULE_REMINDER,
                rawText = raw,
                normalizedText = norm,
                delayMinutes = 15L,
                message = msg,
                backgroundJobId = "bg_reminder",
                requireConfirmation = false,
            )
        }

        // 0F. Background Job Controls
        if (norm.contains("پس زمینه") || norm.contains("پس‌زمینه") || norm.contains("سرویس صوتی") || norm.contains("پشتیبان")) {
            val jobId = when {
                norm.contains("پشتیبان") -> "bg_memory_backup"
                norm.contains("باتری") -> "bg_battery_guard"
                norm.contains("صبحگاهی") || norm.contains("روتین") -> "bg_daily_briefing"
                norm.contains("مرتب") || norm.contains("نمایه") -> "bg_memory_indexer"
                norm.contains("موقعیت") -> "bg_location_checkin"
                norm.contains("سوابق") || norm.contains("بهینه") || norm.contains("پاکسازی") -> "bg_history_cleanup"
                norm.contains("سرویس صوتی") || norm.contains("روخوانی") -> "bg_tts_reader_service"
                else -> "bg_memory_backup"
            }
            return ParsedCommand(
                action = SolAction.BACKGROUND_CONTROL,
                rawText = raw,
                normalizedText = norm,
                backgroundJobId = jobId,
                message = cleaned,
                requireConfirmation = false,
            )
        }

        // 1. Learning Alias: «هر وقت میگم دفتر، منظورم شرکت کارنو صنعت ماکیان است»
        val aliasRegex = Regex(
            "(?:از این به بعد\\s+)?هر\\s*وقت\\s+(?:میگم|می‌گم|گفتم)\\s+[«\"]?(.+?)[»\"]?[,،\\s]+(?:منظورم|یعنی)\\s+[«\"]?(.+?)[»\"]?(?:\\s+(?:است|هست))?$"
        )
        aliasRegex.find(cleaned)?.let { m ->
            val key = m.groupValues[1].trim()
            val value = m.groupValues[2].trim()
            return ParsedCommand(
                action = SolAction.LEARN_ALIAS,
                rawText = raw,
                normalizedText = norm,
                aliasKey = key,
                aliasValue = value,
                requireConfirmation = false,
            )
        }

        // 2. Learning Fact / Owner Name: «یاد بگیر ...» / «یادت باشه ...» / «اسم من ... هست»
        val ownerNameMatch = Regex("^(?:اسم|نام)\\s+من\\s+(.+?)\\s+(?:است|هست)$").find(cleaned)
        if (ownerNameMatch != null) {
            return ParsedCommand(
                action = SolAction.LEARN_FACT,
                rawText = raw,
                normalizedText = norm,
                aliasKey = "owner_name",
                aliasValue = ownerNameMatch.groupValues[1].trim(),
                message = cleaned,
                requireConfirmation = false,
            )
        }

        val learnFactMatch = Regex(
            "(?:اینو\\s+|این را\\s+)?(?:یاد بگیر|به خاطر بسپار|ذخیره کن|یادداشت کن|یادت باشه|یادت بمونه|بنویس)\\s*(?:که\\s+)?(.+)$"
        ).find(cleaned)
        if (learnFactMatch != null) {
            val fact = learnFactMatch.groupValues[1].trim()
            if (fact.isNotBlank()) {
                return ParsedCommand(
                    action = SolAction.LEARN_FACT,
                    rawText = raw,
                    normalizedText = norm,
                    message = fact,
                    requireConfirmation = false,
                )
            }
        }

        // 3. Forget memory
        if (norm.contains("فراموش کن") || (norm.contains("پاک کن") && norm.contains("حافظه"))) {
            val target = cleaned
                .replace(Regex("(?:از\\s+حافظه\\s+)?(?:فراموش کن|پاک کن)"), "")
                .replace(Regex("(?:را|رو)$"), "")
                .trim()
            return ParsedCommand(
                action = SolAction.FORGET_MEMORY,
                rawText = raw,
                normalizedText = norm,
                aliasKey = target,
                requireConfirmation = false,
            )
        }

        // 4. List memory
        if (norm.contains("حافظه") || norm.contains("چی یادت") || norm.contains("چیا بلدی") || norm.contains("چیا ذخیره")) {
            return ParsedCommand(
                action = SolAction.LIST_MEMORY,
                rawText = raw,
                normalizedText = norm,
                requireConfirmation = false,
            )
        }

        // 5. Save person with phone
        val phone = extractor.extractPhone(cleaned)
        if (phone != null && !norm.contains("زنگ") && !norm.contains("تماس") && !norm.contains("پیام")) {
            val personMatch = Regex("(?:شماره\\s+)?([آ-یa-zA-Z\\s]{2,25})").find(cleaned)
            val candidateName = personMatch?.groupValues?.getOrNull(1)
                ?.replace("همکار منه", "")
                ?.replace("دوست منه", "")
                ?.replace("شماره", "")
                ?.replace("است", "")
                ?.trim()
                .orEmpty()
            val relation = when {
                norm.contains("همکار") -> "همکار"
                norm.contains("دوست") -> "دوست"
                norm.contains("خانواده") || norm.contains("برادر") || norm.contains("خواهر") -> "خانواده"
                norm.contains("مدیر") -> "مدیر"
                else -> ""
            }
            if (candidateName.isNotBlank()) {
                return ParsedCommand(
                    action = SolAction.SAVE_PERSON,
                    rawText = raw,
                    normalizedText = norm,
                    contact = candidateName,
                    phone = phone,
                    relation = relation,
                    requireConfirmation = false,
                )
            }
        }

        // 6. Current confirmed location
        val setLocMatch = Regex("^(?:من\\s+)?الان\\s+(?:در|تو|توی|به)?\\s*(.+?)\\s+(?:هستم|ام|رسیدم)$").find(cleaned)
            ?: Regex("^(?:من\\s+)?(?:رسیدم|اومدم)\\s+(.+)$").find(cleaned)
            ?: Regex("^موقعیت(?: فعلی)?(?: من)?\\s+(?:الان\\s+)?(.+?)(?:\\s+(?:است|هست))?$").find(cleaned)
        if (setLocMatch != null) {
            val loc = setLocMatch.groupValues[1].trim()
            return ParsedCommand(
                action = SolAction.SET_CURRENT_LOCATION,
                rawText = raw,
                normalizedText = norm,
                locationReference = loc,
                requireConfirmation = false,
            )
        }
        if (norm.contains("الان کجام") || norm.contains("موقعیت فعلی") || norm.contains("کجا هستم") || norm.contains("موقعیتم")) {
            return ParsedCommand(
                action = SolAction.GET_CURRENT_LOCATION,
                rawText = raw,
                normalizedText = norm,
                requireConfirmation = false,
            )
        }

        // 7. Save location
        val saveLocMatch = Regex("(?:آدرس|محل|مکان)\\s+(شرکت|دفتر|خانه|منزل|[آ-ی]+)\\s+(.+?)(?:\\s+(?:است|هست))?$").find(cleaned)
        if (saveLocMatch != null && !norm.contains("وضعیت") && !norm.contains("کجاست") && !norm.contains("چیه")) {
            return ParsedCommand(
                action = SolAction.SAVE_LOCATION,
                rawText = raw,
                normalizedText = norm,
                aliasKey = saveLocMatch.groupValues[1].trim(),
                aliasValue = saveLocMatch.groupValues[2].trim(),
                requireConfirmation = false,
            )
        }

        // 8. Multi-step notify command
        if ((norm.contains("اطلاع بده") || norm.contains("خبر بده") || norm.contains("گزارش کن")) &&
            (norm.contains("وضعیت") || norm.contains("بعد") || norm.contains("گزارش"))
        ) {
            val contact = extractor.extractContactForMessage(cleaned)
                ?: Regex("به\\s+(.+?)\\s+(?:اطلاع|خبر)").find(cleaned)?.groupValues?.getOrNull(1)?.trim()
            val locRef = extractor.extractLocationReference(norm)
            val msg = extractor.extractMessageAfterSay(cleaned) ?: cleaned
            return ParsedCommand(
                action = SolAction.MULTI_STEP_NOTIFY,
                rawText = raw,
                normalizedText = norm,
                contact = contact,
                message = msg,
                locationReference = locRef,
                requireConfirmation = true,
                steps = listOf(
                    "یافتن اطلاعات مخاطب",
                    "خواندن موقعیت فعلی تأییدشده",
                    "تنظیم پیام با معرفی شفاف Sol",
                    "دریافت تأیید کاربر",
                    "اجرای اقدام ارتباطی مجاز",
                    "ثبت گزارش نهایی در سوابق",
                ),
            )
        }

        // 9. Call command
        if (norm.contains("تماس بگیر") || norm.contains("زنگ بزن") || norm.startsWith("تماس با ") ||
            (norm.contains("شماره") && norm.contains("بگیر"))
        ) {
            val contact = extractor.extractContactForCall(cleaned)
            val msg = extractor.extractMessageAfterSay(cleaned)
            val locRef = extractor.extractLocationReference(norm)
            return ParsedCommand(
                action = SolAction.CALL,
                rawText = raw,
                normalizedText = norm,
                contact = contact,
                message = msg,
                locationReference = locRef,
                requireConfirmation = true,
            )
        }

        // 10. SMS command
        if (norm.contains("پیام") || norm.contains("پیامک") || norm.contains("اس ام اس") || norm.contains("اطلاع بده") || norm.contains("خبر بده")) {
            val contact = extractor.extractContactForMessage(cleaned)
            val msg = extractor.extractMessageAfterSay(cleaned)
            val locRef = extractor.extractLocationReference(norm)
            return ParsedCommand(
                action = SolAction.SEND_SMS,
                rawText = raw,
                normalizedText = norm,
                contact = contact,
                message = msg,
                locationReference = locRef,
                requireConfirmation = true,
            )
        }

        // 11. Device Settings / Status / Volume / App Launcher / Routines
        if (norm.contains("باتری") || norm.contains("شارژ") || norm.contains("وضعیت گوشی") || norm.contains("وضعیت دستگاه")) {
            return ParsedCommand(
                action = SolAction.DEVICE_STATUS,
                rawText = raw,
                normalizedText = norm,
                requireConfirmation = false,
            )
        }

        if ((norm.contains("صدا") || norm.contains("ولوم")) &&
            (norm.contains("زیاد") || norm.contains("بلند") || norm.contains("بالا") ||
                norm.contains("کم") || norm.contains("آروم") || norm.contains("پایین") ||
                norm.contains("کاهش") || norm.contains("افزایش") || norm.contains("ضعیف"))
        ) {
            val delta = if (norm.contains("زیاد") || norm.contains("بلند") || norm.contains("افزایش") || norm.contains("بالا") || norm.contains("ضعیف")) 2 else -2
            return ParsedCommand(
                action = SolAction.ADJUST_VOLUME,
                rawText = raw,
                normalizedText = norm,
                volumeDelta = delta,
                requireConfirmation = false,
            )
        }

        if (norm.contains("وای فای") || norm.contains("وایفای") || norm.contains("wifi") ||
            norm.contains("تنظیمات مکان") || norm.contains("مکان یاب") || norm.contains("لوکیشن") || norm.contains("gps") ||
            norm.contains("بلوتوث") || norm.contains("تنظیمات صدا") || norm.contains("تنظیمات باتری") || norm.contains("تنظیمات اینترنت")
        ) {
            val target = when {
                norm.contains("وای") || norm.contains("wifi") || norm.contains("اینترنت") -> "WIFI"
                norm.contains("مکان") || norm.contains("لوکیشن") || norm.contains("gps") -> "LOCATION"
                norm.contains("بلوتوث") -> "BLUETOOTH"
                norm.contains("صدا") -> "SOUND"
                else -> "BATTERY"
            }
            return ParsedCommand(
                action = SolAction.OPEN_SETTINGS,
                rawText = raw,
                normalizedText = norm,
                settingTarget = target,
                requireConfirmation = false,
            )
        }

        if (norm.contains("روتین") || norm.contains("حالت کار") || norm.contains("حالت شب")) {
            val routine = if (norm.contains("شب")) "NIGHT" else "WORK"
            return ParsedCommand(
                action = SolAction.RUN_ROUTINE,
                rawText = raw,
                normalizedText = norm,
                routineName = routine,
                requireConfirmation = false,
            )
        }

        val openAppMatch = Regex("(?:برنامه|اپ|اپلیکیشن)?\\s*(.+?)\\s+(?:را\\s+|رو\\s+)?باز کن$").find(cleaned)
            ?: Regex("^باز کن\\s+(.+)$").find(cleaned)
        if (openAppMatch != null) {
            val targetApp = openAppMatch.groupValues[1].trim()
            return ParsedCommand(
                action = SolAction.OPEN_APP,
                rawText = raw,
                normalizedText = norm,
                targetApp = targetApp,
                requireConfirmation = false,
            )
        }

        // 12. Time, Date (Jalali), Identity, Greetings, Help
        val isTimeQuestion = norm == "ساعت" || norm.contains("ساعت چنده") || norm.contains("ساعت چند") ||
            norm.startsWith("ساعت ") || norm.contains("چند ساعت") && norm.contains("الان")
        val isDateQuestion = norm == "امروز" || norm.contains("تاریخ") || norm.contains("امروز چند") ||
            norm.contains("امروز چه روز") || norm.contains("امروز چیه") ||
            norm.contains("چندم برج") || norm.contains("چندم ماه") || norm.contains("چندمه") ||
            norm.contains("شمسی") || norm.contains("چه روزی") || norm.contains("چندشنبه") || norm.contains("کبیسه")
        if (isTimeQuestion || isDateQuestion) {
            return ParsedCommand(action = SolAction.TIME_DATE, rawText = raw, normalizedText = norm, requireConfirmation = false)
        }
        if (norm.contains("اسم تو") || norm.contains("نام تو") || norm.contains("کی هستی") || norm.contains("چیستی") || norm.contains("اسمت چیه")) {
            return ParsedCommand(action = SolAction.IDENTITY, rawText = raw, normalizedText = norm, requireConfirmation = false)
        }
        if (norm.contains("کمک") || norm.contains("راهنما") || norm.contains("چیکار میتونی") ||
            norm.contains("چکار میتونی") || norm.contains("چه کارهایی") || norm.contains("کاربردت") || norm.contains("چی بلدی")
        ) {
            return ParsedCommand(action = SolAction.HELP, rawText = raw, normalizedText = norm, requireConfirmation = false)
        }
        if (rawNorm == "سلام" || rawNorm.startsWith("سلام ") || rawNorm == "درود" ||
            rawNorm.contains("خوبی") || rawNorm.contains("چطوری") ||
            rawNorm.startsWith("صبح بخیر") || rawNorm.startsWith("شب بخیر") ||
            rawNorm.contains("مرسی") || rawNorm.contains("ممنون") || rawNorm.contains("متشکرم") || rawNorm.contains("خسته نباشی")
        ) {
            return ParsedCommand(action = SolAction.GREETING, rawText = raw, normalizedText = norm, requireConfirmation = false)
        }

        return ParsedCommand(action = SolAction.UNKNOWN, rawText = raw, normalizedText = norm, requireConfirmation = false)
    }

    private fun extractBookOrWebQuery(cleaned: String): String {
        val urlMatch = Regex("(https?://\\S+)").find(cleaned)
        if (urlMatch != null) return urlMatch.groupValues[1].trim()

        val afterBook = Regex("کتاب\\s+[«\"]?(.+?)[»\"]?(?:\\s+(?:را|رو)\\s+|\\s+(?:پیدا|دانلود|بیار|بخوان|بخون|وانمود|و\\s+با)|$)").find(cleaned)
            ?.groupValues?.getOrNull(1)?.trim()
        if (!afterBook.isNullOrBlank()) return afterBook

        val aboutMatch = Regex("(?:درباره|در مورد|راجب|راجع به)\\s+(.+?)(?:\\s+(?:از اینترنت|تو اینترنت|پیدا کن|دانلود کن|بیار|جستجو کن|جست‌وجو کن|سرچ کن|بگو|بخوان|بخون)|$)").find(cleaned)
            ?.groupValues?.getOrNull(1)?.trim()
        if (!aboutMatch.isNullOrBlank() && !isGenericPlaceholder(aboutMatch)) return aboutMatch

        val cleanedCandidate = cleaned
            .replace(Regex("(?:برو\\s+(?:به|تو|توی)?\\s*(?:اینترنت|وب|گوگل))"), "")
            .replace(Regex("(?:از|تو|توی|در)\\s*(?:اینترنت|وب|گوگل)"), "")
            .replace(Regex("(?:برام|برای من|لطفاً|لطفا|میشه|می‌تونی|می تونی|یه|یک)"), "")
            .replace(Regex("(?:دانلود کن|پیدا کن|جستجو کن|جست‌وجو کن|سرچ کن|بیار|بگیر|بخوان|بخون|باز کن)"), "")
            .replace(Regex("(?:را|رو|به حافظه|و نقشت اضافه کن)$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        return if (isGenericPlaceholder(cleanedCandidate)) "" else cleanedCandidate
    }

    private fun isGenericPlaceholder(txt: String): Boolean {
        val n = PersianNormalizer.normalize(txt)
        return n.isBlank() || n in setOf("چیز", "چیزی", "یک چیز", "یه چیز", "یه چیزی", "یک چیزی", "مطلب", "مطلبی", "فایل", "فایلی", "کتاب", "صفحه")
    }

    private fun stripWakePrefix(text: String): String {
        return text
            .replace(Regex("^(?:سلام\\s+)?(?:sol|سول|سال)[,،:\\s]+", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun stripPoliteFiller(text: String): String {
        return text
            .replace(Regex("^(?:ببخشید|لطفاً|لطفا|بی\\s*زحمت|بیزحمت|اگر\\s+میشه|اگه\\s+میشه|میشه|می‌تونی|میتونی)\\s+"), "")
            .replace(Regex("^(?:لطفاً|لطفا)\\s+"), "")
            .trim()
    }
}
