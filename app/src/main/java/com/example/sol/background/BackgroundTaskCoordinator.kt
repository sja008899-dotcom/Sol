package com.example.sol.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.sol.memory.KnowledgeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

class BackgroundTaskCoordinator(
    private val context: Context,
    private val knowledgeRepository: KnowledgeRepository,
) {
    private val workManager: WorkManager by lazy {
        WorkManager.getInstance(context.applicationContext)
    }

    private val _enabledPeriodicJobs = MutableStateFlow<Set<String>>(emptySet())
    val enabledPeriodicJobs: StateFlow<Set<String>> = _enabledPeriodicJobs.asStateFlow()

    fun scheduleReminder(message: String, delayMinutes: Long) {
        val safeMinutes = delayMinutes.coerceIn(1L, 1440L)
        val req = OneTimeWorkRequestBuilder<ScheduledReminderWorker>()
            .setInitialDelay(safeMinutes, TimeUnit.MINUTES)
            .setInputData(workDataOf(ScheduledReminderWorker.KEY_REMINDER_TEXT to message))
            .build()
        workManager.enqueue(req)
    }

    suspend fun triggerImmediateBackgroundJob(jobId: String, extraText: String = ""): String {
        return when (jobId) {
            "bg_reminder" -> {
                val msg = extraText.ifBlank { "یادآور فوری Sol در پس‌زمینه" }
                val req = OneTimeWorkRequestBuilder<ScheduledReminderWorker>()
                    .setInitialDelay(10, TimeUnit.SECONDS)
                    .setInputData(workDataOf(ScheduledReminderWorker.KEY_REMINDER_TEXT to msg))
                    .build()
                workManager.enqueue(req)
                knowledgeRepository.logBackgroundJob(jobId, "یادآور زمان‌بندی‌شده", "SCHEDULED", "برای ۱۰ ثانیه دیگر زمان‌بندی شد: $msg")
                "یادآور «$msg» در پس‌زمینه برای ۱۰ ثانیه دیگر زمان‌بندی شد."
            }

            "bg_daily_briefing" -> {
                workManager.enqueue(OneTimeWorkRequestBuilder<DailyRoutineWorker>().build())
                "روتین روزانه در صف اجرای پس‌زمینه قرار گرفت."
            }

            "bg_battery_guard" -> {
                workManager.enqueue(OneTimeWorkRequestBuilder<BatteryMonitorWorker>().build())
                "پایش باتری در پس‌زمینه اجرا شد."
            }

            "bg_memory_backup" -> {
                workManager.enqueue(OneTimeWorkRequestBuilder<MemoryBackupWorker>().build())
                "پشتیبان‌گیری محلی از حافظه، کتاب‌ها و نقش‌ها در پس‌زمینه آغاز شد."
            }

            "bg_memory_indexer" -> {
                workManager.enqueue(OneTimeWorkRequestBuilder<MemoryIndexerWorker>().build())
                "مرتب‌سازی و نمایه‌سازی حافظه در پس‌زمینه آغاز شد."
            }

            "bg_location_checkin" -> {
                workManager.enqueue(OneTimeWorkRequestBuilder<LocationCheckInWorker>().build())
                "یادآور تأیید موقعیت فعلی ارسال شد."
            }

            "bg_tts_reader_service" -> {
                val textToRead = extraText.ifBlank {
                    val books = knowledgeRepository.getAllBooksOnce()
                    val roles = knowledgeRepository.getAllRolesOnce().filter { it.isActive }
                    buildString {
                        append("گزارش سرویس صوتی Sol. ")
                        if (roles.isNotEmpty()) {
                            append("نقش فعال: ${roles.first().roleName}. ")
                        }
                        if (books.isNotEmpty()) {
                            append("آخرین کتاب ذخیره‌شده: ${books.first().title}. ${books.first().summaryText.take(240)}")
                        } else {
                            append("هنوز کتابی ذخیره نشده است.")
                        }
                    }
                }
                SolBackgroundService.startVoiceReader(
                    context = context,
                    title = "روخوانی صوتی Sol",
                    text = textToRead,
                )
                knowledgeRepository.logBackgroundJob(jobId, "سرویس روخوانی صوتی", "RUNNING", textToRead.take(120))
                "سرویس روخوانی صوتی در پس‌زمینه (Foreground Service) فعال شد."
            }

            "bg_history_cleanup" -> {
                workManager.enqueue(OneTimeWorkRequestBuilder<HistoryMaintenanceWorker>().build())
                "بهینه‌سازی و هرس سوابق قدیمی در پس‌زمینه اجرا شد."
            }

            else -> "وظیفهٔ پس‌زمینه در صف اجرا قرار گرفت."
        }
    }

    fun enqueueBookFetchWorker(query: String) {
        val req = OneTimeWorkRequestBuilder<BookFetchWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(workDataOf(BookFetchWorker.KEY_BOOK_QUERY to query))
            .build()
        workManager.enqueue(req)
    }

    fun togglePeriodicJob(jobId: String, enable: Boolean) {
        val uniqueName = "sol_periodic_$jobId"
        if (!enable) {
            workManager.cancelUniqueWork(uniqueName)
            _enabledPeriodicJobs.value = _enabledPeriodicJobs.value - jobId
            return
        }

        val request = when (jobId) {
            "bg_daily_briefing" -> PeriodicWorkRequestBuilder<DailyRoutineWorker>(24, TimeUnit.HOURS).build()
            "bg_battery_guard" -> PeriodicWorkRequestBuilder<BatteryMonitorWorker>(2, TimeUnit.HOURS).build()
            "bg_memory_backup" -> PeriodicWorkRequestBuilder<MemoryBackupWorker>(12, TimeUnit.HOURS).build()
            "bg_memory_indexer" -> PeriodicWorkRequestBuilder<MemoryIndexerWorker>(6, TimeUnit.HOURS).build()
            "bg_location_checkin" -> PeriodicWorkRequestBuilder<LocationCheckInWorker>(4, TimeUnit.HOURS).build()
            "bg_history_cleanup" -> PeriodicWorkRequestBuilder<HistoryMaintenanceWorker>(24, TimeUnit.HOURS).build()
            else -> return
        }
        workManager.enqueueUniquePeriodicWork(
            uniqueName,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        _enabledPeriodicJobs.value = _enabledPeriodicJobs.value + jobId
    }
}
