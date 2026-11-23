package com.example.sol.speaker

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Cosine-similarity speaker verifier + fail-safe gate for N5 owner voice verification.
 *
 * TODO(verify): threshold must be calibrated on the target device with real owner recordings;
 * do not claim accuracy without hardware testing.
 */
class SpeakerVerifier(
    private val threshold: Float = DEFAULT_THRESHOLD,
) {
    fun isOwner(candidate: FloatArray, owner: FloatArray): Boolean {
        if (candidate.isEmpty() || owner.isEmpty()) return false
        return similarity(candidate, owner) >= threshold
    }

    fun similarity(candidate: FloatArray, owner: FloatArray): Float {
        if (candidate.isEmpty() || owner.isEmpty()) return 0f
        return cosine(candidate, owner)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = min(a.size, b.size)
        if (n == 0) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(max(na, 1e-8f) * max(nb, 1e-8f))
        return if (denom <= 0f) 0f else (dot / denom).coerceIn(-1f, 1f)
    }

    companion object {
        // TODO(verify): calibrate cosine similarity threshold on target phone with real owner voice samples.
        const val DEFAULT_THRESHOLD = 0.75f
    }
}

sealed interface SpeakerVerificationOutcome {
    data object InactiveFailSafe : SpeakerVerificationOutcome
    data class VerifiedOwner(val similarity: Float) : SpeakerVerificationOutcome
    data class RejectedNonOwner(val similarity: Float, val politeMessageFa: String) : SpeakerVerificationOutcome
}

data class SpeakerIdUiState(
    val isModelInstalled: Boolean = false,
    val isOwnerEnrolled: Boolean = false,
    val isActive: Boolean = false,
    val isRecordingOwner: Boolean = false,
    val statusLabelFa: String = "N5 غیرفعال: مدل شناسایی گوینده یا نمونهٔ صدای مالک ثبت نشده است.",
    val feedbackMessageFa: String = "",
    val feedbackIsError: Boolean = false,
)

/**
 * Coordinates N5 Speaker ID lifecycle cleanly:
 * - Fail-safe: when either the speaker ONNX model or the encrypted owner profile is absent, N5 reports inactive and never blocks commands.
 * - Active gate: when both exist, voice audio is checked via [verifyVoicePcm] before executing commands in CommandBrain.
 */
class SpeakerIdManager(
    context: Context,
    val modelFile: File = File(context.applicationContext.filesDir, "speaker/speaker_embedder.onnx"),
    val ownerProfile: OwnerProfile = OwnerProfile(context),
    val embedder: SpeakerEmbedder = SpeakerEmbedder(modelFile),
    val verifier: SpeakerVerifier = SpeakerVerifier(),
) {
    private val appContext = context.applicationContext

    @Volatile
    private var lastFeedbackFa: String = ""

    @Volatile
    private var lastFeedbackIsError: Boolean = false

    private val _uiState = MutableStateFlow(computeStatus())
    val uiState: StateFlow<SpeakerIdUiState> = _uiState.asStateFlow()

    val isActive: Boolean
        get() = embedder.isModelPresent && embedder.isReady &&
            embedder.isPreprocessingSupported && ownerProfile.exists()

    fun refreshStatus() {
        _uiState.value = computeStatus(isRecording = _uiState.value.isRecordingOwner)
    }

    private fun setFeedback(messageFa: String, isError: Boolean) {
        lastFeedbackFa = messageFa
        lastFeedbackIsError = isError
        refreshStatus()
    }

    private fun computeStatus(isRecording: Boolean = false): SpeakerIdUiState {
        val hasModel = embedder.isModelPresent
        val hasOwner = ownerProfile.exists()
        val active = hasModel && embedder.isReady &&
            embedder.isPreprocessingSupported && hasOwner
        val label = when {
            isRecording -> "در حال ضبط (لطفاً با صدای عادی صحبت کنید)…"
            active -> "✅ شناسایی صدای مالک (N5) فعال است؛ دستورات صوتی فقط برای صدای مالک اجرا می‌شوند."
            hasModel && !embedder.isPreprocessingSupported -> "N5 غیرفعال: پیش‌پردازش صوت مدل هنوز با مشخصات رسمی مدل تطبیق داده نشده است؛ احراز هویت صوتی برای جلوگیری از خطا فعال نمی‌شود."
            hasModel && !embedder.isReady -> "N5 غیرفعال: فایل مدل موجود است اما موتور ONNX آماده نیست؛ مدل را دوباره وارد کنید."
            hasModel && !hasOwner -> "N5 غیرفعال: مدل گوینده نصب است، اما هنوز نمونهٔ صدای مالک ضبط نشده است."
            !hasModel && hasOwner -> "N5 غیرفعال: نمونهٔ صدای مالک ذخیره شده، اما مدل ONNX گوینده انتخاب نشده است."
            else -> "N5 غیرفعال: مدل گوینده و نمونهٔ صدای مالک ثبت نشده‌اند (حالت Fail-safe بدون مسدودسازی)."
        }
        return SpeakerIdUiState(
            isModelInstalled = hasModel,
            isOwnerEnrolled = hasOwner,
            isActive = active,
            isRecordingOwner = isRecording,
            statusLabelFa = label,
            feedbackMessageFa = lastFeedbackFa,
            feedbackIsError = lastFeedbackIsError,
        )
    }

    /**
     * Checks voice PCM before CommandBrain execution.
     * - If N5 is inactive (missing model or enrollment), returns [SpeakerVerificationOutcome.InactiveFailSafe].
     * - If active and candidate audio does not match owner (or could not be embedded), rejects politely.
     */
    fun verifyVoicePcm(pcm16k: FloatArray?): SpeakerVerificationOutcome {
        if (!isActive) return SpeakerVerificationOutcome.InactiveFailSafe
        if (pcm16k == null || pcm16k.isEmpty()) {
            return SpeakerVerificationOutcome.RejectedNonOwner(
                similarity = 0f,
                politeMessageFa = POLITE_REJECTION_MESSAGE_FA,
            )
        }
        val ownerEmbedding = ownerProfile.load() ?: return SpeakerVerificationOutcome.InactiveFailSafe
        val candidateEmbedding = embedder.embed(pcm16k)
            ?: return SpeakerVerificationOutcome.RejectedNonOwner(
                similarity = 0f,
                politeMessageFa = POLITE_REJECTION_MESSAGE_FA,
            )
        val sim = verifier.similarity(candidateEmbedding, ownerEmbedding)
        return if (verifier.isOwner(candidateEmbedding, ownerEmbedding)) {
            SpeakerVerificationOutcome.VerifiedOwner(sim)
        } else {
            SpeakerVerificationOutcome.RejectedNonOwner(
                similarity = sim,
                politeMessageFa = POLITE_REJECTION_MESSAGE_FA,
            )
        }
    }

    suspend fun enrollOwnerFromPcm(pcm16k: FloatArray): Result<String> = withContext(Dispatchers.IO) {
        if (!embedder.isModelPresent) {
            val msg = "N5 غیرفعال است: ابتدا فایل مدل ONNX شناسایی گوینده را انتخاب کنید."
            setFeedback(msg, isError = true)
            return@withContext Result.failure(IllegalStateException(msg))
        }
        if (!embedder.isReady && !embedder.initialize()) {
            val msg = "راه‌اندازی مدل ONNX شناسایی گوینده ناموفق بود؛ N5 غیرفعال باقی ماند."
            setFeedback(msg, isError = true)
            return@withContext Result.failure(IllegalStateException(msg))
        }
        if (pcm16k.size < 8_000) {
            val msg = "صدای ضبط‌شده بسیار کوتاه بود؛ لطفاً حدود ۳ ثانیه صحبت کنید."
            setFeedback(msg, isError = true)
            return@withContext Result.failure(IllegalArgumentException(msg))
        }
        val embedding = embedder.embed(pcm16k)
            ?: run {
                val msg = "استخراج بردار صدای مالک از مدل گوینده ناموفق بود؛ مطمئن شوید مدل ONNX انتخاب‌شده مخصوص استخراج بردار صوتی گوینده است (نه Piper TTS)."
                setFeedback(msg, isError = true)
                return@withContext Result.failure(IllegalStateException(msg))
            }
        if (!ownerProfile.save(embedding)) {
            val msg = "ذخیرهٔ رمزنگاری‌شدهٔ صدای مالک ناموفق بود."
            setFeedback(msg, isError = true)
            return@withContext Result.failure(IllegalStateException(msg))
        }
        val okMsg = "صدای شما با موفقیت به‌صورت رمزنگاری‌شده در دستگاه ذخیره شد و N5 فعال گردید."
        setFeedback(okMsg, isError = false)
        Result.success(okMsg)
    }

    @SuppressLint("MissingPermission")
    suspend fun recordAndEnrollOwner(durationMs: Long = 3_000L): Result<String> = withContext(Dispatchers.IO) {
        if (!embedder.isModelPresent) {
            val msg = "N5 غیرفعال است: فایل‌های Piper (.onnx و .json) فقط برای حرف‌زدن Sol هستند؛ برای ثبت صدای مالک ابتدا فایل مدل ONNX شناسایی گوینده را انتخاب کنید."
            setFeedback(msg, isError = true)
            return@withContext Result.failure(IllegalStateException(msg))
        }
        val wasMicServiceRunning = com.example.sol.voice.MicForegroundService.isRunning.value
        if (wasMicServiceRunning) {
            com.example.sol.voice.MicForegroundService.stop(appContext)
            kotlinx.coroutines.delay(150L)
        }

        lastFeedbackFa = ""
        lastFeedbackIsError = false
        _uiState.value = computeStatus(isRecording = true)
        var recorder: AudioRecord? = null
        try {
            val capture = capturePcmSamples(durationMs) { newRecorder -> recorder = newRecorder }
            capture.fold(
                onSuccess = { samples -> enrollOwnerFromPcm(samples) },
                onFailure = { err ->
                    val errMsg = err.localizedMessage ?: "ضبط صدای مالک ناموفق بود."
                    setFeedback(errMsg, isError = true)
                    Result.failure(err)
                },
            )
        } catch (t: Throwable) {
            val msg = "خطا در ضبط صدای مالک: ${t.localizedMessage ?: "نامشخص"}"
            setFeedback(msg, isError = true)
            Result.failure(IllegalStateException(msg))
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            if (wasMicServiceRunning) {
                com.example.sol.voice.MicForegroundService.start(appContext)
            }
            _uiState.value = computeStatus(isRecording = false)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun testMicrophoneHealth(durationMs: Long = 1_500L): Result<String> = withContext(Dispatchers.IO) {
        val wasMicServiceRunning = com.example.sol.voice.MicForegroundService.isRunning.value
        if (wasMicServiceRunning) {
            com.example.sol.voice.MicForegroundService.stop(appContext)
            kotlinx.coroutines.delay(150L)
        }

        lastFeedbackFa = ""
        lastFeedbackIsError = false
        _uiState.value = computeStatus(isRecording = true)
        var recorder: AudioRecord? = null
        try {
            val capture = capturePcmSamples(durationMs) { newRecorder -> recorder = newRecorder }
            capture.fold(
                onSuccess = { samples ->
                    var sumSq = 0.0
                    for (s in samples) sumSq += s.toDouble() * s.toDouble()
                    val rms = sqrt(sumSq / samples.size.coerceAtLeast(1))
                    val pct = (rms * 500.0).toInt().coerceIn(0, 100)
                    val okMsg = "✅ میکروفون دستگاه سالم است و صدا دریافت شد (سطح صدا: $pct٪ • ${samples.size} نمونه ۱۶kHz)."
                    setFeedback(okMsg, isError = false)
                    Result.success(okMsg)
                },
                onFailure = { err ->
                    val msg = err.localizedMessage ?: "میکروفون پاسخ نداد؛ مجوز صدا و برنامهٔ پس‌زمینه را بررسی کنید."
                    setFeedback(msg, isError = true)
                    Result.failure(err)
                },
            )
        } catch (t: Throwable) {
            val msg = "تست میکروفون ناموفق بود: ${t.localizedMessage ?: "خطای سخت‌افزار"}"
            setFeedback(msg, isError = true)
            Result.failure(IllegalStateException(msg))
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            if (wasMicServiceRunning) {
                com.example.sol.voice.MicForegroundService.start(appContext)
            }
            _uiState.value = computeStatus(isRecording = false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun capturePcmSamples(
        durationMs: Long,
        onRecorderCreated: (AudioRecord) -> Unit,
    ): Result<FloatArray> {
        val sampleRate = 16_000
        val targetSamples = ((sampleRate * durationMs) / 1000L).toInt().coerceAtLeast(12_000)
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)

        val recorder = createAudioRecorder(sampleRate, minBuf * 2)
        onRecorderCreated(recorder)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            return Result.failure(IllegalStateException("آماده‌سازی میکروفون برای ضبط ممکن نشد (دسترسی میکروفون مشغول یا مسدود است)."))
        }

        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            return Result.failure(IllegalStateException("شروع ضبط میکروفون توسط سیستم قطع شد؛ برنامهٔ دیگری در حال استفاده از میکروفون است."))
        }

        val captured = FloatArray(targetSamples)
        val chunk = ShortArray(2048)
        var offset = 0
        var consecutiveReadErrors = 0
        val deadline = System.currentTimeMillis() + durationMs + 1_500L
        while (offset < targetSamples && System.currentTimeMillis() < deadline) {
            val read = recorder.read(chunk, 0, min(chunk.size, targetSamples - offset))
            if (read > 0) {
                consecutiveReadErrors = 0
                for (i in 0 until read) {
                    captured[offset + i] = chunk[i] / 32768f
                }
                offset += read
            } else if (read < 0) {
                consecutiveReadErrors++
                if (consecutiveReadErrors >= 5) {
                    return Result.failure(IllegalStateException("دریافت صدا از میکروفون متوقف شد (کد خواندن: $read)."))
                }
            }
        }
        if (offset < 8_000) {
            return Result.failure(IllegalStateException("دادهٔ صوتی کافی از میکروفون دریافت نشد."))
        }
        return Result.success(if (offset == captured.size) captured else captured.copyOf(offset))
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecorder(sampleRate: Int, bufferBytes: Int): AudioRecord {
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

    suspend fun importSpeakerModel(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        modelFile.parentFile?.mkdirs()
        val temp = File(modelFile.parentFile, "speaker_tmp_${System.nanoTime()}.onnx")
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { out -> input.copyTo(out) }
            } ?: return@withContext Result.failure(IllegalArgumentException("باز کردن فایل مدل گوینده ممکن نشد."))

            if (!temp.exists() || temp.length() == 0L) {
                val msg = "فایل مدل گوینده خالی است."
                setFeedback(msg, isError = true)
                return@withContext Result.failure(IllegalArgumentException(msg))
            }

            val testEmbedder = SpeakerEmbedder(temp)
            if (!testEmbedder.initialize()) {
                testEmbedder.release()
                val msg = "فایل انتخاب‌شده یک مدل معتبر ONNX نیست."
                setFeedback(msg, isError = true)
                return@withContext Result.failure(IllegalArgumentException(msg))
            }
            testEmbedder.release()

            embedder.release()
            if (modelFile.exists()) modelFile.delete()
            if (!temp.renameTo(modelFile)) {
                temp.copyTo(modelFile, overwrite = true)
            }
            if (!embedder.initialize()) {
                val msg = "فایل مدل کپی شد اما راه‌اندازی موتور ONNX ناموفق بود؛ N5 غیرفعال باقی ماند."
                setFeedback(msg, isError = true)
                return@withContext Result.failure(IllegalStateException(msg))
            }
            val msg = "مدل شناسایی گوینده وارد و موتور ONNX آماده شد. اکنون دکمه «ضبط صدای من» را بزنید."
            setFeedback(msg, isError = false)
            Result.success(msg)
        } catch (t: Throwable) {
            val msg = "خطا در وارد کردن مدل گوینده: ${t.localizedMessage ?: "نامشخص"}"
            setFeedback(msg, isError = true)
            Result.failure(IllegalArgumentException(msg))
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun clearOwnerProfile() {
        ownerProfile.clear()
        setFeedback("نمونهٔ صدای مالک پاک شد (N5 غیرفعال).", isError = false)
    }

    fun deleteAllSpeakerData() {
        embedder.release()
        ownerProfile.clear()
        runCatching { modelFile.delete() }
        lastFeedbackFa = ""
        lastFeedbackIsError = false
        refreshStatus()
    }

    companion object {
        const val POLITE_REJECTION_MESSAGE_FA =
            "عذرخواهی می‌کنم؛ صدای شما با صدای ثبت‌شدهٔ مالک دستگاه مطابقت نداشت. برای حفظ امنیت، این دستور اجرا نشد."
    }
}
