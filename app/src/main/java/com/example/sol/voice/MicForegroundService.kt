package com.example.sol.voice

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioRecord
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.sol.memory.PersianNormalizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * N2: Foreground microphone service allowing wake-word detection even when the screen is off
 * (explicitly authorized by the project owner for Android 12+ compliance).
 * Uses [SafeAudioCapture] and pauses cleanly whenever another Sol component leases the microphone.
 */
class MicForegroundService : Service() {
    private val running = AtomicBoolean(false)

    @Volatile
    private var activeRecorderRef: AudioRecord? = null

    @Volatile
    private var workerThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                running.set(false)
                _isPaused.value = false
                _isRunning.value = false
                _isMicActive.value = false
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                running.set(false)
                _isPaused.value = true
                runCatching { activeRecorderRef?.stop() }
                runCatching { activeRecorderRef?.release() }
                activeRecorderRef = null
                workerThread?.interrupt()
                return START_STICKY
            }
        }

        val hasMicPerm = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm) {
            Log.w(TAG, "MicForegroundService started without RECORD_AUDIO permission; stopping safely.")
            _isRunning.value = false
            _isPaused.value = false
            _isMicActive.value = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (findValidVoskDir(File(filesDir, "vosk-model-fa")) == null) {
            Log.w(TAG, "Vosk offline model not installed; refusing empty MicForegroundService loop.")
            _isRunning.value = false
            _isPaused.value = false
            _isMicActive.value = false
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed entering foreground microphone state", t)
            _isRunning.value = false
            _isPaused.value = false
            _isMicActive.value = false
            stopSelf()
            return START_NOT_STICKY
        }

        _isPaused.value = false
        _isRunning.value = true
        if (running.compareAndSet(false, true)) {
            startMicLoop()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startMicLoop() {
        workerThread = Thread({
            var voskModel: Model? = null
            var recognizer: Recognizer? = null
            var wakeTimestampToEmit = -1L
            try {
                val sampleRate = 16_000
                val voskDir = findValidVoskDir(File(filesDir, "vosk-model-fa"))
                if (voskDir == null) {
                    Log.w(TAG, "Vosk model missing inside MicForegroundService.")
                    return@Thread
                }

                voskModel = runCatching { Model(voskDir.absolutePath) }.getOrNull()
                if (voskModel == null) {
                    Log.e(TAG, "Failed initializing Vosk Model for MicForegroundService.")
                    return@Thread
                }
                val grammar = VoiceVocabularyBias().buildWakeWordGrammarJson()
                recognizer = runCatching {
                    Recognizer(voskModel, sampleRate.toFloat(), grammar)
                }.getOrNull()
                if (recognizer == null) {
                    Log.e(TAG, "Failed initializing Vosk Recognizer for MicForegroundService.")
                    return@Thread
                }

                _isMicActive.value = true
                var lastWakeAt = 0L
                val activeRec = recognizer
                val stopOutcome = SafeAudioCapture.runCaptureLoop(
                    sampleRate = sampleRate,
                    chunkSize = 2048,
                    shouldContinue = { running.get() && !Thread.currentThread().isInterrupted },
                    onRecorderSwap = { activeRecorderRef = it },
                    onSamples = { buf, read ->
                        val completed = activeRec.acceptWaveForm(buf, read)
                        val jsonStr = if (completed) activeRec.result else activeRec.partialResult
                        val key = if (completed) "text" else "partial"
                        val spoken = extractText(jsonStr, key)
                        if (spoken.isNotBlank()) {
                            val norm = PersianNormalizer.normalize(spoken)
                            val now = System.currentTimeMillis()
                            if ((norm.contains("سول") || norm.contains("sol")) && now - lastWakeAt > 4_000L) {
                                lastWakeAt = now
                                wakeTimestampToEmit = now
                                running.set(false)
                                _isPaused.value = true
                                return@runCaptureLoop false
                            }
                        }
                        true
                    },
                )
                if (stopOutcome is SafeAudioCaptureStop.Failed) {
                    Log.w(TAG, "MicForegroundService capture stopped: ${stopOutcome.messageFa}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "MicForegroundService loop error", t)
            } finally {
                running.set(false)
                activeRecorderRef = null
                _isMicActive.value = false
                runCatching { recognizer?.close() }
                runCatching { voskModel?.close() }

                // Emit wake event AFTER AudioRecord & Vosk have completely released hardware mic!
                if (wakeTimestampToEmit > 0L) {
                    _wakeEvents.tryEmit(wakeTimestampToEmit)
                }

                if (!_isPaused.value) {
                    _isRunning.value = false
                    stopSelf()
                }
            }
        }, "SolMicForegroundLoop").also { it.start() }
    }

    private fun findValidVoskDir(root: File): File? {
        if (!root.exists()) return null
        if (hasVoskSignature(root)) return root
        return root.listFiles()?.firstOrNull { it.isDirectory && hasVoskSignature(it) }
    }

    private fun hasVoskSignature(dir: File): Boolean =
        File(dir, "am").isDirectory &&
            File(dir, "conf").isDirectory &&
            File(dir, "graph").isDirectory

    private fun extractText(jsonStr: String?, key: String): String {
        if (jsonStr.isNullOrBlank()) return ""
        return runCatching { JSONObject(jsonStr).optString(key, "").trim() }.getOrDefault("")
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "شنود همیشگی Sol (صفحه خاموش)",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(MainActivity.EXTRA_START_VOICE, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, MicForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("سول در حال گوش دادن است")
            .setContentText("حالت میکروفون صفحه خاموش فعال است • برای توقف ضربه بزنید")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "توقف", stopIntent)
            .build()
    }

    override fun onDestroy() {
        running.set(false)
        _isMicActive.value = false
        if (!_isPaused.value) {
            _isRunning.value = false
        }
        runCatching { activeRecorderRef?.stop() }
        runCatching { activeRecorderRef?.release() }
        activeRecorderRef = null
        workerThread?.interrupt()
        workerThread = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MicForegroundService"
        private const val CHANNEL_ID = "sol_mic"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.example.sol.voice.STOP_MIC"
        const val ACTION_PAUSE = "com.example.sol.voice.PAUSE_MIC"
        const val ACTION_RESUME = "com.example.sol.voice.RESUME_MIC"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _isPaused = MutableStateFlow(false)
        val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

        private val _isMicActive = MutableStateFlow(false)
        val isMicActive: StateFlow<Boolean> = _isMicActive.asStateFlow()

        private val _wakeEvents = MutableSharedFlow<Long>(extraBufferCapacity = 4)
        val wakeEvents: SharedFlow<Long> = _wakeEvents.asSharedFlow()

        fun start(context: Context): Result<Unit> {
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMic) {
                return Result.failure(SecurityException("مجوز میکروفون هنوز صادر نشده است."))
            }
            return try {
                val intent = Intent(context, MicForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Result.success(Unit)
            } catch (t: Throwable) {
                val msg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    t is ForegroundServiceStartNotAllowedException
                ) {
                    "اندروید اجازهٔ شروع سرویس پس‌زمینهٔ میکروفون را از حالت بسته نداد؛ برنامه را باز نگه دارید یا باتری Sol را روی نامحدود بگذارید."
                } else {
                    t.localizedMessage ?: "شروع سرویس شنود پس‌زمینه ناموفق بود."
                }
                Result.failure(IllegalStateException(msg, t))
            }
        }

        fun pause(context: Context) {
            if (!_isRunning.value && !_isMicActive.value) return
            _isPaused.value = true
            runCatching {
                context.startService(
                    Intent(context, MicForegroundService::class.java).setAction(ACTION_PAUSE)
                )
            }
        }

        suspend fun pauseAndAwaitRelease(context: Context, maxWaitMs: Long = 800L) {
            if (!_isRunning.value && !_isMicActive.value) return
            pause(context)
            val deadline = System.currentTimeMillis() + maxWaitMs
            while (_isMicActive.value && System.currentTimeMillis() < deadline) {
                delay(25L)
            }
        }

        fun resume(context: Context): Result<Unit> {
            _isPaused.value = false
            return start(context)
        }

        fun stop(context: Context) {
            _isPaused.value = false
            _isRunning.value = false
            _isMicActive.value = false
            runCatching {
                context.stopService(Intent(context, MicForegroundService::class.java))
            }
        }
    }
}
