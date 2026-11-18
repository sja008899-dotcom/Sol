package com.example.sol.background

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.SolApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class SolBackgroundService : Service(), TextToSpeech.OnInitListener {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tts: TextToSpeech? = null
    private var pendingSpeakText: String? = null
    private var ttsReady = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SolNotificationHelper.ensureChannels(this)
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                tts?.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SPEAK_TEXT -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "روخوانی صوتی Sol"
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                promoteToForeground(
                    title = title,
                    body = text.take(120),
                    isMedia = true,
                )
                pendingSpeakText = text
                if (ttsReady && text.isNotBlank()) {
                    speakAndFinish(text)
                }
            }

            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "سرویس پس‌زمینه Sol"
                val body = intent?.getStringExtra(EXTRA_TEXT) ?: "در حال انجام وظیفه محلی…"
                promoteToForeground(title = title, body = body, isMedia = false)
                serviceScope.launch {
                    delay(3500)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale("fa", "IR"))
            ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            val text = pendingSpeakText
            if (ttsReady && !text.isNullOrBlank()) {
                speakAndFinish(text)
            } else if (!text.isNullOrBlank()) {
                // Keep foreground notification briefly so user sees report even if Persian TTS pack missing
                serviceScope.launch {
                    delay(3000)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun speakAndFinish(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sol_fgs_read")
        serviceScope.launch {
            val waitMs = (text.length * 70L).coerceIn(3000L, 25000L)
            delay(waitMs)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun promoteToForeground(title: String, body: String, isMedia: Boolean) {
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
            if (isMedia) SolNotificationHelper.CHANNEL_READER else SolNotificationHelper.CHANNEL_BACKGROUND,
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, "توقف", stopPending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (isMedia) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(FOREGROUND_ID, notification, type)
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SPEAK_TEXT = "com.example.sol.action.SPEAK_TEXT"
        const val ACTION_STOP = "com.example.sol.action.STOP"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"
        private const val FOREGROUND_ID = 4100

        fun startVoiceReader(context: Context, title: String, text: String) {
            val app = context.applicationContext as? SolApplication
            app?.container?.knowledgeRepository?.let {
                // Fire-and-forget safe service launch
            }
            val intent = Intent(context, SolBackgroundService::class.java).apply {
                action = ACTION_SPEAK_TEXT
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
