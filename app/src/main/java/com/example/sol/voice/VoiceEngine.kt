package com.example.sol.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface VoiceListeningState {
    data object Idle : VoiceListeningState
    data object Listening : VoiceListeningState
    data object Processing : VoiceListeningState
    data class Error(val messageFa: String) : VoiceListeningState
}

class VoiceEngine(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    private val _state = MutableStateFlow<VoiceListeningState>(VoiceListeningState.Idle)
    val state: StateFlow<VoiceListeningState> = _state.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _rmsDb = MutableStateFlow(0f)
    val rmsDb: StateFlow<Float> = _rmsDb.asStateFlow()

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(onFinalResult: (String) -> Unit) {
        if (!isAvailable()) {
            _state.value = VoiceListeningState.Error(
                "سرویس تشخیص گفتار روی این گوشی فعال نیست. می‌توانید فرمان را تایپ کنید."
            )
            return
        }
        stopListening()
        _partialText.value = ""
        _rmsDb.value = 0f

        val instance = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        recognizer = instance
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
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
                    SpeechRecognizer.ERROR_NO_MATCH -> "صدایی تشخیص داده نشد؛ لطفاً دوباره بگو یا تایپ کن"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "زمان انتظار برای شروع صحبت تمام شد"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "موتور تشخیص گفتار مشغول است"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "بستهٔ آفلاین فارسی روی سرویس گفتار گوشی نصب نیست یا نیاز به دانلود یک‌باره دارد"
                    else -> "تشخیص گفتار ناموفق بود (کد $error)"
                }
                _state.value = VoiceListeningState.Error(msg)
                _rmsDb.value = 0f
                cleanup()
            }

            override fun onResults(results: Bundle?) {
                val best = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                _rmsDb.value = 0f
                _partialText.value = ""
                _state.value = VoiceListeningState.Idle
                cleanup()
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
                    _partialText.value = partial
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        runCatching {
            _state.value = VoiceListeningState.Listening
            instance.startListening(intent)
        }.onFailure {
            _state.value = VoiceListeningState.Error("شروع شنود ناموفق بود: ${it.localizedMessage ?: "خطای ناشناخته"}")
            cleanup()
        }
    }

    fun stopListening() {
        runCatching { recognizer?.stopListening() }
        cleanup()
        _rmsDb.value = 0f
        if (_state.value is VoiceListeningState.Listening || _state.value is VoiceListeningState.Processing) {
            _state.value = VoiceListeningState.Idle
        }
    }

    fun clearError() {
        if (_state.value is VoiceListeningState.Error) {
            _state.value = VoiceListeningState.Idle
        }
    }

    private fun cleanup() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}
