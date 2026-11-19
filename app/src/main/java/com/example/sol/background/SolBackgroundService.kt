package com.example.sol.background

import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.SolApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SolBackgroundService : Service(), TextToSpeech.OnInitListener {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tts: TextToSpeech? = null
    private var pendingSpeakText: String? = null
    private var ttsReady = false
    private var ttsInitializing = false

    private var backgroundTtsProbeQueue: ArrayDeque<String> = ArrayDeque()
    private var bindingPackage: String = ""
    private var triedAltEngine = false
    private var watchdogJob: Job? = null
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
                pendingSpeakText = text
                val extraPkg = intent.getStringExtra(EXTRA_TTS_PACKAGE).orEmpty()
                if (ttsReady && tts != null) {
                    speakAndAwaitCompletion(text)
                } else if (!ttsInitializing) {
                    initializeTtsAsync(extraPkg)
                }
            }

            else -> {
                // Only mediaPlayback voice reader action is valid; stop cleanly without faking work.
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun initializeTtsAsync(passedPackage: String) {
        ttsInitializing = true
        serviceScope.launch {
            val preferredPkg = passedPackage.ifBlank {
                withContext(Dispatchers.IO) {
                    runCatching {
                        (application as? SolApplication)?.container?.preferencesRepository
                            ?.getSnapshot()?.preferredTtsEnginePackage.orEmpty()
                    }.getOrDefault("")
                }
            }
            bindingPackage = preferredPkg
            triedAltEngine = false
            backgroundTtsProbeQueue.clear()
            ttsReady = false
            tts = runCatching {
                if (preferredPkg.isNotBlank()) {
                    TextToSpeech(this@SolBackgroundService, this@SolBackgroundService, preferredPkg)
                } else {
                    TextToSpeech(this@SolBackgroundService, this@SolBackgroundService)
                }
            }.getOrNull()
            if (tts == null) {
                ttsInitializing = false
                stopCleanly()
            }
        }
    }

    override fun onInit(status: Int) {
        ttsInitializing = false
        val engine = tts
        if (status == TextToSpeech.SUCCESS && engine != null) {
            val activePkg = bindingPackage.ifBlank { runCatching { engine.defaultEngine.orEmpty() }.getOrDefault("") }
            if (!triedAltEngine && backgroundTtsProbeQueue.isEmpty()) {
                val others = com.example.sol.voice.PersianTTS.snapshotEngines(engine)
                    .map { it.packageName }
                    .filter { it.isNotBlank() && it != activePkg }
                backgroundTtsProbeQueue.addAll(others)
            }
            val persianOk = com.example.sol.voice.PersianTTS.configurePersianLanguageAndVoices(engine)
            val isSherpa = com.example.sol.voice.PersianTTS.isSherpaEngine(packageName = activePkg)
            if (persianOk || isSherpa) {
                attachUtteranceListener(engine)
                ttsReady = true
                val text = pendingSpeakText
                if (!text.isNullOrBlank()) {
                    speakAndAwaitCompletion(text)
                }
                return
            }
        }

        if (backgroundTtsProbeQueue.isNotEmpty()) {
            triedAltEngine = true
            ttsInitializing = true
            val nextPkg = backgroundTtsProbeQueue.removeFirst()
            bindingPackage = nextPkg
            runCatching {
                tts?.stop()
                tts?.shutdown()
            }
            tts = runCatching {
                TextToSpeech(this, this, nextPkg)
            }.getOrNull()
            if (tts == null) {
                ttsInitializing = false
                stopCleanly()
            }
            return
        }

        // All engines failed to initialize Persian TTS; stop cleanly right away.
        stopCleanly()
    }

    private fun attachUtteranceListener(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                serviceScope.launch { stopCleanly() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                serviceScope.launch { stopCleanly() }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                serviceScope.launch { stopCleanly() }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                serviceScope.launch { stopCleanly() }
            }
        })
    }

    private fun speakAndAwaitCompletion(text: String) {
        watchdogJob?.cancel()
        val utteranceId = "sol_fgs_read_${System.currentTimeMillis()}"
        val speakResult = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) ?: TextToSpeech.ERROR
        if (speakResult != TextToSpeech.SUCCESS) {
            stopCleanly()
            return
        }
        // Watchdog only guards against a hung third-party TTS engine failing to emit UtteranceProgressListener.
        watchdogJob = serviceScope.launch {
            val watchdogMs = (text.length * 140L).coerceIn(15_000L, 120_000L)
            delay(watchdogMs)
            stopCleanly()
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
        watchdogJob?.cancel()
        watchdogJob = null
        pendingSpeakText = null
        runCatching { tts?.stop() }
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
        watchdogJob?.cancel()
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SPEAK_TEXT = "com.example.sol.action.SPEAK_TEXT"
        const val ACTION_STOP = "com.example.sol.action.STOP"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_TTS_PACKAGE = "extra_tts_package"
        private const val FOREGROUND_ID = 4100
        private const val FALLBACK_NOTIFICATION_ID = 4101

        fun startVoiceReader(
            context: Context,
            title: String,
            text: String,
            preferredTtsPackage: String = "",
        ): Boolean {
            if (text.isBlank()) return false
            val intent = Intent(context, SolBackgroundService::class.java).apply {
                action = ACTION_SPEAK_TEXT
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                if (preferredTtsPackage.isNotBlank()) {
                    putExtra(EXTRA_TTS_PACKAGE, preferredTtsPackage)
                }
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
