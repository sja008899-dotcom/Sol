package com.example

import com.example.sol.brain.EntityExtractor
import com.example.sol.brain.IntentParser
import com.example.sol.brain.SolAction
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
    fun parseCurrentLocationAndDeviceStatus() {
        val locCmd = parser.parse("الان شرکت هستم")
        assertEquals(SolAction.SET_CURRENT_LOCATION, locCmd.action)
        assertEquals("شرکت", locCmd.locationReference)

        val batCmd = parser.parse("وضعیت باتری چطوره؟")
        assertEquals(SolAction.DEVICE_STATUS, batCmd.action)
    }
}
