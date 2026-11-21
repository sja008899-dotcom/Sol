package com.example.sol.tts

/**
 * Lightweight Persian -> phoneme mapper for Piper ONNX TTS models.
 * TODO(verify): Exact phoneme inventory depends on the user's Piper voice checkpoint;
 * tune mappings after listening verification on the target device.
 */
object PersianPhonemizer {

    fun toPhonemes(text: String): String {
        if (text.isBlank()) return ""
        val sb = StringBuilder(text.length * 2)
        for (rawChar in text) {
            val ch = when (rawChar) {
                'ك' -> 'ک'
                'ي', 'ى', 'ئ' -> 'ی'
                'ة' -> 'ه'
                'ؤ' -> 'و'
                else -> rawChar
            }
            when {
                ch == ' ' || ch == '\t' || ch == '\n' -> sb.append(' ')
                ch == '\u200C' -> Unit // ZWNJ: keep word boundary clean without extra pause
                ch == '،' || ch == ',' -> sb.append(", ")
                ch == '.' || ch == '؟' || ch == '?' || ch == '!' -> sb.append("$ch ")
                ch in PERSIAN_LETTERS -> sb.append(PERSIAN_LETTERS[ch])
                ch.isLetterOrDigit() -> sb.append(ch.lowercaseChar())
                else -> sb.append(' ')
            }
        }
        return sb.toString().replace(Regex(" +"), " ").trim()
    }

    fun toPhonemeIds(text: String, config: PiperConfig): LongArray {
        val phonemes = toPhonemes(text)
        if (phonemes.isBlank()) return LongArray(0)

        val map = config.phonemeIdMap
        val padIds = map["_"] ?: emptyList()
        val bosIds = map["^"] ?: emptyList()
        val eosIds = map["$"] ?: emptyList()

        val result = ArrayList<Long>(phonemes.length * 2 + 4)
        bosIds.forEach { result.add(it.toLong()) }
        padIds.forEach { result.add(it.toLong()) }

        var appendedContent = false
        for (ch in phonemes) {
            val ids = map[ch.toString()] ?: continue
            ids.forEach { result.add(it.toLong()) }
            padIds.forEach { result.add(it.toLong()) }
            appendedContent = true
        }

        if (!appendedContent) return LongArray(0)
        eosIds.forEach { result.add(it.toLong()) }
        return result.toLongArray()
    }

    // TODO(verify): align IPA symbols with the exact fa_IR Piper model phoneme_id_map.
    private val PERSIAN_LETTERS: Map<Char, String> = mapOf(
        'ا' to "ɒ",
        'آ' to "ɒ",
        'أ' to "ɒ",
        'إ' to "e",
        'ب' to "b",
        'پ' to "p",
        'ت' to "t",
        'ث' to "s",
        'ج' to "dʒ",
        'چ' to "tʃ",
        'ح' to "h",
        'خ' to "x",
        'د' to "d",
        'ذ' to "z",
        'ر' to "ɾ",
        'ز' to "z",
        'ژ' to "ʒ",
        'س' to "s",
        'ش' to "ʃ",
        'ص' to "s",
        'ض' to "z",
        'ط' to "t",
        'ظ' to "z",
        'ع' to "ʔ",
        'غ' to "ɣ",
        'ف' to "f",
        'ق' to "ɢ",
        'ک' to "k",
        'گ' to "ɡ",
        'ل' to "l",
        'م' to "m",
        'ن' to "n",
        'و' to "v",
        'ه' to "h",
        'ی' to "j",
        'ء' to "ʔ",
        '۰' to "0",
        '۱' to "1",
        '۲' to "2",
        '۳' to "3",
        '۴' to "4",
        '۵' to "5",
        '۶' to "6",
        '۷' to "7",
        '۸' to "8",
        '۹' to "9",
    )
}
