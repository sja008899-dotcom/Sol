package com.example.sol.background

import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.SolApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground mediaPlayback service for reading messages/books aloud using Sol's
 * 100% self-contained Piper TTS engine (zero dependency on external phone TTS engines).
 */
class SolBackgroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var speakJob: Job? = null
    private var activeStartId: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SolNotificationHelper.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeStartId = startId
        when (intent?.action) {
            ACTION_STOP -> {
                stopCleanly(startId)
                return START_NOT_STICKY
            }

            ACTION_SPEAK_TEXT -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "روخوانی صوتی Sol"
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (text.isBlank()) {
                    stopCleanly(startId)
                    return START_NOT_STICKY
                }
                promoteToForeground(
                    title = title,
                    body = text.take(120),
                )
                speakWithSelfContainedPiper(text, startId)
            }

            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun speakWithSelfContainedPiper(text: String, startId: Int) {
        speakJob?.cancel()
        val ttsManager = (application as? SolApplication)?.container?.ttsManager
        if (ttsManager == null) {
            stopCleanly(startId)
            return
        }
        speakJob = serviceScope.launch {
            try {
                ttsManager.speakBlocking(text)
            } finally {
                stopCleanly(startId)
            }
        }
    }

    private fun promoteToForeground(title: String, body: String) {
        val openPending = PendingIntent.getActivity(
            this,
            901,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPending = PendingIntent.getService(
            this,
            902,
            Intent(this, SolBackgroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(
            this,
            SolNotificationHelper.CHANNEL_READER,
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, "توقف", stopPending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    private fun stopCleanly(startId: Int = activeStartId) {
        speakJob?.cancel()
        speakJob = null
        runCatching { (application as? SolApplication)?.container?.ttsManager?.stop() }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        if (startId >= 0) {
            stopSelf(startId)
        } else {
            stopSelf()
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopCleanly(startId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            super.onTimeout(startId, fgsType)
        }
    }

    override fun onDestroy() {
        speakJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SPEAK_TEXT = "com.example.sol.action.SPEAK_TEXT"
        const val ACTION_STOP = "com.example.sol.action.STOP"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"
        private const val FOREGROUND_ID = 4100
        private const val FALLBACK_NOTIFICATION_ID = 4101

        fun startVoiceReader(
            context: Context,
            title: String,
            text: String,
        ): Boolean {
            if (text.isBlank()) return false
            val intent = Intent(context, SolBackgroundService::class.java).apply {
                action = ACTION_SPEAK_TEXT
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                val isFgsBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
                if (isFgsBlocked || e is IllegalStateException || e is SecurityException) {
                    SolNotificationHelper.postNotification(
                        context = context,
                        channelId = SolNotificationHelper.CHANNEL_READER,
                        notificationId = FALLBACK_NOTIFICATION_ID,
                        title = title,
                        body = "برای روخوانی صوتی برنامه را باز کنید: ${text.take(120)}",
                    )
                    false
                } else {
                    false
                }
            }
        }
    }
}
