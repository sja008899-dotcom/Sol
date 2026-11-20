package com.example.sol.calendar

import android.content.Context
import android.os.SystemClock
import com.example.sol.memory.KnowledgeRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

data class TimerStartResult(
    val durationSeconds: Long,
    val labelFa: String,
    val outcome: AlarmScheduleOutcome,
)

data class TimerStatusSnapshot(
    val isActive: Boolean,
    val labelFa: String = "",
    val remainingSeconds: Long = 0L,
    val endsAtMillis: Long = 0L,
)

data class StopwatchStatusSnapshot(
    val isRunning: Boolean,
    val elapsedMillis: Long,
    val formattedFa: String,
)

/**
 * Manages exact countdown timers via [ExactAlarmScheduler] + notifications (no external Clock app required)
 * and a low-overhead local stopwatch backed by SharedPreferences and [SystemClock.elapsedRealtime].
 */
class TimerController(
    private val context: Context,
    private val exactAlarmScheduler: ExactAlarmScheduler,
    private val knowledgeRepository: KnowledgeRepository,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val elapsedClockMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val mutex = Mutex()

    suspend fun startCountdownTimer(
        durationSeconds: Long,
        labelFa: String = "تایمر Sol",
    ): TimerStartResult = mutex.withLock {
        val safeSeconds = durationSeconds.coerceIn(1L, 86_400L)
        val cleanLabel = labelFa.ifBlank { "تایمر Sol" }
        val now = wallClockMillis()
        val triggerAt = now + safeSeconds * 1000L

        cancelExistingTimerLocked()

        val outcome = exactAlarmScheduler.scheduleJalaliAlarm(
            triggerAtMillis = triggerAt,
            titleFa = "پایان $cleanLabel",
            messageBody = "زمان ${formatDurationFa(safeSeconds)} به پایان رسید.",
            minLeadMillis = 1_000L,
        )

        prefs.edit()
            .putLong(KEY_TIMER_ALARM_ID, outcome.alarmId)
            .putLong(KEY_TIMER_END_MS, outcome.triggerAtMillis)
            .putString(KEY_TIMER_LABEL, cleanLabel)
            .apply()

        TimerStartResult(
            durationSeconds = safeSeconds,
            labelFa = cleanLabel,
            outcome = outcome,
        )
    }

    suspend fun getCountdownStatus(): TimerStatusSnapshot = mutex.withLock {
        val endMs = prefs.getLong(KEY_TIMER_END_MS, 0L)
        val alarmId = prefs.getLong(KEY_TIMER_ALARM_ID, 0L)
        val label = prefs.getString(KEY_TIMER_LABEL, "تایمر Sol").orEmpty().ifBlank { "تایمر Sol" }
        val remainingMs = endMs - wallClockMillis()
        if (endMs <= 0L || remainingMs <= 0L) {
            return@withLock TimerStatusSnapshot(isActive = false)
        }
        val alarmExists = if (alarmId > 0L) {
            knowledgeRepository.getPendingAlarmsOnce().any { it.id == alarmId }
        } else {
            true
        }
        if (!alarmExists) {
            return@withLock TimerStatusSnapshot(isActive = false)
        }
        TimerStatusSnapshot(
            isActive = true,
            labelFa = label,
            remainingSeconds = (remainingMs + 999L) / 1000L,
            endsAtMillis = endMs,
        )
    }

    suspend fun cancelCountdownTimer(): Boolean = mutex.withLock {
        cancelExistingTimerLocked()
    }

    private suspend fun cancelExistingTimerLocked(): Boolean {
        val existingId = prefs.getLong(KEY_TIMER_ALARM_ID, 0L)
        val endMs = prefs.getLong(KEY_TIMER_END_MS, 0L)
        val hadActive = existingId > 0L && endMs > wallClockMillis()
        if (existingId > 0L) {
            val entity = knowledgeRepository.getAllAlarmsOnce().firstOrNull { it.id == existingId }
            if (entity != null) {
                exactAlarmScheduler.cancelAlarm(entity)
            }
        }
        prefs.edit()
            .remove(KEY_TIMER_ALARM_ID)
            .remove(KEY_TIMER_END_MS)
            .remove(KEY_TIMER_LABEL)
            .apply()
        return hadActive
    }

    fun startStopwatch(): StopwatchStatusSnapshot {
        val isRunning = prefs.getBoolean(KEY_SW_RUNNING, false)
        val accumulated = prefs.getLong(KEY_SW_ACCUMULATED_MS, 0L)
        if (!isRunning) {
            prefs.edit()
                .putBoolean(KEY_SW_RUNNING, true)
                .putLong(KEY_SW_STARTED_ELAPSED_MS, elapsedClockMillis())
                .putLong(KEY_SW_ACCUMULATED_MS, accumulated)
                .apply()
        }
        return getStopwatchStatus()
    }

    fun stopStopwatch(): StopwatchStatusSnapshot {
        val total = computeStopwatchElapsedMs()
        prefs.edit()
            .putBoolean(KEY_SW_RUNNING, false)
            .putLong(KEY_SW_ACCUMULATED_MS, total)
            .remove(KEY_SW_STARTED_ELAPSED_MS)
            .apply()
        return StopwatchStatusSnapshot(
            isRunning = false,
            elapsedMillis = total,
            formattedFa = formatElapsedMillisFa(total),
        )
    }

    fun resetStopwatch(): StopwatchStatusSnapshot {
        prefs.edit()
            .putBoolean(KEY_SW_RUNNING, false)
            .putLong(KEY_SW_ACCUMULATED_MS, 0L)
            .remove(KEY_SW_STARTED_ELAPSED_MS)
            .apply()
        return StopwatchStatusSnapshot(
            isRunning = false,
            elapsedMillis = 0L,
            formattedFa = formatElapsedMillisFa(0L),
        )
    }

    fun getStopwatchStatus(): StopwatchStatusSnapshot {
        val isRunning = prefs.getBoolean(KEY_SW_RUNNING, false)
        val elapsed = computeStopwatchElapsedMs()
        return StopwatchStatusSnapshot(
            isRunning = isRunning,
            elapsedMillis = elapsed,
            formattedFa = formatElapsedMillisFa(elapsed),
        )
    }

    private fun computeStopwatchElapsedMs(): Long {
        val accumulated = prefs.getLong(KEY_SW_ACCUMULATED_MS, 0L).coerceAtLeast(0L)
        val isRunning = prefs.getBoolean(KEY_SW_RUNNING, false)
        if (!isRunning) return accumulated
        val startedAt = prefs.getLong(KEY_SW_STARTED_ELAPSED_MS, 0L)
        val delta = if (startedAt > 0L) (elapsedClockMillis() - startedAt).coerceAtLeast(0L) else 0L
        return accumulated + delta
    }

    companion object {
        private const val PREFS_NAME = "sol_timer_stopwatch_prefs"
        private const val KEY_TIMER_ALARM_ID = "timer_alarm_id"
        private const val KEY_TIMER_END_MS = "timer_end_ms"
        private const val KEY_TIMER_LABEL = "timer_label"
        private const val KEY_SW_RUNNING = "sw_running"
        private const val KEY_SW_STARTED_ELAPSED_MS = "sw_started_elapsed_ms"
        private const val KEY_SW_ACCUMULATED_MS = "sw_accumulated_ms"

        fun formatDurationFa(totalSeconds: Long): String {
            val s = totalSeconds.coerceAtLeast(0L)
            val hours = s / 3600L
            val minutes = (s % 3600L) / 60L
            val seconds = s % 60L
            val parts = mutableListOf<String>()
            if (hours > 0L) parts.add("$hours ساعت")
            if (minutes > 0L) parts.add("$minutes دقیقه")
            if (seconds > 0L || parts.isEmpty()) parts.add("$seconds ثانیه")
            return parts.joinToString(" و ")
        }

        fun formatElapsedMillisFa(elapsedMs: Long): String {
            val safe = elapsedMs.coerceAtLeast(0L)
            val totalSec = safe / 1000L
            val tenths = (safe % 1000L) / 100L
            val mins = totalSec / 60L
            val secs = totalSec % 60L
            return String.format(Locale.US, "%02d:%02d.%d", mins, secs, tenths)
        }
    }
}
