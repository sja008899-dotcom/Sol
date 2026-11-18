package com.example.sol.voice

import android.content.Context
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
    READY_PERSIAN("صدای فارسی محلی آماده است"),
    MISSING_PERSIAN_DATA("بستهٔ صدای فارسی روی موتور TTS گوشی نصب نیست (پاسخ متنی فعال)"),
    UNAVAILABLE("موتور گفتار در دستگاه در دسترس نیست"),
}

data class TtsVoiceInfo(
    val name: String,
    val localeTag: String,
    val isOffline: Boolean,
)

class PersianTTS(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)

    private val _availability = MutableStateFlow(TtsAvailability.INITIALIZING)
    val availability: StateFlow<TtsAvailability> = _availability.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _persianVoices = MutableStateFlow<List<TtsVoiceInfo>>(emptyList())
    val persianVoices: StateFlow<List<TtsVoiceInfo>> = _persianVoices.asStateFlow()

    private var ready = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            _availability.value = TtsAvailability.UNAVAILABLE
            return
        }
        val engine = tts ?: return
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
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _isSpeaking.value = false
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

        if (resFa == TextToSpeech.LANG_MISSING_DATA || resFa == TextToSpeech.LANG_NOT_SUPPORTED) {
            _availability.value = TtsAvailability.MISSING_PERSIAN_DATA
            ready = false
        } else {
            _availability.value = TtsAvailability.READY_PERSIAN
            ready = true
        }
    }

    fun speak(
        text: String,
        speechRate: Float = 1.0f,
        persona: VoicePersona = VoicePersona.FEMALE_CALM,
        preferredVoiceName: String = "",
    ) {
        if (!ready || text.isBlank()) return
        val engine = tts ?: return
        if (preferredVoiceName.isNotBlank()) {
            val match: Voice? = runCatching {
                engine.voices?.firstOrNull { it.name == preferredVoiceName }
            }.getOrNull()
            if (match != null) {
                engine.voice = match
            }
        }
        engine.setSpeechRate(speechRate.coerceIn(0.6f, 1.5f))
        engine.setPitch(persona.pitch)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sol_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        _isSpeaking.value = false
    }
}
