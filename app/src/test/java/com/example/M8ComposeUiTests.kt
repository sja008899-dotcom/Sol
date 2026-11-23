package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.example.sol.contacts.ContactSource
import com.example.sol.contacts.ResolvedContact
import com.example.sol.device.StorageStatus
import com.example.sol.dialogue.DialogueUiChoice
import com.example.sol.knowledge.SlmModelCatalog
import com.example.sol.security.RamUsageSnapshot
import com.example.sol.tasks.CommunicationChannel
import com.example.sol.tasks.PendingCommunicationConfirmation
import com.example.sol.ui.ChatBubbleCard
import com.example.sol.ui.ChatMessageItem
import com.example.sol.ui.CommunicationConfirmationDialog
import com.example.sol.ui.ReliabilityScreen
import com.example.ui.theme.SolTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class M8ComposeUiTests {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `confirmation dialog renders candidate SMS editor and invokes confirm and cancel callbacks`() {
        var confirmed = false
        var cancelled = false
        var editedMessage = "پیام از طرف دستیار Sol"
        var selectedContactName = ""

        val ali1 = ResolvedContact("1", "علی رضایی", "09121112233", ContactSource.MEMORY, "همکار")
        val ali2 = ResolvedContact("2", "علی کریمی", "09123334455", ContactSource.MEMORY, "دوست")

        val confirmation = PendingCommunicationConfirmation(
            channel = CommunicationChannel.SMS,
            candidateContacts = listOf(ali1, ali2),
            selectedContact = ali1,
            messageBody = editedMessage,
            callScriptPreview = "متن تماس آزمایشی",
            confirmedCurrentLocation = "شرکت کارنو",
            isMultiStepReport = false,
        )

        composeTestRule.setContent {
            SolTheme(darkTheme = false) {
                CommunicationConfirmationDialog(
                    confirmation = confirmation,
                    isListeningVoiceConfirm = false,
                    voiceConfirmFeedback = "",
                    onStartVoiceConfirm = {},
                    onStopVoiceConfirm = {},
                    onSelectCandidate = { selectedContactName = it.name },
                    onSelectChannel = {},
                    onMessageChange = { editedMessage = it },
                    onConfirm = { confirmed = true },
                    onCancel = { cancelled = true },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("confirm_sms_body_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_sms_body_input").performTextReplacement("متن ویرایش‌شده")
        assertEquals("متن ویرایش‌شده", editedMessage)

        composeTestRule.onNodeWithTag("confirm_communication_button").assertIsDisplayed().performClick()
        assertTrue("Expected confirm callback on CommunicationConfirmationDialog", confirmed)

        composeTestRule.onNodeWithTag("cancel_communication_button").assertIsDisplayed().performClick()
        assertTrue("Expected cancel callback on CommunicationConfirmationDialog", cancelled)
        assertEquals("", selectedContactName)
    }

    @Test
    fun `reliability screen shows status Samsung guide self-test and invokes deep link callbacks`() {
        var batteryClicked = false
        var exactAlarmClicked = false
        var notifClicked = false
        var selfTestClicked = false
        var backClicked = false

        val spec = SlmModelCatalog.recommendedModelForDevice(3700L)
        composeTestRule.setContent {
            SolTheme(darkTheme = false) {
                ReliabilityScreen(
                    isIgnoringBatteryOptimizations = false,
                    canScheduleExactAlarms = true,
                    areNotificationsEnabled = true,
                    lastReminderDeliveredAt = 1_742_544_000_000L,
                    lastReminderDeliveredTitle = "یادآور آزمایشی Sol",
                    ramSnapshot = RamUsageSnapshot(
                        totalDeviceRamMb = 3_750L,
                        availableDeviceRamMb = 1_250L,
                        appHeapUsedMb = 48L,
                        isLowMemory = false,
                    ),
                    storageStatus = StorageStatus(
                        freeBytes = 10L * 1024L * 1024L * 1024L,
                        totalBytes = 64L * 1024L * 1024L * 1024L,
                    ),
                    recommendedSlmModel = spec,
                    onOpenBatteryOptimizationSettings = { batteryClicked = true },
                    onOpenExactAlarmSettings = { exactAlarmClicked = true },
                    onOpenNotificationSettings = { notifClicked = true },
                    onScheduleSelfTestReminder = { selfTestClicked = true },
                    onBack = { backClicked = true },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("reliability_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reliability_battery_button").performClick()
        composeTestRule.onNodeWithTag("reliability_screen").performScrollToNode(hasTestTag("reliability_exact_alarm_button"))
        composeTestRule.onNodeWithTag("reliability_exact_alarm_button").performClick()
        composeTestRule.onNodeWithTag("reliability_screen").performScrollToNode(hasTestTag("reliability_notification_button"))
        composeTestRule.onNodeWithTag("reliability_notification_button").performClick()
        composeTestRule.onNodeWithTag("reliability_screen").performScrollToNode(hasTestTag("reliability_samsung_guide_card"))
        composeTestRule.onNodeWithTag("reliability_samsung_guide_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reliability_screen").performScrollToNode(hasTestTag("reliability_self_test_button"))
        composeTestRule.onNodeWithTag("reliability_self_test_button").performClick()
        composeTestRule.onNodeWithTag("reliability_screen").performScrollToNode(hasTestTag("reliability_back_button"))
        composeTestRule.onNodeWithTag("reliability_back_button").performClick()

        assertTrue(batteryClicked)
        assertTrue(exactAlarmClicked)
        assertTrue(notifClicked)
        assertTrue(selfTestClicked)
        assertTrue(backClicked)
    }

    @Test
    fun `clarification buttons in ChatBubbleCard render and submit chosen option`() {
        val clickedPayloads = mutableListOf<String>()
        val msg = ChatMessageItem(
            id = 42L,
            isUser = false,
            text = "مطمئن نیستم دقیقاً کدام کار را می‌خواهید؛ لطفاً انتخاب کنید:",
            choices = listOf(
                DialogueUiChoice(id = "clarify_0", labelFa = "۱. تماس تلفنی", submitText = "۱", isPrimary = true),
                DialogueUiChoice(id = "clarify_1", labelFa = "۲. ارسال پیامک", submitText = "۲"),
                DialogueUiChoice(id = "cancel", labelFa = "لغو", submitText = "لغو", isDestructive = true),
            ),
        )

        composeTestRule.setContent {
            SolTheme(darkTheme = false) {
                ChatBubbleCard(
                    item = msg,
                    onSpeakClick = {},
                    onChoiceClick = { clickedPayloads.add(it) },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("dialogue_choice_clarify_0")
            .assertIsDisplayed()
            .assertTextContains("تماس تلفنی", substring = true)
            .performClick()

        composeTestRule.onNodeWithTag("dialogue_choice_clarify_1")
            .assertIsDisplayed()
            .performClick()

        composeTestRule.onNodeWithTag("dialogue_choice_cancel")
            .assertIsDisplayed()
            .performClick()

        assertEquals(listOf("۱", "۲", "لغو"), clickedPayloads)
    }
}
