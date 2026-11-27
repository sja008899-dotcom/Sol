package com.example.sol.tts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.sol.memory.VoicePersona
import com.example.sol.network.SolHttpClient
import com.example.sol.network.SolNetworkException
import com.example.sol.network.SolStorageGuard
import com.example.sol.text.PersianNumbers
import com.example.sol.voice.SolAudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed interface PiperTtsStatus {
    data object NotInstalled : PiperTtsStatus

    data class Downloading(
        val progressPercent: Int,
        val stageFa: String,
    ) : PiperTtsStatus

    data class PartialSelection(
        val noteFa: String,
    ) : PiperTtsStatus

    data class NeedsRiskConfirmation(
        val onnxSha256: String,
        val configSha256: String,
        val reasonFa: String,
    ) : PiperTtsStatus

    data class Ready(
        val sampleRate: Int,
        val sizeMb: Int,
        val sha256Summary: String = "",
    ) : PiperTtsStatus

    data class Error(
        val messageFa: String,
    ) : PiperTtsStatus
}

class StreamingSpeechSession internal constructor(
    private val channel: Channel<String>,
    private val job: Job,
    private val onCancelPlayback: () -> Unit,
) {
    fun enqueue(sentence: String) {
        val trimmed = sentence.trim()
        if (trimmed.isNotEmpty() && job.isActive) {
            channel.trySend(trimmed)
        }
    }

    fun finish() {
        channel.close()
    }

    fun cancel() {
        channel.close()
        job.cancel()
        onCancelPlayback()
    }
}

/**
 * Pure-Kotlin incremental buffer for SLM token streams so complete Persian/English sentences
 * are flushed immediately to Piper TTS without waiting for the entire LLM response to complete.
 */
class SlmSpeechStreamBuffer(
    private val minSentenceChars: Int = 10,
    private val maxClauseChars: Int = 95,
) {
    private var consumedLength: Int = 0

    fun appendAndDrainReadySentences(latestFullText: String): List<String> {
        if (latestFullText.isEmpty()) return emptyList()
        if (consumedLength > latestFullText.length) {
            consumedLength = latestFullText.length
        }

        val ready = mutableListOf<String>()
        while (consumedLength < latestFullText.length) {
            val splitEnd = findNextBoundaryEnd(latestFullText, consumedLength) ?: break
            val segment = latestFullText.substring(consumedLength, splitEnd).trim()
            consumedLength = splitEnd
            if (segment.isNotEmpty()) {
                ready.add(segment)
            }
        }
        return ready
    }

    fun flushRemaining(finalFullText: String): String? {
        if (consumedLength > finalFullText.length) {
            consumedLength = finalFullText.length
        }
        val tail = finalFullText.substring(consumedLength).trim()
        consumedLength = finalFullText.length
        return tail.takeIf { it.isNotEmpty() }
    }

    private fun findNextBoundaryEnd(text: String, start: Int): Int? {
        val len = text.length
        var i = start
        while (i < len) {
            val ch = text[i]
            val candidateLen = (i - start + 1)
            val isNewline = ch == '\n'
            val isStrongPunct = ch == '؟' || ch == '?' || ch == '!' || ch == '؛' || ch == ';'
            val isPeriod = ch == '.'

            if (isPeriod) {
                val prevDigit = i > start && isAnyDigit(text[i - 1])
                val atEnd = i + 1 >= len
                val nextDigit = !atEnd && isAnyDigit(text[i + 1])
                // Hold off on trailing digit+dot so decimals (e.g., 12.5) and list numbers ("1.") don't split mid-token
                val isDecimal = prevDigit && (nextDigit || atEnd)
                val nonWhitespaceLen = text.substring(start, i + 1).trim().length
                if (!isDecimal && nonWhitespaceLen >= minSentenceChars) {
                    return i + 1
                }
            } else if (isNewline || isStrongPunct) {
                val nonWhitespaceLen = text.substring(start, i + 1).trim().length
                if (nonWhitespaceLen >= 4) {
                    return i + 1
                }
            }

            // Soft clause split for long unpunctuated streams so playback starts quickly
            if (candidateLen >= maxClauseChars) {
                val searchStart = (start + 32).coerceAtMost(i)
                for (j in i downTo searchStart) {
                    val c = text[j]
                    if (c == '،' || c == ',' || c.isWhitespace()) {
                        val trimmedLen = text.substring(start, j + 1).trim().length
                        if (trimmedLen >= minSentenceChars) {
                            return j + 1
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    private fun isAnyDigit(ch: Char): Boolean =
        ch in '0'..'9' || ch in '۰'..'۹' || ch in '٠'..'٩'
}

class TtsManager(
    context: Context,
    private val audioManager: SolAudioManager,
    private val httpClient: SolHttpClient = SolHttpClient(),
    private val availableBytesProvider: (File) -> Long = SolStorageGuard::readAvailableBytes,
    private val trustedCustomHashesProvider: (suspend () -> Pair<String, String>?)? = null,
    private val onSaveTrustedCustomHashes: (suspend (onnxSha: String, configSha: String) -> Unit)? = null,
    private val onClearTrustedCustomHashes: (suspend () -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadMutex = Mutex()

    private val piperDir = File(appContext.filesDir, "tts/piper")
    private val modelFile = File(piperDir, "fa_IR.onnx")
    private val configFile = File(piperDir, "fa_IR.onnx.json")

    // Legacy location used in intermediate build; migrate transparently if present
    private val legacyPiperDir = File(appContext.filesDir, "piper")

    private val piperEngine = PiperTtsEngine(modelFile, configFile)

    @Volatile
    private var activeSpeakJob: Job? = null

    @Volatile
    private var activeDownloadJob: Job? = null

    @Volatile
    private var pendingAutoSpeakRequest: PendingAutoSpeak? = null

    private data class PendingAutoSpeak(
        val text: String,
        val speechRate: Float,
        val persona: VoicePersona,
    )

    private val _isPiperSpeaking = MutableStateFlow(false)
    val isPiperSpeaking: StateFlow<Boolean> = _isPiperSpeaking.asStateFlow()

    val isSpeaking: StateFlow<Boolean> = _isPiperSpeaking.asStateFlow()

    private val _piperStatus = MutableStateFlow<PiperTtsStatus>(PiperTtsStatus.NotInstalled)
    val piperStatus: StateFlow<PiperTtsStatus> = _piperStatus.asStateFlow()

    init {
        migrateLegacyPiperIfPresent()
        val hasBoth = modelFile.isFile && modelFile.length() > 0L && configFile.isFile && configFile.length() > 0L
        if (hasBoth) {
            // Avoid blocking Main thread on 60MB SHA-256 + ONNX load during lazy container init
            scope.launch { refreshInstalledPiper() }
        } else {
            refreshInstalledPiper()
        }
    }

    val isInstalledAndReady: Boolean
        get() = _piperStatus.value is PiperTtsStatus.Ready

    fun isModelLoadedInRam(): Boolean = piperEngine.isReady

    fun isBusy(): Boolean =
        _isPiperSpeaking.value || _piperStatus.value is PiperTtsStatus.Downloading

    private fun migrateLegacyPiperIfPresent() {
        if (!legacyPiperDir.isDirectory) return
        val legacyModel = File(legacyPiperDir, "persian.onnx")
        val legacyConfig = File(legacyPiperDir, "persian.onnx.json")
        if ((legacyModel.isFile || legacyConfig.isFile) && !modelFile.exists() && !configFile.exists()) {
            piperDir.mkdirs()
            if (legacyModel.isFile) legacyModel.copyTo(modelFile, overwrite = true)
            if (legacyConfig.isFile) legacyConfig.copyTo(configFile, overwrite = true)
            runCatching { legacyPiperDir.deleteRecursively() }
        }
    }

    fun refreshInstalledPiper(): Boolean {
        val hasModel = modelFile.isFile && modelFile.length() > 0L
        val hasConfig = configFile.isFile && configFile.length() > 0L

        if (!hasModel && !hasConfig) {
            piperEngine.release()
            if (_piperStatus.value !is PiperTtsStatus.Downloading) {
                _piperStatus.value = PiperTtsStatus.NotInstalled
            }
            return false
        }

        if (!hasModel || !hasConfig) {
            piperEngine.release()
            _piperStatus.value = PiperTtsStatus.PartialSelection(
                noteFa = if (hasModel) {
                    "فایل مدل (.onnx) انتخاب شده؛ اکنون فایل تنظیمات JSON مربوط به همان مدل را نیز انتخاب کنید یا دانلود خودکار را بزنید."
                } else {
                    "فایل تنظیمات (.json) انتخاب شده؛ اکنون فایل مدل ONNX مربوط به همان صدا را نیز انتخاب کنید یا دانلود خودکار را بزنید."
                },
            )
            return false
        }

        val trustedCustom = runCatching {
            kotlinx.coroutines.runBlocking { trustedCustomHashesProvider?.invoke() }
        }.getOrNull()

        return when (val hashRes = piperEngine.inspectChecksums(trustedCustom)) {
            is PiperHashCheckResult.VerifiedOfficial -> {
                initializeEngineAndMarkReady(
                    onnxSha = hashRes.onnxSha256,
                    trustedCustom = trustedCustom,
                    allowUnverifiedRisk = false,
                )
            }
            is PiperHashCheckResult.VerifiedCustomTrusted -> {
                initializeEngineAndMarkReady(
                    onnxSha = hashRes.onnxSha256,
                    trustedCustom = trustedCustom,
                    allowUnverifiedRisk = false,
                )
            }
            is PiperHashCheckResult.MismatchNeedsConfirmation -> {
                piperEngine.release()
                _piperStatus.value = PiperTtsStatus.NeedsRiskConfirmation(
                    onnxSha256 = hashRes.onnxSha256,
                    configSha256 = hashRes.configSha256,
                    reasonFa = "اثر انگشت فایل‌های Piper با نسخهٔ تأییدشده متفاوت است. فقط در صورت اطمینان از منبع فایل آن را بپذیرید.",
                )
                false
            }
            is PiperHashCheckResult.Error -> {
                piperEngine.release()
                _piperStatus.value = PiperTtsStatus.Error(hashRes.messageFa)
                false
            }
        }
    }

    private fun initializeEngineAndMarkReady(
        onnxSha: String,
        trustedCustom: Pair<String, String>?,
        allowUnverifiedRisk: Boolean,
    ): Boolean {
        val initialized = piperEngine.initialize(
            trustedCustomHashes = trustedCustom,
            allowUnverifiedRisk = allowUnverifiedRisk,
        )
        return if (initialized) {
            val totalBytes = modelFile.length() + configFile.length()
            _piperStatus.value = PiperTtsStatus.Ready(
                sampleRate = piperEngine.sampleRate,
                sizeMb = (totalBytes / (1024L * 1024L)).toInt().coerceAtLeast(1),
                sha256Summary = if (onnxSha.isNotBlank()) onnxSha.take(16) else "",
            )
            if (modelFile.length() > 100_000L) {
                scope.launch { piperEngine.warmup() }
            }
            true
        } else {
            _piperStatus.value = PiperTtsStatus.Error(
                messageFa = piperEngine.lastErrorFa.ifBlank {
                    "راه‌اندازی موتور خودکفای Piper ناموفق بود؛ فایل ONNX و تنظیمات JSON را بررسی کنید."
                },
            )
            false
        }
    }

    fun downloadOfficialPiperModel(onReady: (() -> Unit)? = null) {
        if (_piperStatus.value is PiperTtsStatus.Downloading || _piperStatus.value is PiperTtsStatus.Ready) {
            if (_piperStatus.value is PiperTtsStatus.Ready) onReady?.invoke()
            return
        }
        activeDownloadJob = scope.launch {
            val res = downloadOfficialPiperModelSync()
            if (res.isSuccess) {
                onReady?.invoke()
                val queued = pendingAutoSpeakRequest
                pendingAutoSpeakRequest = null
                if (queued != null) {
                    speak(queued.text, queued.speechRate, queued.persona)
                }
            } else {
                pendingAutoSpeakRequest = null
            }
        }
    }

    suspend fun downloadOfficialPiperModelSync(
        onnxUrl: String = PiperConfig.OFFICIAL_ONNX_URL,
        configUrl: String = PiperConfig.OFFICIAL_CONFIG_URL,
        expectedOnnxSha256: String = PiperConfig.EXPECTED_ONNX_SHA256,
        expectedConfigSha256: String = PiperConfig.EXPECTED_CONFIG_SHA256,
        estimatedOnnxBytes: Long = PiperConfig.OFFICIAL_APPROX_BYTES,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            if (_piperStatus.value is PiperTtsStatus.Ready && piperEngine.isReady) {
                return@withLock Result.success(Unit)
            }
            piperDir.mkdirs()
            val configPart = File(piperDir, "fa_IR.onnx.json.part")
            val onnxPart = File(piperDir, "fa_IR.onnx.part")
            try {
                _piperStatus.value = PiperTtsStatus.Downloading(
                    progressPercent = 2,
                    stageFa = "در حال دریافت تنظیمات صدای فارسی Piper (Mana)…",
                )
                val cfgHead = runCatching {
                    httpClient.preflightHeadMetadata(configUrl, setOf("huggingface.co", "hf.co"))
                }.getOrNull()
                httpClient.downloadOfficialHttpsFileResult(
                    urlString = configUrl,
                    partFile = configPart,
                    estimatedBytes = (cfgHead?.contentLengthBytes ?: 8_192L).coerceAtLeast(4_096L),
                    expectedSha256Override = expectedConfigSha256,
                    expectedBytesOverride = cfgHead?.contentLengthBytes ?: -1L,
                    allowedHostSuffixes = setOf("huggingface.co", "hf.co"),
                    redirectAllowedHostSuffixes = SolHttpClient.DEFAULT_CDN_REDIRECT_HOSTS,
                )
                PiperConfig.parse(configPart)

                val diskCheck = SolStorageGuard.checkFreeSpace(
                    targetPath = piperDir,
                    payloadBytes = estimatedOnnxBytes,
                    availableBytesProvider = availableBytesProvider,
                )
                if (!diskCheck.hasEnoughSpace) {
                    throw SolNetworkException.InsufficientDiskSpaceException(
                        availableMb = diskCheck.availableMb,
                        requiredMb = diskCheck.requiredMb,
                    )
                }

                _piperStatus.value = PiperTtsStatus.Downloading(
                    progressPercent = 5,
                    stageFa = "در حال دانلود صدای خودکفای فارسی Piper (~63MB)…",
                )
                val onnxHead = runCatching {
                    httpClient.preflightHeadMetadata(onnxUrl, setOf("huggingface.co", "hf.co"))
                }.getOrNull()
                httpClient.downloadOfficialHttpsFileResult(
                    urlString = onnxUrl,
                    partFile = onnxPart,
                    estimatedBytes = onnxHead?.contentLengthBytes?.takeIf { it > 0L } ?: estimatedOnnxBytes,
                    expectedSha256Override = expectedOnnxSha256,
                    expectedBytesOverride = onnxHead?.contentLengthBytes ?: -1L,
                    allowedHostSuffixes = setOf("huggingface.co", "hf.co"),
                    redirectAllowedHostSuffixes = SolHttpClient.DEFAULT_CDN_REDIRECT_HOSTS,
                    onProgress = { pct, doneBytes, totalBytes ->
                        val doneMb = (doneBytes / (1024L * 1024L)).coerceAtLeast(0L)
                        val totalMb = (totalBytes / (1024L * 1024L)).coerceAtLeast(1L)
                        _piperStatus.value = PiperTtsStatus.Downloading(
                            progressPercent = pct.coerceIn(5, 96),
                            stageFa = "در حال دانلود صدای فارسی Piper ($doneMb/$totalMb MB • $pct٪)…",
                        )
                    },
                )

                _piperStatus.value = PiperTtsStatus.Downloading(
                    progressPercent = 98,
                    stageFa = "در حال تأیید اثر انگشت SHA-256 و فعال‌سازی Piper…",
                )
                piperEngine.release()
                if (configFile.exists()) configFile.delete()
                if (!configPart.renameTo(configFile)) {
                    configPart.copyTo(configFile, overwrite = true)
                    configPart.delete()
                }
                if (modelFile.exists()) modelFile.delete()
                if (!onnxPart.renameTo(modelFile)) {
                    onnxPart.copyTo(modelFile, overwrite = true)
                    onnxPart.delete()
                }

                check(refreshInstalledPiper()) {
                    piperEngine.lastErrorFa.ifBlank { "راه‌اندازی موتور صدای Piper پس از دانلود ناموفق بود." }
                }
                Result.success(Unit)
            } catch (err: Throwable) {
                if (err !is SolNetworkException.TransportException && err !is SolNetworkException.CancelledException) {
                    configPart.delete()
                    onnxPart.delete()
                }
                val msg = if (err is SolNetworkException) {
                    err.userMessageFa
                } else {
                    "دانلود صدای خودکفای Piper ناموفق بود: ${err.localizedMessage ?: "خطای شبکه یا فایل"}"
                }
                _piperStatus.value = PiperTtsStatus.Error(msg)
                Result.failure(err)
            }
        }
    }

    fun acceptCustomPiperWithRisk() {
        val current = _piperStatus.value as? PiperTtsStatus.NeedsRiskConfirmation ?: return
        scope.launch {
            runCatching { onSaveTrustedCustomHashes?.invoke(current.onnxSha256, current.configSha256) }
            initializeEngineAndMarkReady(
                onnxSha = current.onnxSha256,
                trustedCustom = current.onnxSha256 to current.configSha256,
                allowUnverifiedRisk = true,
            )
        }
    }

    fun rejectPendingCustomPiper() {
        disableAndDeletePiper()
    }

    fun unloadFromRamIfIdle() {
        if (_isPiperSpeaking.value) return
        if (piperEngine.isReady) {
            piperEngine.unloadSessionOnly()
        }
    }

    fun speak(
        text: String,
        speechRate: Float = 1.0f,
        persona: VoicePersona = VoicePersona.FEMALE_CALM,
    ) {
        if (text.isBlank()) return

        if (piperEngine.isReady || _piperStatus.value is PiperTtsStatus.Ready) {
            val cleanText = PersianNumbers.prepareTextForTts(text).ifBlank { text }
            speakWithPiper(cleanText, speechRate, persona)
        } else if (!httpClient.isOfflineStrict() &&
            (_piperStatus.value is PiperTtsStatus.NotInstalled ||
                _piperStatus.value is PiperTtsStatus.Error ||
                _piperStatus.value is PiperTtsStatus.Downloading)
        ) {
            // 100% self-contained: automatically download official verified Piper model when online & permitted!
            pendingAutoSpeakRequest = PendingAutoSpeak(text, speechRate, persona)
            if (_piperStatus.value !is PiperTtsStatus.Downloading) {
                downloadOfficialPiperModel()
            }
        }
    }

    suspend fun speakBlocking(
        text: String,
        speechRate: Float = 1.0f,
    ): Boolean = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext false
        if (!piperEngine.isReady) {
            val ready = refreshInstalledPiper()
            if (!ready) {
                if (httpClient.isOfflineStrict()) return@withContext false
                val dl = downloadOfficialPiperModelSync()
                if (dl.isFailure) return@withContext false
            }
        }
        val cleanText = PersianNumbers.prepareTextForTts(text).ifBlank { text }
        val grantedFocus = audioManager.requestSpeakingFocus {
            piperEngine.stop()
            _isPiperSpeaking.value = false
        }
        _isPiperSpeaking.value = true
        return@withContext try {
            playSegmentsPipelined(cleanText, speechRate)
        } catch (t: Throwable) {
            Log.e(TAG, "خطا در تولید یا پخش صدای پس‌زمینه Piper", t)
            false
        } finally {
            _isPiperSpeaking.value = false
            if (grantedFocus) {
                audioManager.abandonSpeakingFocus()
            }
        }
    }

    private fun speakWithPiper(
        text: String,
        speechRate: Float,
        persona: VoicePersona,
    ) {
        activeSpeakJob?.cancel()
        piperEngine.stop()
        _isPiperSpeaking.value = true

        val personaRateOffset = when (persona) {
            VoicePersona.FEMALE_CALM -> 0.96f
            VoicePersona.MALE_FORMAL -> 0.92f
            VoicePersona.NEUTRAL_FRIENDLY -> 1.0f
        }
        val effectiveRate = (speechRate * personaRateOffset).coerceIn(0.65f, 1.5f)

        activeSpeakJob = scope.launch {
            val grantedFocus = audioManager.requestSpeakingFocus {
                piperEngine.stop()
                _isPiperSpeaking.value = false
            }
            try {
                if (!piperEngine.isReady && _piperStatus.value is PiperTtsStatus.Ready) {
                    val trusted = runCatching { trustedCustomHashesProvider?.invoke() }.getOrNull()
                    if (!piperEngine.initialize(trustedCustomHashes = trusted, allowUnverifiedRisk = trusted != null)) {
                        return@launch
                    }
                }
                playSegmentsPipelined(text, effectiveRate)
            } catch (t: Throwable) {
                Log.e(TAG, "خطا در تولید یا پخش صدای Piper", t)
            } finally {
                _isPiperSpeaking.value = false
                if (grantedFocus) {
                    audioManager.abandonSpeakingFocus()
                }
            }
        }
    }

    fun beginStreamingSpeech(
        speechRate: Float = 1.0f,
        persona: VoicePersona = VoicePersona.FEMALE_CALM,
    ): StreamingSpeechSession? {
        if (!piperEngine.isReady && _piperStatus.value !is PiperTtsStatus.Ready) {
            return null
        }

        pendingAutoSpeakRequest = null
        activeSpeakJob?.cancel()
        piperEngine.stop()
        _isPiperSpeaking.value = true

        val personaRateOffset = when (persona) {
            VoicePersona.FEMALE_CALM -> 0.96f
            VoicePersona.MALE_FORMAL -> 0.92f
            VoicePersona.NEUTRAL_FRIENDLY -> 1.0f
        }
        val effectiveRate = (speechRate * personaRateOffset).coerceIn(0.65f, 1.5f)
        val channel = Channel<String>(Channel.UNLIMITED)

        val job = scope.launch {
            val grantedFocus = audioManager.requestSpeakingFocus {
                channel.close()
                piperEngine.stop()
                _isPiperSpeaking.value = false
            }
            var prefetched: Deferred<ShortArray?>? = null
            try {
                if (!piperEngine.isReady && _piperStatus.value is PiperTtsStatus.Ready) {
                    val trusted = runCatching { trustedCustomHashesProvider?.invoke() }.getOrNull()
                    if (!piperEngine.initialize(trustedCustomHashes = trusted, allowUnverifiedRisk = trusted != null)) {
                        return@launch
                    }
                }

                var currentRaw: String? = channel.receiveCatching().getOrNull()
                while (currentRaw != null && isActive) {
                    val cleanCurrent = PersianNumbers.prepareTextForTts(currentRaw).ifBlank { currentRaw }
                    val pcm = if (prefetched != null) {
                        val awaited = prefetched.await()
                        prefetched = null
                        awaited
                    } else {
                        piperEngine.synthesize(cleanCurrent, speechRate = effectiveRate)
                    }

                    // If next sentence already arrived while synthesizing, pre-synthesize it in parallel with playback
                    val queuedNext = channel.tryReceive().getOrNull()
                    if (queuedNext != null) {
                        val cleanNext = PersianNumbers.prepareTextForTts(queuedNext).ifBlank { queuedNext }
                        prefetched = async(Dispatchers.IO) {
                            piperEngine.synthesize(cleanNext, speechRate = effectiveRate)
                        }
                    }

                    if (pcm != null && pcm.isNotEmpty()) {
                        if (!piperEngine.play(pcm, piperEngine.sampleRate)) {
                            break
                        }
                    }

                    currentRaw = queuedNext ?: channel.receiveCatching().getOrNull()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "خطا در پخش جمله‌به‌جمله صدای Piper", t)
            } finally {
                prefetched?.cancel()
                channel.close()
                _isPiperSpeaking.value = false
                if (grantedFocus) {
                    audioManager.abandonSpeakingFocus()
                }
            }
        }
        activeSpeakJob = job

        return StreamingSpeechSession(
            channel = channel,
            job = job,
            onCancelPlayback = {
                piperEngine.stop()
                _isPiperSpeaking.value = false
            },
        )
    }

    private suspend fun playSegmentsPipelined(text: String, effectiveRate: Float): Boolean =
        withContext(Dispatchers.IO) {
            val segments = splitForSynthesis(text)
            if (segments.isEmpty()) return@withContext false

            var nextPrefetch: Deferred<ShortArray?>? = null
            var ok = true
            try {
                for (i in segments.indices) {
                    val pcm = nextPrefetch?.await()
                        ?: piperEngine.synthesize(segments[i], speechRate = effectiveRate)
                    nextPrefetch = null

                    if (pcm == null || pcm.isEmpty()) {
                        ok = false
                        break
                    }

                    // Pre-synthesize the next clause concurrently while AudioTrack streams the current clause
                    if (i + 1 < segments.size) {
                        val upcoming = segments[i + 1]
                        nextPrefetch = async(Dispatchers.IO) {
                            piperEngine.synthesize(upcoming, speechRate = effectiveRate)
                        }
                    }

                    if (!piperEngine.play(pcm, piperEngine.sampleRate)) {
                        ok = false
                        break
                    }
                }
            } finally {
                nextPrefetch?.cancel()
            }
            ok
        }

    fun stop() {
        pendingAutoSpeakRequest = null
        activeSpeakJob?.cancel()
        activeSpeakJob = null
        piperEngine.stop()
        _isPiperSpeaking.value = false
        audioManager.abandonSpeakingFocus()
    }

    fun disableAndDeletePiper() {
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        stop()
        piperEngine.release()
        runCatching { piperDir.deleteRecursively() }
        runCatching { legacyPiperDir.deleteRecursively() }
        scope.launch { runCatching { onClearTrustedCustomHashes?.invoke() } }
        _piperStatus.value = PiperTtsStatus.NotInstalled
    }

    fun importPiperUris(uris: List<Uri>): Result<String> {
        return try {
            piperDir.mkdirs()

            var importedAny = false
            for (uri in uris) {
                val displayName = resolveDisplayName(uri).lowercase()
                val target = when {
                    displayName.endsWith(".onnx") -> modelFile
                    displayName.endsWith(".json") -> configFile
                    else -> null
                } ?: continue

                val partFile = File(piperDir, "${target.name}.part")
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    partFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalArgumentException("فایل قابل خواندن نیست.")

                if (target === configFile) {
                    PiperConfig.parse(partFile)
                }
                if (target.exists()) target.delete()
                if (!partFile.renameTo(target)) {
                    partFile.copyTo(target, overwrite = true)
                    partFile.delete()
                }
                importedAny = true
            }

            if (!importedAny) {
                return Result.failure(
                    IllegalArgumentException("فایل معتبر با پسوند .onnx یا .json شناسایی نشد.")
                )
            }

            val activated = refreshInstalledPiper()
            when (val status = _piperStatus.value) {
                is PiperTtsStatus.Ready -> Result.success("مدل خودکفای Piper با موفقیت فعال شد.")
                is PiperTtsStatus.Downloading -> Result.success(status.stageFa)
                is PiperTtsStatus.PartialSelection -> Result.success(status.noteFa)
                is PiperTtsStatus.NeedsRiskConfirmation -> Result.success(status.reasonFa)
                is PiperTtsStatus.Error -> Result.failure(IllegalStateException(status.messageFa))
                PiperTtsStatus.NotInstalled -> {
                    if (activated) Result.success("مدل Piper فعال شد.")
                    else Result.failure(IllegalStateException("مدل Piper فعال نشد."))
                }
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val v = cursor.getString(idx)
                    if (!v.isNullOrBlank()) return v
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    fun release() {
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        stop()
        piperEngine.release()
    }

    companion object {
        private const val TAG = "TtsManager"
        private val SENTENCE_SPLIT_REGEX = Regex("(?:(?<!\\d)\\.(?!\\d)|[؟?!؛;\\n])+")

        internal fun splitForSynthesis(text: String, maxChunkChars: Int = 220): List<String> {
            val base = splitIntoSentences(text, maxChunkChars)
            if (base.size <= 1 && (base.firstOrNull()?.length ?: 0) <= 110) return base
            // Keep the first spoken phrase compact so time-to-first-audio is fast
            val first = base.first()
            if (first.length <= 110) return base
            val commaSplit = first.substring(0, 110).let { win ->
                val idx = maxOf(win.lastIndexOf('،'), win.lastIndexOf(','), win.lastIndexOf(' '))
                if (idx >= 24) idx else -1
            }
            if (commaSplit <= 0) return base
            val head = first.substring(0, commaSplit).trim()
            val tail = first.substring(commaSplit).trim().trimStart('،', ',', ' ')
            if (head.isEmpty() || tail.isEmpty()) return base
            return buildList(base.size + 1) {
                add(head)
                add(tail)
                for (k in 1 until base.size) add(base[k])
            }
        }

        fun splitIntoSentences(text: String, maxChunkChars: Int = 320): List<String> {
            val normalized = text.replace(Regex("\\s+"), " ").trim()
            if (normalized.isEmpty()) return emptyList()

            val rawPieces = normalized
                .split(SENTENCE_SPLIT_REGEX)
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (rawPieces.isEmpty()) return listOf(normalized.take(maxChunkChars))

            val boundedPieces = mutableListOf<String>()
            for (piece in rawPieces) {
                if (piece.length <= maxChunkChars) {
                    boundedPieces.add(piece)
                } else {
                    var remaining = piece
                    while (remaining.length > maxChunkChars) {
                        val window = remaining.substring(0, maxChunkChars)
                        val splitAt = listOf(
                            window.lastIndexOf('،'),
                            window.lastIndexOf(','),
                            window.lastIndexOf(' '),
                        ).maxOrNull()?.takeIf { it > maxChunkChars / 3 } ?: maxChunkChars
                        boundedPieces.add(remaining.substring(0, splitAt).trim())
                        remaining = remaining.substring(splitAt).trim()
                    }
                    if (remaining.isNotEmpty()) {
                        boundedPieces.add(remaining)
                    }
                }
            }

            val merged = mutableListOf<String>()
            for (piece in boundedPieces) {
                val last = merged.lastOrNull()
                if (last != null && (last.length < 18 || piece.length < 18) &&
                    last.length + piece.length + 2 <= maxChunkChars
                ) {
                    merged[merged.lastIndex] = "$last، $piece"
                } else {
                    merged.add(piece)
                }
            }
            return merged
        }
    }
}
