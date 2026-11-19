package com.example.sol.brain

import com.example.sol.memory.PersianNormalizer

class EntityExtractor {

    fun extractPhone(text: String): String? {
        val cleaned = PersianNormalizer.cleanText(text)
        val match = Regex("(?:\\+98|0098|0)?9\\d{9}|0\\d{2,3}-?\\d{7,8}").find(cleaned)
        return match?.value
    }

    fun extractLocationReference(normalized: String): String? {
        return when {
            normalized.contains("شرکت") -> "WORK"
            normalized.contains("دفتر") -> "OFFICE"
            normalized.contains("خانه") || normalized.contains("خونه") || normalized.contains("منزل") -> "HOME"
            else -> null
        }
    }

    fun extractMessageAfterSay(clean: String): String? {
        val patterns = listOf(
            Regex("(?:بگو|بگم|بنویس|اطلاع بده|خبر بده|بفرست)\\s+(?:که\\s+)?(.+)$"),
            Regex("پیام(?:ک)?\\s+بده\\s+(?:به\\s+.+?\\s+)?(?:که\\s+)?(.+)$"),
        )
        for (p in patterns) {
            val found = p.find(clean)?.groupValues?.getOrNull(1)?.trim()
            if (!found.isNullOrBlank()) return found
        }
        return null
    }

    fun extractContactForCall(clean: String): String? {
        val byWith = Regex("(?:با|به)\\s+(.+?)\\s+(?:یه\\s+)?(?:تماس|زنگ)").find(clean)
            ?.groupValues?.getOrNull(1)?.trim()
        if (!byWith.isNullOrBlank()) return cleanContactStopWords(byWith)

        val dialNumber = Regex("شماره\\s+(.+?)\\s+(?:را|رو)\\s+بگیر").find(clean)
            ?.groupValues?.getOrNull(1)?.trim()
        if (!dialNumber.isNullOrBlank()) return cleanContactStopWords(dialNumber)

        val afterCall = Regex("(?:زنگ بزن به|تماس بگیر با|تماس با)\\s+(.+?)(?:\\s+و\\s+بگو|\\s+بگو|$)")
            .find(clean)?.groupValues?.getOrNull(1)?.trim()
        return afterCall?.let(::cleanContactStopWords)?.takeIf { it.isNotBlank() }
    }

    fun extractContactForMessage(clean: String): String? {
        val p1 = Regex("(?:به|برای)\\s+(.+?)\\s+(?:یه\\s+)?(?:پیامک|پیام|اس ام اس|اس‌ام‌اس|اطلاع|خبر)")
            .find(clean)?.groupValues?.getOrNull(1)?.trim()
        if (!p1.isNullOrBlank()) return cleanContactStopWords(p1)

        val p2 = Regex("(?:پیامک|پیام|اس ام اس)\\s+(?:به|برای)\\s+(.+?)(?:\\s+(?:بده|بفرست|بگو|که)|$)")
            .find(clean)?.groupValues?.getOrNull(1)?.trim()
        return p2?.let(::cleanContactStopWords)?.takeIf { it.isNotBlank() }
    }

    private fun cleanContactStopWords(raw: String): String {
        return raw
            .removePrefix("آقای ")
            .removePrefix("خانم ")
            .removeSuffix(" رو")
            .removeSuffix(" را")
            .trim()
    }
}
