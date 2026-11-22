package com.example.sol.calendar

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.example.SolApplication
import com.example.sol.background.SolNotificationHelper
import com.example.sol.memory.KnowledgeRepository
import com.example.sol.memory.PreferencesRepository
import com.example.sol.memory.ScheduledAlarmEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AlarmScheduleOutcome(
    val isExactGranted: Boolean,
    val triggerAtMillis: Long,
    val jalaliDateTimeFa: String,
    val alarmId: Long,
)

data class RescheduleSummary(
    val rescheduledCount: Int,
    val missedDeliveredCount: Int,
    val isExactGranted: Boolean,
)

class ExactAlarmScheduler(
    private val context: Context,
    private val knowledgeRepository: KnowledgeRepository,
    private val preferencesRepository: PreferencesRepository? = null,
) {
    private val alarmManager: AlarmManager?
        get() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private val _isExactModeActive = MutableStateFlow(canScheduleExactAlarms())
    val isExactModeActive: StateFlow<Boolean> = _isExactModeActive.asStateFlow()

    fun refreshExactAlarmState(): Boolean {
        val exact = canScheduleExactAlarms()
        _isExactModeActive.value = exact
        return exact
    }

    fun canScheduleExactAlarms(): Boolean {
        val am = alarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun openExactAlarmSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    suspend fun scheduleJalaliAlarm(
        triggerAtMillis: Long,
        titleFa: String,
        messageBody: String,
        targetContact: String = "",
        minLeadMillis: Long = 5_000L,
    ): AlarmScheduleOutcome {
        val safeTrigger = maxOf(triggerAtMillis, System.currentTimeMillis() + minLeadMillis.coerceAtLeast(1_000L))
        val jalaliFormatted = JalaliCalendar.formatDateTimeFa(safeTrigger)
        val exactAllowed = refreshExactAlarmState()

        val savedAlarm = knowledgeRepository.insertAlarmWithUniqueRequestCode(
            ScheduledAlarmEntity(
                requestCode = 0,
                titleFa = titleFa,
                messageBody = messageBody,
                triggerAtMillis = safeTrigger,
                jalaliLabel = jalaliFormatted,
                targetContact = targetContact,
                isExact = exactAllowed,
                isTriggered = false,
            )
        )

        val registeredExact = scheduleWithAlarmManager(savedAlarm, exactAllowed)
        if (registeredExact != exactAllowed) {
            knowledgeRepository.updateAlarmExactFlag(savedAlarm.id, registeredExact)
        }

        knowledgeRepository.logBackgroundJob(
            jobKey = "bg_exact_alarm",
            titleFa = if (registeredExact) "یادآور دقیق شمسی (AlarmManager)" else "یادآور با احترام به Doze",
            status = "SCHEDULED",
            detailFa = "$jalaliFormatted — $messageBody",
        )

        return AlarmScheduleOutcome(
            isExactGranted = registeredExact,
            triggerAtMillis = safeTrigger,
            jalaliDateTimeFa = jalaliFormatted,
            alarmId = savedAlarm.id,
        )
    }

    suspend fun reschedulePendingAlarms(
        reason: String,
        fireMissedAsNotification: Boolean = true,
        nowMillis: Long = System.currentTimeMillis(),
    ): RescheduleSummary {
        val exactAllowed = refreshExactAlarmState()
        val pendingAlarms = knowledgeRepository.getPendingAlarmsOnce()
        var rescheduled = 0
        var missedDelivered = 0

        for (alarm in pendingAlarms) {
            if (alarm.triggerAtMillis > nowMillis) {
                val actuallyExact = scheduleWithAlarmManager(alarm, exactAllowed)
                if (alarm.isExact != actuallyExact) {
                    knowledgeRepository.updateAlarmExactFlag(alarm.id, actuallyExact)
                }
                rescheduled++
            } else {
                if (fireMissedAsNotification) {
                    val contactPrefix = if (alarm.targetContact.isNotBlank()) {
                        "مرتبط با ${alarm.targetContact} • "
                    } else {
                        ""
                    }
                    SolNotificationHelper.postNotification(
                        context = context,
                        channelId = SolNotificationHelper.CHANNEL_REMINDERS,
                        notificationId = 7000 + (alarm.requestCode % 3000),
                        title = "یادآور جاافتاده: ${alarm.titleFa}",
                        body = "${contactPrefix}زمان «${alarm.jalaliLabel}» گذشت: ${alarm.messageBody}",
                    )
                    preferencesRepository?.recordLastReminderDelivered("یادآور جاافتاده: ${alarm.titleFa}", nowMillis)
                    missedDelivered++
                }
                knowledgeRepository.markAlarmTriggeredById(alarm.id)
            }
        }

        if (pendingAlarms.isNotEmpty() || reason == ACTION_REASON_PERMISSION_CHANGE) {
            val modeFa = if (exactAllowed) "دقیق (AlarmManager)" else "تقریبی (Doze)"
            knowledgeRepository.logBackgroundJob(
                jobKey = "bg_exact_alarm",
                titleFa = "همگام‌سازی یادآورها ($reason)",
                status = "COMPLETED",
                detailFa = "$rescheduled یادآور آینده بازنشانی شد ($modeFa)، $missedDelivered یادآور جاافتاده اعلان شد.",
            )
        }

        return RescheduleSummary(
            rescheduledCount = rescheduled,
            missedDeliveredCount = missedDelivered,
            isExactGranted = exactAllowed,
        )
    }

    suspend fun cancelAlarm(alarm: ScheduledAlarmEntity) {
        val pi = buildPendingIntent(
            alarmId = alarm.id,
            requestCode = alarm.requestCode,
            titleFa = alarm.titleFa,
            messageBody = alarm.messageBody,
            targetContact = alarm.targetContact,
        )
        alarmManager?.cancel(pi)
        knowledgeRepository.deleteScheduledAlarm(alarm.id)
    }

    private fun scheduleWithAlarmManager(
        alarm: ScheduledAlarmEntity,
        preferExact: Boolean,
    ): Boolean {
        val am = alarmManager ?: return false
        val pi = buildPendingIntent(
            alarmId = alarm.id,
            requestCode = alarm.requestCode,
            titleFa = alarm.titleFa,
            messageBody = alarm.messageBody,
            targetContact = alarm.targetContact,
        )
        if (preferExact) {
            val exactResult = runCatching {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerAtMillis, pi)
            }
            if (exactResult.isSuccess) return true
        }
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerAtMillis, pi)
        }
        return false
    }

    private fun buildPendingIntent(
        alarmId: Long,
        requestCode: Int,
        titleFa: String,
        messageBody: String,
        targetContact: String,
    ): PendingIntent {
        val intent = Intent(context, ExactReminderReceiver::class.java).apply {
            putExtra(ExactReminderReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(ExactReminderReceiver.EXTRA_REQUEST_CODE, requestCode)
            putExtra(ExactReminderReceiver.EXTRA_TITLE, titleFa)
            putExtra(ExactReminderReceiver.EXTRA_MESSAGE, messageBody)
            putExtra(ExactReminderReceiver.EXTRA_CONTACT, targetContact)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        internal const val ACTION_REASON_PERMISSION_CHANGE = "تغییر دسترسی هشدار دقیق"
    }
}

class ExactReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        val requestCode = intent.getIntExtra(EXTRA_REQUEST_CODE, 1001)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "یادآور دقیق Sol"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "زمان یادآور شما فرا رسید."
        val contact = intent.getStringExtra(EXTRA_CONTACT).orEmpty()

        val fullBody = if (contact.isNotBlank()) {
            "مرتبط با $contact: $message"
        } else {
            message
        }

        SolNotificationHelper.postNotification(
            context = context,
            channelId = SolNotificationHelper.CHANNEL_REMINDERS,
            notificationId = 5000 + (requestCode % 4000),
            title = title,
            body = fullBody,
        )

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as? SolApplication)?.container
                val repo = container?.knowledgeRepository
                if (repo != null) {
                    if (alarmId > 0L) {
                        repo.markAlarmTriggeredById(alarmId)
                    } else {
                        repo.markAlarmTriggered(requestCode)
                    }
                    repo.logBackgroundJob(
                        jobKey = "bg_exact_alarm",
                        titleFa = title,
                        status = "COMPLETED",
                        detailFa = fullBody,
                    )
                }
                container?.preferencesRepository?.recordLastReminderDelivered(title)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_REQUEST_CODE = "extra_request_code"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_CONTACT = "extra_contact"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? SolApplication ?: return@launch
                val reason = when (action) {
                    Intent.ACTION_BOOT_COMPLETED -> "راه‌اندازی مجدد گوشی"
                    Intent.ACTION_MY_PACKAGE_REPLACED -> "به‌روزرسانی برنامه"
                    Intent.ACTION_TIME_CHANGED -> "تغییر ساعت سیستم"
                    Intent.ACTION_TIMEZONE_CHANGED -> "تغییر منطقه زمانی"
                    else -> "رویداد سیستم"
                }
                app.container.exactAlarmScheduler.reschedulePendingAlarms(
                    reason = reason,
                    fireMissedAsNotification = true,
                )
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}

class ExactAlarmPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? SolApplication ?: return@launch
                app.container.exactAlarmScheduler.reschedulePendingAlarms(
                    reason = ExactAlarmScheduler.ACTION_REASON_PERMISSION_CHANGE,
                    fireMissedAsNotification = true,
                )
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
