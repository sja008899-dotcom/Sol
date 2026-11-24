package com.example.sol.tts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.sol.memory.VoicePersona
import com.example.sol.voice.PersianTTS
import com.example.sol.voice.SolAudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface PiperTtsStatus {
    data object NotInstalled : PiperTtsStatus

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

class TtsManager(
    context: Context,
    private val systemTts: PersianTTS,
    private val audioManager: SolAudioManager,
    private val trustedCustomHashesProvider: (suspend () -> Pair<String, String>?)? = null,
    private val onSaveTrustedCustomHashes: (suspend (onnxSha: String, configSha: String) -> Unit)? = null,
    private val onClearTrustedCustomHashes: (suspend () -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val piperDir = File(appContext.filesDir, "tts/piper")
    private val modelFile = File(piperDir, "fa_IR.onnx")
    private val configFile = File(piperDir, "fa_IR.onnx.json")

    // Legacy location used in intermediate build; migrate transparently if present
    private val legacyPiperDir = File(appContext.filesDir, "piper")

    private val piperEngine = PiperTtsEngine(modelFile, configFile)

    @Volatile
    private var activeSpeakJob: Job? = null

    private val _isPiperSpeaking = MutableStateFlow(false)
    val isPiperSpeaking: StateFlow<Boolean> = _isPiperSpeaking.asStateFlow()

    private val _piperStatus = MutableStateFlow<PiperTtsStatus>(PiperTtsStatus.NotInstalled)
    val piperStatus: StateFlow<PiperTtsStatus> = _piperStatus.asStateFlow()

    init {
        migrateLegacyPiperIfPresent()
        refreshInstalledPiper()
    }

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
            _piperStatus.value = PiperTtsStatus.NotInstalled
            return false
        }

        if (!hasModel || !hasConfig) {
            piperEngine.release()
            _piperStatus.value = PiperTtsStatus.PartialSelection(
                noteFa = if (hasModel) {
                    "فایل مدل (.onnx) انتخاب شده؛ اکنون فایل تنظیمات JSON مربوط به همان مدل را نیز انتخاب کنید."
                } else {
                    "فایل تنظیمات (.json) انتخاب شده؛ اکنون فایل مدل ONNX مربوط به همان صدا را نیز انتخاب کنید."
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
            true
        } else {
            _piperStatus.value = PiperTtsStatus.Error(
                messageFa = piperEngine.lastErrorFa.ifBlank {
                    "راه‌اندازی موتور Piper ناموفق بود؛ فایل ONNX و تنظیمات JSON را بررسی کنید."
                },
            )
            false
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
        preferredVoiceName: String = "",
    ) {
        if (text.isBlank()) return

        val readyOrLazyInit = piperEngine.isReady || (
            _piperStatus.value is PiperTtsStatus.Ready &&
                piperEngine.initialize(
                    trustedCustomHashes = runCatching {
                        kotlinx.coroutines.runBlocking { trustedCustomHashesProvider?.invoke() }
                    }.getOrNull(),
                )
            )

        if (readyOrLazyInit) {
            speakWithPiper(text, speechRate, persona, preferredVoiceName)
        } else {
            speakWithSystem(
                text = text,
                speechRate = speechRate,
                persona = persona,
                preferredVoiceName = preferredVoiceName,
            )
        }
    }

    private fun speakWithPiper(
        text: String,
        speechRate: Float,
        persona: VoicePersona,
        preferredVoiceName: String,
    ) {
        activeSpeakJob?.cancel()
        piperEngine.stop()
        _isPiperSpeaking.value = true

        activeSpeakJob = scope.launch {
            var shouldFallbackToSystem = false
            val grantedFocus = audioManager.requestSpeakingFocus {
                piperEngine.stop()
                _isPiperSpeaking.value = false
            }
            try {
                val segments = splitForSynthesis(text)
                for (segment in segments) {
                    val pcm = piperEngine.synthesize(segment, speechRate = speechRate)
                    if (pcm == null || pcm.isEmpty() || !piperEngine.play(pcm, piperEngine.sampleRate)) {
                        shouldFallbackToSystem = true
                        break
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "خطا در تولید یا پخش صدای Piper", t)
                shouldFallbackToSystem = true
            } finally {
                _isPiperSpeaking.value = false
                if (grantedFocus) {
                    audioManager.abandonSpeakingFocus()
                }
            }
            if (shouldFallbackToSystem) {
                withContext(Dispatchers.Main) {
                    speakWithSystem(
                        text = text,
                        speechRate = speechRate,
                        persona = persona,
                        preferredVoiceName = preferredVoiceName,
                    )
                }
            }
        }
    }

    fun speakWithSystem(
        text: String,
        speechRate: Float = 1.0f,
        persona: VoicePersona = VoicePersona.FEMALE_CALM,
        preferredVoiceName: String = "",
        onDone: (() -> Unit)? = null,
    ) {
        systemTts.speak(
            text = text,
            speechRate = speechRate,
            persona = persona,
            preferredVoiceName = preferredVoiceName,
        )
        onDone?.invoke()
    }

    fun stop() {
        activeSpeakJob?.cancel()
        activeSpeakJob = null
        piperEngine.stop()
        systemTts.stop()
        _isPiperSpeaking.value = false
        audioManager.abandonSpeakingFocus()
    }

    fun disableAndDeletePiper() {
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
                is PiperTtsStatus.Ready -> Result.success("مدل Piper با موفقیت فعال شد.")
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
        stop()
        piperEngine.release()
    }

    companion object {
        private const val TAG = "TtsManager"

        internal fun splitForSynthesis(text: String, maxChunkChars: Int = 260): List<String> {
            val cleaned = text.trim()
            if (cleaned.length <= maxChunkChars) return listOf(cleaned)
            val result = mutableListOf<String>()
            val current = StringBuilder()
            val parts = cleaned.split(Regex("(?<=[.؟!؛\\n])\\s*"))
            for (part in parts) {
                val trimmed = part.trim()
                if (trimmed.isEmpty()) continue
                if (current.length + trimmed.length + 1 <= maxChunkChars) {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(trimmed)
                } else {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.clear()
                    }
                    if (trimmed.length <= maxChunkChars) {
                        current.append(trimmed)
                    } else {
                        var start = 0
                        while (start < trimmed.length) {
                            val end = (start + maxChunkChars).coerceAtMost(trimmed.length)
                            result.add(trimmed.substring(start, end))
                            start = end
                        }
                    }
                }
            }
            if (current.isNotEmpty()) result.add(current.toString())
            return result.ifEmpty { listOf(cleaned) }
        }
    }
}
