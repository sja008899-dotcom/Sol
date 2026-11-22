package com.example.sol.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.SolApplication
import com.example.sol.calendar.JalaliCalendar
import com.example.sol.knowledge.BookWebFetcher
import com.example.sol.memory.BookKnowledgeEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DailyRoutineWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? SolApplication ?: return Result.failure()
        val prefs = app.container.preferencesRepository.getSnapshot()
        val bat = app.container.deviceController.getBatteryStatus()
        val loc = prefs.confirmedCurrentLocation.ifBlank { "ثبت‌نشده" }
        val today = JalaliCalendar.fromMillis().formatFullFa()
        val summary = "$today • باتری ${bat.levelPercent}% • موقعیت تأییدشده: $loc"
        SolNotificationHelper.postNotification(
            context = applicationContext,
            channelId = SolNotificationHelper.CHANNEL_REMINDERS,
            notificationId = 201,
            title = "گزارش روتین روزانه Sol",
            body = summary,
        )
        logToDb("bg_daily_briefing", "روتین روزانه پس‌زمینه", "COMPLETED", summary)
        return Result.success()
    }
}

class BatteryMonitorWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? SolApplication ?: return Result.failure()
        val bat = app.container.deviceController.getBatteryStatus()
        val detail = "سطح باتری: ${bat.levelPercent}% (${bat.chargingSourceLabel}) • دما: ${bat.temperatureCelsius}°C"
        if (bat.levelPercent <= 20 && !bat.isCharging) {
            SolNotificationHelper.postNotification(
                context = applicationContext,
                channelId = SolNotificationHelper.CHANNEL_REMINDERS,
                notificationId = 202,
                title = "هشدار باتری Sol",
                body = "شارژ گوشی به ${bat.levelPercent}% رسیده است.",
            )
        }
        logToDb("bg_battery_guard", "پایش هوشمند باتری", "COMPLETED", detail)
        return Result.success()
    }
}

class MemoryBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? SolApplication ?: return Result.failure()
        val c = app.container
        val memories = c.memoryRepository.getAllOnce()
        val books = c.knowledgeRepository.getAllBooksOnce()
        val roles = c.knowledgeRepository.getAllRolesOnce()

        val root = JSONObject().apply {
            put("exportedAt", System.currentTimeMillis())
            put("memoriesCount", memories.size)
            put("booksCount", books.size)
            put("rolesCount", roles.size)
            put("memories", JSONArray().apply {
                memories.forEach { m ->
                    put(JSONObject().apply {
                        put("kind", m.kind)
                        put("key", m.keyText)
                        put("value", m.valueText)
                    })
                }
            })
            put("roles", JSONArray().apply {
                roles.forEach { r ->
                    put(JSONObject().apply {
                        put("roleName", r.roleName)
                        put("instructions", r.instructions)
                        put("linkedBook", r.linkedBookTitle)
                    })
                }
            })
        }

        val dir = File(applicationContext.filesDir, "backups").apply { mkdirs() }
        val file = File(dir, "sol_memory_backup.json")
        file.writeText(root.toString(2), Charsets.UTF_8)

        val detail = "نسخه پشتیبان محلی با ${memories.size} خاطره، ${books.size} کتاب و ${roles.size} نقش ذخیره شد."
        SolNotificationHelper.postNotification(
            context = applicationContext,
            channelId = SolNotificationHelper.CHANNEL_BACKGROUND,
            notificationId = 203,
            title = "پشتیبان‌گیری خودکار حافظه",
            body = detail,
        )
        logToDb("bg_memory_backup", "پشتیبان‌گیری محلی حافظه", "COMPLETED", detail)
        return Result.success()
    }
}

class MemoryIndexerWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? SolApplication ?: return Result.failure()
        val c = app.container
        val memories = c.memoryRepository.getAllOnce()
        val books = c.knowledgeRepository.getAllBooksOnce()
        val roles = c.knowledgeRepository.getAllRolesOnce()
        val detail = "نمایه‌سازی ${memories.size} قاعده/نکته، ${books.size} کتاب و ${roles.size} نقش انجام شد."
        logToDb("bg_memory_indexer", "نمایه‌سازی حافظه و نقش‌ها", "COMPLETED", detail)
        return Result.success()
    }
}

class LocationCheckInWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? SolApplication ?: return Result.failure()
        val prefs = app.container.preferencesRepository.getSnapshot()
        val current = prefs.confirmedCurrentLocation
        val body = if (current.isBlank()) {
            "موقعیت فعلی شما هنوز ثبت نشده؛ برای تأیید دستی Sol را باز کنید."
        } else {
            "آخرین موقعیت تأییدشده: «$current» — در صورت تغییر به‌روزرسانی کنید."
        }
        SolNotificationHelper.postNotification(
            context = applicationContext,
            channelId = SolNotificationHelper.CHANNEL_REMINDERS,
            notificationId = 204,
            title = "یادآور تأیید موقعیت Sol",
            body = body,
        )
        logToDb("bg_location_checkin", "یادآور تأیید موقعیت", "COMPLETED", body)
        return Result.success()
    }
}

class HistoryMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? SolApplication ?: return Result.failure()
        val prefs = app.container.preferencesRepository.getSnapshot()
        val removed = app.container.historyRepository.enforceRetention(prefs.historyRetentionMode)
        val detail = if (removed > 0) {
            "$removed سابقه قدیمی‌تر طبق سیاست «${prefs.historyRetentionMode.labelFa}» پاک‌سازی شد."
        } else {
            "سوابق مطابق سیاست «${prefs.historyRetentionMode.labelFa}» بهینه است."
        }
        logToDb("bg_history_cleanup", "بهینه‌سازی سوابق", "COMPLETED", detail)
        return Result.success()
    }
}

class BookFetchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val query = inputData.getString(KEY_BOOK_QUERY) ?: return Result.failure()
        val app = applicationContext as? SolApplication ?: return Result.failure()
        val fetcher = BookWebFetcher(applicationContext)
        val result = fetcher.fetchBookFromInternet(query)
        return result.fold(
            onSuccess = { book ->
                val bookId = app.container.knowledgeRepository.saveBook(
                    BookKnowledgeEntity(
                        title = book.title,
                        author = book.author,
                        subjects = book.subjects,
                        summaryText = book.summaryText,
                        sourceType = book.sourceType,
                        sourceUrl = book.sourceUrl,
                    )
                )
                val outcome = app.container.bookRagEngine.indexBookContentWithNotice(bookId, book.title, book.fullText)
                val noticeSuffix = if (outcome.wasTruncated) "\n${outcome.userNoticeFa}" else ""
                val msg = "کتاب «${book.title}» اثر ${book.author} ذخیره و به ${outcome.chunksCount} بخش برای جست‌وجوی BM25 نمایه‌سازی شد.$noticeSuffix"
                SolNotificationHelper.postNotification(
                    context = applicationContext,
                    channelId = SolNotificationHelper.CHANNEL_BACKGROUND,
                    notificationId = 205,
                    title = "دریافت کتاب کامل شد",
                    body = msg,
                )
                logToDb("bg_book_fetch_worker", "دریافت کتاب در پس‌زمینه", "COMPLETED", msg)
                Result.success()
            },
            onFailure = { err ->
                val msg = err.message ?: "خطا در دریافت کتاب"
                logToDb("bg_book_fetch_worker", "دریافت کتاب در پس‌زمینه", "FAILED", msg)
                Result.failure()
            }
        )
    }

    companion object {
        const val KEY_BOOK_QUERY = "book_query"
    }
}

private suspend fun CoroutineWorker.logToDb(
    key: String,
    titleFa: String,
    status: String,
    detailFa: String,
) {
    val app = applicationContext as? SolApplication ?: return
    app.container.knowledgeRepository.logBackgroundJob(
        jobKey = key,
        titleFa = titleFa,
        status = status,
        detailFa = detailFa,
    )
}
