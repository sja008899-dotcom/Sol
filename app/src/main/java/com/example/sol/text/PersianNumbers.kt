package com.example.sol.text

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Bidirectional Persian number words <-> numeric conversion and TTS preparation.
 * Pure Kotlin, zero external dependencies, deterministic for unit testing and offline execution.
 */
object PersianNumbers {

    private val UNITS = mapOf(
        "صفر" to 0L,
        "یک" to 1L,
        "دو" to 2L,
        "سه" to 3L,
        "چهار" to 4L,
        "پنج" to 5L,
        "شش" to 6L,
        "شیش" to 6L,
        "هفت" to 7L,
        "هشت" to 8L,
        "نه" to 9L,
    )

    private val TEENS = mapOf(
        "ده" to 10L,
        "یازده" to 11L,
        "دوازده" to 12L,
        "سیزده" to 13L,
        "چهارده" to 14L,
        "پانزده" to 15L,
        "پونزده" to 15L,
        "شانزده" to 16L,
        "شونزده" to 16L,
        "هفده" to 17L,
        "هیفده" to 17L,
        "هجده" to 18L,
        "هیجده" to 18L,
        "نوزده" to 19L,
    )

    private val TENS = mapOf(
        "بیست" to 20L,
        "سی" to 30L,
        "چهل" to 40L,
        "پنجاه" to 50L,
        "شصت" to 60L,
        "هفتاد" to 70L,
        "هشتاد" to 80L,
        "نود" to 90L,
    )

    private val HUNDREDS = mapOf(
        "صد" to 100L,
        "یکصد" to 100L,
        "دویست" to 200L,
        "سیصد" to 300L,
        "چهارصد" to 400L,
        "پانصد" to 500L,
        "پونصد" to 500L,
        "ششصد" to 600L,
        "شیشصد" to 600L,
        "هفتصد" to 700L,
        "هشتصد" to 800L,
        "نهصد" to 900L,
    )

    private val SCALES = mapOf(
        "هزار" to 1_000L,
        "میلیون" to 1_000_000L,
        "میلیارد" to 1_000_000_000L,
    )

    private val FRACTIONS = mapOf(
        "نیم" to 0.5,
        "نصف" to 0.5,
        "ربع" to 0.25,
    )

    private val ALL_NUMBER_WORDS: Set<String> =
        UNITS.keys + TEENS.keys + TENS.keys + HUNDREDS.keys + SCALES.keys + FRACTIONS.keys

    private val ONES_FA = arrayOf("", "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه")
    private val TEENS_FA = arrayOf("ده", "یازده", "دوازده", "سیزده", "چهارده", "پانزده", "شانزده", "هفده", "هیجده", "نوزده")
    private val TENS_FA = arrayOf("", "", "بیست", "سی", "چهل", "پنجاه", "شصت", "هفتاد", "هشتاد", "نود")
    private val HUNDREDS_FA = arrayOf("", "صد", "دویست", "سیصد", "چهارصد", "پانصد", "ششصد", "هفتصد", "هشتصد", "نهصد")

    private val URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val EMOJI_DECOR_REGEX = Regex("[🎭📘🔎🎙⚠️✅❌•]")
    private val MULTI_SPACE_REGEX = Regex("\\s+")
    private val TIME_HH_MM_REGEX = Regex("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)")
    private val DECIMAL_NUMBER_REGEX = Regex("(?<!\\d)(\\d{1,4})[.,٫/](\\d{1,2})(?!\\d)")
    private val PERCENT_BEFORE_REGEX = Regex("%(\\d{1,3})")
    private val PERCENT_AFTER_REGEX = Regex("(\\d{1,3})\\s*[%٪]")
    private val STANDALONE_INT_REGEX = Regex("(?<!\\d)(\\d{1,4})(?!\\d)")
    private val HOUR_HALF_REGEX = Regex("(?:(\\d+(?:\\.\\d+)?)|(?:یک|دو|سه|چهار|پنج|شش|هفت|هشت|نه|ده|یازده|دوازده))\\s+(ساعت|دقیقه|روز)\\s+و\\s+نیم")

    /**
     * Parses a Persian number phrase (integers, compound numbers like "هزار و سیصد و چهل",
     * fractions like "نیم", "ربع", "سه ربع", and mixed numbers like "یک و نیم").
     * Returns null if [phrase] does not represent a number expression.
     */
    fun wordsToNumber(phrase: String): Double? {
        val norm = PersianTextNormalizer.normalizeForMatching(phrase)
        if (norm.isBlank()) return null

        norm.toDoubleOrNull()?.let { return it }

        val tokens = norm.split(MULTI_SPACE_REGEX).filter { it.isNotBlank() }
        return parseTokenRangeAsNumber(tokens, 0, tokens.size)?.first
    }

    /**
     * Replaces Persian word numbers inside a sentence with ASCII digits on token boundaries,
     * preserving surrounding text and formatting fractional durations cleanly.
     */
    fun replaceWordNumbersWithDigits(text: String): String {
        val norm = PersianTextNormalizer.normalizeForMatching(text)
        if (norm.isBlank()) return ""

        val tokens = norm.split(MULTI_SPACE_REGEX).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""

        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            // Avoid turning standalone polite/determiner words into digits unless part of a compound or unit context
            if (tok !in ALL_NUMBER_WORDS) {
                out.add(tok)
                i++
                continue
            }

            // Look ahead to consume contiguous compound number expression [numWord (و? numWord)*]
            val endIdx = findCompoundNumberEnd(tokens, i)
            val spanLen = endIdx - i

            // Special guard for single-token "یک" / "نه" when NOT clearly numeric
            if (spanLen == 1) {
                val next = tokens.getOrNull(i + 1)
                if (tok == "نه" && next !in setOf("ساعت", "دقیقه", "ثانیه", "روز", "ماه", "صبح", "عصر", "شب", "ظهر", "تومن", "تومان", "درصد", "بار", "مرتبه", "واحد")) {
                    // Preserve standalone negation "نه" unless paired with time/count unit!
                    // Keep "ساعت نه" -> look at previous token
                    val prev = tokens.getOrNull(i - 1)
                    if (prev != "ساعت" && prev != "شماره" && prev != "عدد") {
                        out.add(tok)
                        i++
                        continue
                    }
                }
                if (tok == "یک" && next in setOf("چیز", "چیزی", "مطلب", "مطلبی", "کم", "کمی", "ذره", "لحظه", "کلام", "بار")) {
                    out.add(tok)
                    i++
                    continue
                }
            }

            val parsed = parseTokenRangeAsNumber(tokens, i, endIdx)
            if (parsed != null) {
                val value = parsed.first
                val formatted = formatNumericClean(value)
                out.add(formatted)
                i = endIdx
            } else {
                out.add(tok)
                i++
            }
        }
        return out.joinToString(" ")
    }

    /**
     * Parses spoken Persian duration phrases into minutes, supporting:
     * - "یک ساعت و نیم" -> 90L
     * - "نیم ساعت" -> 30L
     * - "ربع ساعت" -> 15L
     * - "سه ربع ساعت" -> 45L
     * - "بیست و پنج دقیقه" -> 25L
     * - "دو ساعت" -> 120L
     */
    fun parseSpokenDurationMinutes(raw: String): Long? {
        val norm = PersianTextNormalizer.normalizeForMatching(raw)
        if (norm.isBlank()) return null

        if (norm.contains("نیم ساعت") || Regex("(?:یک|1)?\\s*نیم\\s*ساعت").containsMatchIn(norm)) {
            return 30L
        }
        if (norm.contains("سه ربع ساعت") || norm.contains("سه ربع")) {
            if (norm.contains("ساعت")) return 45L
        }
        if (norm.contains("ربع ساعت")) {
            return 15L
        }

        val withWordDigits = replaceWordNumbersWithDigits(norm)
        // Pattern: "<N> ساعت و نیم" / "1 ساعت و 0.5"
        Regex("(\\d+(?:\\.\\d+)?)\\s*ساعت\\s*و\\s*(?:نیم|0\\.5)").find(withWordDigits)?.let { m ->
            val h = m.groupValues[1].toDoubleOrNull() ?: 1.0
            return (h * 60.0 + 30.0).roundToLong().coerceAtLeast(1L)
        }
        Regex("(\\d+(?:\\.\\d+)?)\\s*ساعت\\s*و\\s*(?:ربع|0\\.25)").find(withWordDigits)?.let { m ->
            val h = m.groupValues[1].toDoubleOrNull() ?: 1.0
            return (h * 60.0 + 15.0).roundToLong().coerceAtLeast(1L)
        }
        Regex("(\\d+(?:\\.\\d+)?)\\s*ساعت\\s*و\\s*(\\d+)\\s*(?:دقیقه)?").find(withWordDigits)?.let { m ->
            val h = m.groupValues[1].toDoubleOrNull() ?: 1.0
            val mins = m.groupValues[2].toDoubleOrNull() ?: 0.0
            return (h * 60.0 + mins).roundToLong().coerceAtLeast(1L)
        }
        Regex("(\\d+(?:\\.\\d+)?)\\s*(ساعت|دقیقه|ثانیه)").find(withWordDigits)?.let { m ->
            val amount = m.groupValues[1].toDoubleOrNull() ?: return null
            return when (m.groupValues[2]) {
                "ساعت" -> (amount * 60.0).roundToLong().coerceAtLeast(1L)
                "ثانیه" -> 1L
                else -> amount.roundToLong().coerceAtLeast(1L)
            }
        }
        return null
    }

    fun numberToWords(value: Long): String {
        if (value == 0L) return "صفر"
        if (value < 0L) return "منفی ${numberToWords(abs(value))}"

        val scales = listOf(
            1_000_000_000_000L to "تریلیون",
            1_000_000_000L to "میلیارد",
            1_000_000L to "میلیون",
            1_000L to "هزار",
            1L to "",
        )
        var remainder = value
        val parts = mutableListOf<String>()
        for ((scaleVal, scaleName) in scales) {
            if (remainder >= scaleVal) {
                val chunk = (remainder / scaleVal).toInt()
                remainder %= scaleVal
                if (scaleName == "هزار" && chunk == 1) {
                    parts.add("هزار")
                } else {
                    val chunkWords = threeDigitsToWords(chunk)
                    if (chunkWords.isNotEmpty()) {
                        parts.add(if (scaleName.isEmpty()) chunkWords else "$chunkWords $scaleName")
                    }
                }
            }
        }
        return parts.joinToString(" و ")
    }

    fun numberToWords(value: Double): String {
        if (value < 0.0) return "منفی ${numberToWords(abs(value))}"
        val roundedTwo = ((value * 100.0).roundToLong()) / 100.0
        val whole = roundedTwo.toLong()
        val frac = roundedTwo - whole
        if (frac < 0.005) return numberToWords(whole)

        val fracWord = when {
            abs(frac - 0.5) < 0.01 -> "نیم"
            abs(frac - 0.25) < 0.01 -> "ربع"
            abs(frac - 0.75) < 0.01 -> "سه ربع"
            else -> {
                val hundredths = (frac * 100.0).roundToLong()
                if (hundredths % 10L == 0L) {
                    "${numberToWords(hundredths / 10L)} دهم"
                } else {
                    "${numberToWords(hundredths)} صدم"
                }
            }
        }
        return if (whole == 0L) {
            fracWord
        } else if (fracWord == "نیم" || fracWord == "ربع" || fracWord == "سه ربع") {
            "${numberToWords(whole)} و $fracWord"
        } else {
            "${numberToWords(whole)} ممیز $fracWord"
        }
    }

    /**
     * Prepares Persian response text for SherpaTTS / system TTS engines:
     * cleans URLs/emojis, expands percentages, times (14:30), decimals (1.5 -> یک و نیم),
     * and standalone 1..4 digit numbers into spoken Persian words while keeping 5+ digit phone numbers intact.
     */
    fun prepareTextForTts(text: String): String {
        if (text.isBlank()) return ""
        var s = PersianTextNormalizer.cleanDisplayText(text)
            .let { URL_REGEX.replace(it, "لینک") }
            .replace("•", "،")
            .let { EMOJI_DECOR_REGEX.replace(it, "") }

        // Percentages: "50%" or "%50" -> "پنجاه درصد"
        s = PERCENT_AFTER_REGEX.replace(s) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            "${numberToWords(n)} درصد"
        }
        s = PERCENT_BEFORE_REGEX.replace(s) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            "${numberToWords(n)} درصد"
        }

        // Clock times like 14:30 or 9:05 -> "چهارده و سی" / "نه و پنج"
        s = TIME_HH_MM_REGEX.replace(s) { m ->
            val hh = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val mm = m.groupValues[2].toLongOrNull() ?: return@replace m.value
            if (hh in 0..23 && mm in 0..59) {
                if (mm == 0L) numberToWords(hh)
                else if (mm == 30L) "${numberToWords(hh)} و نیم"
                else "${numberToWords(hh)} و ${numberToWords(mm)}"
            } else {
                m.value
            }
        }

        // Jalali / Gregorian slash dates like 1404/01/02 -> spoken date components
        s = Regex("(?<!\\d)(\\d{4})/(\\d{1,2})/(\\d{1,2})(?!\\d)").replace(s) { m ->
            val y = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val mo = m.groupValues[2].toLongOrNull() ?: return@replace m.value
            val d = m.groupValues[3].toLongOrNull() ?: return@replace m.value
            "${numberToWords(y)} / ${numberToWords(mo)} / ${numberToWords(d)}"
        }

        // Decimals like 1.5 -> "یک و نیم" (skip if adjacent to more dots/digits)
        s = DECIMAL_NUMBER_REGEX.replace(s) { m ->
            if (m.value.contains('/')) return@replace m.value
            val normalizedFloat = "${m.groupValues[1]}.${m.groupValues[2]}".toDoubleOrNull()
                ?: return@replace m.value
            numberToWords(normalizedFloat)
        }

        // Standalone 1..4 digit integers (avoid touching 5+ digit phone numbers/IDs)
        s = STANDALONE_INT_REGEX.replace(s) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            numberToWords(n)
        }

        return MULTI_SPACE_REGEX.replace(s, " ").trim()
    }

    private fun threeDigitsToWords(n: Int): String {
        if (n <= 0) return ""
        val h = n / 100
        val rem = n % 100
        val parts = mutableListOf<String>()
        if (h > 0 && h < HUNDREDS_FA.size) parts.add(HUNDREDS_FA[h])
        if (rem in 10..19) {
            parts.add(TEENS_FA[rem - 10])
        } else {
            val t = rem / 10
            val u = rem % 10
            if (t >= 2 && t < TENS_FA.size) parts.add(TENS_FA[t])
            if (u in 1..9) parts.add(ONES_FA[u])
        }
        return parts.joinToString(" و ")
    }

    private fun findCompoundNumberEnd(tokens: List<String>, start: Int): Int {
        var i = start
        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok in ALL_NUMBER_WORDS) {
                i++
                // Check for optional "و" linking to another number word
                if (i + 1 < tokens.size && tokens[i] == "و" && tokens[i + 1] in ALL_NUMBER_WORDS) {
                    i++ // consume "و" and continue loop to next number word
                } else {
                    break
                }
            } else {
                break
            }
        }
        return i
    }

    private fun parseTokenRangeAsNumber(tokens: List<String>, start: Int, end: Int): Pair<Double, Int>? {
        if (start >= end) return null
        var total = 0L
        var currentGroup = 0L
        var fractionAdd = 0.0
        var hasValue = false
        var idx = start

        while (idx < end) {
            val tok = tokens[idx]
            if (tok == "و") {
                idx++
                continue
            }
            if (tok == "سه" && idx + 1 < end && tokens[idx + 1] == "ربع") {
                fractionAdd += 0.75
                hasValue = true
                idx += 2
                continue
            }
            FRACTIONS[tok]?.let { f ->
                fractionAdd += f
                hasValue = true
                idx++
                continue
            }
            UNITS[tok]?.let { u ->
                currentGroup += u
                hasValue = true
                idx++
                continue
            }
            TEENS[tok]?.let { t ->
                currentGroup += t
                hasValue = true
                idx++
                continue
            }
            TENS[tok]?.let { t ->
                currentGroup += t
                hasValue = true
                idx++
                continue
            }
            HUNDREDS[tok]?.let { h ->
                currentGroup += h
                hasValue = true
                idx++
                continue
            }
            SCALES[tok]?.let { s ->
                val mult = if (currentGroup == 0L) 1L else currentGroup
                total += mult * s
                currentGroup = 0L
                hasValue = true
                idx++
                continue
            }
            return null
        }

        if (!hasValue) return null
        val finalVal = total.toDouble() + currentGroup.toDouble() + fractionAdd
        return finalVal to idx
    }

    private fun formatNumericClean(value: Double): String {
        val asLong = value.toLong()
        return if (abs(value - asLong.toDouble()) < 1e-6) {
            asLong.toString()
        } else {
            value.toString().trimEnd('0').trimEnd('.')
        }
    }
}
