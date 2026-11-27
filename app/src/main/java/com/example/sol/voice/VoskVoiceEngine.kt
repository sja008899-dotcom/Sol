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
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

sealed interface VoskModelStatus {
    data object NotInstalled : VoskModelStatus
    data class Downloading(val progressPercent: Int, val stageFa: String) : VoskModelStatus
    data class Installed(
        val modelSizeMb: Int,
        val isLoadedInRam: Boolean,
        val lastNoteFa: String,
        val sha256Hex: String = "",
    ) : VoskModelStatus
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
    private val trustedSha256Provider: suspend () -> String? = { null },
    private val onTofuSha256Pinned: suspend (sha256: String, totalBytes: Long) -> Unit = { _, _ -> },
    private val onClearTofuPin: suspend () -> Unit = {},
    private val micArbiter: MicArbiter? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    val vocabularyBias = VoiceVocabularyBias()

    private val modelRootDir: File
        get() = File(context.filesDir, MODEL_DIR_NAME)

    private val modelShaSidecarFile: File
        get() = File(context.filesDir, "$MODEL_DIR_NAME.sha256")

    private val _modelStatus = MutableStateFlow<VoskModelStatus>(VoskModelStatus.NotInstalled)
    val modelStatus: StateFlow<VoskModelStatus> = _modelStatus.asStateFlow()

    @Volatile
    private var voskModel: Model? = null

    @Volatile
    private var activeListeningJob: Job? = null

    @Volatile
    private var activeSessionStop: AtomicBoolean? = null

    @Volatile
    private var autoUnloadJob: Job? = null

    private val sessionCounter = AtomicLong(0L)
    private val isRecording = AtomicBoolean(false)

    init {
        runCatching { LibVosk.setLogLevel(LogLevel.WARNINGS) }
        refreshInstalledStatus()
    }

    fun isModelInstalled(): Boolean = findValidModelDir() != null

    fun isModelLoadedInRam(): Boolean = voskModel != null

    fun isBusy(): Boolean = isRecording.get() || _modelStatus.value is VoskModelStatus.Downloading

    private fun saveVoskShaSidecar(sha256Hex: String) {
        if (!SolHttpClient.isValidSha256Hex(sha256Hex)) return
        runCatching { modelShaSidecarFile.writeText(sha256Hex.trim().lowercase()) }
    }

    private fun readVoskShaSidecar(): String {
        if (!modelShaSidecarFile.isFile) return ""
        val text = runCatching { modelShaSidecarFile.readText().trim().lowercase() }.getOrDefault("")
        return if (SolHttpClient.isValidSha256Hex(text)) text else ""
    }

    fun refreshInstalledStatus(note: String = "مدل آفلاین فارسی آماده اجراست") {
        val dir = findValidModelDir()
        if (dir != null) {
            val sizeMb = (dirSizeBytes(dir) / (1024L * 1024L)).toInt().coerceAtLeast(1)
            _modelStatus.value = VoskModelStatus.Installed(
                modelSizeMb = sizeMb,
                isLoadedInRam = voskModel != null,
                lastNoteFa = note,
                sha256Hex = readVoskShaSidecar(),
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
        runCatching { modelShaSidecarFile.delete() }
        scope.launch { runCatching { onClearTofuPin() } }
        _modelStatus.value = VoskModelStatus.NotInstalled
    }

    fun downloadPersianSmallModel() {
        if (_modelStatus.value is VoskModelStatus.Downloading) return
        scope.launch {
            downloadPersianSmallModelSync(allowHttpsWithoutPinnedHash = true)
        }
    }

    internal suspend fun downloadPersianSmallModelSync(
        downloadUrl: String = VOSK_FA_SMALL_URL,
        expectedBytes: Long = VOSK_FA_SMALL_EXPECTED_BYTES,
        expectedSha256: String = VOSK_FA_SMALL_SHA256,
        allowHttpsWithoutPinnedHash: Boolean = false,
        enforceSizeBounds: Boolean = true,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val partFile = File(context.cacheDir, "vosk-fa-small.zip.part")
        try {
            val hasPinnedMetadata = SolHttpClient.isValidSha256Hex(expectedSha256) && expectedBytes > 0L
            if (!hasPinnedMetadata && !allowHttpsWithoutPinnedHash) {
                partFile.delete()
                throw SolNetworkException.IntegrityMetadataMissingException()
            }

            val downloadedShaAndSize: Pair<String, Long> = if (hasPinnedMetadata) {
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
                expectedSha256.lowercase() to expectedBytes
            } else {
                val pinnedTofuSha = runCatching { trustedSha256Provider() }
                    .getOrNull()
                    ?.trim()
                    ?.takeIf { SolHttpClient.isValidSha256Hex(it) }
                val res = downloadVoskFromMirrorsWithFallback(
                    primaryUrl = downloadUrl,
                    partFile = partFile,
                    tofuExpectedSha256 = pinnedTofuSha,
                    enforceSizeBounds = enforceSizeBounds,
                )
                res.sha256Hex to res.totalBytes
            }

            _modelStatus.value = VoskModelStatus.Downloading(90, "در حال استخراج امن فایل‌های مدل…")
            partFile.inputStream().use { extractZipSafely(it) }
            require(isModelInstalled()) { "آرشیو دانلودشده فاقد ساختار معتبر مدل Vosk است." }
            partFile.delete()

            val (actualSha, actualBytes) = downloadedShaAndSize
            saveVoskShaSidecar(actualSha)
            if (SolHttpClient.isValidSha256Hex(actualSha)) {
                runCatching { onTofuSha256Pinned(actualSha, actualBytes) }
            }
            val fingerprintNote = if (actualSha.isNotBlank()) {
                " • اثر انگشت فایل: ${actualSha.take(16)}…"
            } else {
                ""
            }
            refreshInstalledStatus("مدل آفلاین فارسی Vosk با موفقیت دانلود و نصب شد$fingerprintNote")
            Result.success(Unit)
        } catch (err: Throwable) {
            if (err !is SolNetworkException.TransportException && err !is SolNetworkException.CancelledException) {
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

    private fun downloadVoskFromMirrorsWithFallback(
        primaryUrl: String,
        partFile: File,
        tofuExpectedSha256: String?,
        enforceSizeBounds: Boolean,
    ): com.example.sol.network.DownloadedFileResult {
        val candidates = (listOf(primaryUrl) + VOSK_FA_SMALL_FALLBACK_URLS).distinct()
        var lastError: Throwable? = null
        val minBytes = if (enforceSizeBounds) VOSK_FA_SMALL_MIN_BYTES else -1L
        val maxBytes = if (enforceSizeBounds) VOSK_FA_SMALL_MAX_BYTES else -1L
        for ((idx, candidateUrl) in candidates.withIndex()) {
            _modelStatus.value = VoskModelStatus.Downloading(
                progressPercent = 2,
                stageFa = "در حال اتصال امن به مخزن رسمی Vosk Persian…",
            )
            try {
                return httpClient.downloadOfficialHttpsFileResult(
                    urlString = candidateUrl,
                    partFile = partFile,
                    estimatedBytes = VOSK_FA_SMALL_ESTIMATED_BYTES,
                    tofuExpectedSha256 = tofuExpectedSha256,
                    minAllowedBytes = minBytes,
                    maxAllowedBytes = maxBytes,
                    allowedHostSuffixes = setOf("alphacephei.com"),
                    redirectAllowedHostSuffixes = setOf("alphacephei.com"),
                    onProgress = { pct, doneBytes, totalBytes ->
                        val doneMb = (doneBytes / (1024L * 1024L)).coerceAtLeast(0L)
                        val totalMb = (totalBytes / (1024L * 1024L)).coerceAtLeast(1L)
                        _modelStatus.value = VoskModelStatus.Downloading(
                            progressPercent = pct.coerceIn(5, 88),
                            stageFa = "در حال دانلود مدل آفلاین فارسی ($doneMb/$totalMb MB • $pct٪)…",
                        )
                    },
                )
            } catch (err: SolNetworkException.HttpStatusException) {
                lastError = err
                val canFallback = err.statusCode in setOf(403, 404, 410, 416) && idx < candidates.lastIndex
                if (canFallback) {
                    partFile.delete()
                    continue
                }
                throw err
            }
        }
        throw (lastError ?: SolNetworkException.TransportException())
    }

    fun importLocalModelZip(uri: Uri) {
        if (_modelStatus.value is VoskModelStatus.Downloading) return
        scope.launch {
            val tempZip = File(context.cacheDir, "vosk_import_${System.nanoTime()}.zip")
            try {
                _modelStatus.value = VoskModelStatus.Downloading(20, "در حال خواندن فایل ZIP مدل Vosk…")
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                var totalBytes = 0L
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    tempZip.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        var len: Int
                        while (stream.read(buf).also { len = it } != -1) {
                            out.write(buf, 0, len)
                            digest.update(buf, 0, len)
                            totalBytes += len
                        }
                    }
                } ?: error("دسترسی به فایل انتخاب‌شده ممکن نشد.")

                _modelStatus.value = VoskModelStatus.Downloading(70, "در حال استخراج فایل‌های مدل Vosk…")
                tempZip.inputStream().use { extractZipSafely(it) }
                require(isModelInstalled()) { "فایل ZIP انتخاب‌شده شامل پوشه معتبر مدل Vosk نیست." }

                val shaHex = digest.digest().joinToString("") { "%02x".format(it) }
                saveVoskShaSidecar(shaHex)
                runCatching { onTofuSha256Pinned(shaHex, totalBytes) }
                refreshInstalledStatus("مدل آفلاین Vosk از حافظه گوشی نصب شد • اثر انگشت فایل: ${shaHex.take(16)}…")
            } catch (err: Throwable) {
                _modelStatus.value = VoskModelStatus.Error(
                    err.localizedMessage ?: "وارد کردن فایل مدل ناموفق بود."
                )
            } finally {
                tempZip.delete()
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

        val prevJob = activeListeningJob
        activeSessionStop?.set(false)
        prevJob?.cancel()
        autoUnloadJob?.cancel()

        val mySessionId = sessionCounter.incrementAndGet()
        val sessionActive = AtomicBoolean(true)
        activeSessionStop = sessionActive
        isRecording.set(true)

        val micOwner = if (isWakeWordMode) MicOwner.WAKE_WORD_UI else MicOwner.VOICE_COMMAND
        activeListeningJob = scope.launch {
            if (prevJob != null) {
                runCatching { prevJob.join() }
            }
            if (!sessionActive.get() || sessionCounter.get() != mySessionId) {
                if (sessionCounter.get() == mySessionId) {
                    isRecording.set(false)
                }
                return@launch
            }

            var activeRecorderRef: AudioRecord? = null
            var recognizer: Recognizer? = null
            var leased = false
            try {
                micArbiter?.acquire(owner = micOwner)
                leased = micArbiter != null

                if (!sessionActive.get() || sessionCounter.get() != mySessionId) {
                    return@launch
                }

                val model = voskModel ?: Model(modelDir.absolutePath).also {
                    voskModel = it
                    refreshInstalledStatus("مدل Vosk در RAM بارگذاری شد")
                }

                recognizer = if (!grammarJson.isNullOrBlank()) {
                    Recognizer(model, SAMPLE_RATE.toFloat(), grammarJson)
                } else {
                    Recognizer(model, SAMPLE_RATE.toFloat())
                }

                val maxCaptureSamples = SAMPLE_RATE * 4
                val pcmRing = FloatArray(maxCaptureSamples)
                var pcmTotalWritten = 0
                val startedAt = System.currentTimeMillis()
                var hasHeardSpeech = false
                var lastVoiceActivityAt = startedAt
                var lastPartialSent = ""
                val activeRec = recognizer

                val captureOutcome = SafeAudioCapture.runCaptureLoop(
                    sampleRate = SAMPLE_RATE,
                    chunkSize = 2048,
                    shouldContinue = { sessionActive.get() && sessionCounter.get() == mySessionId },
                    onRecorderSwap = { activeRecorderRef = it },
                    onSamples = { shortBuf, read ->
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

                        val isEndOfUtterance = activeRec.acceptWaveForm(shortBuf, read)
                        if (isEndOfUtterance) {
                            val txt = extractVoskText(activeRec.result, "text")
                            if (txt.isNotBlank()) {
                                val corrected = vocabularyBias.applyBiasCorrection(txt)
                                if (isWakeWordMode) {
                                    val norm = PersianNormalizer.normalize(corrected)
                                    if (norm.contains("سول") || norm.contains("sol")) {
                                        sessionActive.set(false)
                                        mainHandler.post { onFinalResult(corrected) }
                                        return@runCaptureLoop false
                                    }
                                } else {
                                    saveCapturedPcmFromRing(pcmRing, pcmTotalWritten, maxCaptureSamples)
                                    sessionActive.set(false)
                                    mainHandler.post { onFinalResult(corrected) }
                                    return@runCaptureLoop false
                                }
                            }
                        } else {
                            val partial = extractVoskText(activeRec.partialResult, "partial")
                            if (partial.isNotBlank() && partial != lastPartialSent) {
                                lastPartialSent = partial
                                hasHeardSpeech = true
                                lastVoiceActivityAt = now
                                if (isWakeWordMode) {
                                    val norm = PersianNormalizer.normalize(partial)
                                    if (norm.contains("سول") || norm.contains("sol")) {
                                        sessionActive.set(false)
                                        mainHandler.post { onFinalResult(partial) }
                                        return@runCaptureLoop false
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
                                val finalTxt = extractVoskText(activeRec.finalResult, "text")
                                    .ifBlank { lastPartialSent }
                                sessionActive.set(false)
                                if (finalTxt.isNotBlank()) {
                                    saveCapturedPcmFromRing(pcmRing, pcmTotalWritten, maxCaptureSamples)
                                    val corrected = vocabularyBias.applyBiasCorrection(finalTxt)
                                    mainHandler.post { onFinalResult(corrected) }
                                } else {
                                    mainHandler.post { onError("صدایی تشخیص داده نشد؛ لطفاً دوباره بگو") }
                                }
                                false
                            }

                            EndPointingAction.TIMEOUT_NO_SPEECH -> {
                                val fallbackTxt = extractVoskText(activeRec.finalResult, "text")
                                    .ifBlank { lastPartialSent }
                                sessionActive.set(false)
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
                                false
                            }

                            EndPointingAction.WAKE_TIMEOUT -> {
                                sessionActive.set(false)
                                val timeoutMsg = "گوش‌به‌زنگ واژه «Sol» پس از سکوت خودکار متوقف شد."
                                mainHandler.post { onTimeout?.invoke(timeoutMsg) }
                                false
                            }

                            EndPointingAction.CONTINUE -> true
                        }
                    },
                )

                if (captureOutcome is SafeAudioCaptureStop.Failed && sessionActive.get()) {
                    sessionActive.set(false)
                    mainHandler.post { onError(captureOutcome.messageFa) }
                }
            } catch (_: SecurityException) {
                if (sessionActive.get()) {
                    mainHandler.post { onError("مجوز میکروفون صادر نشده است") }
                }
            } catch (t: Throwable) {
                if (sessionActive.get() && t !is kotlinx.coroutines.CancellationException) {
                    mainHandler.post {
                        onError("خطا در موتور آفلاین Vosk: ${t.localizedMessage ?: "نامشخص"}")
                    }
                }
            } finally {
                sessionActive.set(false)
                if (sessionCounter.get() == mySessionId) {
                    isRecording.set(false)
                }
                runCatching { activeRecorderRef?.stop() }
                runCatching { activeRecorderRef?.release() }
                runCatching { recognizer?.close() }
                if (leased) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        micArbiter?.release(owner = micOwner)
                    }
                }
                if (sessionCounter.get() == mySessionId) {
                    mainHandler.post { onRmsChanged(0f) }
                }
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
        sessionCounter.incrementAndGet()
        activeSessionStop?.set(false)
        activeSessionStop = null
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
        val graph = File(dir, "graph")
        return am.isDirectory && conf.isDirectory && graph.isDirectory
    }

    private fun dirSizeBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { dirSizeBytes(it) } ?: 0L
    }

    private fun extractZipSafely(inputStream: InputStream) {
        val staging = File(context.filesDir, "${MODEL_DIR_NAME}.staging")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        val canonicalStaging = staging.canonicalPath + File.separator

        try {
            var entryCount = 0
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                val buffer = ByteArray(8192)
                while (entry != null) {
                    entryCount++
                    // Reject any executable binaries (Play DCL compliance)
                    val lower = entry.name.lowercase()
                    require(
                        !lower.endsWith(".so") &&
                            !lower.endsWith(".dex") &&
                            !lower.endsWith(".jar") &&
                            !lower.endsWith(".apk")
                    ) { "فایل غیرمجاز در آرشیو مدل شناسایی شد: ${entry.name}" }

                    val outFile = File(staging, entry.name)
                    val canonicalDest = outFile.canonicalPath
                    require(canonicalDest.startsWith(canonicalStaging)) { "مسیر نامعتبر در فایل ZIP" }

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

            require(entryCount > 0) { "فایل انتخاب‌شده یک آرشیو معتبر ZIP مدل Vosk نیست." }
            val hasValid = hasVoskSignature(staging) ||
                staging.listFiles()?.any { it.isDirectory && hasVoskSignature(it) } == true
            require(hasValid) { "فایل ZIP انتخاب‌شده شامل پوشه معتبر مدل Vosk (am، conf و graph) نیست." }

            val root = modelRootDir
            if (root.exists()) root.deleteRecursively()
            if (!staging.renameTo(root)) {
                staging.copyRecursively(root, overwrite = true)
                staging.deleteRecursively()
            }
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    companion object {
        private const val MODEL_DIR_NAME = "vosk-model-fa"
        private const val SAMPLE_RATE = 16000
        private const val VAD_SPEECH_THRESHOLD = 0.022f
        private const val IDLE_UNLOAD_DELAY_MS = 90_000L
        const val MIN_VAD_SILENCE_MS = 700L
        const val MAX_VAD_SILENCE_MS = 3_500L
        const val DEFAULT_VAD_SILENCE_MS = 1_350L
        const val DEFAULT_MAX_LISTEN_DURATION_MS = 15_000L
        const val DEFAULT_WAKE_TIMEOUT_MS = 180_000L

        const val VOSK_FA_SMALL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.5.zip"
        val VOSK_FA_SMALL_FALLBACK_URLS = listOf(
            "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.42.zip",
            "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.4.zip",
        )
        const val VOSK_FA_SMALL_ESTIMATED_BYTES = 60L * 1024L * 1024L
        const val VOSK_FA_SMALL_MIN_BYTES = 20L * 1024L * 1024L
        const val VOSK_FA_SMALL_MAX_BYTES = 200L * 1024L * 1024L
        // TODO(verify): Alpha Cephei repository index does not publish raw SHA-256 / byte length.
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
