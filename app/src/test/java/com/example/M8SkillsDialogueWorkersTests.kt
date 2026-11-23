package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.example.sol.background.BatteryMonitorWorker
import com.example.sol.background.BookFetchWorker
import com.example.sol.background.DailyRoutineWorker
import com.example.sol.background.HistoryMaintenanceWorker
import com.example.sol.background.LocationCheckInWorker
import com.example.sol.background.MemoryBackupWorker
import com.example.sol.background.MemoryIndexerWorker
import com.example.sol.brain.ParsedCommand
import com.example.sol.brain.PendingSolTask
import com.example.sol.brain.SlotValue
import com.example.sol.brain.SolAction
import com.example.sol.brain.skills.Sensitivity
import com.example.sol.brain.skills.SkillContext
import com.example.sol.brain.skills.SkillRegistry
import com.example.sol.brain.skills.SkillResult
import com.example.sol.brain.skills.SolSkill
import com.example.sol.dialogue.PendingDialogueState
import com.example.sol.memory.BookKnowledgeEntity
import com.example.sol.memory.MemoryDatabase
import com.example.sol.speaker.SpeakerVerificationOutcome
import com.example.sol.voice.MicForegroundService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowCameraManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class M8SkillsDialogueWorkersTests {

    @Test
    fun `sensitive actions never fire dialer or SMS intents without explicit user confirmation`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as SolApplication
        val c = app.container
        val shadowApp = Shadows.shadowOf(app)

        // Drain any pre-existing started activities
        while (shadowApp.nextStartedActivity != null) {
            // drain
        }
        c.dialogueManager.clearHistory()
        c.confirmationManager.dismiss()
        c.personRepository.savePerson("مریم نوری", "09125550101", "همکار")

        try {
            // 1. CALL missing slot -> asks contact; with contact -> awaits confirmation -> NO dialer intent launched
            val callMissing = c.commandBrain.processDialogue("تماس بگیر")
            assertTrue(c.dialogueManager.currentPendingState() is PendingDialogueState.MissingSlot)
            assertNull("No dialer intent on missing slot", shadowApp.nextStartedActivity)
            assertTrue(callMissing.replyText.contains("چه کسی"))

            val callReady = c.commandBrain.processDialogue("مریم نوری")
            assertTrue(c.dialogueManager.currentPendingState() is PendingDialogueState.AwaitingConfirmation)
            assertTrue(callReady.pendingTask is PendingSolTask.Communicate)
            assertNull("CALL must NEVER start dialer without confirmation", shadowApp.nextStartedActivity)

            // Even passing PendingSolTask.Communicate into TaskManager only opens ConfirmationManager (still no dialer!)
            c.taskManager.startPendingTask(callReady.pendingTask as PendingSolTask.Communicate) {}
            assertNotNull(c.confirmationManager.pending.value)
            assertNull("TaskManager must wait for user confirmation before opening dialer", shadowApp.nextStartedActivity)

            // Cancel closes flow cleanly without launching dialer
            c.commandBrain.processDialogue("لغو")
            c.confirmationManager.dismiss()
            assertNull(c.dialogueManager.currentPendingState())
            assertNull(shadowApp.nextStartedActivity)

            // 2. SEND_SMS missing body -> fill -> awaits confirmation -> NO SMS intent launched until confirmed
            c.commandBrain.processDialogue("به مریم نوری پیام بده")
            assertTrue(c.dialogueManager.currentPendingState() is PendingDialogueState.MissingSlot)
            val smsConfirm = c.commandBrain.processDialogue("ساعت ۵ می‌رسم")
            assertTrue(c.dialogueManager.currentPendingState() is PendingDialogueState.AwaitingConfirmation)
            assertTrue(smsConfirm.pendingTask is PendingSolTask.Communicate)
            assertNull("SEND_SMS must NEVER launch SMS intent without confirmation", shadowApp.nextStartedActivity)

            // Explicit confirm in TaskManager is what opens SMS composer
            val confirmed = smsConfirm.pendingTask as PendingSolTask.Communicate
            c.taskManager.startPendingTask(confirmed) {}
            val pendingUi = c.confirmationManager.pending.value!!
            c.taskManager.confirmAndExecute(pendingUi) {}
            val smsIntent = shadowApp.nextStartedActivity
            assertNotNull("Confirmed SMS should launch SENDTO intent", smsIntent)
            assertEquals(Intent.ACTION_SENDTO, smsIntent!!.action)
            assertTrue(smsIntent.getStringExtra("sms_body").orEmpty().contains("Sol"))

            // 3. MULTI_STEP_NOTIFY -> awaits confirmation -> NO intent before confirmation
            c.dialogueManager.clearHistory()
            val multiTurn = c.commandBrain.processDialogue("وضعیت شرکت را به مریم نوری اطلاع بده و بعد نتیجه را به من گزارش کن")
            assertTrue(c.dialogueManager.currentPendingState() is PendingDialogueState.AwaitingConfirmation)
            assertTrue(multiTurn.pendingTask is PendingSolTask.Communicate)
            assertNull("MULTI_STEP_NOTIFY must NOT launch intent unconfirmed", shadowApp.nextStartedActivity)

            // 4. Ambiguous contact choice + intent clarification cancel
            c.dialogueManager.clearHistory()
            c.personRepository.savePerson("سارا احمدی", "09121110001", "دوست")
            c.personRepository.savePerson("سارا کریمی", "09121110002", "همکار")
            val ambig = c.commandBrain.processDialogue("به سارا زنگ بزن")
            assertTrue(c.dialogueManager.currentPendingState() is PendingDialogueState.AwaitingContactChoice)
            assertEquals(2, ambig.actionChoices.count { it.id.startsWith("contact_") })
            c.commandBrain.processDialogue("لغو")
            assertNull(c.dialogueManager.currentPendingState())

            // 5. Backup restore confirmation gate cancels cleanly
            var backupCallbackCalled = false
            val restorePrompt = c.dialogueManager.requestBackupRestoreConfirmation("فایل تست") {
                backupCallbackCalled = true
                "بازیابی شد"
            }
            assertTrue(c.dialogueManager.currentPendingState() is PendingDialogueState.AwaitingConfirmation)
            assertTrue(restorePrompt.actionChoices.any { it.submitText == "تأیید" })
            c.commandBrain.processDialogue("لغو")
            assertFalse(backupCallbackCalled)
            assertNull(c.dialogueManager.currentPendingState())
        } finally {
            c.confirmationManager.dismiss()
            c.dialogueManager.clearHistory()
        }
    }

    @Test
    fun `every registered skill has working success and failure or edge-case behavior`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val app = context.applicationContext as SolApplication
        val c = app.container

        fun cmd(
            action: SolAction,
            rawText: String = "",
            message: String? = null,
            contact: String? = null,
            phone: String? = null,
            relation: String? = null,
            roleName: String? = null,
            aliasKey: String? = null,
            aliasValue: String? = null,
            locationReference: String? = null,
            bookQuery: String? = null,
            searchQuery: String? = null,
            targetApp: String? = null,
            settingTarget: String? = null,
            volumeDelta: Int = 0,
            delayMinutes: Long = 5L,
            routineName: String? = null,
            backgroundJobId: String? = null,
            slots: Map<String, SlotValue> = emptyMap(),
        ) = ParsedCommand(
            action = action,
            rawText = rawText,
            normalizedText = rawText,
            message = message,
            contact = contact,
            phone = phone,
            relation = relation,
            roleName = roleName,
            aliasKey = aliasKey,
            aliasValue = aliasValue,
            locationReference = locationReference,
            bookQuery = bookQuery,
            searchQuery = searchQuery,
            targetApp = targetApp,
            settingTarget = settingTarget,
            volumeDelta = volumeDelta,
            delayMinutes = delayMinutes,
            routineName = routineName,
            backgroundJobId = backgroundJobId,
            slots = slots,
        )

        // Reset knowledge base for clean edge cases
        c.knowledgeRepository.deleteAllUserData()
        c.preferencesRepository.resetAllPreferences()

        // 1-4: GREETING, IDENTITY, SHOW_CAPABILITIES, HELP
        assertTrue(c.skillRegistry.execute(cmd(SolAction.GREETING, "سلام")).replyText.contains("Sol"))
        assertTrue(c.skillRegistry.execute(cmd(SolAction.IDENTITY, "اسمت چیه")).replyText.contains("Sol"))
        assertTrue(c.skillRegistry.execute(cmd(SolAction.SHOW_CAPABILITIES, "قابلیت")).pendingTask is PendingSolTask.OpenCapabilitiesCatalog)
        assertTrue(c.skillRegistry.execute(cmd(SolAction.HELP, "راهنما")).replyText.contains("نمونه"))

        // 5: LEARN_ALIAS success & failure (empty alias)
        val aliasOk = c.skillRegistry.execute(cmd(SolAction.LEARN_ALIAS, aliasKey = "دفتر", aliasValue = "شرکت آریا"))
        assertTrue(aliasOk.replyText.contains("آریا"))
        val aliasEmpty = c.skillRegistry.execute(cmd(SolAction.LEARN_ALIAS, aliasKey = "", aliasValue = ""))
        assertTrue(aliasEmpty.replyText.isNotBlank())

        // 6: LEARN_FACT / FORGET_MEMORY / LIST_MEMORY success & missing-target failure
        val factOk = c.skillRegistry.execute(cmd(SolAction.LEARN_FACT, message = "رمز کمد ۱۲۳۴ است"))
        assertTrue(factOk.replyText.contains("۱۲۳۴"))
        val ownerNameOk = c.skillRegistry.execute(cmd(SolAction.LEARN_FACT, aliasKey = "owner_name", aliasValue = "امیر"))
        assertTrue(ownerNameOk.replyText.contains("امیر"))
        val listOk = c.skillRegistry.execute(cmd(SolAction.LIST_MEMORY))
        assertTrue(listOk.replyText.contains("امیر") || listOk.replyText.contains("آریا"))
        val forgetMiss = c.skillRegistry.execute(cmd(SolAction.FORGET_MEMORY, aliasKey = "چیز_ناموجود_۹۹۹"))
        assertTrue(forgetMiss.replyText.contains("پیدا نکردم"))
        val forgetOk = c.skillRegistry.execute(cmd(SolAction.FORGET_MEMORY, aliasKey = "دفتر"))
        assertTrue(forgetOk.replyText.contains("حذف شد"))

        // 7: SAVE_PERSON success & edge case
        val personOk = c.skillRegistry.execute(cmd(SolAction.SAVE_PERSON, contact = "نرگس", phone = "09127778899", relation = "خواهر"))
        assertTrue(personOk.replyText.contains("نرگس"))
        val personEmpty = c.skillRegistry.execute(cmd(SolAction.SAVE_PERSON, contact = "", phone = "", relation = ""))
        assertTrue(personEmpty.replyText.isNotBlank())

        // 8: SAVE_LOCATION / SET_CURRENT_LOCATION / GET_CURRENT_LOCATION
        assertTrue(c.skillRegistry.execute(cmd(SolAction.GET_CURRENT_LOCATION)).replyText.contains("تأیید نکرده‌اید"))
        assertTrue(c.skillRegistry.execute(cmd(SolAction.SAVE_LOCATION, aliasKey = "باشگاه", aliasValue = "انقلاب")).replyText.contains("انقلاب"))
        assertTrue(c.skillRegistry.execute(cmd(SolAction.SET_CURRENT_LOCATION, locationReference = "باشگاه")).replyText.contains("انقلاب"))
        assertTrue(c.skillRegistry.execute(cmd(SolAction.GET_CURRENT_LOCATION)).replyText.contains("انقلاب"))

        // 9: TIME_DATE success & unrecognized date fallback
        assertTrue(c.skillRegistry.execute(cmd(SolAction.TIME_DATE, rawText = "۱۴۰۴/۰۱/۰۱ به میلادی چندم است؟")).replyText.contains("2025/03/21"))
        assertTrue(c.skillRegistry.execute(cmd(SolAction.TIME_DATE, rawText = "تاریخ امروز")).replyText.isNotBlank())

        // 10: SCHEDULE_REMINDER success & min-delay clamp
        assertTrue(c.skillRegistry.execute(cmd(SolAction.SCHEDULE_REMINDER, message = "تست", delayMinutes = 5)).replyText.contains("تنظیم شد"))
        assertTrue(c.skillRegistry.execute(cmd(SolAction.SCHEDULE_REMINDER, message = "", delayMinutes = 0)).replyText.contains("1 دقیقه"))

        // 11: BACKGROUND_CONTROL success & unknown fallback
        assertTrue(c.skillRegistry.execute(cmd(SolAction.BACKGROUND_CONTROL, backgroundJobId = "bg_memory_backup")).replyText.contains("پشتیبان‌گیری"))
        assertTrue(c.skillRegistry.execute(cmd(SolAction.BACKGROUND_CONTROL, backgroundJobId = "unknown_job")).replyText.contains("صف اجرا"))

        // 12: WEB_BOOK_LEARN_ROLE with query vs blank query
        val bookWithQ = c.skillRegistry.execute(cmd(SolAction.WEB_BOOK_LEARN_ROLE, bookQuery = "عادت‌های اتمی"))
        assertTrue(bookWithQ.pendingTask is PendingSolTask.InternetBookRoleFetch)
        val bookBlank = c.skillRegistry.execute(cmd(SolAction.WEB_BOOK_LEARN_ROLE, bookQuery = ""))
        assertTrue(bookBlank.replyText.contains("وارد کنید"))

        // 13: SEARCH_WEB with query vs blank query
        val searchWithQ = c.skillRegistry.execute(cmd(SolAction.SEARCH_WEB, searchQuery = "تقویم ۱۴۰۴"))
        assertTrue(searchWithQ.pendingTask is PendingSolTask.ConfirmWebSearch)
        val searchBlank = c.skillRegistry.execute(cmd(SolAction.SEARCH_WEB, searchQuery = ""))
        assertTrue(searchBlank.replyText.contains("کادر"))

        // 14: TEACH_ROLE_VOICE success & blank role fallback
        val roleBlank = c.skillRegistry.execute(cmd(SolAction.TEACH_ROLE_VOICE, roleName = "", message = "خلاصه بگو"))
        assertTrue(roleBlank.replyText.contains("دستیار تخصصی"))
        c.knowledgeRepository.saveBook(BookKnowledgeEntity(title = "کتاب آزمون", summaryText = "خلاصه کتاب آزمون", sourceType = "TEXT"))
        val roleOk = c.skillRegistry.execute(cmd(SolAction.TEACH_ROLE_VOICE, roleName = "مشاور", message = "با دقت پاسخ بده"))
        assertTrue(roleOk.replyText.contains("کتاب آزمون"))

        // 15: CONSULT_ROLE_BOOK success vs empty KB
        val consultOk = c.skillRegistry.execute(cmd(SolAction.CONSULT_ROLE_BOOK, rawText = "درباره کتاب آزمون بگو"))
        assertTrue(consultOk.replyText.contains("مشاور") || consultOk.replyText.contains("کتاب آزمون"))
        c.knowledgeRepository.deleteAllUserData()
        val consultEmpty = c.skillRegistry.execute(cmd(SolAction.CONSULT_ROLE_BOOK, rawText = "راهنمایی کن"))
        assertTrue(consultEmpty.replyText.contains("هنوز کتاب یا نقشی"))

        // 16-17: CALL / SEND_SMS / MULTI_STEP_NOTIFY missing contact vs valid contact
        assertNull(c.skillRegistry.execute(cmd(SolAction.CALL, contact = "")).pendingTask)
        assertTrue(c.skillRegistry.execute(cmd(SolAction.CALL, contact = "نرگس")).pendingTask is PendingSolTask.Communicate)
        assertNull(c.skillRegistry.execute(cmd(SolAction.SEND_SMS, contact = "")).pendingTask)
        assertTrue(c.skillRegistry.execute(cmd(SolAction.SEND_SMS, contact = "نرگس", message = "سلام")).pendingTask is PendingSolTask.Communicate)
        assertNull(c.skillRegistry.execute(cmd(SolAction.MULTI_STEP_NOTIFY, contact = "")).pendingTask)
        assertTrue(c.skillRegistry.execute(cmd(SolAction.MULTI_STEP_NOTIFY, contact = "نرگس", message = "رسیدم")).pendingTask is PendingSolTask.Communicate)

        // 18-19: DEVICE_STATUS & ADJUST_VOLUME
        assertTrue(c.skillRegistry.execute(cmd(SolAction.DEVICE_STATUS)).replyText.contains("باتری"))
        assertTrue(c.skillRegistry.execute(cmd(SolAction.ADJUST_VOLUME, volumeDelta = 1)).replyText.contains("افزایش"))
        assertTrue(c.skillRegistry.execute(cmd(SolAction.ADJUST_VOLUME, volumeDelta = -1)).replyText.contains("کاهش"))

        // 20-22: OPEN_SETTINGS / OPEN_APP / RUN_ROUTINE
        assertTrue(c.skillRegistry.execute(cmd(SolAction.OPEN_SETTINGS, settingTarget = "BATTERY")).replyText.contains("باتری"))
        assertTrue(c.skillRegistry.execute(cmd(SolAction.OPEN_APP, targetApp = "اپ_کاملا_ناموجود_۱۲۳")).replyText.contains("پیدا نشد"))
        assertTrue(c.skillRegistry.execute(cmd(SolAction.RUN_ROUTINE, routineName = "NIGHT")).replyText.contains("شب"))

        // 23: TIMER_STOPWATCH success + zero-duration failure
        assertTrue(c.skillRegistry.execute(cmd(SolAction.TIMER_STOPWATCH, slots = mapOf("timer_op" to SlotValue.Text("START"), "duration_seconds" to SlotValue.Number(0.0)))).replyText.contains("مدت تایمر"))
        assertTrue(c.skillRegistry.execute(cmd(SolAction.TIMER_STOPWATCH, slots = mapOf("timer_op" to SlotValue.Text("START"), "duration_seconds" to SlotValue.Number(120.0)))).replyText.contains("تنظیم شد"))
        assertTrue(c.skillRegistry.execute(cmd(SolAction.TIMER_STOPWATCH, slots = mapOf("timer_op" to SlotValue.Text("CANCEL")))).replyText.contains("لغو شد"))
        assertTrue(c.skillRegistry.execute(cmd(SolAction.TIMER_STOPWATCH, slots = mapOf("timer_op" to SlotValue.Text("CANCEL")))).replyText.contains("نبود"))

        // 24: FLASHLIGHT failure (no torch) and success (ShadowCameraManager registered torch camera)
        val noFlash = c.skillRegistry.execute(cmd(SolAction.FLASHLIGHT, slots = mapOf("torch_enabled" to SlotValue.Flag(true))))
        assertTrue(noFlash.replyText.contains("پیدا نشد") || noFlash.replyText.contains("چراغ"))
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val shadowCm = Shadows.shadowOf(cm)
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val shadowChars = Shadows.shadowOf(chars)
        shadowChars.set(CameraCharacteristics.FLASH_INFO_AVAILABLE, true)
        shadowChars.set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
        shadowCm.addCamera("0", chars)
        val flashOn = c.skillRegistry.execute(cmd(SolAction.FLASHLIGHT, slots = mapOf("torch_enabled" to SlotValue.Flag(true))))
        assertTrue(flashOn.replyText.contains("روشن") || flashOn.replyText.contains("چراغ"))

        // 25: CREATE_CALENDAR_EVENT
        assertTrue(c.skillRegistry.execute(cmd(SolAction.CREATE_CALENDAR_EVENT, rawText = "فردا ساعت ۱۰ جلسه", message = "جلسه")).replyText.contains("تقویم"))

        // 26: CALCULATOR success & failures
        assertTrue(c.skillRegistry.execute(cmd(SolAction.CALCULATOR, rawText = "۱۲ + ۸", message = "۱۲ + ۸")).replyText.contains("20"))
        assertTrue(c.skillRegistry.execute(cmd(SolAction.CALCULATOR, rawText = "۵ / ۰", message = "۵ / ۰")).replyText.isNotBlank())

        // 27: NOTES_TODO success & not-found failures
        assertTrue(c.skillRegistry.execute(cmd(SolAction.NOTES_TODO, message = "خرید نان", slots = mapOf("note_kind" to SlotValue.Text("SHOPPING"), "note_op" to SlotValue.Text("ADD")))).replyText.contains("ثبت شد"))
        assertTrue(c.skillRegistry.execute(cmd(SolAction.NOTES_TODO, message = "آیتم_ناموجود", slots = mapOf("note_kind" to SlotValue.Text("SHOPPING"), "note_op" to SlotValue.Text("DONE")))).replyText.contains("پیدا نشد"))
        assertTrue(c.skillRegistry.execute(cmd(SolAction.NOTES_TODO, message = "آیتم_ناموجود", slots = mapOf("note_kind" to SlotValue.Text("SHOPPING"), "note_op" to SlotValue.Text("DELETE")))).replyText.contains("پیدا نشد"))

        // 28: MEDIA_CONTROL
        assertTrue(c.skillRegistry.execute(cmd(SolAction.MEDIA_CONTROL, slots = mapOf("media_op" to SlotValue.Text("NEXT")))).replyText.contains("آهنگ بعدی"))

        // 29: UNKNOWN fallback when no SLM model exists
        val unk = c.skillRegistry.execute(cmd(SolAction.UNKNOWN, rawText = "یه جمله کاملا نامربوط که در حافظه نیست"))
        assertTrue(unk.replyText.contains(".litertlm"))
    }

    @Test
    fun `permission-missing paths return Persian guidance without crashing`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as SolApplication
        val c = app.container

        val permissionsToTest = listOf(
            Manifest.permission.RECORD_AUDIO to "میکروفون",
            Manifest.permission.READ_CONTACTS to "مخاطبین",
            Manifest.permission.CAMERA to "دوربین",
        )
        for ((perm, expectedLabel) in permissionsToTest) {
            val openedSettings = AtomicBoolean(false)
            val blockedSkill = object : SolSkill {
                override val id = "perm_guard_${perm.substringAfterLast('.')}"
                override val titleFa = "مهارت نیازمند مجوز"
                override val descriptionFa = "تست مجوز"
                override val intents = setOf(SolAction.SEARCH_WEB)
                override val sampleCommandsFa = listOf("تست")
                override val sensitivity = Sensitivity.NONE
                override val requiredPermissions = listOf(perm)
                override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult =
                    SkillResult("نباید اجرا شود", cmd)
            }
            val registry = SkillRegistry(
                context = context,
                preferencesRepository = c.preferencesRepository,
                skills = listOf(blockedSkill),
                openPermissionSettings = {
                    openedSettings.set(true)
                    true
                },
            )
            val res = registry.execute(ParsedCommand(SolAction.SEARCH_WEB, "تست", "تست"))
            assertTrue("Expected permission settings opened for $perm", openedSettings.get())
            assertTrue("Expected '$expectedLabel' in '${res.replyText}'", res.replyText.contains(expectedLabel))
        }

        // MicForegroundService fails safe cleanly without RECORD_AUDIO
        val micStart = MicForegroundService.start(context)
        assertTrue(micStart.isFailure)
        assertTrue(micStart.exceptionOrNull() is SecurityException)

        // SpeakerIdManager stays in InactiveFailSafe when ONNX model / owner profile are absent
        c.speakerIdManager.deleteAllSpeakerData()
        assertFalse(c.speakerIdManager.isActive)
        assertEquals(
            SpeakerVerificationOutcome.InactiveFailSafe,
            c.speakerIdManager.verifyVoicePcm(FloatArray(16_000)),
        )
    }

    @Test
    fun `all background WorkManager workers run deterministically via TestListenableWorkerBuilder`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as SolApplication
        val c = app.container
        c.preferencesRepository.setOwnerName("آرش")
        c.preferencesRepository.confirmCurrentLocation("دفتر مرکزی")
        c.memoryRepository.remember("fact:worker", "تست وورکر", kind = "fact")

        val db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java).allowMainThreadQueries().build()
        db.close()

        val daily = TestListenableWorkerBuilder<DailyRoutineWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), daily.doWork())

        val battery = TestListenableWorkerBuilder<BatteryMonitorWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), battery.doWork())

        val backup = TestListenableWorkerBuilder<MemoryBackupWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), backup.doWork())
        val backupJson = File(context.filesDir, "backups/sol_memory_backup.json")
        assertTrue("Expected local JSON backup file created by MemoryBackupWorker", backupJson.exists())

        val indexer = TestListenableWorkerBuilder<MemoryIndexerWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), indexer.doWork())

        val locCheckIn = TestListenableWorkerBuilder<LocationCheckInWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), locCheckIn.doWork())

        val histMaint = TestListenableWorkerBuilder<HistoryMaintenanceWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), histMaint.doWork())

        // BookFetchWorker fails cleanly when book_query input is missing
        val missingInputBookWorker = TestListenableWorkerBuilder<BookFetchWorker>(context)
            .setInputData(workDataOf())
            .build()
        assertEquals(ListenableWorker.Result.failure(), missingInputBookWorker.doWork())
    }
}
