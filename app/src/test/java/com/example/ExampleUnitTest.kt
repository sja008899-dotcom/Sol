package com.example

import com.example.sol.brain.EntityExtractor
import com.example.sol.brain.IntentParser
import com.example.sol.brain.SolAction
import com.example.sol.knowledge.CapabilityCategory
import com.example.sol.knowledge.SolCapabilitiesCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
