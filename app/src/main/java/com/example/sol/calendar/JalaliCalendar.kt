package com.example.sol.calendar

import com.example.sol.memory.PersianNormalizer
import java.util.Calendar
import java.util.Locale

data class JalaliDate(
    val year: Int,
    val month: Int, // 1..12
    val day: Int,   // 1..31
    val dayOfWeek: Int, // Calendar.SATURDAY .. Calendar.FRIDAY
) {
    val monthNameFa: String get() = JalaliCalendar.MONTH_NAMES_FA.getOrElse(month - 1) { "" }
    val weekdayNameFa: String get() = JalaliCalendar.weekdayNameFa(dayOfWeek)

    fun formatFullFa(): String = "$weekdayNameFa $day $monthNameFa $year"
    fun formatShortFa(): String = "$year/${month.toString().padStart(2, '0')}/${day.toString().padStart(2, '0')}"
}

data class ParsedJalaliSchedule(
    val triggerAtMillis: Long,
    val jalaliLabel: String,
    val timeLabel: String,
    val reminderMessage: String,
    val contactHint: String? = null,
    val isMessageReminder: Boolean = false,
)

object JalaliCalendar {

    val MONTH_NAMES_FA = listOf(
        "فروردین", "اردیبهشت", "خرداد",
        "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر",
        "دی", "بهمن", "اسفند",
    )

    private val WEEKDAY_KEYWORDS = listOf(
        "چهارشنبه" to Calendar.WEDNESDAY,
        "پنجشنبه" to Calendar.THURSDAY,
        "پنج شنبه" to Calendar.THURSDAY,
        "سه شنبه" to Calendar.TUESDAY,
        "سه‌شنبه" to Calendar.TUESDAY,
        "یکشنبه" to Calendar.SUNDAY,
        "دوشنبه" to Calendar.MONDAY,
        "شنبه" to Calendar.SATURDAY,
        "جمعه" to Calendar.FRIDAY,
    )

    fun weekdayNameFa(calendarDayOfWeek: Int): String = when (calendarDayOfWeek) {
        Calendar.SATURDAY -> "شنبه"
        Calendar.SUNDAY -> "یکشنبه"
        Calendar.MONDAY -> "دوشنبه"
        Calendar.TUESDAY -> "سه‌شنبه"
        Calendar.WEDNESDAY -> "چهارشنبه"
        Calendar.THURSDAY -> "پنجشنبه"
        Calendar.FRIDAY -> "جمعه"
        else -> ""
    }

    private fun leapOffset(jYear: Int): Int {
        val jy = jYear + 1595
        return (jy / 33) * 8 + ((jy % 33) + 3) / 4
    }

    fun isJalaliLeapYear(jYear: Int): Boolean {
        return leapOffset(jYear + 1) > leapOffset(jYear)
    }

    fun daysInJalaliMonth(jYear: Int, jMonth: Int): Int = when {
        jMonth in 1..6 -> 31
        jMonth in 7..11 -> 30
        jMonth == 12 -> if (isJalaliLeapYear(jYear)) 30 else 29
        else -> 30
    }

    /**
     * Exact Gregorian to Jalali (Persian Solar) conversion.
     */
    fun gregorianToJalali(gYear: Int, gMonth: Int, gDay: Int, dayOfWeek: Int = Calendar.SATURDAY): JalaliDate {
        val gDm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gMonth > 2) gYear + 1 else gYear
        var days = 355666 + (365 * gYear) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gDay + gDm[gMonth - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + (days / 31)
            jd = 1 + (days % 31)
        } else {
            jm = 7 + ((days - 186) / 30)
            jd = 1 + ((days - 186) % 30)
        }
        return JalaliDate(year = jy, month = jm, day = jd, dayOfWeek = dayOfWeek)
    }

    /**
     * Exact Jalali to Gregorian triple (year, month 1..12, day 1..31).
     */
    fun jalaliToGregorian(jYear: Int, jMonth: Int, jDay: Int): Triple<Int, Int, Int> {
        val jy = jYear + 1595
        var days = -355668 + (365 * jy) + ((jy / 33) * 8) + (((jy % 33) + 3) / 4) + jDay +
            (if (jMonth < 7) (jMonth - 1) * 31 else ((jMonth - 7) * 30) + 186)
        var gy = 400 * (days / 146097)
        days %= 146097
        if (days > 36524) {
            gy += 100 * (--days / 36524)
            days %= 36524
            if (days >= 365) days++
        }
        gy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            gy += (days - 1) / 365
            days = (days - 1) % 365
        }
        var gd = days + 1
        val salA = intArrayOf(
            0, 31,
            if ((gy % 4 == 0 && gy % 100 != 0) || (gy % 400 == 0)) 29 else 28,
            31, 30, 31, 30, 31, 31, 30, 31, 30, 31
        )
        var gm = 0
        while (gm < 13 && gd > salA[gm]) {
            gd -= salA[gm]
            gm++
        }
        return Triple(gy, gm, gd)
    }

    fun fromMillis(millis: Long = System.currentTimeMillis()): JalaliDate {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return gregorianToJalali(
            gYear = cal.get(Calendar.YEAR),
            gMonth = cal.get(Calendar.MONTH) + 1,
            gDay = cal.get(Calendar.DAY_OF_MONTH),
            dayOfWeek = cal.get(Calendar.DAY_OF_WEEK),
        )
    }

    fun formatDateTimeFa(millis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val j = fromMillis(millis)
        val hh = cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val mm = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
        return "${j.formatFullFa()} ساعت $hh:$mm"
    }

    fun answerCalendarQuestion(raw: String, nowMillis: Long = System.currentTimeMillis()): String {
        val norm = PersianNormalizer.normalize(raw)
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val today = fromMillis(nowMillis)
        val timeStr = String.format(
            Locale.US,
            "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
        )

        if (norm.contains("فردا")) {
            val tomorrow = fromMillis(nowMillis + 86_400_000L)
            return "فردا ${tomorrow.formatFullFa()} (${tomorrow.formatShortFa()}) است."
        }
        if (norm.contains("پس فردا") || norm.contains("پس‌فردا")) {
            val dayAfter = fromMillis(nowMillis + 2 * 86_400_000L)
            return "پس‌فردا ${dayAfter.formatFullFa()} (${dayAfter.formatShortFa()}) است."
        }

        val daysLeft = daysInJalaliMonth(today.year, today.month) - today.day
        val leapText = if (isJalaliLeapYear(today.year)) "کبیسه" else "عادی"
        return "امروز ${today.formatFullFa()} (${today.formatShortFa()}) و ساعت $timeStr است.\n" +
            "${daysLeft} روز تا پایان ${today.monthNameFa} مانده (سال ${today.year} $leapText است)."
    }

    /**
     * Parses complex Persian Jalali scheduling phrases:
     * - «چهارشنبه ساعت ۹ صبح یادآوری کن جلسه دارم»
     * - «دو روز دیگه به علی پیام یادآوری بفرست»
     * - «فردا ساعت ۱۴ و ۳۰ دقیقه یادآوری کن دارو بخورم»
     * - «۱۵ آذر ساعت ۱۰ صبح یادآوری کن پرداخت قبض»
     */
    fun parsePersianDateTimeCommand(
        raw: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): ParsedJalaliSchedule? {
        val cleaned = PersianNormalizer.cleanText(raw)
        val norm = normalizePersianNumberWords(PersianNormalizer.normalize(cleaned))

        val isReminderOrDelayedMessage =
            norm.contains("یادآور") || norm.contains("یادم بنداز") || norm.contains("آلارم") ||
                ((norm.contains("پیام") || norm.contains("خبر") || norm.contains("اطلاع")) &&
                    (norm.contains("روز دیگه") || norm.contains("روز دیگر") || norm.contains("فردا") ||
                        norm.contains("پس فردا") || norm.contains("ساعت ") || findWeekday(norm) != null || findJalaliMonthDate(norm) != null))

        if (!isReminderOrDelayedMessage) return null

        // Check simple relative minute/hour/second: "۵ دقیقه دیگر ..."
        val relMinHour = Regex("(\\d+)\\s*(دقیقه|ثانیه|ساعت)\\s*(?:دیگر|دیگه|بعد)").find(norm)
        if (relMinHour != null && findWeekday(norm) == null && !norm.contains("فردا") && !norm.contains("روز")) {
            val amount = relMinHour.groupValues[1].toLongOrNull() ?: 1L
            val unit = relMinHour.groupValues[2]
            val deltaMs = when (unit) {
                "ساعت" -> amount * 3_600_000L
                "ثانیه" -> (amount * 1000L).coerceAtLeast(10_000L)
                else -> amount * 60_000L
            }
            val triggerAt = nowMillis + deltaMs
            val j = fromMillis(triggerAt)
            val msg = extractReminderBody(cleaned)
            return buildScheduleResult(triggerAt, j, msg, cleaned, norm)
        }

        val targetCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        var dateSpecified = false

        // 1. Relative day expressions: "دو روز دیگه" / "فردا" / "پس فردا"
        val daysOffsetMatch = Regex("(\\d+)\\s*روز\\s*(?:دیگر|دیگه|بعد)").find(norm)
        when {
            daysOffsetMatch != null -> {
                val days = daysOffsetMatch.groupValues[1].toIntOrNull()?.coerceIn(1, 365) ?: 1
                targetCal.add(Calendar.DAY_OF_YEAR, days)
                dateSpecified = true
            }
            norm.contains("پس فردا") -> {
                targetCal.add(Calendar.DAY_OF_YEAR, 2)
                dateSpecified = true
            }
            norm.contains("فردا") -> {
                targetCal.add(Calendar.DAY_OF_YEAR, 1)
                dateSpecified = true
            }
            else -> {
                // 2. Explicit Jalali day+month: "۱۵ آذر"
                val explicitJalali = findJalaliMonthDate(norm)
                if (explicitJalali != null) {
                    val todayJ = fromMillis(nowMillis)
                    var targetJYear = todayJ.year
                    if (explicitJalali.first < todayJ.month ||
                        (explicitJalali.first == todayJ.month && explicitJalali.second < todayJ.day)
                    ) {
                        targetJYear += 1
                    }
                    val (gy, gm, gd) = jalaliToGregorian(targetJYear, explicitJalali.first, explicitJalali.second)
                    targetCal.set(Calendar.YEAR, gy)
                    targetCal.set(Calendar.MONTH, gm - 1)
                    targetCal.set(Calendar.DAY_OF_MONTH, gd)
                    dateSpecified = true
                } else {
                    // 3. Weekday: "چهارشنبه"
                    val targetDow = findWeekday(norm)
                    if (targetDow != null) {
                        val todayDow = targetCal.get(Calendar.DAY_OF_WEEK)
                        var diff = (targetDow - todayDow + 7) % 7
                        if (diff == 0 && !norm.contains("امروز")) {
                            diff = 7
                        }
                        targetCal.add(Calendar.DAY_OF_YEAR, diff)
                        dateSpecified = true
                    }
                }
            }
        }

        // 4. Time parsing: "ساعت ۹ صبح" / "ساعت ۱۴:۳۰" / "ساعت ۳ بعدازظهر" / "ساعت ۲۱ و ۱۵ دقیقه"
        val parsedTime = parsePersianTime(norm)
        if (!dateSpecified && parsedTime == null) return null

        if (parsedTime != null) {
            targetCal.set(Calendar.HOUR_OF_DAY, parsedTime.first)
            targetCal.set(Calendar.MINUTE, parsedTime.second)
            targetCal.set(Calendar.SECOND, 0)
            targetCal.set(Calendar.MILLISECOND, 0)
            if (!dateSpecified && targetCal.timeInMillis <= nowMillis) {
                targetCal.add(Calendar.DAY_OF_YEAR, 1)
            }
        } else {
            // Day specified without explicit hour -> default 09:00 AM
            targetCal.set(Calendar.HOUR_OF_DAY, 9)
            targetCal.set(Calendar.MINUTE, 0)
            targetCal.set(Calendar.SECOND, 0)
            targetCal.set(Calendar.MILLISECOND, 0)
        }

        val triggerAt = targetCal.timeInMillis
        val j = fromMillis(triggerAt)
        val msg = extractReminderBody(cleaned)
        return buildScheduleResult(triggerAt, j, msg, cleaned, norm)
    }

    private fun buildScheduleResult(
        triggerAt: Long,
        j: JalaliDate,
        msg: String,
        cleaned: String,
        norm: String,
    ): ParsedJalaliSchedule {
        val cal = Calendar.getInstance().apply { timeInMillis = triggerAt }
        val timeLabel = String.format(
            Locale.US,
            "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
        )
        val contactMatch = Regex("(?:به|برای)\\s+([آ-یa-zA-Z]{2,20})\\s+(?:پیام|خبر|اطلاع|زنگ|تماس)").find(cleaned)
            ?.groupValues?.getOrNull(1)?.trim()
        val isMsgReminder = norm.contains("پیام") && contactMatch != null
        return ParsedJalaliSchedule(
            triggerAtMillis = triggerAt,
            jalaliLabel = j.formatFullFa(),
            timeLabel = timeLabel,
            reminderMessage = msg,
            contactHint = contactMatch,
            isMessageReminder = isMsgReminder,
        )
    }

    private fun findWeekday(norm: String): Int? {
        for ((kw, dow) in WEEKDAY_KEYWORDS) {
            if (norm.contains(kw)) return dow
        }
        return null
    }

    private fun findJalaliMonthDate(norm: String): Pair<Int, Int>? {
        MONTH_NAMES_FA.forEachIndexed { idx, monthName ->
            val m = Regex("(\\d{1,2})\\s*$monthName").find(norm)
            if (m != null) {
                val day = m.groupValues[1].toIntOrNull() ?: return@forEachIndexed
                if (day in 1..31) return (idx + 1) to day
            }
        }
        return null
    }

    private fun parsePersianTime(norm: String): Pair<Int, Int>? {
        val hhMmColon = Regex("ساعت\\s*(\\d{1,2})\\s*[:٫.]\\s*(\\d{1,2})").find(norm)
        if (hhMmColon != null) {
            val h = adjustAmPm(hhMmColon.groupValues[1].toIntOrNull() ?: return null, norm)
            val m = (hhMmColon.groupValues[2].toIntOrNull() ?: 0).coerceIn(0, 59)
            return h to m
        }

        val hhAndMm = Regex("ساعت\\s*(\\d{1,2})\\s*(?:و\\s*(\\d{1,2})\\s*(?:دقیقه)?|و\\s*نیم)?").find(norm)
        if (hhAndMm != null) {
            val rawHour = hhAndMm.groupValues[1].toIntOrNull() ?: return null
            val h = adjustAmPm(rawHour, norm)
            val m = when {
                norm.contains("و نیم") -> 30
                hhAndMm.groupValues[2].isNotBlank() -> (hhAndMm.groupValues[2].toIntOrNull() ?: 0).coerceIn(0, 59)
                else -> 0
            }
            return h to m
        }
        return null
    }

    private fun adjustAmPm(hour: Int, norm: String): Int {
        val h = hour.coerceIn(0, 23)
        val isPm = norm.contains("بعدازظهر") || norm.contains("بعد از ظهر") || norm.contains("عصر") || norm.contains("شب")
        val isAm = norm.contains("صبح")
        return when {
            isPm && h in 1..11 -> h + 12
            isAm && h == 12 -> 0
            else -> h
        }
    }

    private fun extractReminderBody(cleaned: String): String {
        val afterVerb = Regex("(?:یادآوری کن|یادم بنداز|به من بگو)\\s*(?:که\\s+)?(.*)$").find(cleaned)
            ?.groupValues?.getOrNull(1)?.trim()
        if (!afterVerb.isNullOrBlank()) return afterVerb
        return cleaned
            .replace(Regex("^(?:امروز|فردا|پس‌فردا|پس فردا|\\d+\\s*روز\\s*(?:دیگه|دیگر|بعد))\\s*"), "")
            .replace(Regex("(?:ساعت\\s*\\d{1,2}(?:[:٫.]\\d{1,2}|\\s*و\\s*نیم|\\s*و\\s*\\d{1,2}\\s*(?:دقیقه)?)?(?:\\s*(?:صبح|ظهر|عصر|شب|بعدازظهر|بعد از ظهر))?)"), "")
            .trim()
            .ifBlank { "یادآور زمان‌بندی‌شده Sol" }
    }

    private fun normalizePersianNumberWords(text: String): String {
        return com.example.sol.text.PersianNumbers.replaceWordNumbersWithDigits(text)
    }
}
