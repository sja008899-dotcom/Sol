package com.example.sol.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

enum class SafeReadAction {
    SAMPLES,
    ZERO_WAIT_CONTINUE,
    REBUILD_RECORDER,
    FAIL_SILENCED_BY_SYSTEM,
    FAIL_ALL_ZERO_AUDIO,
    FAIL_TIMEOUT,
    FAIL_READ_ERROR,
}

/**
 * Pure-Kotlin state tracker for SafeAudioCapture read loops so behavior is deterministic and unit-testable.
 */
class SafeAudioReadTracker(
    private val maxRebuildAttempts: Int = 1,
    private val allZeroFailureThresholdMs: Long = 2_000L,
    private val zeroReadTimeoutMs: Long = 2_500L,
) {
    private var rebuildsUsed = 0
    private var consecutiveZeroStartedAtMs = -1L
    private var continuousAllZeroStartedAtMs = -1L

    fun onReadResult(
        readCount: Int,
        samples: ShortArray,
        nowMs: Long,
        isSilencedBySystem: Boolean,
    ): SafeReadAction {
        if (isSilencedBySystem) {
            return SafeReadAction.FAIL_SILENCED_BY_SYSTEM
        }

        if (readCount < 0) {
            return if (rebuildsUsed < maxRebuildAttempts) {
                rebuildsUsed++
                consecutiveZeroStartedAtMs = -1L
                continuousAllZeroStartedAtMs = -1L
                SafeReadAction.REBUILD_RECORDER
            } else {
                SafeReadAction.FAIL_READ_ERROR
            }
        }

        if (readCount == 0) {
            if (consecutiveZeroStartedAtMs < 0L) {
                consecutiveZeroStartedAtMs = nowMs
            }
            return if (nowMs - consecutiveZeroStartedAtMs >= zeroReadTimeoutMs) {
                SafeReadAction.FAIL_TIMEOUT
            } else {
                SafeReadAction.ZERO_WAIT_CONTINUE
            }
        }

        consecutiveZeroStartedAtMs = -1L
        val nonZeroDetected = hasNonZeroSignal(samples, readCount)
        if (!nonZeroDetected) {
            if (continuousAllZeroStartedAtMs < 0L) {
                continuousAllZeroStartedAtMs = nowMs
            }
            if (nowMs - continuousAllZeroStartedAtMs >= allZeroFailureThresholdMs) {
                return SafeReadAction.FAIL_ALL_ZERO_AUDIO
            }
        } else {
            continuousAllZeroStartedAtMs = -1L
        }
        return SafeReadAction.SAMPLES
    }

    companion object {
        fun hasNonZeroSignal(samples: ShortArray, count: Int): Boolean {
            val safeCount = count.coerceAtMost(samples.size)
            for (i in 0 until safeCount) {
                if (abs(samples[i].toInt()) > 0) return true
            }
            return false
        }
    }
}

sealed interface SafeAudioCaptureStop {
    data object StoppedNormally : SafeAudioCaptureStop
    data class Failed(val messageFa: String) : SafeAudioCaptureStop
}

object SafeAudioCapture {
    const val SILENCED_MESSAGE_FA = "میکروفون توسط سیستم یا برنامهٔ دیگری مسدود شده است."
    private const val READ_RETRY_SLEEP_MS = 15L

    @SuppressLint("MissingPermission")
    fun createAudioRecorder(
        sampleRate: Int = 16_000,
        bufferBytes: Int,
    ): AudioRecord {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (source in sources) {
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

    /**
     * Captures 16 kHz mono PCM frames cleanly with:
     * - API 29+ AudioRecordingCallback silencing checks
     * - 1x AudioRecord rebuild on negative read error
     * - 15ms backoff on read == 0 + deadline check
     * - 2-second continuous digital zero-silence detector
     */
    @SuppressLint("MissingPermission")
    fun runCaptureLoop(
        sampleRate: Int = 16_000,
        chunkSize: Int = 2048,
        shouldContinue: () -> Boolean,
        deadlineMs: Long? = null,
        onRecorderSwap: (AudioRecord?) -> Unit = {},
        onSamples: (buffer: ShortArray, readCount: Int) -> Boolean,
    ): SafeAudioCaptureStop {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val bufferBytes = minBuf * 2

        val silencedBySystem = AtomicBoolean(false)
        var recorder: AudioRecord? = null
        var recordingCallback: Any? = null
        val mainHandler = Handler(Looper.getMainLooper())

        fun attachAndStart(): Result<AudioRecord> {
            val candidate = createAudioRecorder(sampleRate, bufferBytes)
            if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { candidate.release() }
                return Result.failure(
                    IllegalStateException("آماده‌سازی میکروفون ممکن نشد (دسترسی میکروفون مشغول یا مسدود است).")
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cb = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                        val mine = configs.firstOrNull { it.clientAudioSessionId == candidate.audioSessionId }
                        if (mine != null && mine.isClientSilenced) {
                            silencedBySystem.set(true)
                        }
                    }
                }
                recordingCallback = cb
                runCatching { candidate.registerAudioRecordingCallback({ r -> mainHandler.post(r) }, cb) }
            }
            candidate.startRecording()
            if (candidate.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    (recordingCallback as? AudioManager.AudioRecordingCallback)?.let { cb ->
                        runCatching { candidate.unregisterAudioRecordingCallback(cb) }
                    }
                }
                runCatching { candidate.stop() }
                runCatching { candidate.release() }
                return Result.failure(
                    IllegalStateException("شروع ضبط میکروفون توسط سیستم قطع شد؛ برنامهٔ دیگری در حال استفاده از میکروفون است.")
                )
            }
            recorder = candidate
            onRecorderSwap(candidate)
            return Result.success(candidate)
        }

        fun releaseCurrent() {
            val current = recorder
            recorder = null
            onRecorderSwap(null)
            if (current != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    (recordingCallback as? AudioManager.AudioRecordingCallback)?.let { cb ->
                        runCatching { current.unregisterAudioRecordingCallback(cb) }
                    }
                }
                recordingCallback = null
                runCatching { current.stop() }
                runCatching { current.release() }
            }
        }

        val initial = attachAndStart()
        if (initial.isFailure) {
            return SafeAudioCaptureStop.Failed(
                initial.exceptionOrNull()?.localizedMessage ?: SILENCED_MESSAGE_FA
            )
        }

        val tracker = SafeAudioReadTracker()
        val buf = ShortArray(chunkSize)
        return try {
            while (shouldContinue() && !Thread.currentThread().isInterrupted) {
                val now = System.currentTimeMillis()
                if (deadlineMs != null && now >= deadlineMs) {
                    break
                }
                val activeRecorder = recorder ?: break
                val isApiSilenced = silencedBySystem.get() ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        runCatching { activeRecorder.activeRecordingConfiguration?.isClientSilenced == true }
                            .getOrDefault(false))

                val read = activeRecorder.read(buf, 0, buf.size)
                when (
                    tracker.onReadResult(
                        readCount = read,
                        samples = buf,
                        nowMs = System.currentTimeMillis(),
                        isSilencedBySystem = isApiSilenced,
                    )
                ) {
                    SafeReadAction.SAMPLES -> {
                        val keepGoing = onSamples(buf, read)
                        if (!keepGoing) break
                    }
                    SafeReadAction.ZERO_WAIT_CONTINUE -> {
                        try {
                            Thread.sleep(READ_RETRY_SLEEP_MS)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    SafeReadAction.REBUILD_RECORDER -> {
                        releaseCurrent()
                        try {
                            Thread.sleep(60L)
                        } catch (_: InterruptedException) {
                            break
                        }
                        val rebuilt = attachAndStart()
                        if (rebuilt.isFailure) {
                            return SafeAudioCaptureStop.Failed(SILENCED_MESSAGE_FA)
                        }
                    }
                    SafeReadAction.FAIL_SILENCED_BY_SYSTEM,
                    SafeReadAction.FAIL_ALL_ZERO_AUDIO -> {
                        return SafeAudioCaptureStop.Failed(SILENCED_MESSAGE_FA)
                    }
                    SafeReadAction.FAIL_TIMEOUT,
                    SafeReadAction.FAIL_READ_ERROR -> {
                        return SafeAudioCaptureStop.Failed("دریافت صدا از میکروفون متوقف شد (کد خواندن: $read).")
                    }
                }
            }
            SafeAudioCaptureStop.StoppedNormally
        } finally {
            releaseCurrent()
        }
    }
}
