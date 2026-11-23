package com.example.sol.tts

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.example.sol.memory.VoicePersona
import com.example.sol.voice.PersianTTS
import com.example.sol.voice.SolAudioManager
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PiperTtsStatus {
    READY,
    MODEL_MISSING,
    INIT_FAILED,
    DISABLED
}

class TtsManager(
    private val context: Context,
    private val systemTts: PersianTTS,
    private val audioManager: SolAudioManager
) {
    private val appContext = context.applicationContext
    private val filesDir = appContext.filesDir
    private val piperDir = File(filesDir, "piper")
    private val modelFile = File(piperDir, "persian.onnx")
    private val configFile = File(piperDir, "persian.onnx.json")

    private val piperEngine = PiperTtsEngine(modelFile, configFile)

    private val _isPiperSpeaking = MutableStateFlow(false)
    val isPiperSpeaking: StateFlow<Boolean> = _isPiperSpeaking.asStateFlow()

    private val _piperStatus = MutableStateFlow(PiperTtsStatus.DISABLED)
    val piperStatus: StateFlow<PiperTtsStatus> = _piperStatus.asStateFlow()

    init {
        refreshInstalledPiper()
    }

    fun refreshInstalledPiper(): Boolean {
        if (!modelFile.exists() || !configFile.exists()) {
            piperEngine.release()
            _piperStatus.value = PiperTtsStatus.MODEL_MISSING
            return false
        }

        val initialized = piperEngine.initialize()
        _piperStatus.value =
            if (initialized) PiperTtsStatus.READY
            else PiperTtsStatus.INIT_FAILED

        return initialized
    }

    fun speak(
        text: String,
        speechRate: Float = 1.0f,
        persona: VoicePersona = VoicePersona.Natural,
        preferredVoiceName: String = ""
    ) {
        if (text.isBlank()) return

        if (piperEngine.isReady) {
            speakWithPiper(text)
        } else {
            speakWithSystem(text)
        }
    }

    private fun speakWithPiper(text: String) {
        _isPiperSpeaking.value = true

        Thread {
            val pcm = piperEngine.synthesize(text)
            val played = pcm != null &&
                pcm.isNotEmpty() &&
                piperEngine.play(pcm, piperEngine.sampleRate)

            if (played) {
                onPiperDone()
            } else {
                Log.e(
                    "TtsManager",
                    "خطا در تولید یا پخش صدای Piper. استفاده از موتور سیستم."
                )
                _isPiperSpeaking.value = false
                speakWithSystem(text)
            }
        }.start()
    }

    private fun onPiperDone() {
        _isPiperSpeaking.value = false
    }

    fun speakWithSystem(
        text: String,
        onDone: (() -> Unit)? = null
    ) {
        systemTts.speak(text)
        onDone?.invoke()
    }

    fun stop() {
        piperEngine.stop()
        systemTts.stop()
        _isPiperSpeaking.value = false
        audioManager.abandonSpeakingFocus()
    }

    fun disableAndDeletePiper() {
        piperEngine.release()
        piperDir.deleteRecursively()
        _piperStatus.value = PiperTtsStatus.DISABLED
        _isPiperSpeaking.value = false
    }

    fun importPiperUris(uris: List<android.net.Uri>): Result<String> {
        return try {
            piperDir.mkdirs()

            val importFile = { uri: android.net.Uri, target: File ->
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalArgumentException("فایل قابل خواندن نیست.")
            }

            var importedModel = false
            var importedConfig = false

            uris.forEach { uri ->
                val name = appContext.contentResolver.query(
                    uri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(cursor.getColumnIndexOrThrow(
                            android.provider.OpenableColumns.DISPLAY_NAME
                        ))
                    } else {
                        ""
                    }
                }.orEmpty()

                val lower = name.lowercase()
                when {
                    lower.endsWith(".onnx") ->
                        importFile(uri, modelFile).also { importedModel = true }

                    lower.endsWith(".json") ->
                        importFile(uri, configFile).also { importedConfig = true }

                    else -> Unit
                }
            }

            if (!importedModel || !importedConfig) {
                throw IllegalArgumentException(
                    "هر دو فایل ONNX و JSON را انتخاب کنید."
                )
            }

            if (refreshInstalledPiper()) {
                Result.success("مدل Piper با موفقیت فعال شد.")
            } else {
                Result.failure(Exception("مدل Piper فعال نشد."))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun release() {
        stop()
        piperEngine.release()
    }
}
