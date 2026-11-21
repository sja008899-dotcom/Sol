package com.example.sol.tts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.sol.memory.VoicePersona
import com.example.sol.text.PersianNumbers
import com.example.sol.voice.PersianTTS
import com.example.sol.voice.SolAudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface PiperTtsStatus {
    data object NotInstalled : PiperTtsStatus
    data class PartialSelection(
        val hasOnnx: Boolean,
        val hasJson: Boolean,
        val noteFa: String,
    ) : PiperTtsStatus

    data class Ready(
        val sampleRate: Int,
        val sizeMb: Int,
    ) : PiperTtsStatus

    data class Error(val messageFa: String) : PiperTtsStatus
}

/**
 * Orchestrates self-contained offline Piper ONNX TTS and falls back cleanly to system [PersianTTS].
 * Rejects mismatched SHA-256 model/config files without crashing.
 */
class TtsManager(
    context: Context,
    private val systemTts: PersianTTS,
    private val audioManager: SolAudioManager? = null,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val piperDir: File = File(appContext.filesDir, "tts/piper")
    val modelFile: File get() = File(piperDir, ONNX_FILE_NAME)
    val configFile: File get() = File(piperDir, CONFIG_FILE_NAME)

    private var piperEngine: PiperTtsEngine? = null
    private var playbackJob: Job? = null
    private var preferPiper: Boolean = true

    private val _piperStatus = MutableStateFlow<PiperTtsStatus>(PiperTtsStatus.NotInstalled)
    val piperStatus: StateFlow<PiperTtsStatus> = _piperStatus.asStateFlow()

    private val _isPiperSpeaking = MutableStateFlow(false)
    val isPiperSpeaking: StateFlow<Boolean> = _isPiperSpeaking.asStateFlow()

    val isPiperAvailable: Boolean
        get() = piperEngine?.isReady == true

    init {
        refreshInstalledPiper()
    }

    fun refreshInstalledPiper() {
        val hasOnnx = modelFile.exists() && modelFile.length() > 0L
        val hasJson = configFile.exists() && configFile.length() > 0L
        when {
            !hasOnnx && !hasJson -> {
                piperEngine?.release()
                piperEngine = null
                _piperStatus.value = PiperTtsStatus.NotInstalled
            }
            !hasOnnx || !hasJson -> {
                piperEngine?.release()
                piperEngine = null
                val missing = if (!hasOnnx) "مدل (.onnx)" else "تنظیمات (.json)"
                _piperStatus.value = PiperTtsStatus.PartialSelection(
                    hasOnnx = hasOnnx,
                    hasJson = hasJson,
                    noteFa = "فقط یک فایل انتخاب شده است؛ برای فعال‌سازی موتور خودکفا، فایل $missing را هم انتخاب کنید.",
                )
            }
            else -> {
                val engine = PiperTtsEngine(modelFile, configFile, verifyChecksums = true)
                if (engine.initialize()) {
                    piperEngine?.release()
                    piperEngine = engine
                    val sizeMb = ((modelFile.length() + configFile.length()) / (1024L * 1024L)).toInt().coerceAtLeast(1)
                    _piperStatus.value = PiperTtsStatus.Ready(
                        sampleRate = engine.sampleRate,
                        sizeMb = sizeMb,
                    )
                } else {
                    engine.release()
                    piperEngine?.release()
                    piperEngine = null
                    _piperStatus.value = PiperTtsStatus.Error(
                        engine.lastErrorFa.ifBlank { "بارگذاری فایل‌های Piper ناموفق بود." }
                    )
                }
            }
        }
    }

    fun setPreferPiper(enabled: Boolean) {
        preferPiper = enabled
    }

    /**
     * Imports one or two user-picked SAF documents (.onnx and/or .json), verifying exact SHA-256
     * before placing them into internal storage. Never crashes on wrong files.
     */
    suspend fun importPiperUris(uris: List<Uri>): Result<String> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("هیچ فایلی انتخاب نشد."))
        }
        piperDir.mkdirs()

        var onnxImported = false
        var jsonImported = false
        val errors = mutableListOf<String>()

        for (uri in uris) {
            val displayName = resolveDisplayName(uri).lowercase()
            val tempFile = File(piperDir, "candidate_${System.nanoTime()}.tmp")
            try {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { out -> input.copyTo(out) }
                } ?: run {
                    errors.add("باز کردن فایل «$displayName» ناموفق بود.")
                    continue
                }

                if (!tempFile.exists() || tempFile.length() == 0L) {
                    errors.add("فایل انتخاب‌شده خالی است.")
                    continue
                }

                val sha = PiperConfig.sha256(tempFile)
                val looksJson = displayName.endsWith(".json") || isLikelyJsonFile(tempFile)
                val looksOnnx = displayName.endsWith(".onnx") || !looksJson

                when {
                    looksJson -> {
                        if (!PiperConfig.verifyConfigHash(sha)) {
                            val msg = "فایل تنظیمات (.json) با اثر انگشت SHA-256 تأییدشده مطابقت ندارد و پذیرفته نشد."
                            errors.add(msg)
                            _piperStatus.value = PiperTtsStatus.Error(msg)
                            continue
                        }
                        val parsed = runCatching { PiperConfig.parse(tempFile) }
                        if (parsed.isFailure) {
                            val msg = parsed.exceptionOrNull()?.message ?: "ساختار فایل تنظیمات Piper معتبر نیست."
                            errors.add(msg)
                            _piperStatus.value = PiperTtsStatus.Error(msg)
                            continue
                        }
                        if (configFile.exists()) configFile.delete()
                        if (!tempFile.renameTo(configFile)) {
                            tempFile.copyTo(configFile, overwrite = true)
                        }
                        jsonImported = true
                    }
                    looksOnnx -> {
                        if (!PiperConfig.verifyOnnxHash(sha)) {
                            val msg = "فایل مدل (.onnx) با اثر انگشت SHA-256 تأییدشده مطابقت ندارد و پذیرفته نشد."
                            errors.add(msg)
                            _piperStatus.value = PiperTtsStatus.Error(msg)
                            continue
                        }
                        if (modelFile.exists()) modelFile.delete()
                        if (!tempFile.renameTo(modelFile)) {
                            tempFile.copyTo(modelFile, overwrite = true)
                        }
                        onnxImported = true
                    }
                }
            } catch (t: Throwable) {
                val msg = "بررسی فایل Piper ناموفق بود: ${t.localizedMessage ?: "خطای ناشناخته"}"
                errors.add(msg)
                _piperStatus.value = PiperTtsStatus.Error(msg)
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        }

        if (errors.isNotEmpty() && !onnxImported && !jsonImported) {
            return@withContext Result.failure(IllegalArgumentException(errors.first()))
        }

        refreshInstalledPiper()

        return@withContext when (val status = _piperStatus.value) {
            is PiperTtsStatus.Ready -> {
                Result.success("موتور خودکفای Piper با موفقیت تأیید SHA-256 و فعال شد (${status.sampleRate} Hz).")
            }
            is PiperTtsStatus.PartialSelection -> {
                Result.success(status.noteFa)
            }
            is PiperTtsStatus.Error -> {
                Result.failure(IllegalArgumentException(status.messageFa))
            }
            PiperTtsStatus.NotInstalled -> {
                Result.failure(IllegalArgumentException(errors.firstOrNull() ?: "فایل معتبری وارد نشد."))
            }
        }
    }

    fun disableAndDeletePiper() {
        stop()
        piperEngine?.release()
        piperEngine = null
        runCatching { modelFile.delete() }
        runCatching { configFile.delete() }
        _piperStatus.value = PiperTtsStatus.NotInstalled
    }

    fun speak(
        text: String,
        speechRate: Float = 1.0f,
        persona: VoicePersona = VoicePersona.FEMALE_CALM,
        preferredVoiceName: String = "",
        onDone: (() -> Unit)? = null,
    ) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        val activePiper = piperEngine
        if (preferPiper && activePiper != null && activePiper.isReady) {
            val clean = PersianNumbers.prepareTextForTts(text)
            val chunks = PersianTTS.splitIntoSentences(clean)
            if (chunks.isEmpty()) {
                onDone?.invoke()
                return
            }
            stop()
            _isPiperSpeaking.value = true
            audioManager?.requestSpeakingFocus { stop() }
            playbackJob = scope.launch {
                var spokeAny = false
                try {
                    for (chunk in chunks) {
                        if (!isActive) break
                        val pcm = activePiper.synthesize(chunk, speechRate = speechRate)
                        if (pcm != null && pcm.isNotEmpty()) {
                            spokeAny = true
                            activePiper.play(pcm, activePiper.sampleRate)
                        }
                    }
                } finally {
                    _isPiperSpeaking.value = false
                    audioManager?.abandonSpeakingFocus()
                }
                if (!spokeAny && isActive) {
                    withContext(Dispatchers.Main) {
                        systemTts.speak(text, speechRate, persona, preferredVoiceName)
                    }
                }
                onDone?.invoke()
            }
            return
        }

        systemTts.speak(
            text = text,
            speechRate = speechRate,
            persona = persona,
            preferredVoiceName = preferredVoiceName,
        )
        onDone?.invoke()
    }

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        piperEngine?.stop()
        _isPiperSpeaking.value = false
        systemTts.stop()
    }

    fun release() {
        stop()
        piperEngine?.release()
        piperEngine = null
    }

    private fun resolveDisplayName(uri: Uri): String {
        runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return cursor.getString(idx).orEmpty()
                    }
                }
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun isLikelyJsonFile(file: File): Boolean {
        return runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(128)
                val n = input.read(head)
                if (n <= 0) return false
                val prefix = String(head, 0, n, Charsets.UTF_8).trimStart()
                prefix.startsWith("{") || prefix.startsWith("[")
            }
        }.getOrDefault(false)
    }

    companion object {
        const val ONNX_FILE_NAME = "fa_IR-piper.onnx"
        const val CONFIG_FILE_NAME = "fa_IR-piper.onnx.json"
    }
}
