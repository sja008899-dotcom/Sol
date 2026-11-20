package com.example

import com.example.sol.brain.EntityExtractor
import com.example.sol.brain.IntentParser
import com.example.sol.brain.SlotValue
import com.example.sol.brain.SolAction
import com.example.sol.text.ColloquialLexicon
import com.example.sol.text.PersianNumbers
import com.example.sol.text.PersianTextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3TextAndNormalizationTests {

    private val parser = IntentParser(EntityExtractor(), ColloquialLexicon.loadDefault())

    @Test
    fun unicodeZwnjDiacriticsTatweelDigitsAndPunctuationNormalizeCleanly() {
        val raw = "مِی\u200Cخْوَام كِتَابِ «شازدۀ كوچولو» رُا بـا قیمت ۱۲٬۳۴۵ و ۰۹۱۲؟"
        val norm = PersianTextNormalizer.normalizeForMatching(raw)

        assertFalse(norm.contains('\u200C'))
        assertFalse(norm.contains('\u0640')) // tatweel removed
        assertFalse(norm.contains('ي'))
        assertFalse(norm.contains('ك'))
        assertFalse(norm.contains('ۀ'))
        assertTrue(norm.contains("می خوام"))
        assertTrue(norm.contains("کتاب"))
        assertTrue(norm.contains("شازده کوچولو"))
        assertTrue(norm.contains("12"))
        assertTrue(norm.contains("0912"))
        assertTrue(norm.endsWith("?"))

        // Display cleanup keeps ZWNJ intact while converting Arabic variants and digits
        val display = PersianTextNormalizer.cleanDisplayText("ساعت ۱۴:۳۰ می‌روم كلاس")
        assertTrue(display.contains('\u200C'))
        assertTrue(display.contains("14:30"))
        assertTrue(display.contains("کلاس"))
    }

    @Test
    fun tokenizerAndSuffixStripperRespectProtectedStems() {
        val tokens = PersianTextNormalizer.tokenize(
            raw = "لطفاً برنامه\u200Cهای گوشی علی را بررسی کن!",
            applyColloquialLexicon = false,
            stripSuffixes = true,
        )
        assertTrue(tokens.contains("برنامه"))
        assertTrue(tokens.contains("گوشی")) // must NOT strip to گوش
        assertTrue(tokens.contains("علی"))  // must NOT strip to عل
        assertEquals("کتاب", PersianTextNormalizer.stripSuffix("کتابهایشان"))
        assertEquals("یادآور", PersianTextNormalizer.stripSuffix("یادآورها"))
        assertEquals("خوب", PersianTextNormalizer.stripSuffix("خوب"))
    }

    @Test
    fun colloquialLexiconLoadsFromAssetFileAndMapsNaturalSpeech() {
        val lexicon = ColloquialLexicon.loadDefault()
        assertTrue(lexicon.phraseReplacements.isNotEmpty())
        assertTrue(lexicon.tokenReplacements.isNotEmpty())

        val mapped = PersianTextNormalizer.tokenize(
            raw = "میخوام برم خونه، ساعت چنده؟",
            applyColloquialLexicon = true,
            lexicon = lexicon,
        )
        val joined = mapped.joinToString(" ")
        assertTrue(joined.contains("می خواهم"))
        assertTrue(joined.contains("خانه"))
        assertTrue(joined.contains("ساعت چند است"))
    }

    @Test
    fun persianNumbersConvertBidirectionallyAndPrepareTts() {
        assertEquals(25.0, PersianNumbers.wordsToNumber("بیست و پنج")!!, 0.001)
        assertEquals(1340.0, PersianNumbers.wordsToNumber("هزار و سیصد و چهل")!!, 0.001)
        assertEquals(0.5, PersianNumbers.wordsToNumber("نیم")!!, 0.001)
        assertEquals(0.25, PersianNumbers.wordsToNumber("ربع")!!, 0.001)
        assertEquals(0.75, PersianNumbers.wordsToNumber("سه ربع")!!, 0.001)
        assertEquals(1.5, PersianNumbers.wordsToNumber("یک و نیم")!!, 0.001)
        assertNull(PersianNumbers.wordsToNumber("کلمه نامربوط"))

        assertEquals("بیست و پنج", PersianNumbers.numberToWords(25L))
        assertEquals("هزار و سیصد و چهل", PersianNumbers.numberToWords(1340L))
        assertEquals("یک و نیم", PersianNumbers.numberToWords(1.5))
        assertEquals("سه ربع", PersianNumbers.numberToWords(0.75))

        assertEquals(90L, PersianNumbers.parseSpokenDurationMinutes("یک ساعت و نیم دیگر یادآوری کن"))
        assertEquals(45L, PersianNumbers.parseSpokenDurationMinutes("سه ربع ساعت دیگر یادآوری کن"))
        assertEquals(25L, PersianNumbers.parseSpokenDurationMinutes("بیست و پنج دقیقه دیگه یادم بنداز"))

        val replaced = PersianNumbers.replaceWordNumbersWithDigits("بیست و پنج دقیقه دیگه بیدارم کن")
        assertTrue(replaced.startsWith("25 دقیقه"))

        // Preservation of non-numeric negation "نه"
        val negPreserved = PersianNumbers.replaceWordNumbersWithDigits("نه نمیخوام الان")
        assertTrue(negPreserved.startsWith("نه "))

        val tts = PersianNumbers.prepareTextForTts("شارژ باتری 75% است و ساعت 14:30 جلسه داریم • 09123456789")
        assertTrue(tts.contains("هفتاد و پنج درصد"))
        assertTrue(tts.contains("چهارده و نیم"))
        assertTrue(tts.contains("09123456789")) // phone digits preserved verbatim
    }

    @Test
    fun b2RuleRefactorEliminatesSubstringFalsePositivesAndSupportsRankedParsing() {
        // Substring "وب" inside "خوب" MUST NOT trigger WEB_BOOK_LEARN_ROLE
        val goodGreeting = parser.parse("حال شما خوب است؟")
        assertEquals(SolAction.GREETING, goodGreeting.action)
        assertNotEquals(SolAction.WEB_BOOK_LEARN_ROLE, goodGreeting.action)

        // Substring "برو" inside "بروم" MUST NOT trigger WEB_BOOK_LEARN_ROLE or OPEN_APP
        val goingOut = parser.parse("باید بروم بیرون کار دارم")
        assertEquals(SolAction.UNKNOWN, goingOut.action)

        // "زنگ تفریح" / "زنگ در" MUST NOT trigger CALL
        assertEquals(SolAction.UNKNOWN, parser.parse("زنگ تفریح کی هست؟").action)
        assertEquals(SolAction.UNKNOWN, parser.parse("زنگ در خراب شده").action)

        // Spoken fractional reminder via B1 + B2 integration
        val halfHourReminder = parser.parse("یک ساعت و نیم دیگه یادآوری کن قرصم رو بخورم")
        assertEquals(SolAction.SCHEDULE_REMINDER, halfHourReminder.action)
        assertEquals(90L, halfHourReminder.delayMinutes)
        assertTrue(halfHourReminder.slots["delay_minutes"] is SlotValue.DurationMinutes)
        assertEquals(90L, (halfHourReminder.slots["delay_minutes"] as SlotValue.DurationMinutes).minutes)

        // Ranked output exposes primary intent cleanly ordered by score & priority
        val ranked = parser.parseRanked("سلام سول، به علی زنگ بزن و بگو ساعت ۵ جلسه داریم")
        assertTrue(ranked.isNotEmpty())
        assertEquals(SolAction.CALL, ranked.first().action)
        assertNotNull(ranked.first().command.matchedRuleId)
    }
}
