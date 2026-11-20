package com.example

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.sol.background.SolBackgroundService
import com.example.sol.background.SolNotificationHelper
import com.example.sol.calendar.BootReceiver
import com.example.sol.calendar.ExactAlarmPermissionReceiver
import com.example.sol.calendar.ExactReminderReceiver
import com.example.sol.memory.BookChunkEntity
import com.example.sol.memory.BookKnowledgeEntity
import com.example.sol.memory.MemoryDatabase
import com.example.sol.memory.MemoryFactEntity
import com.example.sol.memory.ScheduledAlarmEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class M1ReliabilityTests {

    @Test
    fun `room migration from v2 to v5 preserves user data and creates required indices`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_v2_to_v5.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        // Build real SQLite database at schema version 2
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `memories` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `kind` TEXT NOT NULL,
                                `keyText` TEXT NOT NULL,
                                `valueText` TEXT NOT NULL,
                                `updatedAt` INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memories_keyText` ON `memories` (`keyText`)")
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `persons` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `name` TEXT NOT NULL,
                                `normalizedName` TEXT NOT NULL,
                                `phone` TEXT NOT NULL,
                                `relation` TEXT NOT NULL,
                                `callScriptNote` TEXT NOT NULL,
                                `updatedAt` INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_persons_normalizedName` ON `persons` (`normalizedName`)")
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `locations` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `alias` TEXT NOT NULL,
                                `normalizedAlias` TEXT NOT NULL,
                                `fullName` TEXT NOT NULL,
                                `addressNote` TEXT NOT NULL,
                                `updatedAt` INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_locations_normalizedAlias` ON `locations` (`normalizedAlias`)")
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `history` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `userText` TEXT NOT NULL,
                                `solText` TEXT NOT NULL,
                                `intentAction` TEXT NOT NULL,
                                `status` TEXT NOT NULL,
                                `createdAt` INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `books` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `title` TEXT NOT NULL,
                                `author` TEXT NOT NULL,
                                `subjects` TEXT NOT NULL,
                                `summaryText` TEXT NOT NULL,
                                `voiceNotes` TEXT NOT NULL,
                                `sourceType` TEXT NOT NULL,
                                `sourceUrl` TEXT NOT NULL,
                                `createdAt` INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `sol_roles` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `roleName` TEXT NOT NULL,
                                `normalizedRoleName` TEXT NOT NULL,
                                `instructions` TEXT NOT NULL,
                                `linkedBookTitle` TEXT NOT NULL,
                                `isActive` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sol_roles_normalizedRoleName` ON `sol_roles` (`normalizedRoleName`)")
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `background_logs` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `jobKey` TEXT NOT NULL,
                                `titleFa` TEXT NOT NULL,
                                `status` TEXT NOT NULL,
                                `detailFa` TEXT NOT NULL,
                                `executedAt` INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { v2db ->
            val memValues = ContentValues().apply {
                put("kind", "alias")
                put("keyText", "دفتر")
                put("valueText", "شرکت کارنو")
                put("updatedAt", 123456L)
            }
            v2db.insert("memories", SQLiteDatabase.CONFLICT_ABORT, memValues)
            val histValues = ContentValues().apply {
                put("userText", "سلام")
                put("solText", "سلام!")
                put("intentAction", "GREETING")
                put("status", "COMPLETED")
                put("createdAt", 123456L)
            }
            v2db.insert("history", SQLiteDatabase.CONFLICT_ABORT, histValues)
        }
        helper.close()

        // Open through Room with ALL_MIGRATIONS and NO destructive fallback
        val migratedDb = Room.databaseBuilder(context, MemoryDatabase::class.java, dbName)
            .addMigrations(*MemoryDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = migratedDb.memoryDao()
            val memories = dao.getAllMemoriesOnce()
            assertEquals(1, memories.size)
            assertEquals("شرکت کارنو", memories.first().valueText)

            val history = dao.observeHistory().first()
            assertEquals(1, history.size)
            assertEquals("سلام!", history.first().solText)

            // Verify new indices across v3..v5 in SQLite master
            val sqliteDb = migratedDb.openHelper.writableDatabase
            val indexNames = mutableSetOf<String>()
            sqliteDb.query("SELECT name FROM sqlite_master WHERE type='index'").use { cursor ->
                while (cursor.moveToNext()) {
                    indexNames.add(cursor.getString(0))
                }
            }
            assertTrue(indexNames.contains("index_history_createdAt"))
            assertTrue(indexNames.contains("index_scheduled_alarms_isTriggered_triggerAtMillis"))
            assertTrue(indexNames.contains("index_user_utterance_examples_intent"))
            assertTrue(indexNames.contains("index_user_utterance_examples_normalizedText"))
            assertEquals(0, dao.getAllUserUtteranceExamplesOnce().size)
        } finally {
            migratedDb.close()
            dbFile.delete()
        }
    }

    @Test
    fun `room transactions and bounded queries behave deterministically`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.memoryDao()
            repeat(15) { idx ->
                dao.upsertMemory(MemoryFactEntity(kind = "fact", keyText = "k_$idx", valueText = "v_$idx"))
            }
            assertEquals(5, dao.observeAllMemories(limit = 5).first().size)
            assertEquals(15, dao.getAllMemoriesOnce().size)

            val bookId = dao.upsertBook(
                BookKnowledgeEntity(title = "کتاب آزمایشی", summaryText = "خلاصه", sourceType = "TEXT")
            )
            dao.replaceChunksForBook(
                bookId,
                listOf(
                    BookChunkEntity(bookId = bookId, bookTitle = "کتاب آزمایشی", chunkIndex = 0, contentText = "متن اول", normalizedTokens = "متن اول"),
                    BookChunkEntity(bookId = bookId, bookTitle = "کتاب آزمایشی", chunkIndex = 1, contentText = "متن دوم", normalizedTokens = "متن دوم"),
                ),
            )
            assertEquals(2, dao.getChunksForBookOnce(bookId).size)

            dao.deleteBookCascade(bookId)
            assertTrue(dao.getAllBooksOnce().isEmpty())
            assertTrue(dao.getChunksForBookOnce(bookId).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `same-timestamp alarms get collision-free requestCodes and cancel independently`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as SolApplication
        val scheduler = app.container.exactAlarmScheduler
        val repo = app.container.knowledgeRepository
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarm = Shadows.shadowOf(alarmManager)

        val futureTime = System.currentTimeMillis() + 600_000L
        val first = scheduler.scheduleJalaliAlarm(futureTime, "اول", "پیام اول")
        val second = scheduler.scheduleJalaliAlarm(futureTime, "دوم", "پیام دوم")

        assertNotEquals(first.alarmId, second.alarmId)
        val alarmsInDb = repo.getAllAlarmsOnce().filter { it.id == first.alarmId || it.id == second.alarmId }
        assertEquals(2, alarmsInDb.size)
        assertNotEquals(alarmsInDb[0].requestCode, alarmsInDb[1].requestCode)
        assertTrue(alarmsInDb.all { it.requestCode >= 10_000 })

        // Verify BackgroundTaskCoordinator routes relative reminders through ExactAlarmScheduler
        val outcome = app.container.backgroundTaskCoordinator.scheduleReminder("دارو بخورم", 5)
        assertTrue(outcome.alarmId > 0L)
        assertTrue(shadowAlarm.scheduledAlarms.size >= 3)

        // Cancel first and ensure second stays intact
        val firstEntity = alarmsInDb.first { it.id == first.alarmId }
        scheduler.cancelAlarm(firstEntity)
        val remainingIds = repo.getPendingAlarmsOnce().map { it.id }.toSet()
        assertFalse(remainingIds.contains(first.alarmId))
        assertTrue(remainingIds.contains(second.alarmId))
    }

    @Test
    fun `reschedulePendingAlarms restores future alarms and delivers single missed notification`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as SolApplication
        val repo = app.container.knowledgeRepository
        val scheduler = app.container.exactAlarmScheduler
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNm = Shadows.shadowOf(nm)

        // Clear any leftover alarms
        repo.getAllAlarmsOnce().forEach { repo.deleteScheduledAlarm(it.id) }
        nm.cancelAll()

        val now = System.currentTimeMillis()
        val futureAlarm = repo.insertAlarmWithUniqueRequestCode(
            ScheduledAlarmEntity(
                requestCode = 0,
                titleFa = "یادآور آینده",
                messageBody = "تماس با مهندس",
                triggerAtMillis = now + 300_000L,
                jalaliLabel = "۱۴۰۴/۰۱/۰۵ - ۱۰:۳۰",
                isExact = true,
                isTriggered = false,
            )
        )
        val missedAlarm = repo.insertAlarmWithUniqueRequestCode(
            ScheduledAlarmEntity(
                requestCode = 0,
                titleFa = "یادآور گذشته",
                messageBody = "مصرف دارو",
                triggerAtMillis = now - 120_000L,
                jalaliLabel = "۱۴۰۴/۰۱/۰۵ - ۰۹:۰۰",
                isExact = true,
                isTriggered = false,
            )
        )

        val summary = scheduler.reschedulePendingAlarms(
            reason = "راه‌اندازی مجدد گوشی",
            fireMissedAsNotification = true,
            nowMillis = now,
        )
        assertEquals(1, summary.rescheduledCount)
        assertEquals(1, summary.missedDeliveredCount)

        val missedNotifications = shadowNm.allNotifications.filter {
            Shadows.shadowOf(it).contentTitle.toString().contains("یادآور جاافتاده")
        }
        assertEquals(1, missedNotifications.size)

        val afterDb = repo.getAllAlarmsOnce().associateBy { it.id }
        assertFalse(afterDb.getValue(futureAlarm.id).isTriggered)
        assertTrue(afterDb.getValue(missedAlarm.id).isTriggered)

        // Running again must NOT duplicate missed notifications
        val secondRun = scheduler.reschedulePendingAlarms(
            reason = "راه‌اندازی مجدد گوشی",
            fireMissedAsNotification = true,
            nowMillis = now,
        )
        assertEquals(1, secondRun.rescheduledCount)
        assertEquals(0, secondRun.missedDeliveredCount)

        // Verify permission state change updates ExactAlarmScheduler live state cleanly
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        ExactAlarmPermissionReceiver().onReceive(
            context,
            Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED),
        )
        delay(200)
        assertFalse(scheduler.isExactModeActive.value)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler.refreshExactAlarmState()
        assertTrue(scheduler.isExactModeActive.value)
    }

    @Test
    fun `exactReminderReceiver and bootReceiver handle broadcasts safely without leaking`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as SolApplication
        val repo = app.container.knowledgeRepository

        val alarm = repo.insertAlarmWithUniqueRequestCode(
            ScheduledAlarmEntity(
                requestCode = 0,
                titleFa = "جلسه",
                messageBody = "شروع جلسه هفتگی",
                triggerAtMillis = System.currentTimeMillis() + 60_000L,
                jalaliLabel = "۱۴۰۴/۰۱/۰۵",
                isExact = true,
                isTriggered = false,
            )
        )

        val fireIntent = Intent(context, ExactReminderReceiver::class.java).apply {
            putExtra(ExactReminderReceiver.EXTRA_ALARM_ID, alarm.id)
            putExtra(ExactReminderReceiver.EXTRA_REQUEST_CODE, alarm.requestCode)
            putExtra(ExactReminderReceiver.EXTRA_TITLE, alarm.titleFa)
            putExtra(ExactReminderReceiver.EXTRA_MESSAGE, alarm.messageBody)
        }
        ExactReminderReceiver().onReceive(context, fireIntent)
        delay(200)

        val updated = repo.getAllAlarmsOnce().first { it.id == alarm.id }
        assertTrue(updated.isTriggered)

        // BootReceiver handles BOOT_COMPLETED safely without exception
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        delay(100)
    }

    @Test
    fun `solBackgroundService rejects empty or fake actions and handles onTimeout cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(SolBackgroundService.startVoiceReader(context, "تست", ""))

        // Unrecognized/legacy action stops cleanly without posting a fake dataSync foreground notification
        val unknownController = Robolectric.buildService(
            SolBackgroundService::class.java,
            Intent(context, SolBackgroundService::class.java).setAction("com.example.sol.UNKNOWN"),
        ).create().startCommand(0, 1)
        val unknownShadow = Shadows.shadowOf(unknownController.get())
        assertTrue(unknownShadow.isStoppedBySelf)
        assertFalse(unknownShadow.isForegroundStopped)
        unknownController.destroy()

        // Valid ACTION_SPEAK_TEXT promotes to foreground and cleans up cleanly on onTimeout
        val speakController = Robolectric.buildService(
            SolBackgroundService::class.java,
            Intent(context, SolBackgroundService::class.java).apply {
                action = SolBackgroundService.ACTION_SPEAK_TEXT
                putExtra(SolBackgroundService.EXTRA_TITLE, "روخوانی Sol")
                putExtra(SolBackgroundService.EXTRA_TEXT, "سلام این یک متن آزمایشی است.")
            },
        ).create().startCommand(0, 2)
        val speakService = speakController.get()
        val speakShadow = Shadows.shadowOf(speakService)
        assertTrue(speakShadow.lastForegroundNotification != null)

        speakService.onTimeout(2, 0)
        assertTrue(speakShadow.isForegroundStopped)
        assertTrue(speakShadow.isStoppedBySelf)
        speakController.destroy()
    }
}
