package com.example.sol.text

import android.content.Context
import java.io.File
import java.text.Normalizer
import java.util.Locale

data class PhraseMapping(
    val fromTokens: List<String>,
    val toTokens: List<String>,
)

class ColloquialLexicon(
    val phraseReplacements: List<PhraseMapping>,
    val tokenReplacements: Map<String, List<String>>,
) {
    fun applyToTokens(tokens: List<String>): List<String> {
        if (tokens.isEmpty()) return emptyList()
        val afterPhrases = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            var matchedPhrase: PhraseMapping? = null
            for (phrase in phraseReplacements) {
                val len = phrase.fromTokens.size
                if (len > 0 && i + len <= tokens.size) {
                    var ok = true
                    for (k in 0 until len) {
                        if (tokens[i + k] != phrase.fromTokens[k]) {
                            ok = false
                            break
                        }
                    }
                    if (ok) {
                        matchedPhrase = phrase
                        break
                    }
                }
            }
            if (matchedPhrase != null) {
                afterPhrases.addAll(matchedPhrase.toTokens)
                i += matchedPhrase.fromTokens.size
            } else {
                afterPhrases.add(tokens[i])
                i++
            }
        }

        val out = ArrayList<String>(afterPhrases.size)
        for (tok in afterPhrases) {
            val mapped = tokenReplacements[tok]
            if (mapped != null) {
                out.addAll(mapped)
            } else {
                out.add(tok)
            }
        }
        return out
    }

    companion object {
        @Volatile
        private var cachedDefault: ColloquialLexicon? = null

        fun loadDefault(): ColloquialLexicon {
            cachedDefault?.let { return it }
            val candidates = listOf(
                File("app/src/main/assets/colloquial_map.json"),
                File("src/main/assets/colloquial_map.json"),
            )
            for (f in candidates) {
                if (f.isFile) {
                    val loaded = runCatching { fromJsonString(f.readText(Charsets.UTF_8)) }.getOrNull()
                    if (loaded != null && (loaded.phraseReplacements.isNotEmpty() || loaded.tokenReplacements.isNotEmpty())) {
                        cachedDefault = loaded
                        return loaded
                    }
                }
            }
            return embeddedFallback().also { cachedDefault = it }
        }

        fun fromContext(context: Context): ColloquialLexicon {
            val json = runCatching {
                context.assets.open("colloquial_map.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull()
            return if (!json.isNullOrBlank()) {
                fromJsonString(json).also { cachedDefault = it }
            } else {
                loadDefault()
            }
        }

        fun fromJsonString(json: String): ColloquialLexicon {
            val phrases = mutableListOf<PhraseMapping>()
            val tokens = linkedMapOf<String, List<String>>()

            val phraseBlock = extractArrayBlock(json, "phraseReplacements")
            if (phraseBlock != null) {
                val objRegex = Regex("""\{\s*"from"\s*:\s*"([^"]+)"\s*,\s*"to"\s*:\s*"([^"]+)"\s*\}""")
                for (m in objRegex.findAll(phraseBlock)) {
                    val fromToks = PersianTextNormalizer.tokenizeRaw(m.groupValues[1])
                    val toToks = PersianTextNormalizer.tokenizeRaw(m.groupValues[2])
                    if (fromToks.isNotEmpty() && toToks.isNotEmpty()) {
                        phrases.add(PhraseMapping(fromToks, toToks))
                    }
                }
            }

            val tokenBlock = extractObjectBlock(json, "tokenReplacements")
            if (tokenBlock != null) {
                val pairRegex = Regex(""""([^"]+)"\s*:\s*"([^"]*)"""")
                for (m in pairRegex.findAll(tokenBlock)) {
                    val keyNorm = PersianTextNormalizer.normalizeForMatching(m.groupValues[1])
                    val valToks = PersianTextNormalizer.tokenizeRaw(m.groupValues[2])
                    if (keyNorm.isNotBlank() && valToks.isNotEmpty()) {
                        tokens[keyNorm] = valToks
                    }
                }
            }

            // Sort longest phrases first for deterministic greedy phrase matching
            val sortedPhrases = phrases.sortedByDescending { it.fromTokens.size }
            return ColloquialLexicon(sortedPhrases, tokens)
        }

        private fun extractArrayBlock(json: String, key: String): String? {
            val keyIdx = json.indexOf("\"$key\"")
            if (keyIdx < 0) return null
            val openIdx = json.indexOf('[', keyIdx)
            if (openIdx < 0) return null
            var depth = 0
            var inStr = false
            var escape = false
            for (i in openIdx until json.length) {
                val c = json[i]
                if (escape) {
                    escape = false
                    continue
                }
                if (c == '\\') {
                    escape = true
                    continue
                }
                if (c == '"') {
                    inStr = !inStr
                    continue
                }
                if (!inStr) {
                    if (c == '[') depth++
                    else if (c == ']') {
                        depth--
                        if (depth == 0) return json.substring(openIdx + 1, i)
                    }
                }
            }
            return null
        }

        private fun extractObjectBlock(json: String, key: String): String? {
            val keyIdx = json.indexOf("\"$key\"")
            if (keyIdx < 0) return null
            val openIdx = json.indexOf('{', keyIdx)
            if (openIdx < 0) return null
            var depth = 0
            var inStr = false
            var escape = false
            for (i in openIdx until json.length) {
                val c = json[i]
                if (escape) {
                    escape = false
                    continue
                }
                if (c == '\\') {
                    escape = true
                    continue
                }
                if (c == '"') {
                    inStr = !inStr
                    continue
                }
                if (!inStr) {
                    if (c == '{') depth++
                    else if (c == '}') {
                        depth--
                        if (depth == 0) return json.substring(openIdx + 1, i)
                    }
                }
            }
            return null
        }

        private fun embeddedFallback(): ColloquialLexicon {
            val phrases = listOf(
                "ساعت چنده" to "ساعت چند است",
                "امروز چندمه" to "امروز چندم است",
                "الان کجام" to "الان کجا هستم",
                "جست جو" to "جستجو",
                "وای فای" to "وایفای",
                "پی ام" to "پیام",
                "پس زمینه" to "پس زمینه",
                "می خوام" to "می خواهم",
                "می تونی" to "می توانی",
                "می شه" to "می شود",
                "می گم" to "می گویم",
                "می گی" to "می گویی",
                "یه چیزی" to "یک چیزی",
            ).map { (f, t) ->
                PhraseMapping(PersianTextNormalizer.tokenizeRaw(f), PersianTextNormalizer.tokenizeRaw(t))
            }.sortedByDescending { it.fromTokens.size }

            val tokMap = mapOf(
                "خونه" to "خانه",
                "میخوام" to "می خواهم",
                "میخوای" to "می خواهی",
                "میگم" to "می گویم",
                "میگی" to "می گویی",
                "میتونی" to "می توانی",
                "میشه" to "می شود",
                "بهش" to "به او",
                "واسه" to "برای",
                "برام" to "برای من",
                "دیگه" to "دیگر",
                "آره" to "بله",
                "رو" to "را",
                "توی" to "در",
                "یه" to "یک",
                "بذار" to "بگذار",
                "بخون" to "بخوان",
                "سرچ" to "جستجو",
                "آروم" to "آرام",
                "چطوری" to "چگونه ای",
                "چطوره" to "چگونه است",
                "چندمه" to "چندم است",
                "چیکار" to "چکار",
                "کجام" to "کجا هستم",
                "صداتو" to "صدای تو را",
            ).mapValues { PersianTextNormalizer.tokenizeRaw(it.value) }

            return ColloquialLexicon(phrases, tokMap)
        }
    }
}

object PersianTextNormalizer {
    private val MULTI_SPACE_REGEX = Regex("\\s+")
    private val HARAKAT_AND_MARKS_REGEX = Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640\\u200E\\u200F\\u202A-\\u202E\\uFEFF]")
    private val PERSIAN_DIGITS_REGEX = Regex("[۰-۹]")
    private val ARABIC_DIGITS_REGEX = Regex("[٠-٩]")
    private val PUNCT_EDGE_REGEX = Regex("^[\\p{Punct}؟،؛٪«»\"'()\\[\\]{}:;!?.]+|[\\p{Punct}؟،؛٪«»\"'()\\[\\]{}:;!?.]+$")
    private val SPLIT_PUNCT_REGEX = Regex("[\\s,،;؛!؟?.«»\"'()\\[\\]{}]+")
    private val URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

    // Protected stems/words whose final "ی" or ending must NOT be stripped
    private val PROTECTED_WORDS = setOf(
        "گوشی", "علی", "باتری", "صوتی", "فارسی", "عمومی", "خصوصی",
        "روزانه", "صبحگاهی", "فعلی", "مخفی", "قدیمی", "اتمی", "شخصی",
        "دقیقه", "ثانیه", "هفتگی", "وایفای", "اینترنتی", "انلاین", "آنلاین",
        "چی", "کی", "خیلی", "می", "بی", "ولی", "بله", "خیر", "خوبی", "چطوری",
        "مهمانی", "سالی", "ماهی", "هفتی", "بازی", "بررسی", "معرفی", "یادآوری",
        "پشتیبانی", "برنامه", "ریزی", "مربی", "کاربری", "دسترسی", "بهینه", "سازی",
    )

    private val INFLECTION_SUFFIXES = listOf(
        "هایشان", "هایتان", "هایمان",
        "هایش", "هایت", "هایم",
        "هایی", "های", "ها",
        "شان", "تان", "مان",
        "اش", "ات", "ام",
    )

    /**
     * Display-safe cleanup: applies NFKC, replaces Arabic kaf/yeh variants,
     * normalizes Persian/Arabic digits to ASCII 0-9, and collapses whitespace,
     * while preserving ZWNJ (\u200C), punctuation, and case for readability.
     */
    fun cleanDisplayText(raw: String): String {
        if (raw.isEmpty()) return ""
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        return nfkc.trim()
            .replace('\u064A', '\u06CC') // ي -> ی
            .replace('\u0649', '\u06CC') // ى -> ی
            .replace('\u0643', '\u06A9') // ك -> ک
            .replace(PERSIAN_DIGITS_REGEX) { (it.value[0] - '۰').toString() }
            .replace(ARABIC_DIGITS_REGEX) { (it.value[0] - '٠').toString() }
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    /**
     * Canonical matching normalization:
     * - NFKC normalization
     * - Removes harakat/diacritics, tatweel (\u0640), bidi/BOM marks
     * - Normalizes Arabic letters (ي/ى -> ی, ك -> ک, ة/ۀ/هٔ -> ه, أ/إ/ٱ -> ا, ؤ -> و, ئ -> ی)
     * - Converts ZWNJ (\u200C) to space so half-space variants match identically
     * - Converts Persian/Arabic digits to ASCII 0-9
     * - Normalizes Arabic/Persian punctuation (؟ -> ?, ، -> , , ؛ -> ; , ٪ -> %)
     * - Lowercases with Locale.ROOT and collapses whitespace
     */
    fun normalizeForMatching(raw: String): String {
        if (raw.isEmpty()) return ""
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val noDiacritics = HARAKAT_AND_MARKS_REGEX.replace(nfkc, "")
        return noDiacritics
            .replace("هٔ", "ه")
            .replace("ه\u0654", "ه")
            .replace('\u064A', '\u06CC') // ي -> ی
            .replace('\u0649', '\u06CC') // ى -> ی
            .replace('\u0626', '\u06CC') // ئ -> ی
            .replace('\u0643', '\u06A9') // ك -> ک
            .replace('\u0629', '\u0647') // ة -> ه
            .replace('\u06C0', '\u0647') // ۀ -> ه
            .replace('\u0623', '\u0627') // أ -> ا
            .replace('\u0625', '\u0627') // إ -> ا
            .replace('\u0671', '\u0627') // ٱ -> ا
            .replace('\u0624', '\u0648') // ؤ -> و
            .replace('\u200C', ' ')
            .replace('؟', '?')
            .replace('،', ',')
            .replace('؛', ';')
            .replace('٪', '%')
            .replace('«', '"')
            .replace('»', '"')
            .replace(PERSIAN_DIGITS_REGEX) { (it.value[0] - '۰').toString() }
            .replace(ARABIC_DIGITS_REGEX) { (it.value[0] - '٠').toString() }
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
            .lowercase(Locale.ROOT)
    }

    /**
     * Tokenizes raw input after normalization while keeping URLs intact as single tokens.
     */
    fun tokenizeRaw(raw: String): List<String> {
        val norm = normalizeForMatching(raw)
        if (norm.isBlank()) return emptyList()
        val result = ArrayList<String>()
        var cursor = 0
        for (urlMatch in URL_REGEX.findAll(norm)) {
            if (urlMatch.range.first > cursor) {
                splitSegmentIntoTokens(norm.substring(cursor, urlMatch.range.first), result)
            }
            result.add(urlMatch.value.trimEnd('.', ',', ';', '?', '!'))
            cursor = urlMatch.range.last + 1
        }
        if (cursor < norm.length) {
            splitSegmentIntoTokens(norm.substring(cursor), result)
        }
        return result
    }

    fun tokenize(
        raw: String,
        applyColloquialLexicon: Boolean = false,
        stripSuffixes: Boolean = false,
        lexicon: ColloquialLexicon = ColloquialLexicon.loadDefault(),
    ): List<String> {
        val base = tokenizeRaw(raw)
        val mapped = if (applyColloquialLexicon) lexicon.applyToTokens(base) else base
        return if (stripSuffixes) mapped.map(::stripSuffix) else mapped
    }

    /**
     * Light inflectional suffix stripper strictly for NLU matching (never for UI display).
     * Preserves protected stems and avoids over-stripping short tokens.
     */
    fun stripSuffix(token: String): String {
        val t = normalizeForMatching(token)
        if (t.length <= 3 || t in PROTECTED_WORDS || t.startsWith("http")) return t

        for (suffix in INFLECTION_SUFFIXES) {
            if (t.endsWith(suffix)) {
                val stem = t.removeSuffix(suffix)
                if (stem.length >= 3) {
                    return stem
                }
            }
        }
        if (t.endsWith("یی") && t.length >= 5) {
            return t.removeSuffix("یی")
        }
        return t
    }

    private fun splitSegmentIntoTokens(segment: String, out: MutableList<String>) {
        if (segment.isBlank()) return
        for (part in segment.split(SPLIT_PUNCT_REGEX)) {
            val cleaned = PUNCT_EDGE_REGEX.replace(part, "").trim()
            if (cleaned.isNotEmpty()) {
                out.add(cleaned)
            }
        }
    }
}
