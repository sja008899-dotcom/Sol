package com.example.sol.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.sol.memory.PersianNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface VoiceListeningState {
    data object Idle : VoiceListeningState
    data object Listening : VoiceListeningState
    data object WakeWordArmed : VoiceListeningState
    data object Processing : VoiceListeningState
    data class Error(val messageFa: String) : VoiceListeningState
}

enum class ActiveAsrBackend(val labelFa: String) {
    VOSK_OFFLINE("موتور ۱۰۰٪ آفلاین Vosk فارسی"),
    ANDROID_SPEECH_RECOGNIZER("تشخیص گفتار سیستم اندروید (fa-IR)"),
}

class VoiceEngine(
    private val context: Context,
    val voskEngine: VoskVoiceEngine = VoskVoiceEngine(context),
) {
    private var recognizer: SpeechRecognizer? = null

    private val _state = MutableStateFlow<VoiceListeningState>(VoiceListeningState.Idle)
    val state: StateFlow<VoiceListeningState> = _state.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _rmsDb = MutableStateFlow(0f)
    val rmsDb: StateFlow<Float> = _rmsDb.asStateFlow()

    private val _activeBackend = MutableStateFlow(resolvePreferredBackend(preferVosk = true))
    val activeBackend: StateFlow<ActiveAsrBackend> = _activeBackend.asStateFlow()

    fun resolvePreferredBackend(preferVosk: Boolean): ActiveAsrBackend {
        return if (preferVosk && voskEngine.isModelInstalled()) {
            ActiveAsrBackend.VOSK_OFFLINE
        } else {
            ActiveAsrBackend.ANDROID_SPEECH_RECOGNIZER
        }
    }

    fun refreshBackend(preferVosk: Boolean) {
        _activeBackend.value = resolvePreferredBackend(preferVosk)
    }

    fun isAvailable(): Boolean = voskEngine.isModelInstalled() || SpeechRecognizer.isRecognitionAvailable(context)

    fun updateVocabularyBias(terms: List<String>) {
        voskEngine.vocabularyBias.updateTerms(terms)
    }

    fun startListening(
        preferVosk: Boolean = true,
        grammarJson: String? = null,
        onFinalResult: (String) -> Unit,
    ) {
        stopListening()
        _partialText.value = ""
        _rmsDb.value = 0f

        val useVosk = preferVosk && voskEngine.isModelInstalled()
        if (useVosk) {
            _activeBackend.value = ActiveAsrBackend.VOSK_OFFLINE
            _state.value = VoiceListeningState.Listening
            voskEngine.startListening(
                grammarJson = grammarJson,
                isWakeWordMode = false,
                onPartial = { partial -> _partialText.value = partial },
                onRmsChanged = { rms -> _rmsDb.value = rms },
                onFinalResult = { finalTxt ->
                    _rmsDb.value = 0f
                    _partialText.value = ""
                    _state.value = VoiceListeningState.Idle
                    onFinalResult(finalTxt)
                },
                onError = { errMsg ->
                    _rmsDb.value = 0f
                    _state.value = VoiceListeningState.Error(errMsg)
                },
            )
            return
        }

        _activeBackend.value = ActiveAsrBackend.ANDROID_SPEECH_RECOGNIZER
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = VoiceListeningState.Error(
                "سرویس گفتار گوشی در دسترس نیست و مدل آفلاین Vosk نیز هنوز نصب نشده است."
            )
            return
        }

        val instance = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        recognizer = instance
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

        instance.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = VoiceListeningState.Listening
            }

            override fun onBeginningOfSpeech() {
                _state.value = VoiceListeningState.Listening
            }

            override fun onRmsChanged(rmsdB: Float) {
                _rmsDb.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                _state.value = VoiceListeningState.Processing
                _rmsDb.value = 0f
            }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "خطا در دریافت صدا از میکروفون"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "مجوز میکروفون صادر نشده است"
                    SpeechRecognizer.ERROR_NO_MATCH -> "صدایی تشخیص داده نشد؛ لطفاً واضح‌تر بگویید یا تایپ کنید"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "زمان انتظار برای شروع صحبت تمام شد"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "موتور تشخیص گفتار مشغول است"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "اینترنت وصل نیست و بستهٔ آفلاین فارسی گوگل هم نصب نیست؛ از بخش تنظیمات مدل آفلاین Vosk را نصب کنید."
                    else -> "تشخیص گفتار ناموفق بود (کد $error)"
                }
                _state.value = VoiceListeningState.Error(msg)
                _rmsDb.value = 0f
                cleanupRecognizer()
            }

            override fun onResults(results: Bundle?) {
                val candidates = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                val correctedCandidates = candidates.map { voskEngine.vocabularyBias.applyBiasCorrection(it) }
                val best = candidates.zip(correctedCandidates)
                    .firstOrNull { (orig, corr) -> orig != corr }
                    ?.second
                    ?: correctedCandidates.firstOrNull().orEmpty()
                _rmsDb.value = 0f
                _partialText.value = ""
                _state.value = VoiceListeningState.Idle
                cleanupRecognizer()
                if (best.isNotBlank()) {
                    onFinalResult(best)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (partial.isNotBlank()) {
                    _partialText.value = voskEngine.vocabularyBias.applyBiasCorrection(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        runCatching {
            _state.value = VoiceListeningState.Listening
            instance.startListening(intent)
        }.onFailure {
            _state.value = VoiceListeningState.Error("شروع شنود ناموفق بود: ${it.localizedMessage ?: "خطای ناشناخته"}")
            cleanupRecognizer()
        }
    }

    /**
     * Dedicated lightweight wake-word ("Sol" / "سول") listener using Vosk grammar mode when installed.
     */
    fun startWakeWordListening(onWakeDetected: () -> Unit) {
        if (!voskEngine.isModelInstalled()) {
            _state.value = VoiceListeningState.Error(
                "برای گوش‌به‌زنگ کلمه بیدارباش «Sol» بدون اینترنت، ابتدا مدل آفلاین Vosk را در تنظیمات نصب کنید."
            )
            return
        }
        stopListening()
        _activeBackend.value = ActiveAsrBackend.VOSK_OFFLINE
        _state.value = VoiceListeningState.WakeWordArmed
        voskEngine.startListening(
            grammarJson = voskEngine.vocabularyBias.buildWakeWordGrammarJson(),
            isWakeWordMode = true,
            onPartial = {},
            onRmsChanged = { rms -> _rmsDb.value = rms * 0.4f },
            onFinalResult = {
                _state.value = VoiceListeningState.Idle
                onWakeDetected()
            },
            onError = { err ->
                _state.value = VoiceListeningState.Error(err)
            },
        )
    }

    /**
     * Confirmation voice listener returning TRUE (تأیید), FALSE (لغو), or null (unclear).
     */
    fun startConfirmationVoiceListening(
        preferVosk: Boolean = true,
        onDecision: (confirmed: Boolean?, spokenText: String) -> Unit,
    ) {
        val grammar = voskEngine.vocabularyBias.buildConfirmationGrammarJson()
        startListening(preferVosk = preferVosk, grammarJson = grammar) { spoken ->
            val decision = parseVoiceConfirmationDecision(spoken)
            onDecision(decision, spoken)
        }
    }

    fun stopListening() {
        voskEngine.stopListening()
        runCatching { recognizer?.stopListening() }
        cleanupRecognizer()
        _rmsDb.value = 0f
        if (_state.value is VoiceListeningState.Listening ||
            _state.value is VoiceListeningState.Processing ||
            _state.value is VoiceListeningState.WakeWordArmed
        ) {
            _state.value = VoiceListeningState.Idle
        }
    }

    fun clearError() {
        if (_state.value is VoiceListeningState.Error) {
            _state.value = VoiceListeningState.Idle
        }
    }

    private fun cleanupRecognizer() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    companion object {
        fun parseVoiceConfirmationDecision(raw: String): Boolean? {
            val norm = PersianNormalizer.normalize(raw)
            val confirmTokens = listOf("تایید", "تأیید", "بله", "اره", "آره", "اوکی", "انجام بده", "بفرست", "زنگ بزن", "باشه")
            val cancelTokens = listOf("لغو", "نه", "خیر", "کنسل", "منصرف", "متوقف", "ببند", "نفرست")
            return when {
                cancelTokens.any { norm.contains(it) } -> false
                confirmTokens.any { norm.contains(it) } -> true
                else -> null
            }
        }
    }
}
