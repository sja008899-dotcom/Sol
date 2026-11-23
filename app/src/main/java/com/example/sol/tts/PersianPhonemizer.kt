package com.example.sol.tts

object PersianPhonemizer {
    fun toPhonemeIds(text: String, config: PiperConfig): LongArray {
        val phonemeTokens = toPhonemeTokens(text)
        if (phonemeTokens.isEmpty()) return LongArray(0)

        val map = config.phonemeIdMap
        val padIds = map["_"] ?: emptyList()
        val bosIds = map["^"] ?: emptyList()
        val eosIds = map["$"] ?: emptyList()

        val result = ArrayList<Long>(phonemeTokens.size * 2 + 4)
        bosIds.forEach { result.add(it.toLong()) }
        padIds.forEach { result.add(it.toLong()) }

        var appendedContent = false
        for (token in phonemeTokens) {
            val ids = map[token] ?: continue
            ids.forEach { result.add(it.toLong()) }
            padIds.forEach { result.add(it.toLong()) }
            appendedContent = true
        }

        if (!appendedContent) return LongArray(0)
        eosIds.forEach { result.add(it.toLong()) }
        return result.toLongArray()
    }

    private fun toPhonemeTokens(text: String): List<String> {
        val tokens = mutableListOf<String>()
        for (rawChar in text) {
            val ch = when (rawChar) {
                'ك' -> 'ک'
                'ي', 'ى', 'ئ' -> 'ی'
                'ة' -> 'ه'
                'ؤ' -> 'و'
                else -> rawChar
            }
            when {
                ch == ' ' || ch == '\t' || ch == '\n' -> tokens.add(" ")
                ch == '\u200C' -> Unit
                ch == '،' || ch == ',' -> tokens.add(",")
                ch in PERSIAN_LETTERS -> tokens.add(PERSIAN_LETTERS[ch]!!)
                ch.isLetterOrDigit() -> tokens.add(ch.lowercaseChar().toString())
                else -> tokens.add(" ")
            }
        }
        return tokens
    }

    private val PERSIAN_LETTERS: Map<Char, String> = mapOf(
        'ا' to "ɒ", 'آ' to "ɒ", 'ب' to "b", 'پ' to "p", 'ت' to "t", 'ث' to "s",
        'ج' to "dʒ", 'چ' to "tʃ", 'ح' to "h", 'خ' to "x", 'د' to "d", 'ذ' to "z",
        'ر' to "ɾ", 'ز' to "z", 'ژ' to "ʒ", 'س' to "s", 'ش' to "ʃ", 'ص' to "s",
        'ض' to "z", 'ط' to "t", 'ظ' to "z", 'ع' to "ʔ", 'غ' to "ɣ", 'ف' to "f",
        'ق' to "ɢ", 'ک' to "k", 'گ' to "ɡ", 'ل' to "l", 'م' to "m", 'ن' to "n",
        'و' to "v", 'ه' to "h", 'ی' to "j", 'ء' to "ʔ",
        '٠' to "0", '١' to "1", '٢' to "2", '٣' to "3", '٤' to "4",
        '٥' to "5", '٦' to "6", '٧' to "7", '٨' to "8", '٩' to "9"
    )
}
