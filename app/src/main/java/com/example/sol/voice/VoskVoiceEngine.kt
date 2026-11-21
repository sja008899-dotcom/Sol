package com.example.sol.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.example.sol.memory.PersianNormalizer
import com.example.sol.network.SolHttpClient
import com.example.sol.network.SolNetworkException
import com.example.sol.network.SolStorageGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

sealed interface VoskModelStatus {
    data object NotInstalled : VoskModelStatus
    data class Downloading(val progressPercent: Int, val stageFa: String) : VoskModelStatus
    data class Installed(val modelSizeMb: Int, val isLoadedInRam: Boolean, val lastNoteFa: String) : VoskModelStatus
    data class Error(val messageFa: String) : VoskModelStatus
}

enum class EndPointingAction {
    CONTINUE,
    FINALIZE_SPEECH,
    TIMEOUT_NO_SPEECH,
    WAKE_TIMEOUT,
}

class VoiceVocabularyBias {
    @Volatile
    private var domainTerms: List<String> = emptyList()

    fun updateTerms(terms: List<String>) {
        domainTerms = terms
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .take(150)
    }

    fun currentTermsCount(): Int = domainTerms.size

    fun buildConfirmationGrammarJson(): String =
        "[\"تأیید\", \"تایید\", \"بله\", \"آره\", \"انجام بده\", \"بفرست\", \"لغو\", \"نه\", \"خیر\", \"کنسل\", \"متوقف\", \"[unk]\"]"

    fun buildWakeWordGrammarJson(): String =
        "[\"سول\", \"سلام سول\", \"sol\", \"های سول\", \"[unk]\"]"

    /**
     * Post-processes ASR transcript using known Contacts, Locations, Aliases & Books
     * so domain-specific Persian names (e.g. شرکت کارنو صنعت ماکیان) snap cleanly.
     */
    fun applyBiasCorrection(rawTranscript: String): String {
        var result = PersianNormalizer.cleanText(rawTranscript)
        if (result.isBlank() || domainTerms.isEmpty()) return result

        val normInput = PersianNormalizer.normalize(result)
        for (term in domainTerms) {
            val normTerm = PersianNormalizer.normalize(term)
            if (normTerm.length < 4) continue
            val collapsedTerm = normTerm.replace(" ", "")
            val collapsedInput = normInput.replace(" ", "")
            if (collapsedInput.contains(collapsedTerm) && !normInput.contains(normTerm)) {
                // Reconstruct matched spaced variant back to canonical user term
                val words = normTerm.split(' ')
                if (words.size >= 2) {
                    val loosePattern = Regex(words.joinToString("\\s*") { Regex.escape(it) })
                    result = loosePattern.replace(result, term)
                }
            }
        }
        return result
    }
}

class VoskVoiceEngine(
    private val context: Context,
    private val httpClient: SolHttpClient = SolHttpClient(),
    private val availableBytesProvider: (File) -> Long = SolStorageGuard::readAvailableBytes,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    val vocabularyBias = VoiceVocabularyBias()

    private val modelRootDir: File
        get() = File(context.filesDir, MODEL_DIR_NAME)

    private val _modelStatus = MutableStateFlow<VoskModelStatus>(VoskModelStatus.NotInstalled)
    val modelStatus: StateFlow<VoskModelStatus> = _modelStatus.asStateFlow()

    @Volatile
    private var voskModel: Model? = null

    @Volatile
    private var activeListeningJob: Job? = null

    @Volatile
    private var autoUnloadJob: Job? = null

    private val isRecording = AtomicBoolean(false)

    init {
        runCatching { LibVosk.setLogLevel(LogLevel.WARNINGS) }
        refreshInstalledStatus()
    }

    fun isModelInstalled(): Boolean = findValidModelDir() != null

    fun isModelLoadedInRam(): Boolean = voskModel != null

    fun refreshInstalledStatus(note: String = "مدل آفلاین فارسی آماده اجراست") {
        val dir = findValidModelDir()
        if (dir != null) {
            val sizeMb = (dirSizeBytes(dir) / (1024L * 1024L)).toInt().coerceAtLeast(1)
            _modelStatus.value = VoskModelStatus.Installed(
                modelSizeMb = sizeMb,
                isLoadedInRam = voskModel != null,
                lastNoteFa = note,
            )
        } else if (_modelStatus.value !is VoskModelStatus.Downloading) {
            _modelStatus.value = VoskModelStatus.NotInstalled
        }
    }

    fun unloadModelFromRam(reason: String = "آزادسازی خودکار RAM هنگام بیکاری") {
        if (isRecording.get()) return
        autoUnloadJob?.cancel()
        runCatching { voskModel?.close() }
        voskModel = null
        refreshInstalledStatus(reason)
    }

    fun deleteInstalledModel() {
        stopListening()
        unloadModelFromRam("حذف مدل")
        runCatching { modelRootDir.deleteRecursively() }
        _modelStatus.value = VoskModelStatus.NotInstalled
    }

    fun downloadPersianSmallModel() {
        if (_modelStatus.value is VoskModelStatus.Downloading) return
        scope.launch {
            downloadPersianSmallModelSync()
        }
    }

    internal suspend fun downloadPersianSmallModelSync(
        downloadUrl: String = VOSK_FA_SMALL_URL,
        expectedBytes: Long = VOSK_FA_SMALL_EXPECTED_BYTES,
        expectedSha256: String = VOSK_FA_SMALL_SHA256,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val partFile = File(context.cacheDir, "vosk-fa-small.zip.part")
        try {
            if (!SolHttpClient.isValidSha256Hex(expectedSha256) || expectedBytes <= 0L) {
                partFile.delete()
                throw SolNetworkException.IntegrityMetadataMissingException()
            }

            val diskCheck = SolStorageGuard.checkFreeSpace(
                targetPath = context.filesDir,
                payloadBytes = expectedBytes,
                availableBytesProvider = availableBytesProvider,
            )
            if (!diskCheck.hasEnoughSpace) {
                throw SolNetworkException.InsufficientDiskSpaceException(
                    availableMb = diskCheck.availableMb,
                    requiredMb = diskCheck.requiredMb,
                )
            }

            _modelStatus.value = VoskModelStatus.Downloading(2, "در حال اتصال امن به مخزن رسمی Vosk Persian…")
            httpClient.downloadVerifiedFile(
                urlString = downloadUrl,
                partFile = partFile,
                expectedBytes = expectedBytes,
                expectedSha256 = expectedSha256,
                allowedHostSuffixes = setOf("alphacephei.com"),
                onProgress = { pct, _, _ ->
                    _modelStatus.value = VoskModelStatus.Downloading(
                        progressPercent = pct.coerceIn(5, 88),
                        stageFa = "در حال دانلود مدل آفلاین فارسی ($pct٪)…",
                    )
                },
            )

            _modelStatus.value = VoskModelStatus.Downloading(90, "در حال استخراج امن فایل‌های مدل…")
            partFile.inputStream().use { extractZipSafely(it) }
            partFile.delete()
            refreshInstalledStatus("مدل آفلاین فارسی Vosk با موفقیت تأیید و نصب شد")
            Result.success(Unit)
        } catch (err: Throwable) {
            if (err is SolNetworkException.IntegrityMismatchException ||
                err is SolNetworkException.IntegrityMetadataMissingException
            ) {
                partFile.delete()
            }
            val msg = if (err is SolNetworkException) {
                err.userMessageFa
            } else {
                "نصب مدل Vosk ناموفق بود: ${err.localizedMessage ?: "خطای شبکه یا فایل"}"
            }
            _modelStatus.value = VoskModelStatus.Error(msg)
            Result.failure(err)
        }
    }

    fun importLocalModelZip(uri: Uri) {
        if (_modelStatus.value is VoskModelStatus.Downloading) return
        scope.launch {
            runCatching {
                _modelStatus.value = VoskModelStatus.Downloading(20, "در حال خواندن فایل ZIP مدل Vosk…")
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    extractZipSafely(stream)
                } ?: error("دسترسی به فایل انتخاب‌شده ممکن نشد.")
                require(isModelInstalled()) { "فایل ZIP انتخاب‌شده شامل پوشه معتبر مدل Vosk نیست." }
                refreshInstalledStatus("مدل آفلاین Vosk از حافظه گوشی نصب شد")
            }.onFailure { err ->
                _modelStatus.value = VoskModelStatus.Error(
                    err.localizedMessage ?: "وارد کردن فایل مدل ناموفق بود."
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startListening(
        grammarJson: String? = null,
        isWakeWordMode: Boolean = false,
        vadSilenceMs: Long = DEFAULT_VAD_SILENCE_MS,
        maxDurationMs: Long = if (isWakeWordMode) DEFAULT_WAKE_TIMEOUT_MS else DEFAULT_MAX_LISTEN_DURATION_MS,
        onPartial: (String) -> Unit,
        onRmsChanged: (Float) -> Unit,
        onFinalResult: (String) -> Unit,
        onTimeout: ((String) -> Unit)? = null,
        onError: (String) -> Unit,
    ) {
        val modelDir = findValidModelDir()
        if (modelDir == null) {
            onError("مدل آفلاین Vosk نصب نشده است.")
            return
        }

        val safeVadSilenceMs = vadSilenceMs.coerceIn(MIN_VAD_SILENCE_MS, MAX_VAD_SILENCE_MS)
        val safeMaxDurationMs = maxDurationMs.coerceAtLeast(3_000L)

        stopListening()
        autoUnloadJob?.cancel()
        isRecording.set(true)

        activeListeningJob = scope.launch {
            var recorder: AudioRecord? = null
            var recognizer: Recognizer? = null
            try {
                val model = voskModel ?: Model(modelDir.absolutePath).also {
                    voskModel = it
                    refreshInstalledStatus("مدل Vosk در RAM بارگذاری شد")
                }

                recognizer = if (!grammarJson.isNullOrBlank()) {
                    Recognizer(model, SAMPLE_RATE.toFloat(), grammarJson)
                } else {
                    Recognizer(model, SAMPLE_RATE.toFloat())
                }

                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(4096)

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2,
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("میکروفون دستگاه آماده نشد")
                }

                recorder.startRecording()
                val shortBuf = ShortArray(2048)
                val maxCaptureSamples = SAMPLE_RATE * 4
                val pcmRing = FloatArray(maxCaptureSamples)
                var pcmTotalWritten = 0
                val startedAt = System.currentTimeMillis()
                var hasHeardSpeech = false
                var lastVoiceActivityAt = startedAt
                var lastPartialSent = ""

                while (isRecording.get()) {
                    val read = recorder.read(shortBuf, 0, shortBuf.size)
                    if (read <= 0) continue

                    if (!isWakeWordMode) {
                        for (i in 0 until read) {
                            pcmRing[pcmTotalWritten % maxCaptureSamples] = shortBuf[i] / 32768f
                            pcmTotalWritten++
                        }
                    }

                    val rmsNorm = computeNormalizedRms(shortBuf, read)
                    mainHandler.post { onRmsChanged(rmsNorm) }

                    val now = System.currentTimeMillis()
                    if (rmsNorm > VAD_SPEECH_THRESHOLD) {
                        hasHeardSpeech = true
                        lastVoiceActivityAt = now
                    }

                    val isEndOfUtterance = recognizer.acceptWaveForm(shortBuf, read)
                    if (isEndOfUtterance) {
                        val txt = extractVoskText(recognizer.result, "text")
                        if (txt.isNotBlank()) {
                            val corrected = vocabularyBias.applyBiasCorrection(txt)
                            if (isWakeWordMode) {
                                val norm = PersianNormalizer.normalize(corrected)
                                if (norm.contains("سول") || norm.contains("sol")) {
                                    isRecording.set(false)
                                    mainHandler.post { onFinalResult(corrected) }
                                    break
                                }
                            } else {
                                saveCapturedPcmFromRing(pcmRing, pcmTotalWritten, maxCaptureSamples)
                                isRecording.set(false)
                                mainHandler.post { onFinalResult(corrected) }
                                break
                            }
                        }
                    } else {
                        val partial = extractVoskText(recognizer.partialResult, "partial")
                        if (partial.isNotBlank() && partial != lastPartialSent) {
                            lastPartialSent = partial
                            hasHeardSpeech = true
                            lastVoiceActivityAt = now
                            if (isWakeWordMode) {
                                val norm = PersianNormalizer.normalize(partial)
                                if (norm.contains("سول") || norm.contains("sol")) {
                                    isRecording.set(false)
                                    mainHandler.post { onFinalResult(partial) }
                                    break
                                }
                            } else {
                                val correctedPartial = vocabularyBias.applyBiasCorrection(partial)
                                mainHandler.post { onPartial(correctedPartial) }
                            }
                        }
                    }

                    when (
                        evaluateEndPointing(
                            isWakeWordMode = isWakeWordMode,
                            hasHeardSpeech = hasHeardSpeech,
                            nowMs = now,
                            startedAtMs = startedAt,
                            lastVoiceActivityAtMs = lastVoiceActivityAt,
                            vadSilenceMs = safeVadSilenceMs,
                            maxDurationMs = safeMaxDurationMs,
                        )
                    ) {
                        EndPointingAction.FINALIZE_SPEECH -> {
                            val finalTxt = extractVoskText(recognizer.finalResult, "text")
                                .ifBlank { lastPartialSent }
                            isRecording.set(false)
                            if (finalTxt.isNotBlank()) {
                                saveCapturedPcmFromRing(pcmRing, pcmTotalWritten, maxCaptureSamples)
                                val corrected = vocabularyBias.applyBiasCorrection(finalTxt)
                                mainHandler.post { onFinalResult(corrected) }
                            } else {
                                mainHandler.post { onError("صدایی تشخیص داده نشد؛ لطفاً دوباره بگو") }
                            }
                            break
                        }

                        EndPointingAction.TIMEOUT_NO_SPEECH -> {
                            val fallbackTxt = extractVoskText(recognizer.finalResult, "text")
                                .ifBlank { lastPartialSent }
                            isRecording.set(false)
                            if (fallbackTxt.isNotBlank()) {
                                saveCapturedPcmFromRing(pcmRing, pcmTotalWritten, maxCaptureSamples)
                                val corrected = vocabularyBias.applyBiasCorrection(fallbackTxt)
                                mainHandler.post { onFinalResult(corrected) }
                            } else {
                                val timeoutMsg = "زمان شنود به پایان رسید؛ صدایی تشخیص داده نشد"
                                mainHandler.post {
                                    if (onTimeout != null) onTimeout(timeoutMsg) else onError(timeoutMsg)
                                }
                            }
                            break
                        }

                        EndPointingAction.WAKE_TIMEOUT -> {
                            isRecording.set(false)
                            val timeoutMsg = "گوش‌به‌زنگ واژه «Sol» پس از سکوت خودکار متوقف شد."
                            mainHandler.post { onTimeout?.invoke(timeoutMsg) }
                            break
                        }

                        EndPointingAction.CONTINUE -> Unit
                    }
                }
            } catch (sec: SecurityException) {
                if (isRecording.get()) {
                    mainHandler.post { onError("مجوز میکروفون صادر نشده است") }
                }
            } catch (t: Throwable) {
                if (isRecording.get()) {
                    mainHandler.post {
                        onError("خطا در موتور آفلاین Vosk: ${t.localizedMessage ?: "نامشخص"}")
                    }
                }
            } finally {
                isRecording.set(false)
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                runCatching { recognizer?.close() }
                mainHandler.post { onRmsChanged(0f) }
                scheduleAutoUnloadAfterIdle()
            }
        }
    }

    @Volatile
    private var lastCapturedPcm16k: FloatArray? = null

    fun consumeLastCapturedPcm16k(): FloatArray? {
        val snap = lastCapturedPcm16k
        lastCapturedPcm16k = null
        return snap
    }

    private fun saveCapturedPcmFromRing(ring: FloatArray, totalWritten: Int, capacity: Int) {
        if (totalWritten <= 0) {
            lastCapturedPcm16k = null
            return
        }
        val count = totalWritten.coerceAtMost(capacity)
        val start = if (totalWritten > capacity) totalWritten % capacity else 0
        val out = FloatArray(count)
        for (i in 0 until count) {
            out[i] = ring[(start + i) % capacity]
        }
        lastCapturedPcm16k = out
    }

    fun stopListening() {
        isRecording.set(false)
        activeListeningJob?.cancel()
        activeListeningJob = null
        scheduleAutoUnloadAfterIdle()
    }

    private fun scheduleAutoUnloadAfterIdle() {
        autoUnloadJob?.cancel()
        autoUnloadJob = scope.launch {
            delay(IDLE_UNLOAD_DELAY_MS)
            if (!isRecording.get()) {
                unloadModelFromRam("آزادسازی خودکار مدل Vosk بعد از بیکاری (بهینه‌سازی ۴GB RAM)")
            }
        }
    }

    private fun extractVoskText(jsonStr: String?, key: String): String {
        if (jsonStr.isNullOrBlank()) return ""
        return runCatching {
            JSONObject(jsonStr).optString(key, "").trim()
        }.getOrDefault("")
    }

    private fun computeNormalizedRms(samples: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / count.coerceAtLeast(1))
        return (rms / 6500.0).toFloat().coerceIn(0f, 1f)
    }

    private fun findValidModelDir(): File? {
        val root = modelRootDir
        if (!root.exists()) return null
        if (hasVoskSignature(root)) return root
        return root.listFiles()?.firstOrNull { it.isDirectory && hasVoskSignature(it) }
    }

    private fun hasVoskSignature(dir: File): Boolean {
        val am = File(dir, "am")
        val conf = File(dir, "conf")
        return am.isDirectory && conf.isDirectory
    }

    private fun dirSizeBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { dirSizeBytes(it) } ?: 0L
    }

    // Legacy direct HttpURLConnection downloader removed; SolHttpClient handles HTTPS redirects, Range resume & SHA-256.

    private fun extractZipSafely(inputStream: InputStream) {
        val root = modelRootDir
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()
        val canonicalRoot = root.canonicalPath + File.separator

        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(8192)
            while (entry != null) {
                // Reject any executable binaries (Play DCL compliance)
                val lower = entry.name.lowercase()
                require(
                    !lower.endsWith(".so") &&
                        !lower.endsWith(".dex") &&
                        !lower.endsWith(".jar") &&
                        !lower.endsWith(".apk")
                ) { "فایل غیرمجاز در آرشیو مدل شناسایی شد." }

                val outFile = File(root, entry.name)
                val canonicalDest = outFile.canonicalPath
                require(canonicalDest.startsWith(canonicalRoot)) { "مسیر نامعتبر در فایل ZIP" }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    companion object {
        private const val MODEL_DIR_NAME = "vosk-model-fa"
        private const val SAMPLE_RATE = 16000
        private const val VAD_SPEECH_THRESHOLD = 0.08f
        private const val IDLE_UNLOAD_DELAY_MS = 90_000L
        const val MIN_VAD_SILENCE_MS = 700L
        const val MAX_VAD_SILENCE_MS = 3_500L
        const val DEFAULT_VAD_SILENCE_MS = 1_350L
        const val DEFAULT_MAX_LISTEN_DURATION_MS = 15_000L
        const val DEFAULT_WAKE_TIMEOUT_MS = 180_000L

        const val VOSK_FA_SMALL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.4.zip"
        // TODO(verify): Alpha Cephei repository index does not publish raw SHA-256 / byte length; keep fail-safe until verified.
        const val VOSK_FA_SMALL_SHA256 = "TODO(verify)"
        const val VOSK_FA_SMALL_EXPECTED_BYTES = -1L

        fun hasVerifiedDownloadMetadata(): Boolean =
            SolHttpClient.isValidSha256Hex(VOSK_FA_SMALL_SHA256) && VOSK_FA_SMALL_EXPECTED_BYTES > 0L

        fun evaluateEndPointing(
            isWakeWordMode: Boolean,
            hasHeardSpeech: Boolean,
            nowMs: Long,
            startedAtMs: Long,
            lastVoiceActivityAtMs: Long,
            vadSilenceMs: Long,
            maxDurationMs: Long,
        ): EndPointingAction {
            val elapsedFromStart = (nowMs - startedAtMs).coerceAtLeast(0L)
            val silenceMs = (nowMs - lastVoiceActivityAtMs).coerceAtLeast(0L)
            return if (isWakeWordMode) {
                if (silenceMs >= maxDurationMs) EndPointingAction.WAKE_TIMEOUT
                else EndPointingAction.CONTINUE
            } else {
                when {
                    hasHeardSpeech && silenceMs >= vadSilenceMs -> EndPointingAction.FINALIZE_SPEECH
                    elapsedFromStart >= maxDurationMs -> {
                        if (hasHeardSpeech) EndPointingAction.FINALIZE_SPEECH else EndPointingAction.TIMEOUT_NO_SPEECH
                    }
                    else -> EndPointingAction.CONTINUE
                }
            }
        }
    }
}
