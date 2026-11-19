package com.example.sol.voice

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.sol.memory.VoicePersona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class TtsAvailability(val labelFa: String) {
    INITIALIZING("در حال آماده‌سازی صدای Sol…"),
    READY_PERSIAN("صدای فارسی سیستم (TTS گوشی) آماده است"),
    READY_BUILTIN_PERSIAN("صدای فارسی داخلی Sol فعال است (بدون نیاز به نصب روی گوشی)"),
}

data class TtsVoiceInfo(
    val name: String,
    val localeTag: String,
    val isOffline: Boolean,
)

class PersianTTS(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val builtInEngine = BuiltInPersianSpeechEngine()

    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var triedGoogleFallback = false

    private val _availability = MutableStateFlow(TtsAvailability.INITIALIZING)
    val availability: StateFlow<TtsAvailability> = _availability.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _persianVoices = MutableStateFlow<List<TtsVoiceInfo>>(emptyList())
    val persianVoices: StateFlow<List<TtsVoiceInfo>> = _persianVoices.asStateFlow()

    private var systemPersianReady = false

    @Volatile
    private var lastSpokenRequest: Triple<String, Float, VoicePersona>? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            if (!triedGoogleFallback) {
                tryGoogleTtsEngine()
            } else {
                activateBuiltInFallback()
            }
            return
        }

        val engine = tts ?: run {
            activateBuiltInFallback()
            return
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                fallbackToBuiltInAfterSystemError()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _isSpeaking.value = false
                fallbackToBuiltInAfterSystemError()
            }
        })

        val resIr = engine.setLanguage(Locale("fa", "IR"))
        val resFa = if (resIr == TextToSpeech.LANG_MISSING_DATA || resIr == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale("fa"))
        } else {
            resIr
        }

        val availableVoices = runCatching {
            engine.voices
                ?.filter { it.locale.language.equals("fa", ignoreCase = true) }
                ?.map { voice ->
                    TtsVoiceInfo(
                        name = voice.name,
                        localeTag = voice.locale.toLanguageTag(),
                        isOffline = !voice.isNetworkConnectionRequired,
                    )
                }
                ?.sortedByDescending { it.isOffline }
                ?: emptyList()
        }.getOrDefault(emptyList())
        _persianVoices.value = availableVoices

        val hasOfflineSystemFa = resFa != TextToSpeech.LANG_MISSING_DATA &&
            resFa != TextToSpeech.LANG_NOT_SUPPORTED &&
            (availableVoices.isEmpty() || availableVoices.any { it.isOffline } || resFa == TextToSpeech.LANG_COUNTRY_AVAILABLE || resFa == TextToSpeech.LANG_AVAILABLE)

        if (hasOfflineSystemFa) {
            _availability.value = TtsAvailability.READY_PERSIAN
            systemPersianReady = true
        } else if (!triedGoogleFallback && hasGoogleTtsInstalled(engine)) {
            tryGoogleTtsEngine()
        } else {
            activateBuiltInFallback()
        }
    }

    private fun hasGoogleTtsInstalled(engine: TextToSpeech): Boolean {
        return runCatching {
            engine.engines.any { it.name == GOOGLE_TTS_PKG }
        }.getOrDefault(false)
    }

    private fun tryGoogleTtsEngine() {
        triedGoogleFallback = true
        runCatching {
            tts?.stop()
            tts?.shutdown()
            tts = TextToSpeech(appContext, this, GOOGLE_TTS_PKG)
        }.onFailure {
            activateBuiltInFallback()
        }
    }

    private fun activateBuiltInFallback() {
        systemPersianReady = false
        _availability.value = TtsAvailability.READY_BUILTIN_PERSIAN
    }

    private fun fallbackToBuiltInAfterSystemError() {
        val pending = lastSpokenRequest ?: return
        lastSpokenRequest = null
        activateBuiltInFallback()
        builtInEngine.speak(
            text = pending.first,
            speechRate = pending.second,
            persona = pending.third,
            onStart = { _isSpeaking.value = true },
            onDone = { _isSpeaking.value = false },
        )
    }

    fun speak(
        text: String,
        speechRate: Float = 1.0f,
        persona: VoicePersona = VoicePersona.FEMALE_CALM,
        preferredVoiceName: String = "",
    ) {
        if (text.isBlank()) return
        stop()

        val engine = tts
        if (systemPersianReady && engine != null) {
            val cleanForTts = text
                .replace(Regex("https?://\\S+"), "لینک")
                .replace("•", "،")
                .replace(Regex("[🎭📘🔎🎙⚠️✅❌🎙]"), "")
                .trim()
            if (cleanForTts.isBlank()) return

            if (preferredVoiceName.isNotBlank()) {
                val match: Voice? = runCatching {
                    engine.voices?.firstOrNull { it.name == preferredVoiceName }
                }.getOrNull()
                if (match != null) {
                    engine.voice = match
                }
            }
            val rate = speechRate.coerceIn(0.6f, 1.5f)
            engine.setSpeechRate(rate)
            engine.setPitch(persona.pitch)
            lastSpokenRequest = Triple(cleanForTts, rate, persona)

            val result = engine.speak(cleanForTts, TextToSpeech.QUEUE_FLUSH, null, "sol_${System.currentTimeMillis()}")
            if (result == TextToSpeech.SUCCESS) {
                return
            }
        }

        // Built-in in-app Persian speech synthesizer (always speaks!)
        lastSpokenRequest = null
        builtInEngine.speak(
            text = text,
            speechRate = speechRate,
            persona = persona,
            onStart = { _isSpeaking.value = true },
            onDone = { _isSpeaking.value = false },
        )
    }

    fun openSystemTtsSettings() {
        val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appContext.startActivity(intent) }
    }

    fun recheckSystemVoices() {
        triedGoogleFallback = false
        systemPersianReady = false
        _availability.value = TtsAvailability.INITIALIZING
        runCatching {
            tts?.stop()
            tts?.shutdown()
            tts = TextToSpeech(appContext, this)
        }.onFailure {
            activateBuiltInFallback()
        }
    }

    fun stop() {
        lastSpokenRequest = null
        runCatching { tts?.stop() }
        builtInEngine.stop()
        _isSpeaking.value = false
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        systemPersianReady = false
        _isSpeaking.value = false
    }

    companion object {
        private const val GOOGLE_TTS_PKG = "com.google.android.tts"
    }
}
