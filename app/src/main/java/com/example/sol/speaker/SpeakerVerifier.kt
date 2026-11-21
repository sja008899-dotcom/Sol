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

    private val _uiState = MutableStateFlow(computeStatus())
    val uiState: StateFlow<SpeakerIdUiState> = _uiState.asStateFlow()

    val isActive: Boolean
        get() = embedder.isModelPresent && ownerProfile.exists()

    fun refreshStatus() {
        _uiState.value = computeStatus(isRecording = _uiState.value.isRecordingOwner)
    }

    private fun computeStatus(isRecording: Boolean = false): SpeakerIdUiState {
        val hasModel = embedder.isModelPresent
        val hasOwner = ownerProfile.exists()
        val active = hasModel && hasOwner
        val label = when {
            isRecording -> "در حال ضبط نمونهٔ صدای مالک (۳ ثانیه صحبت کنید)…"
            active -> "✅ شناسایی صدای مالک (N5) فعال است؛ دستورات صوتی فقط برای صدای مالک اجرا می‌شوند."
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
            return@withContext Result.failure(
                IllegalStateException("N5 غیرفعال است: ابتدا فایل مدل ONNX شناسایی گوینده را انتخاب کنید.")
            )
        }
        if (pcm16k.size < 8_000) {
            return@withContext Result.failure(
                IllegalArgumentException("صدای ضبط‌شده بسیار کوتاه بود؛ لطفاً حدود ۳ ثانیه صحبت کنید.")
            )
        }
        val embedding = embedder.embed(pcm16k)
            ?: return@withContext Result.failure(
                IllegalStateException("استخراج بردار صدای مالک از مدل ناموفق بود.")
            )
        if (!ownerProfile.save(embedding)) {
            return@withContext Result.failure(
                IllegalStateException("ذخیرهٔ رمزنگاری‌شدهٔ صدای مالک ناموفق بود.")
            )
        }
        refreshStatus()
        Result.success("صدای شما با موفقیت به‌صورت رمزنگاری‌شده در دستگاه ذخیره شد و N5 فعال گردید.")
    }

    @SuppressLint("MissingPermission")
    suspend fun recordAndEnrollOwner(durationMs: Long = 3_000L): Result<String> = withContext(Dispatchers.IO) {
        if (!embedder.isModelPresent) {
            return@withContext Result.failure(
                IllegalStateException("N5 غیرفعال است: برای ثبت صدای مالک، ابتدا مدل ONNX گوینده را انتخاب کنید.")
            )
        }
        _uiState.value = computeStatus(isRecording = true)
        var recorder: AudioRecord? = null
        try {
            val sampleRate = 16_000
            val targetSamples = ((sampleRate * durationMs) / 1000L).toInt().coerceAtLeast(16_000)
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(4096)

            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext Result.failure(IllegalStateException("آماده‌سازی میکروفون برای ضبط صدای مالک ممکن نشد."))
            }

            recorder.startRecording()
            val captured = FloatArray(targetSamples)
            val chunk = ShortArray(2048)
            var offset = 0
            val deadline = System.currentTimeMillis() + durationMs + 1_500L
            while (offset < targetSamples && System.currentTimeMillis() < deadline) {
                val read = recorder.read(chunk, 0, min(chunk.size, targetSamples - offset))
                if (read > 0) {
                    for (i in 0 until read) {
                        captured[offset + i] = chunk[i] / 32768f
                    }
                    offset += read
                }
            }
            if (offset < 8_000) {
                return@withContext Result.failure(IllegalStateException("دادهٔ صوتی کافی دریافت نشد."))
            }
            val finalSamples = if (offset == captured.size) captured else captured.copyOf(offset)
            enrollOwnerFromPcm(finalSamples)
        } catch (t: Throwable) {
            Result.failure(IllegalStateException("خطا در ضبط صدای مالک: ${t.localizedMessage ?: "نامشخص"}"))
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            _uiState.value = computeStatus(isRecording = false)
        }
    }

    suspend fun importSpeakerModel(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        modelFile.parentFile?.mkdirs()
        val temp = File(modelFile.parentFile, "speaker_tmp_${System.nanoTime()}.onnx")
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { out -> input.copyTo(out) }
            } ?: return@withContext Result.failure(IllegalArgumentException("باز کردن فایل مدل گوینده ممکن نشد."))

            if (!temp.exists() || temp.length() == 0L) {
                return@withContext Result.failure(IllegalArgumentException("فایل مدل گوینده خالی است."))
            }

            val testEmbedder = SpeakerEmbedder(temp)
            if (!testEmbedder.initialize()) {
                testEmbedder.release()
                return@withContext Result.failure(IllegalArgumentException("فایل انتخاب‌شده یک مدل معتبر ONNX نیست."))
            }
            testEmbedder.release()

            embedder.release()
            if (modelFile.exists()) modelFile.delete()
            if (!temp.renameTo(modelFile)) {
                temp.copyTo(modelFile, overwrite = true)
            }
            refreshStatus()
            Result.success("مدل شناسایی گوینده وارد شد. اکنون دکمه «ضبط صدای من» را بزنید تا N5 فعال شود.")
        } catch (t: Throwable) {
            Result.failure(IllegalArgumentException("خطا در وارد کردن مدل گوینده: ${t.localizedMessage ?: "نامشخص"}"))
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun clearOwnerProfile() {
        ownerProfile.clear()
        refreshStatus()
    }

    fun deleteAllSpeakerData() {
        embedder.release()
        ownerProfile.clear()
        runCatching { modelFile.delete() }
        refreshStatus()
    }

    companion object {
        const val POLITE_REJECTION_MESSAGE_FA =
            "عذرخواهی می‌کنم؛ صدای شما با صدای ثبت‌شدهٔ مالک دستگاه مطابقت نداشت. برای حفظ امنیت، این دستور اجرا نشد."
    }
}
