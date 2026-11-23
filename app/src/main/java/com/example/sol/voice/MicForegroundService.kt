package com.example.sol.voice

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.sol.memory.PersianNormalizer
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
 * Guarantees AudioRecord and Vosk Recognizer/Model release inside `finally`.
 */
class MicForegroundService : Service() {
    private val running = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var workerThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running.set(false)
            _isRunning.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val hasMicPerm = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm) {
            Log.w(TAG, "MicForegroundService started without RECORD_AUDIO permission; stopping safely.")
            _isRunning.value = false
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
            stopSelf()
            return START_NOT_STICKY
        }

        _isRunning.value = true
        if (running.compareAndSet(false, true)) {
            startMicLoop()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startMicLoop() {
        workerThread = Thread({
            var recorder: AudioRecord? = null
            var voskModel: Model? = null
            var recognizer: Recognizer? = null
            try {
                val sampleRate = 16_000
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(2048)

                recorder = createMicServiceAudioRecord(sampleRate, minBuf * 2)
                audioRecord = recorder
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord not initialized")
                    return@Thread
                }

                val voskDir = findValidVoskDir(File(filesDir, "vosk-model-fa"))
                if (voskDir != null) {
                    voskModel = runCatching { Model(voskDir.absolutePath) }.getOrNull()
                    if (voskModel != null) {
                        val grammar = VoiceVocabularyBias().buildWakeWordGrammarJson()
                        recognizer = runCatching {
                            Recognizer(voskModel, sampleRate.toFloat(), grammar)
                        }.getOrNull()
                    }
                }

                recorder.startRecording()
                val buf = ShortArray(2048)
                var lastWakeAt = 0L

                while (running.get() && !Thread.currentThread().isInterrupted) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read <= 0) continue

                    val rec = recognizer
                    if (rec != null) {
                        val completed = rec.acceptWaveForm(buf, read)
                        val jsonStr = if (completed) rec.result else rec.partialResult
                        val key = if (completed) "text" else "partial"
                        val spoken = extractText(jsonStr, key)
                        if (spoken.isNotBlank()) {
                            val norm = PersianNormalizer.normalize(spoken)
                            val now = System.currentTimeMillis()
                            if ((norm.contains("سول") || norm.contains("sol")) && now - lastWakeAt > 4_000L) {
                                lastWakeAt = now
                                _wakeEvents.tryEmit(now)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "MicForegroundService loop error", t)
            } finally {
                running.set(false)
                _isRunning.value = false
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                audioRecord = null
                runCatching { recognizer?.close() }
                runCatching { voskModel?.close() }
                stopSelf()
            }
        }, "SolMicForegroundLoop").also { it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun createMicServiceAudioRecord(sampleRate: Int, bufferBytes: Int): AudioRecord {
        for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val candidate = AudioRecord(
                source,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                return candidate
            }
            runCatching { candidate.release() }
        }
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
    }

    private fun findValidVoskDir(root: File): File? {
        if (!root.exists()) return null
        if (File(root, "am").isDirectory && File(root, "conf").isDirectory) return root
        return root.listFiles()?.firstOrNull {
            it.isDirectory && File(it, "am").isDirectory && File(it, "conf").isDirectory
        }
    }

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
        _isRunning.value = false
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        workerThread?.interrupt()
        workerThread = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MicForegroundService"
        private const val CHANNEL_ID = "sol_mic"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.example.sol.voice.STOP_MIC"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

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
            return runCatching {
                val intent = Intent(context, MicForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            _isRunning.value = false
            runCatching {
                context.stopService(Intent(context, MicForegroundService::class.java))
            }
        }
    }
}
