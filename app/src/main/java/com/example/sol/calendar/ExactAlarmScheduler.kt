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
import com.example.sol.memory.ScheduledAlarmEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class AlarmScheduleOutcome(
    val isExactGranted: Boolean,
    val triggerAtMillis: Long,
    val jalaliDateTimeFa: String,
    val alarmId: Long,
)

class ExactAlarmScheduler(
    private val context: Context,
    private val knowledgeRepository: KnowledgeRepository,
) {
    private val alarmManager: AlarmManager?
        get() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

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
    ): AlarmScheduleOutcome {
        val safeTrigger = maxOf(triggerAtMillis, System.currentTimeMillis() + 5_000L)
        val requestCode = (safeTrigger % Int.MAX_VALUE).toInt().let { if (it == 0) 1 else it }
        val jalaliFormatted = JalaliCalendar.formatDateTimeFa(safeTrigger)
        val exactAllowed = canScheduleExactAlarms()

        val pi = buildPendingIntent(
            requestCode = requestCode,
            titleFa = titleFa,
            messageBody = messageBody,
            targetContact = targetContact,
        )

        val am = alarmManager
        if (am != null) {
            if (exactAllowed) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTrigger, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTrigger, pi)
            }
        }

        val id = knowledgeRepository.saveScheduledAlarm(
            ScheduledAlarmEntity(
                requestCode = requestCode,
                titleFa = titleFa,
                messageBody = messageBody,
                triggerAtMillis = safeTrigger,
                jalaliLabel = jalaliFormatted,
                targetContact = targetContact,
                isExact = exactAllowed,
            )
        )
        knowledgeRepository.logBackgroundJob(
            jobKey = "bg_exact_alarm",
            titleFa = if (exactAllowed) "یادآور دقیق شمسی (AlarmManager)" else "یادآور با احترام به Doze",
            status = "SCHEDULED",
            detailFa = "$jalaliFormatted — $messageBody",
        )

        return AlarmScheduleOutcome(
            isExactGranted = exactAllowed,
            triggerAtMillis = safeTrigger,
            jalaliDateTimeFa = jalaliFormatted,
            alarmId = id,
        )
    }

    suspend fun cancelAlarm(alarm: ScheduledAlarmEntity) {
        val pi = buildPendingIntent(
            requestCode = alarm.requestCode,
            titleFa = alarm.titleFa,
            messageBody = alarm.messageBody,
            targetContact = alarm.targetContact,
        )
        alarmManager?.cancel(pi)
        knowledgeRepository.deleteScheduledAlarm(alarm.id)
    }

    private fun buildPendingIntent(
        requestCode: Int,
        titleFa: String,
        messageBody: String,
        targetContact: String,
    ): PendingIntent {
        val intent = Intent(context, ExactReminderReceiver::class.java).apply {
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
}

class ExactReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
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
            runCatching {
                val app = context.applicationContext as? SolApplication
                app?.container?.knowledgeRepository?.markAlarmTriggered(requestCode)
                app?.container?.knowledgeRepository?.logBackgroundJob(
                    jobKey = "bg_exact_alarm",
                    titleFa = title,
                    status = "COMPLETED",
                    detailFa = fullBody,
                )
            }
            pendingResult.finish()
        }
    }

    companion object {
        const val EXTRA_REQUEST_CODE = "extra_request_code"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_CONTACT = "extra_contact"
    }
}
