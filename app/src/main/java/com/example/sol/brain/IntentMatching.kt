package com.example.sol.brain

import com.example.sol.text.ColloquialLexicon
import com.example.sol.text.PersianNumbers
import com.example.sol.text.PersianTextNormalizer

/**
 * Tokenized, normalized, colloquial-mapped context shared by all modular [IntentMatcher] rules.
 * Provides word/token-boundary matching primitives to prevent raw substring collisions
 * (e.g., "وب" inside "خوب", "برو" inside "بروم", "زنگ" inside "زنگ تفریح").
 */
class MatchContext(
    val rawText: String,
    val strippedWake: String,
    val cleaned: String,
    val normalized: String,
    val rawNormalized: String,
    val tokens: List<String>,
    val formalTokens: List<String>,
    val stemmedTokens: List<String>,
    val numberConvertedCleaned: String,
    val numberConvertedNormalized: String,
) {
    val tokenSet: Set<String> = tokens.toHashSet()
    val formalTokenSet: Set<String> = formalTokens.toHashSet()
    val combinedTokenSet: Set<String> = tokenSet + formalTokenSet
    val stemmedTokenSet: Set<String> = stemmedTokens.toHashSet()
    val formalNormalized: String = formalTokens.joinToString(" ")

    fun hasToken(token: String): Boolean {
        val normTok = PersianTextNormalizer.normalizeForMatching(token)
        return normTok in combinedTokenSet
    }

    fun hasExactRawToken(token: String): Boolean {
        val normTok = PersianTextNormalizer.normalizeForMatching(token)
        return normTok in tokenSet
    }

    fun hasAnyToken(vararg candidates: String): Boolean {
        return candidates.any { hasToken(it) }
    }

    fun hasAllTokens(vararg required: String): Boolean {
        return required.all { hasToken(it) }
    }

    fun hasStem(stem: String): Boolean {
        val s = PersianTextNormalizer.stripSuffix(stem)
        return s in stemmedTokenSet || hasToken(stem)
    }

    fun hasPhrase(phrase: String): Boolean {
        val phraseTokens = PersianTextNormalizer.tokenizeRaw(phrase)
        if (phraseTokens.isEmpty()) return false
        return containsTokenSequence(tokens, phraseTokens) || containsTokenSequence(formalTokens, phraseTokens)
    }

    fun hasAnyPhrase(vararg phrases: String): Boolean {
        return phrases.any { hasPhrase(it) }
    }

    fun startsWithTokens(vararg prefix: String): Boolean {
        if (tokens.size < prefix.size) return false
        return prefix.indices.all { i ->
            tokens[i] == PersianTextNormalizer.normalizeForMatching(prefix[i])
        }
    }

    fun startsWithAnyPhrase(vararg phrases: String): Boolean {
        return phrases.any { phrase ->
            val pTokens = PersianTextNormalizer.tokenizeRaw(phrase)
            pTokens.isNotEmpty() && (
                (tokens.size >= pTokens.size && tokens.subList(0, pTokens.size) == pTokens) ||
                    (formalTokens.size >= pTokens.size && formalTokens.subList(0, pTokens.size) == pTokens)
                )
        }
    }

    private fun containsTokenSequence(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.size > haystack.size) return false
        val limit = haystack.size - needle.size
        for (i in 0..limit) {
            var matched = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }

    companion object {
        private val WAKE_PREFIX_REGEX = Regex("^(?:سلام\\s+)?(?:sol|سول|سال)[,،:\\s]+", RegexOption.IGNORE_CASE)
        private val POLITE_PREFIX_REGEX_1 = Regex("^(?:ببخشید|لطفاً|لطفا|بی\\s*زحمت|بیزحمت|اگر\\s+میشه|اگه\\s+میشه|اگر\\s+می\\s*شود|اگه\\s+می\\s*شه|میشه|می‌شه|می\\s*شه|می‌تونی|میتونی|می\\s*تونی|لطفا\\s+)\\s*")
        private val POLITE_PREFIX_REGEX_2 = Regex("^(?:لطفاً|لطفا|بی\\s*زحمت|بیزحمت)\\s+")

        fun create(raw: String, lexicon: ColloquialLexicon = ColloquialLexicon.loadDefault()): MatchContext {
            val cleanDisplay = PersianTextNormalizer.cleanDisplayText(raw)
            val strippedWake = WAKE_PREFIX_REGEX.replace(cleanDisplay, "").trim()
            val cleaned = stripPoliteFiller(strippedWake)
            val norm = PersianTextNormalizer.normalizeForMatching(cleaned)
            val rawNorm = PersianTextNormalizer.normalizeForMatching(strippedWake)
            val rawTokens = PersianTextNormalizer.tokenizeRaw(cleaned)
            val formalTokens = lexicon.applyToTokens(rawTokens)
            val stemmedTokens = (rawTokens + formalTokens).map(PersianTextNormalizer::stripSuffix).distinct()
            val numCleaned = PersianNumbers.replaceWordNumbersWithDigits(cleaned)
            val numNorm = PersianTextNormalizer.normalizeForMatching(numCleaned)

            return MatchContext(
                rawText = raw,
                strippedWake = strippedWake,
                cleaned = cleaned,
                normalized = norm,
                rawNormalized = rawNorm,
                tokens = rawTokens,
                formalTokens = formalTokens,
                stemmedTokens = stemmedTokens,
                numberConvertedCleaned = numCleaned,
                numberConvertedNormalized = numNorm,
            )
        }

        private fun stripPoliteFiller(text: String): String {
            val step1 = POLITE_PREFIX_REGEX_1.replace(text, "")
            return POLITE_PREFIX_REGEX_2.replace(step1, "").trim()
        }
    }
}

data class MatchResult(
    val score: Float,
    val slots: Map<String, SlotValue> = emptyMap(),
    val evidence: List<String> = emptyList(),
    val buildCommand: (MatchContext, MatchResult) -> ParsedCommand,
)

interface IntentMatcher {
    val id: String
    val action: SolAction
    val priority: Int
    fun match(ctx: MatchContext): MatchResult?
}
