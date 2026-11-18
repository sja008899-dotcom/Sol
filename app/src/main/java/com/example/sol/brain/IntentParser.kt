package com.example.sol.brain

import com.example.sol.memory.PersianNormalizer

class IntentParser(
    private val extractor: EntityExtractor = EntityExtractor(),
) {

    fun parse(raw: String): ParsedCommand {
        val cleaned = stripWakePrefix(PersianNormalizer.cleanText(raw))
        val norm = PersianNormalizer.normalize(cleaned)

        // 0A. Show 20 capabilities catalog
        if (norm.contains("۲۰ قابلیت") || norm.contains("20 قابلیت") || norm.contains("فهرست قابلیت") || norm.contains("امکانات پس زمینه")) {
            return ParsedCommand(
                action = SolAction.SHOW_CAPABILITIES,
                rawText = raw,
                normalizedText = norm,
                requireConfirmation = false,
            )
        }

        // 0B. Advanced Command: Go to internet + fetch book + add to memory & role with voice explanation
        val hasBookWord = norm.contains("کتاب")
        val hasInternetWord = norm.contains("اینترنت") || norm.contains("آنلاین") || norm.contains("وب") || norm.contains("دانلود") || norm.contains("پیدا کن") || norm.contains("بیار")
        val hasRoleOrVoiceLearn = norm.contains("نقش") || norm.contains("توضیحات صوتی") || norm.contains("توضیح صوتی") || norm.contains("یاد بگیر") || norm.contains("حافظه")
        if (hasBookWord && (hasInternetWord || hasRoleOrVoiceLearn) && !norm.startsWith("فراموش") && !norm.contains("طبق نقش")) {
            val extractedTitle = extractBookTitle(cleaned)
            val roleHint = Regex("(?:به عنوان|نقش)\\s+([آ-یa-zA-Z0-9\\s]{2,35})").find(cleaned)
                ?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val runBg = norm.contains("پس زمینه") || norm.contains("پس‌زمینه")
            return ParsedCommand(
                action = SolAction.WEB_BOOK_LEARN_ROLE,
                rawText = raw,
                normalizedText = norm,
                bookQuery = extractedTitle,
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

        // 0E. Scheduled Reminder in background: «5 دقیقه دیگر یادآوری کن ...»
        val reminderMatch = Regex("(\\d+)\\s*(?:دقیقه|ثانیه|ساعت)\\s*(?:دیگر|دیگه|بعد)\\s*(?:یادآوری کن|یادم بنداز|به من بگو)\\s*(.*)$").find(cleaned)
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
            "(?:از این به بعد\\s+)?هر\\s*وقت\\s+(?:میگم|می‌گم|گفتم)\\s+[«\"]?(.+?)[»\"]?[,،\\s]+(?:منظورم|یعنی)\\s+[«\"]?(.+?)[»\"]?(?:\\s+است)?$"
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

        // 2. Learning Fact: «یاد بگیر ...» / «به خاطر بسپار ...»
        if (norm.startsWith("یاد بگیر ") || norm.startsWith("به خاطر بسپار ") || norm.startsWith("ذخیره کن ")) {
            val fact = cleaned
                .removePrefix("یاد بگیر ")
                .removePrefix("به خاطر بسپار ")
                .removePrefix("ذخیره کن ")
                .trim()
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

        // 3. Forget memory: «فراموش کن ...»
        if (norm.startsWith("فراموش کن ")) {
            val target = cleaned.removePrefix("فراموش کن ").trim()
            return ParsedCommand(
                action = SolAction.FORGET_MEMORY,
                rawText = raw,
                normalizedText = norm,
                aliasKey = target,
                requireConfirmation = false,
            )
        }

        // 4. List memory
        if (norm.contains("حافظه") || norm.contains("چی یادت") || norm.contains("چی بلدی")) {
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
        val setLocMatch = Regex("^(?:من\\s+)?الان\\s+(?:در\\s+)?(.+?)\\s+هستم$").find(cleaned)
            ?: Regex("^موقعیت فعلی(?: من)?\\s+(.+?)(?:\\s+است)?$").find(cleaned)
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
        if (norm.contains("الان کجام") || norm.contains("موقعیت فعلی") || norm.contains("کجا هستم")) {
            return ParsedCommand(
                action = SolAction.GET_CURRENT_LOCATION,
                rawText = raw,
                normalizedText = norm,
                requireConfirmation = false,
            )
        }

        // 7. Save location
        val saveLocMatch = Regex("(?:آدرس|محل|مکان)\\s+(شرکت|دفتر|خانه|منزل|[آ-ی]+)\\s+(.+?)(?:\\s+است)?$").find(cleaned)
        if (saveLocMatch != null && !norm.contains("وضعیت")) {
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
        if (norm.contains("تماس بگیر") || norm.contains("زنگ بزن") || norm.startsWith("تماس با ")) {
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

        if (norm.contains("صدا") && (norm.contains("زیاد") || norm.contains("بلند") || norm.contains("کم") || norm.contains("آروم") || norm.contains("کاهش") || norm.contains("افزایش"))) {
            val delta = if (norm.contains("زیاد") || norm.contains("بلند") || norm.contains("افزایش")) 2 else -2
            return ParsedCommand(
                action = SolAction.ADJUST_VOLUME,
                rawText = raw,
                normalizedText = norm,
                volumeDelta = delta,
                requireConfirmation = false,
            )
        }

        if (norm.contains("وای فای") || norm.contains("وایفای") || norm.contains("wifi") ||
            norm.contains("تنظیمات مکان") || norm.contains("مکان یاب") || norm.contains("gps") ||
            norm.contains("بلوتوث") || norm.contains("تنظیمات صدا") || norm.contains("تنظیمات باتری")
        ) {
            val target = when {
                norm.contains("وای") || norm.contains("wifi") -> "WIFI"
                norm.contains("مکان") || norm.contains("gps") -> "LOCATION"
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

        val openAppMatch = Regex("(?:برنامه|اپ|اپلیکیشن)?\\s*(.+?)\\s+(?:را\\s+)?باز کن$").find(cleaned)
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

        // 12. Time, Date, Identity, Greetings, Help
        if (norm.contains("ساعت") || norm.contains("تاریخ") || norm.contains("امروز")) {
            return ParsedCommand(action = SolAction.TIME_DATE, rawText = raw, normalizedText = norm, requireConfirmation = false)
        }
        if (norm.contains("اسم تو") || norm.contains("نام تو") || norm.contains("کی هستی") || norm.contains("چیستی")) {
            return ParsedCommand(action = SolAction.IDENTITY, rawText = raw, normalizedText = norm, requireConfirmation = false)
        }
        if (norm.contains("کمک") || norm.contains("راهنما") || norm.contains("چیکار میتونی") || norm.contains("چه کارهایی")) {
            return ParsedCommand(action = SolAction.HELP, rawText = raw, normalizedText = norm, requireConfirmation = false)
        }
        if (norm == "سلام" || norm.startsWith("سلام ") || norm == "درود" || norm.startsWith("صبح بخیر") || norm.startsWith("شب بخیر")) {
            return ParsedCommand(action = SolAction.GREETING, rawText = raw, normalizedText = norm, requireConfirmation = false)
        }

        return ParsedCommand(action = SolAction.UNKNOWN, rawText = raw, normalizedText = norm, requireConfirmation = false)
    }

    private fun extractBookTitle(cleaned: String): String {
        val afterBook = Regex("کتاب\\s+[«\"]?(.+?)[»\"]?(?:\\s+(?:را|رو)\\s+|\\s+(?:پیدا|دانلود|بیار|بخوان|وانمود|و\\s+با)|$)").find(cleaned)
            ?.groupValues?.getOrNull(1)?.trim()
        if (!afterBook.isNullOrBlank()) return afterBook
        return cleaned
            .replace(Regex(".*کتاب\\s+"), "")
            .replace(Regex("\\s+(?:را|رو|پیدا کن|دانلود کن|بیار|بخوان|به حافظه).*"), "")
            .trim()
    }

    private fun stripWakePrefix(text: String): String {
        return text
            .replace(Regex("^(?:سلام\\s+)?(?:sol|سول|سال)[,،:\\s]+", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
