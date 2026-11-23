package com.example.sol.tts

import android.content.Context
import android.util.Log
import com.example.sol.memory.VoicePersona
import com.example.sol.voice.PersianTTS
import com.example.sol.voice.SolAudioManager
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

sealed interface PiperTtsStatus {
    data object NotInstalled : PiperTtsStatus

    data class PartialSelection(
        val noteFa: String
    ) : PiperTtsStatus

    data class Ready(
        val sampleRate: Int,
        val sizeMb: Int
    ) : PiperTtsStatus

    data class Error(
        val messageFa: String
    ) : PiperTtsStatus
}

class TtsManager(
    private val context: Context,
    private val systemTts: PersianTTS,
    private val audioManager: SolAudioManager
) {
    private val appContext = context.applicationContext
    private val piperDir = File(appContext.filesDir, "piper")
    private val modelFile = File(piperDir, "persian.onnx")
    private val configFile = File(piperDir, "persian.onnx.json")

    private val piperEngine = PiperTtsEngine(modelFile, configFile)
    private val speechGeneration = AtomicLong(0L)

    private val _isPiperSpeaking = MutableStateFlow(false)
    val isPiperSpeaking: StateFlow<Boolean> = _isPiperSpeaking.asStateFlow()

    private val _piperStatus =
        MutableStateFlow<PiperTtsStatus>(PiperTtsStatus.NotInstalled)
    val piperStatus: StateFlow<PiperTtsStatus> = _piperStatus.asStateFlow()

    init {
        refreshInstalledPiper()
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
                noteFa = "برای فعال‌سازی Piper هر دو فایل مدل ONNX و تنظیمات JSON لازم است."
            )
            return false
        }

        val initialized = piperEngine.initialize()

        _piperStatus.value = if (initialized) {
            val totalBytes = modelFile.length() + configFile.length()

            PiperTtsStatus.Ready(
                sampleRate = piperEngine.sampleRate,
                sizeMb = (totalBytes / (1024L * 1024L))
                    .toInt()
                    .coerceAtLeast(1)
            )
        } else {
            PiperTtsStatus.Error(
                messageFa = "راه‌اندازی موتور Piper ناموفق بود؛ فایل ONNX و تنظیمات JSON را بررسی کنید."
            )
        }

        return initialized
    }

    fun speak(
        text: String,
        speechRate: Float = 1.0f,
        persona: VoicePersona = VoicePersona.FEMALE_CALM,
        preferredVoiceName: String = ""
    ) {
        if (text.isBlank()) return

        if (piperEngine.isReady) {
            speakWithPiper(text)
        } else {
            speakWithSystem(
                text = text,
                speechRate = speechRate,
                persona = persona,
                preferredVoiceName = preferredVoiceName
            )
        }
    }

    private fun speakWithPiper(text: String) {
        val generation = speechGeneration.incrementAndGet()
        piperEngine.stop()
        _isPiperSpeaking.value = true

        Thread({
            val pcm = piperEngine.synthesize(text)
            if (generation != speechGeneration.get()) return@Thread

            val played = pcm != null &&
                pcm.isNotEmpty() &&
                piperEngine.play(pcm, piperEngine.sampleRate)

            if (generation != speechGeneration.get()) return@Thread

            _isPiperSpeaking.value = false
            if (!played) {
                Log.e(
                    "TtsManager",
                    "خطا در تولید یا پخش صدای Piper. استفاده از موتور سیستم."
                )
                speakWithSystem(text)
            }
        }, "Sol-Piper-TTS").start()
    }

    fun speakWithSystem(
        text: String,
        speechRate: Float = 1.0f,
        persona: VoicePersona = VoicePersona.FEMALE_CALM,
        preferredVoiceName: String = "",
        onDone: (() -> Unit)? = null
    ) {
        systemTts.speak(
            text = text,
            speechRate = speechRate,
            persona = persona,
            preferredVoiceName = preferredVoiceName
        )

        onDone?.invoke()
    }

    fun stop() {
        speechGeneration.incrementAndGet()
        piperEngine.stop()
        systemTts.stop()
        _isPiperSpeaking.value = false
        audioManager.abandonSpeakingFocus()
    }

    fun disableAndDeletePiper() {
        speechGeneration.incrementAndGet()
        piperEngine.release()
        piperDir.deleteRecursively()
        _piperStatus.value = PiperTtsStatus.NotInstalled
        _isPiperSpeaking.value = false
    }

    fun importPiperUris(uris: List<android.net.Uri>): Result<String> {
        return try {
            piperDir.mkdirs()

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
                        cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                android.provider.OpenableColumns.DISPLAY_NAME
                            )
                        )
                    } else {
                        ""
                    }
                }.orEmpty().lowercase()

                val target = when {
                    name.endsWith(".onnx") -> modelFile
                    name.endsWith(".json") -> configFile
                    else -> null
                } ?: return@forEach

                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalArgumentException("فایل قابل خواندن نیست.")

                if (target === modelFile) importedModel = true
                if (target === configFile) importedConfig = true
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
