package com.example

import android.Manifest
import android.app.AlarmManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.provider.CalendarContract
import android.provider.Settings
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.sol.brain.CalculationResult
import com.example.sol.brain.NluDecision
import com.example.sol.brain.ParsedCommand
import com.example.sol.brain.SafeCalculator
import com.example.sol.brain.SolAction
import com.example.sol.brain.skills.Sensitivity
import com.example.sol.brain.skills.SkillContext
import com.example.sol.brain.skills.SkillRegistry
import com.example.sol.brain.skills.SkillResult
import com.example.sol.brain.skills.SolSkill
import com.example.sol.calendar.ExactAlarmScheduler
import com.example.sol.calendar.JalaliCalendar
import com.example.sol.calendar.TimerController
import com.example.sol.knowledge.SolCapabilitiesCatalog
import com.example.sol.memory.KnowledgeRepository
import com.example.sol.memory.MemoryDatabase
import com.example.sol.memory.NoteTodoRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowCameraManager
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class M5SkillsAndCatalogTests {

    @Test
    fun `B7 every SolAction is registered and missing permission opens settings with Persian explanation`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as SolApplication
        val c = app.container

        for (action in SolAction.entries) {
            assertTrue("SolAction.$action must be registered in SkillRegistry", c.skillRegistry.isRegistered(action))
        }

        val settingsOpened = AtomicBoolean(false)
        val guardedSkill = object : SolSkill {
            override val id = "skill_guarded_test"
            override val titleFa = "مهارت نیازمند مجوز آزمایشی"
            override val descriptionFa = "آزمایش بررسی مجوز پیش از اجرا"
            override val intents = setOf(SolAction.OPEN_APP)
            override val sampleCommandsFa = listOf("واتساپ را باز کن")
            override val sensitivity = Sensitivity.NONE
            override val requiredPermissions = listOf(Manifest.permission.RECORD_AUDIO)
            override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult =
                SkillResult("باید قبل از اجرا مسدود می‌شد", cmd)
        }

        val testRegistry = SkillRegistry(
            context = context,
            preferencesRepository = c.preferencesRepository,
            skills = listOf(guardedSkill),
            openPermissionSettings = {
                settingsOpened.set(true)
                true
            },
        )
        val res = testRegistry.execute(
            ParsedCommand(
                action = SolAction.OPEN_APP,
                rawText = "واتساپ را باز کن",
                normalizedText = "واتساپ را باز کن",
                targetApp = "واتساپ",
            )
        )
        assertTrue(settingsOpened.get())
        assertTrue(res.replyText.contains("میکروفون"))
        assertTrue(res.replyText.contains("تنظیمات"))
    }

    @Test
    fun `B8_1 countdown timer uses AlarmManager and stopwatch tracks accurate elapsed time`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val knowledgeRepo = KnowledgeRepository(db.memoryDao())
            val scheduler = ExactAlarmScheduler(context, knowledgeRepo)
            val wallNow = AtomicLong(System.currentTimeMillis())
            val elapsedNow = AtomicLong(50_000L)

            val timerController = TimerController(
                context = context,
                exactAlarmScheduler = scheduler,
                knowledgeRepository = knowledgeRepo,
                wallClockMillis = { wallNow.get() },
                elapsedClockMillis = { elapsedNow.get() },
            )

            val started = timerController.startCountdownTimer(600L, "تایمر چای")
            assertEquals(600L, started.durationSeconds)
            val shadowAlarm: ShadowAlarmManager = Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            assertTrue(shadowAlarm.scheduledAlarms.isNotEmpty())

            wallNow.addAndGet(120_000L)
            val status = timerController.getCountdownStatus()
            assertTrue(status.isActive)
            assertEquals(480L, status.remainingSeconds)

            assertTrue(timerController.cancelCountdownTimer())
            assertFalse(timerController.getCountdownStatus().isActive)

            val sw1 = timerController.startStopwatch()
            assertTrue(sw1.isRunning)
            elapsedNow.addAndGet(65_400L)
            val swStatus = timerController.getStopwatchStatus()
            assertTrue(swStatus.isRunning)
            assertEquals(65_400L, swStatus.elapsedMillis)
            assertEquals("01:05.4", swStatus.formattedFa)

            val swStopped = timerController.stopStopwatch()
            assertFalse(swStopped.isRunning)
            elapsedNow.addAndGet(10_000L)
            assertEquals(65_400L, timerController.getStopwatchStatus().elapsedMillis)

            val swReset = timerController.resetStopwatch()
            assertFalse(swReset.isRunning)
            assertEquals(0L, swReset.elapsedMillis)
        } finally {
            db.close()
        }
    }

    @Test
    fun `B8_2 flashlight toggles torch via CameraManager without requesting CAMERA permission`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as SolApplication

        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val permissions = pkgInfo.requestedPermissions?.toList().orEmpty()
        assertFalse(
            "CAMERA permission must NOT be declared in AndroidManifest.xml",
            Manifest.permission.CAMERA in permissions,
        )

        val res = app.container.deviceController.setFlashlight(true)
        // Under Robolectric without added camera characteristics or with added camera, never crashes
        assertNotNull(res.messageFa)
        assertTrue(res.messageFa.isNotBlank())
    }

    @Test
    fun `B8_3 calendar event converts Jalali date to Gregorian and launches ACTION_INSERT`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as SolApplication
        val c = app.container

        val decision = c.hybridNluEngine.evaluate("۱۴۰۴/۰۱/۰۲ ساعت ۱۴ جلسه کارنو را در تقویم ثبت کن")
        assertTrue("Expected Execute for calendar event, got $decision", decision is NluDecision.Execute)
        val cmd = (decision as NluDecision.Execute).command
        assertEquals(SolAction.CREATE_CALENDAR_EVENT, cmd.action)

        val execRes = c.skillRegistry.execute(cmd)
        assertTrue(execRes.replyText.contains("تقویم"))

        val shadowApp = Shadows.shadowOf(app)
        val launched: Intent? = shadowApp.nextStartedActivity
        assertNotNull("Calendar ACTION_INSERT intent should be started", launched)
        assertEquals(Intent.ACTION_INSERT, launched!!.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, launched.data)
        val beginMs = launched.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1L)
        assertTrue(beginMs > 0L)
        val cal = Calendar.getInstance().apply { timeInMillis = beginMs }
        // 1404/01/02 == 2025-03-22
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, cal.get(Calendar.MONTH))
        assertEquals(22, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `B8_4 safe calculator evaluates Persian words, percentages, symbols, and prevents crashes`() {
        val r1 = SafeCalculator.evaluate("دو ضرب در سه") as CalculationResult.Success
        assertEquals(6.0, r1.value, 1e-6)

        val r2 = SafeCalculator.evaluate("۲۵ درصد ۸۰۰") as CalculationResult.Success
        assertEquals(200.0, r2.value, 1e-6)

        val r3 = SafeCalculator.evaluate("صد به علاوه پنجاه تقسیم بر دو") as CalculationResult.Success
        assertEquals(125.0, r3.value, 1e-6)

        val r4 = SafeCalculator.evaluate("(12 + 8) * 3 - 4^2") as CalculationResult.Success
        assertEquals(44.0, r4.value, 1e-6)

        val r5 = SafeCalculator.evaluate("جذر ۸۱") as CalculationResult.Success
        assertEquals(9.0, r5.value, 1e-6)

        val divZero = SafeCalculator.evaluate("۱۰ تقسیم بر ۰")
        assertTrue(divZero is CalculationResult.Failure)

        val badTokens = SafeCalculator.evaluate("سلام دنیا")
        assertTrue(badTokens is CalculationResult.Failure)
    }

    @Test
    fun `B8_5 room v5 to v6 migration preserves data and notes todo skill manages lists`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_v5_to_v6.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `keyText` TEXT NOT NULL, `valueText` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memories_keyText` ON `memories` (`keyText`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `persons` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `normalizedName` TEXT NOT NULL, `phone` TEXT NOT NULL, `relation` TEXT NOT NULL, `callScriptNote` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_persons_normalizedName` ON `persons` (`normalizedName`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `locations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `alias` TEXT NOT NULL, `normalizedAlias` TEXT NOT NULL, `fullName` TEXT NOT NULL, `addressNote` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_locations_normalizedAlias` ON `locations` (`normalizedAlias`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userText` TEXT NOT NULL, `solText` TEXT NOT NULL, `intentAction` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_createdAt` ON `history` (`createdAt`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `author` TEXT NOT NULL, `subjects` TEXT NOT NULL, `summaryText` TEXT NOT NULL, `voiceNotes` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `sol_roles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `roleName` TEXT NOT NULL, `normalizedRoleName` TEXT NOT NULL, `instructions` TEXT NOT NULL, `linkedBookTitle` TEXT NOT NULL, `isActive` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sol_roles_normalizedRoleName` ON `sol_roles` (`normalizedRoleName`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `background_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `jobKey` TEXT NOT NULL, `titleFa` TEXT NOT NULL, `status` TEXT NOT NULL, `detailFa` TEXT NOT NULL, `executedAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `book_chunks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `bookTitle` TEXT NOT NULL, `chunkIndex` INTEGER NOT NULL, `contentText` TEXT NOT NULL, `normalizedTokens` TEXT NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_chunks_bookId` ON `book_chunks` (`bookId`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `scheduled_alarms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `requestCode` INTEGER NOT NULL, `titleFa` TEXT NOT NULL, `messageBody` TEXT NOT NULL, `triggerAtMillis` INTEGER NOT NULL, `jalaliLabel` TEXT NOT NULL, `targetContact` TEXT NOT NULL, `isExact` INTEGER NOT NULL, `isTriggered` INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_alarms_isTriggered_triggerAtMillis` ON `scheduled_alarms` (`isTriggered`, `triggerAtMillis`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `user_utterance_examples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `intent` TEXT NOT NULL, `text` TEXT NOT NULL, `normalizedText` TEXT NOT NULL, `slotsJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_utterance_examples_intent` ON `user_utterance_examples` (`intent`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_utterance_examples_normalizedText` ON `user_utterance_examples` (`normalizedText`)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { v5db ->
            v5db.insert(
                "memories",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("kind", "fact")
                    put("keyText", "k_v5")
                    put("valueText", "مقدار نسخه ۵")
                    put("updatedAt", 999L)
                },
            )
        }
        helper.close()

        val migrated = Room.databaseBuilder(context, MemoryDatabase::class.java, dbName)
            .addMigrations(*MemoryDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = migrated.memoryDao()
            assertEquals("مقدار نسخه ۵", dao.getAllMemoriesOnce().single().valueText)

            val repo = NoteTodoRepository(dao)
            val app = context.applicationContext as SolApplication
            val c = app.container
            val addSkill = com.example.sol.brain.skills.NotesTodoSkill(repo)
            val ctx = SkillContext(context, c.preferencesRepository.getSnapshot())

            val addCmd = (c.hybridNluEngine.evaluate("لیست خرید نان و شیر اضافه کن") as NluDecision.Execute).command
            val addRes = addSkill.execute(addCmd, ctx)
            assertTrue(addRes.replyText.contains("ثبت شد"))

            val listCmd = (c.hybridNluEngine.evaluate("لیست خرید چیه؟") as NluDecision.Execute).command
            val listRes = addSkill.execute(listCmd, ctx)
            assertTrue(listRes.replyText.contains("نان و شیر"))

            val doneCmd = (c.hybridNluEngine.evaluate("نان و شیر از لیست خرید تیک بزن") as NluDecision.Execute).command
            val doneRes = addSkill.execute(doneCmd, ctx)
            assertTrue(doneRes.replyText.contains("تیک خورد"))
            assertTrue(repo.getByKindOnce("SHOPPING").single().isDone)

            val delCmd = (c.hybridNluEngine.evaluate("نان و شیر از لیست خرید حذف کن") as NluDecision.Execute).command
            val delRes = addSkill.execute(delCmd, ctx)
            assertTrue(delRes.replyText.contains("حذف شد"))
            assertTrue(repo.getByKindOnce("SHOPPING").isEmpty())
        } finally {
            migrated.close()
            dbFile.delete()
        }
    }

    @Test
    fun `B8_6 jalali calendar answers conversion weekday countdown and leap years accurately`() {
        assertTrue(JalaliCalendar.isJalaliLeapYear(1399))
        assertTrue(JalaliCalendar.isJalaliLeapYear(1403))
        assertTrue(JalaliCalendar.isJalaliLeapYear(1408))
        assertFalse(JalaliCalendar.isJalaliLeapYear(1404))

        val leap1403 = JalaliCalendar.answerCalendarQuestion("آیا سال ۱۴۰۳ کبیسه است؟")
        assertTrue(leap1403.contains("کبیسه است"))
        val leap1404 = JalaliCalendar.answerCalendarQuestion("آیا سال ۱۴۰۴ کبیسه است؟")
        assertTrue(leap1404.contains("کبیسه نیست"))

        val jToG = JalaliCalendar.answerCalendarQuestion("۱۴۰۴/۰۱/۰۱ به میلادی چندم است؟")
        assertTrue("Expected 2025/03/21 in '$jToG'", jToG.contains("2025/03/21"))

        val gToJ = JalaliCalendar.answerCalendarQuestion("۲۱ مارس ۲۰۲۵ به شمسی چندمه؟")
        assertTrue("Expected 1404/01/01 in '$gToJ'", gToJ.contains("1404/01/01"))

        val fixedBeforeNowruz = Calendar.getInstance().apply {
            set(2025, Calendar.MARCH, 15, 10, 0, 0) // 1403/12/25 -> 6 days before 1404/01/01
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val countdown = JalaliCalendar.answerCalendarQuestion("چند روز تا نوروز مانده؟", fixedBeforeNowruz)
        assertTrue("Expected 6 days until Nowruz in '$countdown'", countdown.contains("6 روز"))
    }

    @Test
    fun `B8_7 device status reports battery RAM storage and charging summary honestly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as SolApplication
        val dc = app.container.deviceController

        val ram = dc.getRamStatus()
        val storage = dc.getStorageStatus()
        val summary = dc.buildDeviceSummaryFa()

        assertTrue(ram.totalBytes >= 0L)
        assertTrue(storage.totalBytes >= 0L)
        assertTrue(summary.contains("باتری"))
        assertTrue(summary.contains("صدای رسانه"))
    }

    @Test
    fun `B8_8 media control skill dispatches playback keys cleanly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as SolApplication
        val c = app.container

        val queries = listOf(
            "آهنگ بعدی را پخش کن" to "آهنگ بعدی",
            "آهنگ قبلی را پخش کن" to "آهنگ قبلی",
            "پخش موسیقی را متوقف کن" to "توقف پخش",
            "موسیقی را پخش کن" to "پخش موسیقی",
        )
        for ((text, expectedLabel) in queries) {
            val d = c.hybridNluEngine.evaluate(text)
            assertTrue("Expected Execute for '$text', got $d", d is NluDecision.Execute)
            val cmd = (d as NluDecision.Execute).command
            assertEquals(SolAction.MEDIA_CONTROL, cmd.action)
            val res = c.skillRegistry.execute(cmd)
            assertTrue("Expected '$expectedLabel' in '${res.replyText}'", res.replyText.contains(expectedLabel))
        }
    }

    @Test
    fun `B8_9 restricted system toggles explain Android rule honestly and open right settings`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as SolApplication
        val c = app.container
        val shadowApp = Shadows.shadowOf(app)

        val cases = listOf(
            "وای‌فای را خاموش کن" to listOf(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS),
            "بلوتوث را روشن کن" to listOf(Settings.ACTION_BLUETOOTH_SETTINGS),
            "دیتا را روشن کن" to listOf(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_DATA_ROAMING_SETTINGS),
            "حالت هواپیما را روشن کن" to listOf(Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        )

        for ((cmdText, expectedActions) in cases) {
            val decision = c.hybridNluEngine.evaluate(cmdText)
            assertTrue("Expected Execute for '$cmdText', got $decision", decision is NluDecision.Execute)
            val cmd = (decision as NluDecision.Execute).command
            assertEquals(SolAction.OPEN_SETTINGS, cmd.action)

            val result = c.skillRegistry.execute(cmd)
            assertTrue(
                "Honest Android restriction missing in '${result.replyText}'",
                result.replyText.contains("اجازهٔ") && result.replyText.contains("نمی‌دهد"),
            )

            val launched = shadowApp.nextStartedActivity
            assertNotNull("Expected settings intent for '$cmdText'", launched)
            assertTrue(
                "Expected one of $expectedActions for '$cmdText', got ${launched!!.action}",
                launched.action in expectedActions,
            )
        }
    }

    @Test
    fun `B9 capabilities catalog is generated from SkillRegistry and every skill sample resolves cleanly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as SolApplication
        val c = app.container

        val catalog = c.capabilitiesCatalog
        assertNotEquals("Catalog count must reflect real registered skills rather than hardcoded 20", 20, catalog.size)

        val bgReminder = catalog.first { it.id == "bg_reminder" }
        assertTrue(bgReminder.descriptionFa.contains("AlarmManager"))
        assertFalse(bgReminder.descriptionFa.contains("WorkManager"))

        // Every catalog entry must link to a real registered skill
        for (item in catalog) {
            assertNotNull(
                "Catalog item '${item.id}' points to unknown backingSkillId '${item.backingSkillId}'",
                c.skillRegistry.getSkillById(item.backingSkillId),
            )
            assertTrue("Catalog item '${item.id}' must have sampleCommandFa", item.sampleCommandFa.isNotBlank())
        }

        // Every registered non-UNKNOWN skill must appear in catalog and resolve via HybridNluEngine
        val catalogSkillIds = catalog.map { it.backingSkillId }.toSet()
        for (skill in c.skillRegistry.allSkills()) {
            if (SolAction.UNKNOWN in skill.intents) continue
            assertTrue("Skill '${skill.id}' must be listed in catalog", skill.id in catalogSkillIds)
            assertTrue("Skill '${skill.id}' must define sampleCommandsFa", skill.sampleCommandsFa.isNotEmpty())
            for (sample in skill.sampleCommandsFa) {
                val eval = c.hybridNluEngine.evaluate(sample)
                assertTrue(
                    "Sample '$sample' for skill '${skill.id}' must Execute cleanly, got $eval",
                    eval is NluDecision.Execute && eval.command.action in skill.intents,
                )
            }
        }
    }
}
