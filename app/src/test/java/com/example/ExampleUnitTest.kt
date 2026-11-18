package com.example

import com.example.sol.brain.EntityExtractor
import com.example.sol.brain.IntentParser
import com.example.sol.brain.SolAction
import com.example.sol.calendar.JalaliCalendar
import com.example.sol.knowledge.CapabilityCategory
import com.example.sol.knowledge.SolCapabilitiesCatalog
import com.example.sol.voice.VoiceEngine
import com.example.sol.voice.VoiceVocabularyBias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ExampleUnitTest {
    private val parser = IntentParser(EntityExtractor())

    @Test
    fun parseAliasLearningCommand() {
        val cmd = parser.parse("Sol، هر وقت می‌گم دفتر منظورم شرکت کارنو صنعت ماکیان است")
        assertEquals(SolAction.LEARN_ALIAS, cmd.action)
        assertEquals("دفتر", cmd.aliasKey)
        assertEquals("شرکت کارنو صنعت ماکیان", cmd.aliasValue)
    }

    @Test
    fun parseCallWithMessageCommand() {
        val cmd = parser.parse("به علی زنگ بزن و بگو بیاد شرکت")
        assertEquals(SolAction.CALL, cmd.action)
        assertEquals("علی", cmd.contact)
        assertEquals("بیاد شرکت", cmd.message)
        assertEquals("WORK", cmd.locationReference)
        assertTrue(cmd.requireConfirmation)
    }

    @Test
    fun parseMultiStepNotificationCommand() {
        val cmd = parser.parse("وضعیت شرکت را به علی اطلاع بده و بعد نتیجه را به من گزارش کن")
        assertEquals(SolAction.MULTI_STEP_NOTIFY, cmd.action)
        assertEquals("علی", cmd.contact)
        assertEquals(6, cmd.steps.size)
        assertTrue(cmd.requireConfirmation)
    }

    @Test
    fun parseWebBookAndRoleLearningCommand() {
        val cmd = parser.parse("به اینترنت برو کتاب شازده کوچولو را پیدا کن و با توضیحات صوتی من به حافظه و نقشت اضافه کن")
        assertEquals(SolAction.WEB_BOOK_LEARN_ROLE, cmd.action)
        assertEquals("شازده کوچولو", cmd.bookQuery)
        assertTrue(cmd.requireConfirmation)
    }

    @Test
    fun parseScheduledBackgroundReminder() {
        val cmd = parser.parse("۵ دقیقه دیگر یادآوری کن جلسه را بررسی کنم")
        assertEquals(SolAction.SCHEDULE_REMINDER, cmd.action)
        assertEquals(5L, cmd.delayMinutes)
        assertEquals("جلسه را بررسی کنم", cmd.message)
    }

    @Test
    fun verifyTwentyCapabilitiesCatalogCounts() {
        val all = SolCapabilitiesCatalog.allTwentyCapabilities
        assertEquals(20, all.size)
        assertEquals(10, all.count { it.category == CapabilityCategory.BACKGROUND })
        assertEquals(1, all.count { it.category == CapabilityCategory.ADVANCED_LEARNING })
        assertEquals(9, all.count { it.category == CapabilityCategory.CORE_ASSISTANT })
    }

    @Test
    fun jalaliConversionAndLeapYearsAreAccurate() {
        // 2025-03-20 == 1403/12/30 (1403 is a Jalali leap year)
        assertTrue(JalaliCalendar.isJalaliLeapYear(1403))
        val end1403 = JalaliCalendar.gregorianToJalali(2025, 3, 20, Calendar.THURSDAY)
        assertEquals(1403, end1403.year)
        assertEquals(12, end1403.month)
        assertEquals(30, end1403.day)

        // 2025-03-21 == 1404/01/01 (Nowruz 1404; 1404 is not a leap year)
        assertFalse(JalaliCalendar.isJalaliLeapYear(1404))
        val nowruz1404 = JalaliCalendar.gregorianToJalali(2025, 3, 21, Calendar.FRIDAY)
        assertEquals(1404, nowruz1404.year)
        assertEquals(1, nowruz1404.month)
        assertEquals(1, nowruz1404.day)
        assertEquals("فروردین", nowruz1404.monthNameFa)

        val roundtrip = JalaliCalendar.jalaliToGregorian(1404, 1, 1)
        assertEquals(Triple(2025, 3, 21), roundtrip)
    }

    @Test
    fun jalaliQuestionAndComplexPersianSchedulingWork() {
        val fixedNow = Calendar.getInstance().apply {
            set(2025, Calendar.MARCH, 22, 10, 0, 0) // Saturday 1404/01/02
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val todayAnswer = JalaliCalendar.answerCalendarQuestion("امروز چندم برج است؟", fixedNow)
        assertTrue(todayAnswer.contains("1404/01/02"))
        assertTrue(todayAnswer.contains("فروردین"))

        val wednesdaySchedule = requireNotNull(
            JalaliCalendar.parsePersianDateTimeCommand(
                "چهارشنبه ساعت ۹ صبح یادآوری کن جلسه مهم دارم",
                nowMillis = fixedNow,
            )
        )
        val cal = Calendar.getInstance().apply { timeInMillis = wednesdaySchedule.triggerAtMillis }
        assertEquals(Calendar.WEDNESDAY, cal.get(Calendar.DAY_OF_WEEK))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals("جلسه مهم دارم", wednesdaySchedule.reminderMessage)

        val twoDaysMsg = requireNotNull(
            JalaliCalendar.parsePersianDateTimeCommand(
                "دو روز دیگه به علی پیام یادآوری بفرست",
                nowMillis = fixedNow,
            )
        )
        assertTrue(twoDaysMsg.isMessageReminder)
        assertEquals("علی", twoDaysMsg.contactHint)
    }

    @Test
    fun voiceConfirmationAndVocabularyBiasWorkCorrectly() {
        assertEquals(true, VoiceEngine.parseVoiceConfirmationDecision("تأیید"))
        assertEquals(true, VoiceEngine.parseVoiceConfirmationDecision("بله انجام بده"))
        assertEquals(false, VoiceEngine.parseVoiceConfirmationDecision("لغو کن"))
        assertEquals(false, VoiceEngine.parseVoiceConfirmationDecision("نه نفرست"))
        assertNull(VoiceEngine.parseVoiceConfirmationDecision("هوا چطوره"))

        val bias = VoiceVocabularyBias()
        bias.updateTerms(listOf("شرکت کارنو صنعت ماکیان", "دفتر"))
        assertEquals(2, bias.currentTermsCount())
        val corrected = bias.applyBiasCorrection("ارسال گزارش به شرکت کارنو   صنعت ماکیان")
        assertTrue(corrected.contains("شرکت کارنو صنعت ماکیان"))
    }
}
