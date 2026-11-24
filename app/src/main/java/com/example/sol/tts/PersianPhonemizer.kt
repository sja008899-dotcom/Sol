package com.example.sol.tts

import android.util.Log

data class PhonemizeReport(
    val ids: LongArray,
    val totalTokens: Int,
    val missingTokens: List<String>,
) {
    val coverageRatio: Float
        get() = if (totalTokens <= 0) 0f else ((totalTokens - missingTokens.size).toFloat() / totalTokens).coerceIn(0f, 1f)
}

object PersianPhonemizer {
    private const val TAG = "PersianPhonemizer"

    fun toPhonemeIds(text: String, config: PiperConfig): LongArray =
        toPhonemeIdsWithReport(text, config).ids

    fun toPhonemeIdsWithReport(text: String, config: PiperConfig): PhonemizeReport {
        val phonemeTokens = toPhonemeTokens(text)
        if (phonemeTokens.isEmpty()) {
            return PhonemizeReport(LongArray(0), 0, emptyList())
        }

        val map = config.phonemeIdMap
        val padIds = map["_"] ?: emptyList()
        val bosIds = map["^"] ?: emptyList()
        val eosIds = map["$"] ?: emptyList()

        val result = ArrayList<Long>(phonemeTokens.size * 2 + 4)
        bosIds.forEach { result.add(it.toLong()) }
        padIds.forEach { result.add(it.toLong()) }

        var appendedContent = false
        val missing = mutableListOf<String>()
        for (token in phonemeTokens) {
            val ids = map[token]
            if (ids == null) {
                if (token.isNotBlank() && token !in missing) {
                    missing.add(token)
                }
                continue
            }
            ids.forEach { result.add(it.toLong()) }
            padIds.forEach { result.add(it.toLong()) }
            appendedContent = true
        }

        if (missing.isNotEmpty() && missing.size * 2 >= phonemeTokens.size) {
            Log.w(
                TAG,
                "Low phoneme coverage for Piper config (${phonemeTokens.size - missing.size}/${phonemeTokens.size} tokens); missing=$missing",
            )
        }

        if (!appendedContent) {
            return PhonemizeReport(LongArray(0), phonemeTokens.size, missing)
        }
        eosIds.forEach { result.add(it.toLong()) }
        return PhonemizeReport(
            ids = result.toLongArray(),
            totalTokens = phonemeTokens.size,
            missingTokens = missing,
        )
    }

    internal fun toPhonemeTokens(text: String): List<String> {
        val tokens = mutableListOf<String>()
        for (rawChar in text) {
            if (isDiacriticOrZeroWidth(rawChar)) continue
            val ch = normalizeChar(rawChar)
            when {
                ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' -> {
                    if (tokens.isNotEmpty() && tokens.last() != " ") {
                        tokens.add(" ")
                    }
                }
                ch == '،' || ch == ',' -> tokens.add(",")
                ch == '؟' || ch == '?' -> tokens.add("?")
                ch == '؛' || ch == ';' -> tokens.add(";")
                ch == '.' || ch == '!' || ch == ':' -> tokens.add(ch.toString())
                ch in PERSIAN_LETTERS -> tokens.add(PERSIAN_LETTERS[ch]!!)
                ch.isLetterOrDigit() -> tokens.add(ch.lowercaseChar().toString())
                else -> {
                    if (tokens.isNotEmpty() && tokens.last() != " ") {
                        tokens.add(" ")
                    }
                }
            }
        }
        while (tokens.lastOrNull() == " ") {
            tokens.removeAt(tokens.lastIndex)
        }
        return tokens
    }

    private fun isDiacriticOrZeroWidth(ch: Char): Boolean =
        ch == '\u200C' ||
            ch == '\u200D' ||
            ch == '\u0670' ||
            ch in '\u064B'..'\u065F'

    private fun normalizeChar(ch: Char): Char = when (ch) {
        'ك' -> 'ک'
        'ي', 'ى', 'ئ' -> 'ی'
        'ة', 'ۀ' -> 'ه'
        'أ', 'إ', 'ٱ' -> 'ا'
        'ؤ' -> 'و'
        else -> ch
    }

    private val PERSIAN_LETTERS: Map<Char, String> = mapOf(
        'ا' to "ɒ", 'آ' to "ɒ", 'ب' to "b", 'پ' to "p", 'ت' to "t", 'ث' to "s",
        'ج' to "dʒ", 'چ' to "tʃ", 'ح' to "h", 'خ' to "x", 'د' to "d", 'ذ' to "z",
        'ر' to "ɾ", 'ز' to "z", 'ژ' to "ʒ", 'س' to "s", 'ش' to "ʃ", 'ص' to "s",
        'ض' to "z", 'ط' to "t", 'ظ' to "z", 'ع' to "ʔ", 'غ' to "ɣ", 'ف' to "f",
        'ق' to "ɢ", 'ک' to "k", 'گ' to "ɡ", 'ل' to "l", 'م' to "m", 'ن' to "n",
        'و' to "v", 'ه' to "h", 'ی' to "j", 'ء' to "ʔ",
        // Persian digits
        '۰' to "0", '۱' to "1", '۲' to "2", '۳' to "3", '۴' to "4",
        '۵' to "5", '۶' to "6", '۷' to "7", '۸' to "8", '۹' to "9",
        // Eastern Arabic digits
        '٠' to "0", '١' to "1", '٢' to "2", '٣' to "3", '٤' to "4",
        '٥' to "5", '٦' to "6", '٧' to "7", '٨' to "8", '٩' to "9",
    )
}
