package com.example.sol.speaker

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.net.Uri
import androidx.core.content.ContextCompat
import com.example.sol.network.SolHttpClient
import com.example.sol.network.SolNetworkException
import com.example.sol.network.SolStorageGuard
import com.example.sol.voice.MicArbiter
import com.example.sol.voice.MicOwner
import com.example.sol.voice.SafeAudioCapture
import com.example.sol.voice.SafeAudioCaptureStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val defaultThreshold: Float
        get() = threshold

    fun isOwner(candidate: FloatArray, owner: FloatArray): Boolean =
        isOwner(candidate, owner, threshold)

    fun isOwner(candidate: FloatArray, owner: FloatArray, thresholdOverride: Float): Boolean {
        if (candidate.isEmpty() || owner.isEmpty()) return false
        return similarity(candidate, owner) >= thresholdOverride
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
        const val DEFAULT_THRESHOLD = 0.75f
    }
}

sealed interface SpeakerVerificationOutcome {
    data object InactiveFailSafe : SpeakerVerificationOutcome
    data class VerifiedOwner(val similarity: Float) : SpeakerVerificationOutcome
    data class RejectedNonOwner(val similarity: Float, val politeMessageFa: String) : SpeakerVerificationOutcome
    data class AudioUnavailable(val reasonFa: String) : SpeakerVerificationOutcome
}

data class SpeakerIdUiState(
    val isModelInstalled: Boolean = false,
    val isOwnerEnrolled: Boolean = false,
    val isActive: Boolean = false,
    val isDownloadingModel: Boolean = false,
    val downloadProgressPercent: Int = 0,
    val isRecordingOwner: Boolean = false,
    val remainingSeconds: Int = 0,
    val liveRmsLevel: Float = 0f,
    val enrollmentStepIndex: Int = 0,
    val enrollmentTotalSteps: Int = 3,
    val enrollmentPromptFa: String = "",
    val calibratedThreshold: Float = SpeakerVerifier.DEFAULT_THRESHOLD,
    val lastSimilarityScore: Float? = null,
    val lastVerificationPassed: Boolean? = null,
    val lastVerificationSummaryFa: String = "",
    val statusLabelFa: String = "N5 غیرفعال: مدل شناسایی گوینده یا نمونهٔ صدای مالک ثبت نشده است.",
    val feedbackMessageFa: String = "",
    val feedbackIsError: Boolean = false,
    val isCustomUnverifiedModel: Boolean = false,
)

/**
 * Coordinates N5 Speaker ID lifecycle cleanly:
 * - Self-contained: automatically downloads & verifies CAM++ FP32 (campplus_zh_en_fp32.onnx) when needed.
 * - Multi-sample calibration: records 3 short Persian sentences, averages L2-normalized embeddings, and calibrates threshold.
 * - Fail-safe: when either the speaker ONNX model or the encrypted owner profile is absent, N5 reports inactive and never blocks commands.
 * - Fallback-safe: when SpeechRecognizer fallback cannot provide raw 16kHz PCM, returns AudioUnavailable instead of locking out the owner.
 */
class SpeakerIdManager(
    context: Context,
    val modelFile: File = File(context.applicationContext.filesDir, "speaker/speaker_embedder.onnx"),
    val ownerProfile: OwnerProfile = OwnerProfile(context),
    val embedder: SpeakerEmbedder = SpeakerEmbedder(modelFile),
    val verifier: SpeakerVerifier = SpeakerVerifier(),
    private val micArbiter: MicArbiter? = null,
    private val httpClient: SolHttpClient = SolHttpClient(),
    private val availableBytesProvider: (File) -> Long = SolStorageGuard::readAvailableBytes,
) {
    private val appContext = context.applicationContext
    private val downloadMutex = Mutex()
    private val customTrustedShaFile: File
        get() = File(modelFile.parentFile ?: appContext.filesDir, "${modelFile.name}.custom_sha256")

    @Volatile
    private var lastFeedbackFa: String = ""

    @Volatile
    private var lastFeedbackIsError: Boolean = false

    @Volatile
    private var currentEnrollmentStep: Int = 0

    @Volatile
    private var currentEnrollmentTotal: Int = DEFAULT_ENROLLMENT_PROMPTS_FA.size

    @Volatile
    private var currentEnrollmentPromptFa: String = ""

    @Volatile
    private var lastSimilarityScore: Float? = null

    @Volatile
    private var lastVerificationPassed: Boolean? = null

    @Volatile
    private var lastVerificationSummaryFa: String = ""

    @Volatile
    private var isVerifyingAudio: Boolean = false

    private val _uiState = MutableStateFlow(computeStatus())
    val uiState: StateFlow<SpeakerIdUiState> = _uiState.asStateFlow()

    init {
        embedder.attachCustomShaProvider { readTrustedCustomSha256() }
    }

    fun isBusy(): Boolean =
        isVerifyingAudio || _uiState.value.isRecordingOwner || _uiState.value.isDownloadingModel

    fun unloadModelIfIdle() {
        if (isBusy()) return
        embedder.release()
    }

    private fun readTrustedCustomSha256(): String? {
        if (!customTrustedShaFile.exists()) return null
        return runCatching { customTrustedShaFile.readText().trim().lowercase() }
            .getOrNull()
            ?.takeIf { SolHttpClient.isValidSha256Hex(it) }
    }

    private fun hasTrustedCustomModel(): Boolean {
        val pinned = readTrustedCustomSha256() ?: return false
        return embedder.isModelPresent && embedder.currentSha256().equals(pinned, ignoreCase = true)
    }

    fun isModelCompatible(): Boolean {
        if (!embedder.isModelPresent) return false
        if (embedder.isReady) return true
        return embedder.isOfficialModelVerified() || hasTrustedCustomModel()
    }

    val isActive: Boolean
        get() = isModelCompatible() && ownerProfile.exists()

    private fun resolveActiveThreshold(): Float =
        ownerProfile.loadCalibrationMetadata()?.calibratedThreshold ?: verifier.defaultThreshold

    fun refreshStatus() {
        val current = _uiState.value
        _uiState.value = computeStatus(
            isDownloading = current.isDownloadingModel,
            downloadPercent = current.downloadProgressPercent,
            isRecording = current.isRecordingOwner,
            remainingSeconds = current.remainingSeconds,
            liveRmsLevel = current.liveRmsLevel,
        )
    }

    private fun setFeedback(messageFa: String, isError: Boolean) {
        lastFeedbackFa = messageFa
        lastFeedbackIsError = isError
        refreshStatus()
    }

    private fun computeStatus(
        isDownloading: Boolean = false,
        downloadPercent: Int = 0,
        isRecording: Boolean = false,
        remainingSeconds: Int = 0,
        liveRmsLevel: Float = 0f,
    ): SpeakerIdUiState {
        val hasCompatibleModel = isModelCompatible()
        val isCustomUnverified = hasCompatibleModel && !embedder.isOfficialModelVerified() && hasTrustedCustomModel()
        val hasOutdatedFile = embedder.isModelPresent && !hasCompatibleModel
        val hasOwner = ownerProfile.exists()
        val active = hasCompatibleModel && hasOwner
        val activeThreshold = resolveActiveThreshold()
        val calMeta = if (hasOwner) ownerProfile.loadCalibrationMetadata() else null
        val thresholdPct = kotlin.math.round(activeThreshold * 100f).toInt()
        val customNote = if (isCustomUnverified) " • مدل سفارشی (تأییدنشده ناشر)" else ""
        val label = when {
            isDownloading -> "در حال دانلود خودکار مدل شناسایی صدا (CAM++ FP32 • $downloadPercent٪)…"
            isRecording && currentEnrollmentStep > 0 ->
                "کالیبراسیون نمونهٔ $currentEnrollmentStep از $currentEnrollmentTotal (${remainingSeconds.coerceAtLeast(1)} ثانیه باقی‌مانده)…"
            isRecording -> "در حال ضبط (${remainingSeconds.coerceAtLeast(1)} ثانیه باقی‌مانده — با صدای عادی صحبت کنید)…"
            active && calMeta != null ->
                "✅ شناسایی صدای مالک (N5) فعال است (${calMeta.sampleCount} نمونه • آستانه تطابق $thresholdPct٪$customNote)."
            active -> "✅ شناسایی صدای مالک (N5) فعال است$customNote؛ دستورات صوتی فقط برای صدای مالک اجرا می‌شوند."
            hasOutdatedFile -> "نسخهٔ قبلی مدل گوینده با موتور گوشی ناسازگار است؛ با زدن «ضبط صدای من» یا دانلود، نسخهٔ سازگار CAM++ (~۲۸MB) خودکار جایگزین می‌شود."
            hasCompatibleModel && !hasOwner -> "✅ مدل گوینده آماده است$customNote؛ دکمه «ضبط صدای من» را بزنید تا N5 با ۳ جملهٔ کوتاه کالیبره شود."
            !hasCompatibleModel && hasOwner -> "نمونهٔ صدای مالک ذخیره شده؛ با اولین دانلود/ضبط، مدل سازگار CAM++ خودکار نصب می‌شود."
            else -> "آمادهٔ راه‌اندازی خودکار: با زدن «ضبط صدای من»، مدل CAM++ (~۲۸MB) خودکار دانلود و صدای شما با ۳ جملهٔ کوتاه ثبت می‌شود."
        }
        return SpeakerIdUiState(
            isModelInstalled = hasCompatibleModel,
            isOwnerEnrolled = hasOwner,
            isActive = active,
            isDownloadingModel = isDownloading,
            downloadProgressPercent = if (isDownloading) downloadPercent else 0,
            isRecordingOwner = isRecording,
            remainingSeconds = if (isRecording) remainingSeconds else 0,
            liveRmsLevel = if (isRecording) liveRmsLevel else 0f,
            enrollmentStepIndex = if (isRecording) currentEnrollmentStep else 0,
            enrollmentTotalSteps = currentEnrollmentTotal.coerceAtLeast(1),
            enrollmentPromptFa = if (isRecording) currentEnrollmentPromptFa else "",
            calibratedThreshold = activeThreshold,
            lastSimilarityScore = lastSimilarityScore,
            lastVerificationPassed = lastVerificationPassed,
            lastVerificationSummaryFa = lastVerificationSummaryFa,
            statusLabelFa = label,
            feedbackMessageFa = lastFeedbackFa,
            feedbackIsError = lastFeedbackIsError,
            isCustomUnverifiedModel = isCustomUnverified,
        )
    }

    /**
     * Checks voice PCM before CommandBrain execution.
     * - If N5 is inactive (missing model or enrollment), returns [SpeakerVerificationOutcome.InactiveFailSafe].
     * - If raw PCM audio was unavailable (e.g., Android SpeechRecognizer fallback denied concurrent AudioRecord),
     *   returns [SpeakerVerificationOutcome.AudioUnavailable] so the owner is never falsely locked out.
     * - If active and candidate audio embeds cleanly, compares against calibrated threshold.
     */
    fun verifyVoicePcm(pcm16k: FloatArray?): SpeakerVerificationOutcome {
        if (!isActive) return SpeakerVerificationOutcome.InactiveFailSafe
        if (pcm16k == null || pcm16k.isEmpty()) {
            val reason = "در حالت تشخیص گفتار سیستم، نمونهٔ صوت خام در دسترس نبود؛ فرمان با وضعیت احرازنشده اجرا شد. برای احراز دائمی N5، موتور آفلاین Vosk را فعال کنید."
            lastSimilarityScore = null
            lastVerificationPassed = null
            lastVerificationSummaryFa = reason
            refreshStatus()
            return SpeakerVerificationOutcome.AudioUnavailable(reason)
        }
        isVerifyingAudio = true
        return try {
            if (!embedder.isReady) {
                val initialized = embedder.initialize(
                    verifyOfficialHash = true,
                    expectedCustomSha256 = readTrustedCustomSha256(),
                )
                if (!initialized) return SpeakerVerificationOutcome.InactiveFailSafe
            }
            val ownerEmbedding = ownerProfile.load() ?: return SpeakerVerificationOutcome.InactiveFailSafe
            val candidateEmbedding = embedder.embed(pcm16k)
                ?: return SpeakerVerificationOutcome.AudioUnavailable(
                    "استخراج بردار صدا در این نوبت ممکن نشد؛ فرمان بدون مسدودسازی اجرا شد."
                )
            val activeThreshold = resolveActiveThreshold()
            val sim = verifier.similarity(candidateEmbedding, ownerEmbedding)
            val passed = sim >= activeThreshold
            val simPct = kotlin.math.round(sim * 100f).toInt()
            val thrPct = kotlin.math.round(activeThreshold * 100f).toInt()
            lastSimilarityScore = sim
            lastVerificationPassed = passed
            lastVerificationSummaryFa = if (passed) {
                "تطابق صدای مالک تأیید شد (شباهت $simPct٪ • آستانه $thrPct٪)"
            } else {
                "تطابق صدای مالک رد شد (شباهت $simPct٪ < آستانه $thrPct٪)"
            }
            refreshStatus()
            if (passed) {
                SpeakerVerificationOutcome.VerifiedOwner(sim)
            } else {
                SpeakerVerificationOutcome.RejectedNonOwner(
                    similarity = sim,
                    politeMessageFa = POLITE_REJECTION_MESSAGE_FA,
                )
            }
        } finally {
            isVerifyingAudio = false
        }
    }

    suspend fun enrollOwnerFromPcm(pcm16k: FloatArray): Result<String> =
        enrollOwnerFromSamples(listOf(pcm16k))

    suspend fun enrollOwnerFromSamples(samplesPcm: List<FloatArray>): Result<String> = withContext(Dispatchers.IO) {
        if (!isModelCompatible()) {
            val msg = "N5 غیرفعال است: ابتدا فایل مدل ONNX سازگار شناسایی گوینده را دریافت یا انتخاب کنید."
            setFeedback(msg, isError = true)
            return@withContext Result.failure(IllegalStateException(msg))
        }
        if (!embedder.isReady && !embedder.initialize(verifyOfficialHash = true, expectedCustomSha256 = readTrustedCustomSha256())) {
            val msg = embedder.lastErrorFa.ifBlank { "بارگذاری مدل شناسایی گوینده ناموفق بود." }
            setFeedback(msg, isError = true)
            return@withContext Result.failure(IllegalStateException(msg))
        }
        if (samplesPcm.isEmpty() || samplesPcm.any { it.size < 8_000 }) {
            val msg = "حداقل یک نمونهٔ صوتی کامل دریافت نشد؛ لطفاً جمله‌ها را واضح‌تر بیان کنید."
            setFeedback(msg, isError = true)
            return@withContext Result.failure(IllegalArgumentException(msg))
        }

        val embeddings = mutableListOf<FloatArray>()
        for (pcm in samplesPcm) {
            val emb = embedder.embed(pcm)
                ?: run {
                    val detail = embedder.lastErrorFa.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                    val msg = "استخراج بردار صدای مالک از مدل گوینده ناموفق بود$detail؛ مطمئن شوید مدل ONNX انتخاب‌شده مخصوص استخراج بردار صوتی گوینده است."
                    setFeedback(msg, isError = true)
                    return@withContext Result.failure(IllegalStateException(msg))
                }
            embeddings.add(emb)
        }

        val centroid = SpeakerEmbedder.averageEmbeddings(embeddings)
            ?: run {
                val msg = "تجمیع بردارهای صدای مالک ممکن نشد."
                setFeedback(msg, isError = true)
                return@withContext Result.failure(IllegalStateException(msg))
            }

        val selfSims = embeddings.map { verifier.similarity(it, centroid) }
        val meanSelfSim = selfSims.average().toFloat().coerceIn(0f, 1f)
        val minSelfSim = (selfSims.minOrNull() ?: meanSelfSim).coerceIn(0f, 1f)
        val calibratedThreshold = if (embeddings.size >= 2) {
            (minSelfSim - 0.12f).coerceIn(0.62f, 0.78f)
        } else {
            verifier.defaultThreshold
        }

        if (!ownerProfile.save(centroid)) {
            val msg = "ذخیرهٔ رمزنگاری‌شدهٔ صدای مالک ناموفق بود."
            setFeedback(msg, isError = true)
            return@withContext Result.failure(IllegalStateException(msg))
        }
        ownerProfile.saveCalibrationMetadata(
            OwnerCalibrationMetadata(
                sampleCount = embeddings.size,
                calibratedThreshold = calibratedThreshold,
                meanSelfSimilarity = meanSelfSim,
            )
        )

        val meanPct = kotlin.math.round(meanSelfSim * 100f).toInt()
        val thrPct = kotlin.math.round(calibratedThreshold * 100f).toInt()
        lastSimilarityScore = meanSelfSim
        lastVerificationPassed = true
        lastVerificationSummaryFa =
            "کالیبراسیون با ${embeddings.size} نمونه انجام شد (همگرایی $meanPct٪ • آستانه تطابق $thrPct٪)."

        val okMsg = if (embeddings.size >= 2) {
            "صدای شما با ${embeddings.size} جمله کالیبره و رمزنگاری شد (همگرایی $meanPct٪ • آستانه $thrPct٪). اکنون N5 فعال است."
        } else {
            "صدای شما با موفقیت به‌صورت رمزنگاری‌شده در دستگاه ذخیره شد و N5 فعال گردید."
        }
        setFeedback(okMsg, isError = false)
        Result.success(okMsg)
    }

    suspend fun ensureAndDownloadSpeakerModel(
        downloadUrl: String = SpeakerEmbedder.CAMPP_MODEL_URL,
        expectedSha256: String = SpeakerEmbedder.EXPECTED_CAMPP_SHA256,
        estimatedBytes: Long = SpeakerEmbedder.CAMPP_APPROX_BYTES,
    ): Result<String> = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            if (embedder.isModelPresent && embedder.initialize(verifyOfficialHash = true)) {
                runCatching { customTrustedShaFile.delete() }
                val okMsg = "مدل شناسایی صدا (CAM++ FP32) آماده است."
                setFeedback(okMsg, isError = false)
                return@withLock Result.success(okMsg)
            }
            // Clear stale/incompatible file (e.g., legacy int8 ConvInteger model) so clean FP32 replaces it
            embedder.release()
            runCatching { customTrustedShaFile.delete() }
            if (modelFile.exists()) {
                runCatching { modelFile.delete() }
            }

            modelFile.parentFile?.mkdirs()
            val partFile = File(modelFile.parentFile, "${modelFile.name}.part")
            try {
                lastFeedbackFa = ""
                lastFeedbackIsError = false
                _uiState.value = computeStatus(isDownloading = true, downloadPercent = 2)

                val diskCheck = SolStorageGuard.checkFreeSpace(
                    targetPath = modelFile.parentFile ?: modelFile,
                    payloadBytes = estimatedBytes,
                    availableBytesProvider = availableBytesProvider,
                )
                if (!diskCheck.hasEnoughSpace) {
                    throw SolNetworkException.InsufficientDiskSpaceException(
                        availableMb = diskCheck.availableMb,
                        requiredMb = diskCheck.requiredMb,
                    )
                }

                val candidateUrls = buildList {
                    add(downloadUrl)
                    if (downloadUrl == SpeakerEmbedder.CAMPP_MODEL_URL) {
                        add(SpeakerEmbedder.CAMPP_FALLBACK_URL)
                    }
                }
                var lastDownloadError: Throwable? = null
                var downloaded = false
                for (url in candidateUrls) {
                    try {
                        val headMeta = runCatching {
                            httpClient.preflightHeadMetadata(url, setOf("huggingface.co", "hf.co"))
                        }.getOrNull()

                        httpClient.downloadOfficialHttpsFileResult(
                            urlString = url,
                            partFile = partFile,
                            estimatedBytes = headMeta?.contentLengthBytes?.takeIf { it > 0L } ?: estimatedBytes,
                            expectedSha256Override = expectedSha256,
                            expectedBytesOverride = headMeta?.contentLengthBytes ?: -1L,
                            allowedHostSuffixes = setOf("huggingface.co", "hf.co"),
                            redirectAllowedHostSuffixes = SolHttpClient.DEFAULT_CDN_REDIRECT_HOSTS,
                            onProgress = { pct, _, _ ->
                                _uiState.value = computeStatus(
                                    isDownloading = true,
                                    downloadPercent = pct.coerceIn(5, 96),
                                )
                            },
                        )
                        downloaded = true
                        break
                    } catch (e: SolNetworkException.IntegrityMismatchException) {
                        throw e
                    } catch (e: SolNetworkException.InsufficientDiskSpaceException) {
                        throw e
                    } catch (e: Throwable) {
                        lastDownloadError = e
                    }
                }
                if (!downloaded) {
                    throw lastDownloadError ?: IllegalStateException("دانلود خودکار مدل شناسایی صدا ناموفق بود.")
                }

                val candidate = SpeakerEmbedder(partFile)
                if (!candidate.initialize(verifyOfficialHash = true)) {
                    val errFa = candidate.lastErrorFa.ifBlank { "فایل دانلودشده CAM++ معتبر نیست." }
                    candidate.release()
                    throw IllegalStateException(errFa)
                }
                candidate.release()

                embedder.release()
                if (modelFile.exists()) modelFile.delete()
                if (!partFile.renameTo(modelFile)) {
                    partFile.copyTo(modelFile, overwrite = true)
                    partFile.delete()
                }
                check(embedder.initialize(verifyOfficialHash = true)) {
                    embedder.lastErrorFa.ifBlank { "راه‌اندازی مدل شناسایی صدای CAM++ ناموفق بود." }
                }

                val okMsg = "مدل شناسایی صدای CAM++ با موفقیت دانلود و تأیید شد."
                lastFeedbackFa = okMsg
                lastFeedbackIsError = false
                _uiState.value = computeStatus(isDownloading = false, downloadPercent = 0)
                Result.success(okMsg)
            } catch (t: Throwable) {
                if (t !is SolNetworkException.TransportException && t !is SolNetworkException.CancelledException) {
                    partFile.delete()
                }
                val msg = if (t is SolNetworkException) {
                    "دانلود خودکار مدل شناسایی صدا ناموفق بود: ${t.userMessageFa}"
                } else {
                    t.localizedMessage ?: "دانلود خودکار مدل شناسایی صدای گوینده ناموفق بود."
                }
                lastFeedbackFa = msg
                lastFeedbackIsError = true
                _uiState.value = computeStatus(isDownloading = false, downloadPercent = 0)
                Result.failure(t)
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun recordAndEnrollOwner(
        durationMsPerSample: Long = DEFAULT_SAMPLE_DURATION_MS,
        promptsFa: List<String> = DEFAULT_ENROLLMENT_PROMPTS_FA,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!hasRecordAudioPermission()) {
            val msg = "مجوز میکروفون صادر نشده است؛ لطفاً ابتدا دسترسی ضبط صدا را بدهید."
            setFeedback(msg, isError = true)
            return@withContext Result.failure(SecurityException(msg))
        }

        val readyLocally = isModelCompatible() &&
            (embedder.isReady || embedder.initialize(verifyOfficialHash = true, expectedCustomSha256 = readTrustedCustomSha256()))
        if (!readyLocally) {
            val dlRes = ensureAndDownloadSpeakerModel()
            if (dlRes.isFailure) {
                return@withContext Result.failure(
                    dlRes.exceptionOrNull() ?: IllegalStateException("دانلود خودکار مدل گوینده ناموفق بود.")
                )
            }
        }

        val activePrompts = promptsFa.ifEmpty { DEFAULT_ENROLLMENT_PROMPTS_FA }
        val wasMicServiceRunning = com.example.sol.voice.MicForegroundService.isRunning.value
        var leased = false
        lastFeedbackFa = ""
        lastFeedbackIsError = false
        currentEnrollmentTotal = activePrompts.size
        currentEnrollmentStep = 1
        currentEnrollmentPromptFa = activePrompts.first()
        val sampleSeconds = ((durationMsPerSample + 999L) / 1000L).toInt().coerceAtLeast(1)
        _uiState.value = computeStatus(isRecording = true, remainingSeconds = sampleSeconds, liveRmsLevel = 0f)
        var recorderRef: AudioRecord? = null
        try {
            if (micArbiter != null) {
                micArbiter.acquire(MicOwner.OWNER_ENROLLMENT)
                leased = true
            } else if (wasMicServiceRunning) {
                com.example.sol.voice.MicForegroundService.pauseAndAwaitRelease(appContext)
            }

            val capturedSamples = mutableListOf<FloatArray>()
            for ((idx, prompt) in activePrompts.withIndex()) {
                currentEnrollmentStep = idx + 1
                currentEnrollmentPromptFa = prompt
                _uiState.value = computeStatus(isRecording = true, remainingSeconds = sampleSeconds, liveRmsLevel = 0f)

                val capture = capturePcmSamples(
                    durationMs = durationMsPerSample,
                    onRecorderSwap = { recorderRef = it },
                    onTelemetry = { remSec, rmsNorm ->
                        _uiState.value = computeStatus(
                            isRecording = true,
                            remainingSeconds = remSec,
                            liveRmsLevel = rmsNorm,
                        )
                    },
                )
                val samples = capture.getOrElse { err ->
                    val errMsg = err.localizedMessage ?: "ضبط نمونهٔ ${idx + 1} صدای مالک ناموفق بود."
                    setFeedback(errMsg, isError = true)
                    return@withContext Result.failure(err)
                }
                val meanRms = computeMeanRms(samples)
                if (meanRms < MIN_ENROLLMENT_MEAN_RMS) {
                    val quietMsg = "صدای جملهٔ ${idx + 1} بسیار ضعیف یا ساکت بود؛ لطفاً نزدیک‌تر و واضح‌تر صحبت کنید."
                    setFeedback(quietMsg, isError = true)
                    return@withContext Result.failure(IllegalStateException(quietMsg))
                }
                capturedSamples.add(samples)

                if (idx < activePrompts.lastIndex) {
                    currentEnrollmentPromptFa = "نمونهٔ ${idx + 1} با موفقیت ثبت شد؛ آمادهٔ جملهٔ بعدی باشید…"
                    _uiState.value = computeStatus(isRecording = true, remainingSeconds = 1, liveRmsLevel = 0f)
                    delay(650L)
                }
            }

            enrollOwnerFromSamples(capturedSamples)
        } catch (t: Throwable) {
            val msg = "خطا در ضبط صدای مالک: ${t.localizedMessage ?: "نامشخص"}"
            setFeedback(msg, isError = true)
            Result.failure(IllegalStateException(msg))
        } finally {
            currentEnrollmentStep = 0
            currentEnrollmentPromptFa = ""
            runCatching { recorderRef?.stop() }
            runCatching { recorderRef?.release() }
            withContext(NonCancellable) {
                if (leased) {
                    micArbiter?.release(MicOwner.OWNER_ENROLLMENT)
                } else if (wasMicServiceRunning) {
                    com.example.sol.voice.MicForegroundService.resume(appContext)
                }
            }
            _uiState.value = computeStatus(isRecording = false, remainingSeconds = 0, liveRmsLevel = 0f)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun testOwnerVoiceVerification(durationMs: Long = DEFAULT_TEST_DURATION_MS): Result<Float> =
        withContext(Dispatchers.IO) {
            if (!hasRecordAudioPermission()) {
                val msg = "مجوز میکروفون صادر نشده است؛ تست تطابق صدا ممکن نیست."
                setFeedback(msg, isError = true)
                return@withContext Result.failure(SecurityException(msg))
            }
            if (!isActive) {
                val msg = "ابتدا صدای مالک (N5) را با «ضبط صدای من» ثبت کنید."
                setFeedback(msg, isError = true)
                return@withContext Result.failure(IllegalStateException(msg))
            }
            if (!embedder.isReady && !embedder.initialize(verifyOfficialHash = true, expectedCustomSha256 = readTrustedCustomSha256())) {
                val msg = embedder.lastErrorFa.ifBlank { "بارگذاری مدل CAM++ ناموفق بود." }
                setFeedback(msg, isError = true)
                return@withContext Result.failure(IllegalStateException(msg))
            }
            val ownerEmbedding = ownerProfile.load()
                ?: run {
                    val msg = "پروفایل رمزنگاری‌شدهٔ صدای مالک یافت نشد."
                    setFeedback(msg, isError = true)
                    return@withContext Result.failure(IllegalStateException(msg))
                }

            val wasMicServiceRunning = com.example.sol.voice.MicForegroundService.isRunning.value
            var leased = false
            lastFeedbackFa = ""
            lastFeedbackIsError = false
            currentEnrollmentStep = 0
            currentEnrollmentPromptFa = DEFAULT_TEST_PROMPT_FA
            val testSec = ((durationMs + 999L) / 1000L).toInt().coerceAtLeast(1)
            _uiState.value = computeStatus(isRecording = true, remainingSeconds = testSec, liveRmsLevel = 0f)
            var recorderRef: AudioRecord? = null
            try {
                if (micArbiter != null) {
                    micArbiter.acquire(MicOwner.MIC_HEALTH_TEST)
                    leased = true
                } else if (wasMicServiceRunning) {
                    com.example.sol.voice.MicForegroundService.pauseAndAwaitRelease(appContext)
                }

                val capture = capturePcmSamples(
                    durationMs = durationMs,
                    onRecorderSwap = { recorderRef = it },
                    onTelemetry = { remSec, rmsNorm ->
                        _uiState.value = computeStatus(
                            isRecording = true,
                            remainingSeconds = remSec,
                            liveRmsLevel = rmsNorm,
                        )
                    },
                )
                val samples = capture.getOrElse { err ->
                    val msg = err.localizedMessage ?: "ضبط صوت برای تست تطابق ناموفق بود."
                    setFeedback(msg, isError = true)
                    return@withContext Result.failure(err)
                }
                if (computeMeanRms(samples) < MIN_ENROLLMENT_MEAN_RMS) {
                    val msg = "صدای دریافت‌شده بسیار ضعیف بود؛ لطفاً یک جمله با صدای عادی بگویید."
                    setFeedback(msg, isError = true)
                    return@withContext Result.failure(IllegalStateException(msg))
                }
                val candidateEmbedding = embedder.embed(samples)
                    ?: run {
                        val msg = embedder.lastErrorFa.ifBlank { "استخراج بردار صدا در تست ناموفق بود." }
                        setFeedback(msg, isError = true)
                        return@withContext Result.failure(IllegalStateException(msg))
                    }

                val activeThreshold = resolveActiveThreshold()
                val sim = verifier.similarity(candidateEmbedding, ownerEmbedding)
                val passed = sim >= activeThreshold
                val simPct = kotlin.math.round(sim * 100f).toInt()
                val thrPct = kotlin.math.round(activeThreshold * 100f).toInt()
                lastSimilarityScore = sim
                lastVerificationPassed = passed
                lastVerificationSummaryFa = if (passed) {
                    "✅ تأیید شد • میزان شباهت: $simPct٪ (حداقل مجاز: $thrPct٪)"
                } else {
                    "⚠️ مطابقت نداشت • میزان شباهت: $simPct٪ (حداقل مجاز: $thrPct٪)"
                }
                val feedback = if (passed) {
                    "✅ صدای شما با پروفایل مالک تطابق داشت (شباهت $simPct٪ • آستانه $thrPct٪)."
                } else {
                    "⚠️ شباهت صدا ($simPct٪) کمتر از آستانهٔ کالیبره‌شده ($thrPct٪) بود؛ در صورت تغییر محیط یا میکروفون، ۳ جملهٔ مالک را دوباره ثبت کنید."
                }
                setFeedback(feedback, isError = !passed)
                Result.success(sim)
            } catch (t: Throwable) {
                val msg = "خطا در تست تطابق صدا: ${t.localizedMessage ?: "نامشخص"}"
                setFeedback(msg, isError = true)
                Result.failure(IllegalStateException(msg))
            } finally {
                currentEnrollmentPromptFa = ""
                runCatching { recorderRef?.stop() }
                runCatching { recorderRef?.release() }
                withContext(NonCancellable) {
                    if (leased) {
                        micArbiter?.release(MicOwner.MIC_HEALTH_TEST)
                    } else if (wasMicServiceRunning) {
                        com.example.sol.voice.MicForegroundService.resume(appContext)
                    }
                }
                _uiState.value = computeStatus(isRecording = false, remainingSeconds = 0, liveRmsLevel = 0f)
            }
        }

    @SuppressLint("MissingPermission")
    suspend fun testMicrophoneHealth(durationMs: Long = 1_500L): Result<String> = withContext(Dispatchers.IO) {
        if (!hasRecordAudioPermission()) {
            val msg = "مجوز میکروفون صادر نشده است؛ آزمایش میکروفون ممکن نیست."
            setFeedback(msg, isError = true)
            return@withContext Result.failure(SecurityException(msg))
        }
        val wasMicServiceRunning = com.example.sol.voice.MicForegroundService.isRunning.value
        var leased = false
        lastFeedbackFa = ""
        lastFeedbackIsError = false
        _uiState.value = computeStatus(isRecording = true, remainingSeconds = 2, liveRmsLevel = 0f)
        var recorderRef: AudioRecord? = null
        try {
            if (micArbiter != null) {
                micArbiter.acquire(MicOwner.MIC_HEALTH_TEST)
                leased = true
            } else if (wasMicServiceRunning) {
                com.example.sol.voice.MicForegroundService.pauseAndAwaitRelease(appContext)
            }

            val capture = capturePcmSamples(
                durationMs = durationMs,
                onRecorderSwap = { recorderRef = it },
                onTelemetry = { remSec, rmsNorm ->
                    _uiState.value = computeStatus(
                        isRecording = true,
                        remainingSeconds = remSec,
                        liveRmsLevel = rmsNorm,
                    )
                },
            )
            capture.fold(
                onSuccess = { samples ->
                    val rms = computeMeanRms(samples)
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
            runCatching { recorderRef?.stop() }
            runCatching { recorderRef?.release() }
            withContext(NonCancellable) {
                if (leased) {
                    micArbiter?.release(MicOwner.MIC_HEALTH_TEST)
                } else if (wasMicServiceRunning) {
                    com.example.sol.voice.MicForegroundService.resume(appContext)
                }
            }
            _uiState.value = computeStatus(isRecording = false, remainingSeconds = 0, liveRmsLevel = 0f)
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun capturePcmSamples(
        durationMs: Long,
        onRecorderSwap: (AudioRecord?) -> Unit,
        onTelemetry: (remainingSec: Int, rmsNorm: Float) -> Unit,
    ): Result<FloatArray> {
        val sampleRate = 16_000
        val targetSamples = ((sampleRate * durationMs) / 1000L).toInt().coerceAtLeast(12_000)
        val captured = FloatArray(targetSamples)
        var offset = 0
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + durationMs + 1_500L
        var lastTelemetryAt = 0L

        val stopOutcome = SafeAudioCapture.runCaptureLoop(
            sampleRate = sampleRate,
            chunkSize = 2048,
            shouldContinue = { offset < targetSamples && System.currentTimeMillis() < deadline },
            deadlineMs = deadline,
            onRecorderSwap = onRecorderSwap,
            onSamples = { chunk, read ->
                val toCopy = min(read, targetSamples - offset)
                var sumSq = 0.0
                for (i in 0 until toCopy) {
                    val f = chunk[i] / 32768f
                    captured[offset + i] = f
                    sumSq += f.toDouble() * f.toDouble()
                }
                offset += toCopy

                val now = System.currentTimeMillis()
                if (now - lastTelemetryAt >= 100L) {
                    lastTelemetryAt = now
                    val elapsedMs = now - startedAt
                    val remSec = (((durationMs - elapsedMs).coerceAtLeast(0L) + 999L) / 1000L).toInt()
                    val chunkRms = sqrt(sumSq / toCopy.coerceAtLeast(1))
                    val rmsNorm = (chunkRms * 5.0).toFloat().coerceIn(0f, 1f)
                    onTelemetry(remSec, rmsNorm)
                }
                offset < targetSamples
            },
        )
        if (stopOutcome is SafeAudioCaptureStop.Failed) {
            return Result.failure(IllegalStateException(stopOutcome.messageFa))
        }
        if (offset < 8_000) {
            return Result.failure(IllegalStateException("دادهٔ صوتی کافی از میکروفون دریافت نشد."))
        }
        return Result.success(if (offset == captured.size) captured else captured.copyOf(offset))
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
            if (!testEmbedder.initialize(verifyOfficialHash = false)) {
                val initErr = testEmbedder.lastErrorFa
                testEmbedder.release()
                val msg = initErr.ifBlank { "فایل انتخاب‌شده یک مدل معتبر ONNX نیست." }
                setFeedback(msg, isError = true)
                return@withContext Result.failure(IllegalArgumentException(msg))
            }
            val importedSha = testEmbedder.currentSha256()
            testEmbedder.release()

            embedder.release()
            if (modelFile.exists()) modelFile.delete()
            if (!temp.renameTo(modelFile)) {
                temp.copyTo(modelFile, overwrite = true)
            }
            if (importedSha.equals(SpeakerEmbedder.EXPECTED_CAMPP_SHA256, ignoreCase = true)) {
                runCatching { customTrustedShaFile.delete() }
            } else if (SolHttpClient.isValidSha256Hex(importedSha)) {
                runCatching { customTrustedShaFile.writeText(importedSha) }
            }
            val msg = "مدل شناسایی گوینده وارد شد. اکنون دکمه «ضبط صدای من» را بزنید تا N5 فعال شود."
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
        lastSimilarityScore = null
        lastVerificationPassed = null
        lastVerificationSummaryFa = ""
        setFeedback("نمونهٔ صدای مالک پاک شد (N5 غیرفعال).", isError = false)
    }

    fun deleteAllSpeakerData() {
        embedder.release()
        ownerProfile.clear()
        runCatching { modelFile.delete() }
        runCatching { customTrustedShaFile.delete() }
        lastFeedbackFa = ""
        lastFeedbackIsError = false
        lastSimilarityScore = null
        lastVerificationPassed = null
        lastVerificationSummaryFa = ""
        refreshStatus()
    }

    companion object {
        const val DEFAULT_SAMPLE_DURATION_MS = 2_800L
        const val DEFAULT_TEST_DURATION_MS = 2_800L
        const val MIN_ENROLLMENT_MEAN_RMS = 0.005f

        val DEFAULT_ENROLLMENT_PROMPTS_FA = listOf(
            "سلام سول، امروز حال و هوا چطوره و برنامه‌های من چیه؟",
            "ساعت و تاریخ دقیق امروز رو بگو و یادآورهای مهم رو چک کن.",
            "فقط با صدای من دستورات خصوصی رو اجرا کن و حریم شخصی رو نگه دار.",
        )

        const val DEFAULT_TEST_PROMPT_FA =
            "جملهٔ آزمایشی: «سلام سول، ساعت چنده و امروز چه خبره؟»"

        const val POLITE_REJECTION_MESSAGE_FA =
            "عذرخواهی می‌کنم؛ صدای شما با صدای ثبت‌شدهٔ مالک دستگاه مطابقت نداشت. برای حفظ امنیت، این دستور اجرا نشد."

        fun computeMeanRms(samples: FloatArray): Float {
            if (samples.isEmpty()) return 0f
            var sumSq = 0.0
            for (s in samples) {
                sumSq += s.toDouble() * s.toDouble()
            }
            return sqrt(sumSq / samples.size).toFloat()
        }
    }
}
