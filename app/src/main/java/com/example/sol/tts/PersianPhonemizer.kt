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

/**
 * Context-aware Persian (fa-IR) Grapheme-to-Phoneme (G2P) converter aligned strictly with
 * `espeak-ng -v fa --ipa` single-character tokens expected by `fa_IR-mana-medium.onnx.json`.
 *
 * Key guarantees:
 * 1. Every emitted phoneme token is a single Unicode character present in Piper's `phoneme_id_map`
 *    (e.g., affricates `dʒ` and `tʃ` are emitted as `"d", "ʒ"` and `"t", "ʃ"`).
 * 2. Uses exact eSpeak-NG `fa` IPA symbols (`ɑ` for آ/ا, `a` for کوتاه /æ/, `r` for ر, `q` for ق/غ, `ɡ` for گ).
 * 3. Combines a high-frequency Persian/Sol pronunciation lexicon, ZWNJ morpheme splitting,
 *    suffix/prefix handling, and rule-based Persian syllable vowel epenthesis.
 */
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
        if (text.isBlank()) return emptyList()
        val tokens = mutableListOf<String>()
        var i = 0
        val len = text.length

        while (i < len) {
            val ch = normalizePunctOrDigit(text[i])
            when {
                ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u200D' -> {
                    appendSpaceIfNeeded(tokens)
                    i++
                }
                ch == '،' || ch == ',' -> {
                    tokens.add(",")
                    i++
                }
                ch == '؟' || ch == '?' -> {
                    tokens.add("?")
                    i++
                }
                ch == '؛' || ch == ';' -> {
                    tokens.add(";")
                    i++
                }
                ch == '.' || ch == '!' || ch == ':' -> {
                    tokens.add(ch.toString())
                    i++
                }
                ch == '«' || ch == '»' || ch == '"' || ch == '\'' || ch == '(' || ch == ')' || ch == '-' || ch == '—' -> {
                    appendSpaceIfNeeded(tokens)
                    i++
                }
                isPersianWordChar(ch) -> {
                    val start = i
                    while (i < len && isPersianWordChar(normalizePunctOrDigit(text[i]))) {
                        i++
                    }
                    val rawWord = text.substring(start, i)
                    emitPersianWordTokens(rawWord, tokens)
                }
                ch in '0'..'9' -> {
                    tokens.add(ch.toString())
                    i++
                }
                ch.isLetter() -> {
                    val start = i
                    while (i < len && text[i].isLetter() && !isPersianWordChar(text[i])) {
                        i++
                    }
                    val latinWord = text.substring(start, i).lowercase()
                    emitLatinWordTokens(latinWord, tokens)
                }
                else -> {
                    appendSpaceIfNeeded(tokens)
                    i++
                }
            }
        }

        while (tokens.lastOrNull() == " ") {
            tokens.removeAt(tokens.lastIndex)
        }
        return tokens
    }

    private fun appendSpaceIfNeeded(tokens: MutableList<String>) {
        if (tokens.isNotEmpty() && tokens.last() != " ") {
            tokens.add(" ")
        }
    }

    private fun emitLatinWordTokens(word: String, out: MutableList<String>) {
        val mapped = LATIN_WORD_IPA[word]
        if (mapped != null) {
            appendIpaString(mapped, out)
            return
        }
        for (c in word) {
            val ipa = when (c) {
                'a' -> "a"
                'e' -> "e"
                'i' -> "i"
                'o' -> "o"
                'u' -> "u"
                'c' -> "k"
                'q' -> "k"
                'w' -> "v"
                'y' -> "j"
                'g' -> "ɡ"
                'x' -> "ks"
                else -> c.toString()
            }
            appendIpaString(ipa, out)
        }
    }

    private fun emitPersianWordTokens(rawWord: String, out: MutableList<String>) {
        val normalizedWithZwnj = normalizePersianWordKeepZwnj(rawWord)
        if (normalizedWithZwnj.isBlank()) return

        // Handle ZWNJ-separated morphemes (e.g., می‌روم -> می + روم, کتاب‌ها -> کتاب + ها)
        if (normalizedWithZwnj.contains('\u200C')) {
            val parts = normalizedWithZwnj.split('\u200C').filter { it.isNotBlank() }
            for (part in parts) {
                emitSinglePersianWord(part, out)
            }
            return
        }
        emitSinglePersianWord(normalizedWithZwnj, out)
    }

    private fun emitSinglePersianWord(wordWithDiacritics: String, out: MutableList<String>) {
        val bareWord = stripDiacritics(wordWithDiacritics)
        if (bareWord.isBlank()) return

        // 1. Direct lexicon lookup (exact eSpeak-fa IPA)
        WORD_IPA[bareWord]?.let { ipa ->
            appendIpaString(ipa, out)
            return
        }

        // 2. Productive Persian prefix/suffix decomposition (e.g., می + X, X + ها / های / ام / ات / اش)
        tryDecomposedLookup(bareWord)?.let { ipa ->
            appendIpaString(ipa, out)
            return
        }

        // 3. Context-aware rule-based Persian G2P with syllable epenthesis
        val ipa = phonemizeWordByRules(wordWithDiacritics)
        appendIpaString(ipa, out)
    }

    private fun tryDecomposedLookup(word: String): String? {
        if (word.startsWith("نمی") && word.length > 4) {
            WORD_IPA[word.removePrefix("نمی")]?.let { stemIpa ->
                return "nˈemiː${stemIpa.replace("ˈ", "")}"
            }
        }
        if (word.startsWith("می") && word.length > 3) {
            WORD_IPA[word.removePrefix("می")]?.let { stemIpa ->
                return "mˈiː${stemIpa.replace("ˈ", "")}"
            }
        }
        val suffixes = listOf(
            "هایشان" to "hɑjeʃɑn",
            "هایتان" to "hɑjetɑn",
            "هایمان" to "hɑjemɑn",
            "هایی" to "hɑjiː",
            "های" to "hɑje",
            "ها" to "hɑ",
            "تان" to "etɑn",
            "مان" to "emɑn",
            "شان" to "eʃɑn",
            "یم" to "iːm",
            "ید" to "iːd",
            "ند" to "and",
            "ام" to "am",
            "ات" to "at",
            "اش" to "aʃ",
            "ست" to "ast",
        )
        for ((suf, sufIpa) in suffixes) {
            if (word.length > suf.length + 1 && word.endsWith(suf)) {
                val stem = word.removeSuffix(suf)
                WORD_IPA[stem]?.let { stemIpa ->
                    val cleanStem = stemIpa.trimEnd('ː')
                    return "$cleanStem$sufIpa"
                }
            }
        }
        return null
    }

    private fun phonemizeWordByRules(wordRaw: String): String {
        val sb = StringBuilder(wordRaw.length * 2)
        val bare = stripDiacritics(wordRaw)
        val n = wordRaw.length

        var hasVowel = false
        var consonantClusterCount = 0

        fun appendVowel(v: String) {
            sb.append(v)
            hasVowel = true
            consonantClusterCount = 0
        }

        fun appendConsonant(c: String, nextIsVowelCarrier: Boolean) {
            // Persian disallows #CC onsets and CCC medial clusters: insert natural short vowel
            if (!hasVowel && consonantClusterCount >= 1) {
                sb.append('a')
                hasVowel = true
                consonantClusterCount = 0
            } else if (consonantClusterCount >= 2 && !nextIsVowelCarrier) {
                sb.append('e')
                consonantClusterCount = 0
            }
            sb.append(c)
            consonantClusterCount++
        }

        var idx = 0
        while (idx < n) {
            val ch = wordRaw[idx]
            val prevBare = findPrevNonDiacritic(wordRaw, idx)
            val nextBare = findNextNonDiacritic(wordRaw, idx)
            val isWordStart = prevBare == null
            val isWordEnd = nextBare == null

            when (ch) {
                '\u064E' -> appendVowel("a") // Fatha
                '\u0650' -> appendVowel("e") // Kasra
                '\u064F' -> appendVowel("o") // Damma
                '\u064B' -> { appendVowel("a"); sb.append('n'); consonantClusterCount = 1 }
                '\u064D' -> { appendVowel("e"); sb.append('n'); consonantClusterCount = 1 }
                '\u064C' -> { appendVowel("o"); sb.append('n'); consonantClusterCount = 1 }
                '\u0651' -> { // Shadda (gemination)
                    if (sb.isNotEmpty()) {
                        val lastChar = sb[sb.length - 1]
                        if (lastChar !in VOWEL_CHARS && lastChar != 'ː') {
                            sb.append(lastChar)
                        }
                    }
                }
                '\u0652', '\u0670', '\u0653', '\u0654', '\u0655', '\u200C', '\u200D' -> Unit

                'آ' -> appendVowel("ɑ")
                'ا' -> {
                    when {
                        isWordStart -> {
                            val next2 = findNextNonDiacriticAfterChar(wordRaw, idx)
                            val initialVowel = when {
                                nextBare in setOf('ن', 'م', 'س', 'ش', 'ز', 'ل', 'ت') && next2 != 'ا' && next2 != 'و' && next2 != 'ی' -> "e"
                                nextBare == 'و' -> "o"
                                else -> "a"
                            }
                            appendVowel(initialVowel)
                        }
                        else -> appendVowel("ɑ")
                    }
                }
                'و' -> {
                    when {
                        prevBare == 'خ' && nextBare == 'ا' -> {
                            // Silent و in خوا (خواهر، خواست، خواند)
                            Unit
                        }
                        isWordStart -> appendConsonant("v", nextIsVowelCarrier = isLikelyVowelLetter(nextBare))
                        nextBare != null && isLikelyVowelLetter(nextBare) -> {
                            appendConsonant("v", nextIsVowelCarrier = true)
                        }
                        prevBare in setOf('آ', 'ا') -> {
                            appendConsonant("v", nextIsVowelCarrier = false)
                        }
                        isWordEnd -> appendVowel("uː")
                        else -> appendVowel("u")
                    }
                }
                'ی' -> {
                    when {
                        isWordStart -> appendConsonant("j", nextIsVowelCarrier = isLikelyVowelLetter(nextBare))
                        prevBare in setOf('آ', 'ا', 'و') && isWordEnd -> {
                            appendConsonant("j", nextIsVowelCarrier = false)
                        }
                        prevBare in setOf('آ', 'ا', 'و', 'ه') && nextBare != null -> {
                            appendConsonant("j", nextIsVowelCarrier = isLikelyVowelLetter(nextBare))
                        }
                        nextBare != null && nextBare in setOf('ا', 'آ', 'و') -> {
                            appendConsonant("j", nextIsVowelCarrier = true)
                        }
                        isWordEnd -> appendVowel("iː")
                        else -> appendVowel("i")
                    }
                }
                'ه' -> {
                    when {
                        isWordEnd && prevBare != null && prevBare !in setOf('ا', 'آ', 'و', 'ی') && bare.length >= 2 -> {
                            appendVowel("eː")
                        }
                        else -> appendConsonant("h", nextIsVowelCarrier = isLikelyVowelLetter(nextBare))
                    }
                }
                'ۀ' -> appendVowel("eje")
                'ع', 'ء' -> {
                    if (isWordStart) {
                        appendConsonant("ʔ", nextIsVowelCarrier = true)
                        if (nextBare != null && !isLikelyVowelLetter(nextBare)) {
                            appendVowel("a")
                        }
                    } else {
                        appendConsonant("ʔ", nextIsVowelCarrier = isLikelyVowelLetter(nextBare))
                    }
                }
                'ب' -> appendConsonant("b", isLikelyVowelLetter(nextBare))
                'پ' -> appendConsonant("p", isLikelyVowelLetter(nextBare))
                'ت', 'ط' -> appendConsonant("t", isLikelyVowelLetter(nextBare))
                'ث', 'س', 'ص' -> appendConsonant("s", isLikelyVowelLetter(nextBare))
                'ج' -> appendConsonant("dʒ", isLikelyVowelLetter(nextBare))
                'چ' -> appendConsonant("tʃ", isLikelyVowelLetter(nextBare))
                'ح' -> appendConsonant("h", isLikelyVowelLetter(nextBare))
                'خ' -> appendConsonant("x", isLikelyVowelLetter(nextBare))
                'د' -> appendConsonant("d", isLikelyVowelLetter(nextBare))
                'ذ', 'ز', 'ض', 'ظ' -> appendConsonant("z", isLikelyVowelLetter(nextBare))
                'ر' -> appendConsonant("r", isLikelyVowelLetter(nextBare))
                'ژ' -> appendConsonant("ʒ", isLikelyVowelLetter(nextBare))
                'ش' -> appendConsonant("ʃ", isLikelyVowelLetter(nextBare))
                'غ', 'ق' -> appendConsonant("q", isLikelyVowelLetter(nextBare))
                'ف' -> appendConsonant("f", isLikelyVowelLetter(nextBare))
                'ک' -> appendConsonant("k", isLikelyVowelLetter(nextBare))
                'گ' -> appendConsonant("ɡ", isLikelyVowelLetter(nextBare))
                'ل' -> appendConsonant("l", isLikelyVowelLetter(nextBare))
                'م' -> appendConsonant("m", isLikelyVowelLetter(nextBare))
                'ن' -> appendConsonant("n", isLikelyVowelLetter(nextBare))
                in '۰'..'۹' -> sb.append((ch - '۰' + '0'.code).toChar())
                in '٠'..'٩' -> sb.append((ch - '٠' + '0'.code).toChar())
                in '0'..'9' -> sb.append(ch)
                else -> Unit
            }
            idx++
        }

        // Trailing short-vowel resolution for 2-consonant words without vowel (e.g., سر, در, من, شب)
        if (!hasVowel && sb.isNotEmpty()) {
            val rawStr = sb.toString()
            if (rawStr.length >= 2) {
                val fixed = "${rawStr[0]}a${rawStr.substring(1)}"
                return insertPrimaryStress(fixed)
            }
        }
        return insertPrimaryStress(sb.toString())
    }

    private fun insertPrimaryStress(ipa: String): String {
        if (ipa.isEmpty() || ipa.contains('ˈ')) return ipa
        // Place primary stress on the last vowel (standard Persian word stress)
        var lastVowelIdx = -1
        for (idx in ipa.indices) {
            if (ipa[idx] in VOWEL_CHARS) {
                lastVowelIdx = idx
            }
        }
        if (lastVowelIdx < 0) return ipa
        return buildString(ipa.length + 1) {
            append(ipa, 0, lastVowelIdx)
            append('ˈ')
            append(ipa, lastVowelIdx, ipa.length)
        }
    }

    private fun appendIpaString(ipa: String, out: MutableList<String>) {
        for (ch in ipa) {
            out.add(ch.toString())
        }
    }

    private fun findPrevNonDiacritic(s: String, idx: Int): Char? {
        var j = idx - 1
        while (j >= 0) {
            val c = s[j]
            if (!isDiacritic(c) && c != '\u200C' && c != '\u200D') return c
            j--
        }
        return null
    }

    private fun findNextNonDiacritic(s: String, idx: Int): Char? {
        var j = idx + 1
        while (j < s.length) {
            val c = s[j]
            if (!isDiacritic(c) && c != '\u200C' && c != '\u200D') return c
            j++
        }
        return null
    }

    private fun findNextNonDiacriticAfterChar(s: String, idx: Int): Char? {
        var j = idx + 1
        while (j < s.length) {
            val c = s[j]
            if (!isDiacritic(c) && c != '\u200C' && c != '\u200D') {
                return findNextNonDiacritic(s, j)
            }
            j++
        }
        return null
    }

    private fun isLikelyVowelLetter(ch: Char?): Boolean =
        ch == 'ا' || ch == 'آ' || ch == 'و' || ch == 'ی'

    private fun isPersianWordChar(ch: Char): Boolean =
        (ch in '\u0600'..'\u06FF' && ch !in setOf('،', '؛', '؟', 'ـ', '۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹', '٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')) ||
            ch == '\u200C'

    private fun normalizePersianWordKeepZwnj(raw: String): String = buildString(raw.length) {
        for (c in raw) {
            val norm = when (c) {
                'ك' -> 'ک'
                'ي', 'ى', 'ئ' -> 'ی'
                'ة' -> 'ه'
                'أ', 'إ', 'ٱ' -> 'ا'
                'ؤ' -> 'و'
                else -> c
            }
            if (norm == 'ـ') continue
            append(norm)
        }
    }

    private fun stripDiacritics(s: String): String = buildString(s.length) {
        for (c in s) {
            if (!isDiacritic(c) && c != '\u200C' && c != '\u200D') {
                append(if (c == 'ۀ') 'ه' else c)
            }
        }
    }

    private fun isDiacritic(ch: Char): Boolean =
        ch in '\u064B'..'\u065F' || ch == '\u0670'

    private fun normalizePunctOrDigit(ch: Char): Char = when (ch) {
        '۰', '٠' -> '0'
        '۱', '١' -> '1'
        '۲', '٢' -> '2'
        '۳', '٣' -> '3'
        '۴', '٤' -> '4'
        '۵', '٥' -> '5'
        '۶', '٦' -> '6'
        '۷', '٧' -> '7'
        '۸', '٨' -> '8'
        '۹', '٩' -> '9'
        else -> ch
    }

    private val VOWEL_CHARS = setOf('a', 'ɑ', 'e', 'i', 'o', 'u')

    private val LATIN_WORD_IPA: Map<String, String> = mapOf(
        "sol" to "sˈuːl",
        "piper" to "pɑjpˈer",
        "vosk" to "vˈosk",
        "onnx" to "ˈoneks",
        "tts" to "tiːtiːˈes",
        "ram" to "rˈam",
        "pdf" to "piːdiːˈef",
        "epub" to "iːpˈɑb",
        "mb" to "meɡɑbˈɑjt",
        "gb" to "ɡiɡɑbˈɑjt",
        "hz" to "hˈerts",
        "ok" to "okˈej",
    )

    // Exact eSpeak-NG `fa` IPA pronunciations (single Unicode characters matching fa_IR-mana-medium.onnx.json)
    private val WORD_IPA: Map<String, String> = mapOf(
        // Greetings, Assistant Identity & Conversational Core
        "سلام" to "salˈɑm",
        "درود" to "dorˈuːd",
        "خداحافظ" to "xodɑhˈɑfez",
        "سول" to "sˈuːl",
        "من" to "mˈan",
        "تو" to "tˈo",
        "او" to "ˈuː",
        "ما" to "mˈɑ",
        "شما" to "ʃomˈɑ",
        "ایشان" to "iːʃˈɑn",
        "خود" to "xˈod",
        "خودم" to "xˈodam",
        "خودت" to "xˈodat",
        "خودتان" to "xˈodetɑn",
        "دستیار" to "dastjˈɑr",
        "شخصی" to "ʃaxsˈiː",
        "هوشمند" to "huːʃmˈand",
        "آفلاین" to "ɑflˈɑjn",
        "آنلاین" to "ɑnlˈɑjn",
        "مستقل" to "mostaqˈel",
        "خودکفا" to "xodkefˈɑ",
        "فارسی" to "fɑrsˈiː",
        "ایران" to "iːrˈɑn",
        "تهران" to "tehrˈɑn",
        "هستم" to "hˈastam",
        "هستی" to "hˈastiː",
        "هست" to "hˈast",
        "است" to "ˈast",
        "نیست" to "nˈiːst",
        "نیستم" to "nˈiːstam",
        "بود" to "bˈuːd",
        "بودم" to "bˈuːdam",
        "شد" to "ʃˈod",
        "شده" to "ʃodˈeː",
        "شود" to "ʃavˈad",
        "دارم" to "dˈɑram",
        "داری" to "dˈɑriː",
        "دارد" to "dˈɑrad",
        "دارند" to "dˈɑrand",
        "می" to "miː",
        "نمی" to "nemiː",
        "خوب" to "xˈuːb",
        "خوبی" to "xˈuːbiː",
        "خوبم" to "xˈuːbam",
        "عالی" to "ʔɑlˈiː",
        "ممنون" to "mamnˈuːn",
        "متشکرم" to "moteʃakˈeram",
        "سپاسگزارم" to "sepɑsɡozˈɑram",
        "مرسی" to "mersˈiː",
        "بله" to "balˈeː",
        "آره" to "ɑrˈeː",
        "نه" to "nˈa",
        "خیر" to "xˈejr",
        "لطفاً" to "lotfˈan",
        "لطفا" to "lotfˈan",
        "باشه" to "bˈɑʃeː",
        "حتماً" to "hatmˈan",
        "حتما" to "hatmˈan",
        "آماده" to "ɑmɑdˈeː",
        "آماده‌ام" to "ɑmɑdˈeam",
        "خوشبختم" to "xoʃbˈaxtam",

        // Common Pronouns, Prepositions, Conjunctions, Question Words
        "و" to "vˈa",
        "با" to "bˈɑ",
        "به" to "bˈeː",
        "از" to "ˈaz",
        "در" to "dˈar",
        "بر" to "bˈar",
        "برای" to "barˈɑje",
        "این" to "ˈiːn",
        "آن" to "ˈɑn",
        "اینجا" to "iːndʒˈɑ",
        "آنجا" to "ɑndʒˈɑ",
        "که" to "kˈeː",
        "را" to "rˈɑ",
        "رو" to "rˈuː",
        "یا" to "jˈɑ",
        "اما" to "ammˈɑ",
        "ولی" to "valˈiː",
        "اگر" to "aɡˈar",
        "تا" to "tˈɑ",
        "هم" to "hˈam",
        "فقط" to "faqˈat",
        "خیلی" to "xˈejliː",
        "بسیار" to "besjˈɑr",
        "کمی" to "kˈamiː",
        "بیشتر" to "biːʃtˈar",
        "کمتر" to "kamtˈar",
        "همه" to "hamˈeː",
        "هیچ" to "hˈiːtʃ",
        "چیز" to "tʃˈiːz",
        "چیزی" to "tʃˈiːziː",
        "چه" to "tʃˈeː",
        "چی" to "tʃˈiː",
        "چیه" to "tʃˈijeː",
        "چیست" to "tʃˈiːst",
        "چرا" to "tʃˈerɑ",
        "چطور" to "tʃetˈoːr",
        "چطوری" to "tʃetˈoːriː",
        "چگونه" to "tʃeɡuːnˈeː",
        "کجا" to "kodʒˈɑ",
        "کجاست" to "kodʒˈɑst",
        "کی" to "kˈej",
        "کدام" to "kodˈɑm",
        "چند" to "tʃˈand",
        "چندم" to "tʃandˈom",
        "چقدر" to "tʃeqˈadr",

        // Common Verbs & Commands
        "کن" to "kˈon",
        "کنید" to "konˈiːd",
        "بگو" to "beɡˈuː",
        "بگویید" to "beɡuːjˈiːd",
        "بنویس" to "benevˈiːs",
        "بخوان" to "bexˈɑn",
        "بخون" to "bexˈuːn",
        "بفرست" to "beferˈest",
        "بگیر" to "beɡˈiːr",
        "بزن" to "bezˈan",
        "بده" to "bedˈeː",
        "بذار" to "bezˈɑr",
        "بگذار" to "beɡozˈɑr",
        "باز" to "bˈɑz",
        "بستن" to "bastˈan",
        "روشن" to "roʃˈan",
        "خاموش" to "xɑmˈuːʃ",
        "شروع" to "ʃorˈuːʔ",
        "توقف" to "tavaqˈof",
        "لغو" to "lˈaqv",
        "تأیید" to "taʔˈiːd",
        "تایید" to "tajˈiːd",
        "ذخیره" to "zaxiːrˈeː",
        "حذف" to "hˈazf",
        "پاک" to "pˈɑk",
        "ثبت" to "sˈabt",
        "تنظیم" to "tanzˈiːm",
        "انجام" to "andʒˈɑm",
        "بدهم" to "bedahˈam",
        "بدم" to "bedˈam",
        "می‌توانم" to "miːtavˈɑnam",
        "می‌توانید" to "miːtavˈɑniːd",
        "می‌خواهم" to "miːxˈɑham",
        "نمی‌دانم" to "nemidˈɑnam",
        "نمی‌فهمم" to "nemifˈahmam",
        "متوجه" to "motevadʒˈeh",
        "نشد" to "naʃˈod",
        "نشدم" to "naʃˈodam",
        "پیدا" to "pejdˈɑ",
        "یاد" to "jˈɑd",
        "گرفتم" to "ɡerˈeftam",
        "سپردم" to "sepˈordam",
        "کمک" to "komˈak",
        "راهنما" to "rɑhnamˈɑ",

        // Numbers & Quantifiers
        "صفر" to "sˈefr",
        "یک" to "jˈek",
        "دو" to "dˈo",
        "سه" to "sˈeː",
        "چهار" to "tʃahˈɑr",
        "پنج" to "pˈandʒ",
        "شش" to "ʃˈeʃ",
        "شیش" to "ʃˈiːʃ",
        "هفت" to "hˈaft",
        "هشت" to "hˈaʃt",
        "نه" to "nˈoh",
        "ده" to "dˈah",
        "یازده" to "jɑzdˈah",
        "دوازده" to "davɑzdˈah",
        "سیزده" to "siːzdˈah",
        "چهارده" to "tʃahɑrdˈah",
        "پانزده" to "pɑnzdˈah",
        "شانزده" to "ʃɑnzdˈah",
        "هفده" to "hevdˈah",
        "هجده" to "hedʒdˈah",
        "هیجده" to "hiːdʒdˈah",
        "نوزده" to "nuːzdˈah",
        "بیست" to "bˈiːst",
        "سی" to "sˈiː",
        "چهل" to "tʃehˈel",
        "پنجاه" to "pandʒˈɑh",
        "شصت" to "ʃˈast",
        "هفتاد" to "haftˈɑd",
        "هشتاد" to "haʃtˈɑd",
        "نود" to "navˈad",
        "صد" to "sˈad",
        "یکصد" to "jeksˈad",
        "دویست" to "devˈiːst",
        "سیصد" to "siːsˈad",
        "چهارصد" to "tʃahɑrsˈad",
        "پانصد" to "pɑnsˈad",
        "ششصد" to "ʃeʃsˈad",
        "هفتصد" to "haftsˈad",
        "هشتصد" to "haʃtsˈad",
        "نهصد" to "nohsˈad",
        "هزار" to "hezˈɑr",
        "میلیون" to "miːljˈuːn",
        "میلیارد" to "miːljˈɑrd",
        "اول" to "avˈal",
        "دوم" to "dovˈom",
        "سوم" to "sevˈom",
        "نیم" to "nˈiːm",
        "نصف" to "nˈesf",
        "ربع" to "rˈobʔ",
        "درصد" to "darsˈad",
        "ممیز" to "momajˈez",
        "منفی" to "manfˈiː",

        // Time, Calendar, Jalali Months & Weekdays
        "ساعت" to "sɑˈʔat",
        "دقیقه" to "daqiːqˈeː",
        "ثانیه" to "sɑniːjˈeː",
        "روز" to "rˈuːz",
        "هفته" to "haftˈeː",
        "ماه" to "mˈɑh",
        "سال" to "sˈɑl",
        "برج" to "bˈordʒ",
        "امروز" to "emrˈuːz",
        "فردا" to "fardˈɑ",
        "پس‌فردا" to "pasfardˈɑ",
        "دیروز" to "diːrˈuːz",
        "الان" to "alˈʔɑn",
        "اکنون" to "aknˈuːn",
        "صبح" to "sˈobh",
        "ظهر" to "zˈohr",
        "عصر" to "ˈasr",
        "شب" to "ʃˈab",
        "شنبه" to "ʃambˈeː",
        "یکشنبه" to "jekʃambˈeː",
        "دوشنبه" to "doʃambˈeː",
        "سه‌شنبه" to "seʃambˈeː",
        "چهارشنبه" to "tʃahɑrʃambˈeː",
        "پنجشنبه" to "pandʒʃambˈeː",
        "پنج‌شنبه" to "pandʒʃambˈeː",
        "جمعه" to "dʒomˈʔeː",
        "فروردین" to "farvardˈiːn",
        "اردیبهشت" to "ordiːbehˈeʃt",
        "خرداد" to "xordˈɑd",
        "تیر" to "tˈiːr",
        "مرداد" to "mordˈɑd",
        "شهریور" to "ʃahriːvˈar",
        "مهر" to "mˈehr",
        "آبان" to "ɑbˈɑn",
        "آذر" to "ɑzˈar",
        "دی" to "dˈej",
        "بهمن" to "bahmˈan",
        "اسفند" to "esfˈand",
        "شمسی" to "ʃamsˈiː",
        "میلادی" to "miːlɑdˈiː",
        "تقویم" to "taqvˈiːm",
        "تاریخ" to "tɑrˈiːx",
        "کبیسه" to "kabiːsˈeː",

        // Sol App Features, UI, Hardware & Memory Vocabulary
        "تایمر" to "tɑjmˈer",
        "کرنومتر" to "koronomˈetr",
        "کرونومتر" to "koronomˈetr",
        "یادآور" to "jɑdʔɑvˈar",
        "یادآوری" to "jɑdʔɑvarˈiː",
        "آلارم" to "ɑlˈɑrm",
        "رویداد" to "ruːjdˈɑd",
        "جلسه" to "dʒalasˈeː",
        "چراغ" to "tʃerˈɑq",
        "قوه" to "qovˈeː",
        "چراغ‌قوه" to "tʃerɑqqovˈeː",
        "فلش" to "felˈaʃ",
        "ماشین‌حساب" to "mɑʃiːnhesˈɑb",
        "محاسبه" to "mohɑsebˈeː",
        "نتیجه" to "natiːdʒˈeː",
        "یادداشت" to "jɑddˈɑʃt",
        "لیست" to "lˈiːst",
        "فهرست" to "fehrˈest",
        "خرید" to "xarˈiːd",
        "کار" to "kˈɑr",
        "کارها" to "kɑrhˈɑ",
        "تماس" to "tamˈɑs",
        "زنگ" to "zˈanɡ",
        "تلفن" to "telefˈon",
        "پیام" to "pajˈɑm",
        "پیامک" to "pajɑmˈak",
        "مخاطب" to "moxɑtˈab",
        "مخاطبین" to "moxɑtabˈiːn",
        "شماره" to "ʃomɑrˈeː",
        "باتری" to "bɑtrˈiː",
        "شارژ" to "ʃˈɑrʒ",
        "دستگاه" to "dastɡˈɑh",
        "گوشی" to "ɡuːʃˈiː",
        "وضعیت" to "vazʔiːjˈat",
        "صدا" to "sedˈɑ",
        "صدای" to "sedˈɑje",
        "صوت" to "sˈoːt",
        "صوتی" to "sˈoːtiː",
        "میکروفون" to "miːkrofˈon",
        "گفتار" to "ɡoftˈɑr",
        "برنامه" to "barnɑmˈeː",
        "تنظیمات" to "tanzimˈɑt",
        "وای‌فای" to "vɑjfˈɑj",
        "بلوتوث" to "boluːtˈuːs",
        "اینترنت" to "iːnternˈet",
        "مکان" to "makˈɑn",
        "موقعیت" to "moqeʔiːjˈat",
        "فعلی" to "feʔlˈiː",
        "حافظه" to "hɑfezˈeː",
        "کتاب" to "ketˈɑb",
        "کتاب‌ها" to "ketɑbhˈɑ",
        "نقش" to "nˈaqʃ",
        "پاسخ" to "pɑsˈox",
        "پرسش" to "porsˈeʃ",
        "سؤال" to "soʔˈɑl",
        "سوال" to "soʔˈɑl",
        "پیام‌رسان" to "pajɑmresˈɑn",
        "اعلان" to "eʔlˈɑn",
        "پشتیبان" to "poʃtiːbˈɑn",
        "نسخه" to "nosxˈeː",
        "فایل" to "fˈɑjl",
        "مدل" to "modˈel",
        "موتور" to "motˈor",
        "دانلود" to "dɑnlˈod",
        "خطا" to "xatˈɑ",
        "موفق" to "movafˈaq",
        "ناموفق" to "nɑmovafˈaq",
        "مگابایت" to "meɡɑbˈɑjt",
        "گیگابایت" to "ɡiːɡɑbˈɑjt",
        "هرتز" to "hˈerts",
        "میلی‌ثانیه" to "miːliːsɑniːjˈeː",
        "رم" to "rˈam",
        "اسم" to "ˈesm",
        "نام" to "nˈɑm",
        "اسمت" to "ˈesmat",
        "کاربر" to "kɑrbˈar",
        "مالک" to "mɑlˈek",
        "دوست" to "dˈuːst",
        "عزیز" to "azˈiːz",
        "شرکت" to "ʃerkˈat",
        "دفتر" to "daftˈar",
        "خانه" to "xɑnˈeː",
        "منزل" to "manzˈel",

        // Common Persian Names
        "جواد" to "dʒavˈɑd",
        "علی" to "alˈiː",
        "رضا" to "rezˈɑ",
        "محمد" to "mohamˈad",
        "حسین" to "hosˈejn",
        "مهدی" to "mehdˈiː",
        "امیر" to "amˈiːr",
        "سارا" to "sɑrˈɑ",
        "مریم" to "marjˈam",
        "زهرا" to "zahrˈɑ",
        "فاطمه" to "fɑtemˈeː",
        "حسن" to "hasˈan",
        "بهرام" to "bahrˈɑm",
        "کاوه" to "kɑvˈeː",
        "آرش" to "ɑrˈaʃ",
        "نیما" to "niːmˈɑ",
        "پارسا" to "pɑrsˈɑ",
    )
}
