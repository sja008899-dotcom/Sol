package com.example.sol.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.sol.memory.PersianNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceErrorAction(val labelFa: String) {
    OPEN_MIC_PERMISSION_SETTINGS("تنظیمات مجوز"),
    OPEN_VOSK_SETTINGS("نصب مدل آفلاین"),
    RETRY_LISTENING("تلاش مجدد"),
    SWITCH_TO_TEXT_INPUT("تایپ فرمان"),
    OPEN_TTS_OR_SYSTEM_SETTINGS("تنظیمات سیستم"),
}

sealed interface VoiceListeningState {
    data object Idle : VoiceListeningState
    data object Listening : VoiceListeningState
    data object WakeWordArmed : VoiceListeningState
    data object Processing : VoiceListeningState
    data class Error(
        val messageFa: String,
        val action: VoiceErrorAction = VoiceErrorAction.RETRY_LISTENING,
    ) : VoiceListeningState
}

enum class ActiveAsrBackend(val labelFa: String) {
    VOSK_OFFLINE("موتور ۱۰۰٪ آفلاین Vosk فارسی"),
    ANDROID_SPEECH_RECOGNIZER("تشخیص گفتار سیستم اندروید (fa-IR)"),
}

class VoiceEngine(
    private val context: Context,
    val voskEngine: VoskVoiceEngine = VoskVoiceEngine(context),
    private val audioManager: SolAudioManager = SolAudioManager(context),
    private val persianTts: PersianTTS? = null,
    private val isExternalSpeaking: () -> Boolean = { false },
    private val onStopExternalSpeech: () -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var maxDurationRunnable: Runnable? = null

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

    /**
     * Barge-in: user starting to speak immediately stops Sol's TTS playback and releases speaking focus.
     */
    fun stopSpeechForBargeIn() {
        if (persianTts?.isSpeaking?.value == true) {
            persianTts.stop()
        }
        onStopExternalSpeech()
        audioManager.abandonSpeakingFocus()
    }

    /**
     * Prevents automated wake-word listeners from triggering on Sol's own voice output.
     */
    fun canStartAutomatedWakeWord(): Boolean =
        persianTts?.isSpeaking?.value != true && !isExternalSpeaking()

    fun consumeLastCapturedPcm16k(): FloatArray? = voskEngine.consumeLastCapturedPcm16k()

    fun startListening(
        preferVosk: Boolean = true,
        grammarJson: String? = null,
        vadSilenceMs: Long = VoskVoiceEngine.DEFAULT_VAD_SILENCE_MS,
        maxDurationMs: Long = VoskVoiceEngine.DEFAULT_MAX_LISTEN_DURATION_MS,
        playCue: Boolean = true,
        onFinalResult: (String) -> Unit,
    ) {
        stopSpeechForBargeIn()
        stopListening()
        _partialText.value = ""
        _rmsDb.value = 0f

        val safeVadMs = vadSilenceMs.coerceIn(VoskVoiceEngine.MIN_VAD_SILENCE_MS, VoskVoiceEngine.MAX_VAD_SILENCE_MS)
        val safeMaxDurationMs = maxDurationMs.coerceIn(5_000L, 45_000L)

        val useVosk = preferVosk && voskEngine.isModelInstalled()
        if (useVosk) {
            _activeBackend.value = ActiveAsrBackend.VOSK_OFFLINE
            audioManager.requestListeningFocus { stopListening() }
            if (playCue) audioManager.playVoiceCue(isStart = true)
            _state.value = VoiceListeningState.Listening
            voskEngine.startListening(
                grammarJson = grammarJson,
                isWakeWordMode = false,
                vadSilenceMs = safeVadMs,
                maxDurationMs = safeMaxDurationMs,
                onPartial = { partial -> _partialText.value = partial },
                onRmsChanged = { rms -> _rmsDb.value = rms },
                onFinalResult = { finalTxt ->
                    audioManager.abandonListeningFocus()
                    if (playCue) audioManager.playVoiceCue(isStart = false)
                    _rmsDb.value = 0f
                    _partialText.value = ""
                    _state.value = VoiceListeningState.Idle
                    onFinalResult(finalTxt)
                },
                onTimeout = { timeoutMsg ->
                    audioManager.abandonListeningFocus()
                    if (playCue) audioManager.playVoiceCue(isStart = false)
                    _rmsDb.value = 0f
                    _state.value = VoiceListeningState.Error(
                        messageFa = timeoutMsg,
                        action = VoiceErrorAction.RETRY_LISTENING,
                    )
                },
                onError = { errMsg ->
                    audioManager.abandonListeningFocus()
                    _rmsDb.value = 0f
                    _state.value = classifyVoskError(errMsg)
                },
            )
            return
        }

        _activeBackend.value = ActiveAsrBackend.ANDROID_SPEECH_RECOGNIZER
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = VoiceListeningState.Error(
                messageFa = "گفتار سیستم در دسترس نیست؛ مدل آفلاین Vosk را نصب کنید.",
                action = VoiceErrorAction.OPEN_VOSK_SETTINGS,
            )
            return
        }

        audioManager.requestListeningFocus { stopListening() }
        if (playCue) audioManager.playVoiceCue(isStart = true)

        val instance = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        recognizer = instance
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, safeVadMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, safeVadMs)
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
                cancelMaxDurationTimer()
                audioManager.abandonListeningFocus()
                _state.value = classifySpeechRecognizerError(error)
                _rmsDb.value = 0f
                cleanupRecognizer()
            }

            override fun onResults(results: Bundle?) {
                cancelMaxDurationTimer()
                audioManager.abandonListeningFocus()
                if (playCue) audioManager.playVoiceCue(isStart = false)
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

        scheduleMaxDurationTimer(safeMaxDurationMs) {
            if (_state.value is VoiceListeningState.Listening || _state.value is VoiceListeningState.Processing) {
                stopListening()
                if (playCue) audioManager.playVoiceCue(isStart = false)
            }
        }

        runCatching {
            _state.value = VoiceListeningState.Listening
            instance.startListening(intent)
        }.onFailure { err ->
            cancelMaxDurationTimer()
            audioManager.abandonListeningFocus()
            _state.value = VoiceListeningState.Error(
                messageFa = "شروع شنود ناموفق بود: ${err.localizedMessage ?: "خطای ناشناخته"}",
                action = VoiceErrorAction.SWITCH_TO_TEXT_INPUT,
            )
            cleanupRecognizer()
        }
    }

    /**
     * Dedicated lightweight wake-word ("Sol" / "سول") listener using Vosk grammar mode ONLY.
     * Explicitly active only while the app is open; stops cleanly on silence timeout or onStop().
     */
    fun startWakeWordListening(
        wakeTimeoutMs: Long = VoskVoiceEngine.DEFAULT_WAKE_TIMEOUT_MS,
        allowBargeIn: Boolean = true,
        playCue: Boolean = false,
        onTimeout: ((String) -> Unit)? = null,
        onWakeDetected: () -> Unit,
    ) {
        if (!allowBargeIn && !canStartAutomatedWakeWord()) {
            return
        }
        if (!voskEngine.isModelInstalled()) {
            _state.value = VoiceListeningState.Error(
                messageFa = "برای بیدارباش «Sol»، ابتدا مدل آفلاین Vosk را نصب کنید.",
                action = VoiceErrorAction.OPEN_VOSK_SETTINGS,
            )
            return
        }
        if (allowBargeIn) {
            stopSpeechForBargeIn()
        }
        stopListening()
        _activeBackend.value = ActiveAsrBackend.VOSK_OFFLINE
        audioManager.requestListeningFocus { stopListening() }
        if (playCue) audioManager.playVoiceCue(isStart = true)
        _state.value = VoiceListeningState.WakeWordArmed
        voskEngine.startListening(
            grammarJson = voskEngine.vocabularyBias.buildWakeWordGrammarJson(),
            isWakeWordMode = true,
            maxDurationMs = wakeTimeoutMs.coerceAtLeast(60_000L),
            onPartial = {},
            onRmsChanged = { rms -> _rmsDb.value = rms * 0.4f },
            onFinalResult = {
                audioManager.abandonListeningFocus()
                _state.value = VoiceListeningState.Idle
                onWakeDetected()
            },
            onTimeout = { msg ->
                audioManager.abandonListeningFocus()
                _rmsDb.value = 0f
                _state.value = VoiceListeningState.Idle
                onTimeout?.invoke(msg)
            },
            onError = { err ->
                audioManager.abandonListeningFocus()
                _state.value = classifyVoskError(err)
            },
        )
    }

    /**
     * Confirmation voice listener using tiny closed grammar returning TRUE (تأیید), FALSE (لغو), or null.
     */
    fun startConfirmationVoiceListening(
        preferVosk: Boolean = true,
        vadSilenceMs: Long = VoskVoiceEngine.DEFAULT_VAD_SILENCE_MS,
        maxDurationMs: Long = VoskVoiceEngine.DEFAULT_MAX_LISTEN_DURATION_MS,
        playCue: Boolean = true,
        onDecision: (confirmed: Boolean?, spokenText: String) -> Unit,
    ) {
        val grammar = voskEngine.vocabularyBias.buildConfirmationGrammarJson()
        startListening(
            preferVosk = preferVosk,
            grammarJson = grammar,
            vadSilenceMs = vadSilenceMs,
            maxDurationMs = maxDurationMs,
            playCue = playCue,
        ) { spoken ->
            val decision = parseVoiceConfirmationDecision(spoken)
            onDecision(decision, spoken)
        }
    }

    fun stopListening() {
        cancelMaxDurationTimer()
        voskEngine.stopListening()
        runCatching { recognizer?.stopListening() }
        cleanupRecognizer()
        audioManager.abandonListeningFocus()
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

    private fun scheduleMaxDurationTimer(timeoutMs: Long, onExpire: () -> Unit) {
        cancelMaxDurationTimer()
        val runnable = Runnable { onExpire() }
        maxDurationRunnable = runnable
        mainHandler.postDelayed(runnable, timeoutMs)
    }

    private fun cancelMaxDurationTimer() {
        maxDurationRunnable?.let { mainHandler.removeCallbacks(it) }
        maxDurationRunnable = null
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

        fun classifySpeechRecognizerError(errorCode: Int): VoiceListeningState.Error = when (errorCode) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceListeningState.Error(
                messageFa = "مجوز میکروفون صادر نشده است.",
                action = VoiceErrorAction.OPEN_MIC_PERMISSION_SETTINGS,
            )
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceListeningState.Error(
                messageFa = "موتور تشخیص گفتار در حال استفاده است؛ دوباره تلاش کنید.",
                action = VoiceErrorAction.RETRY_LISTENING,
            )
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceListeningState.Error(
                messageFa = "اینترنت قطع است؛ برای کار بدون اینترنت مدل Vosk را نصب کنید.",
                action = VoiceErrorAction.OPEN_VOSK_SETTINGS,
            )
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceListeningState.Error(
                messageFa = "صدایی تشخیص داده نشد؛ واضح‌تر بگویید یا فرمان را بنویسید.",
                action = VoiceErrorAction.RETRY_LISTENING,
            )
            SpeechRecognizer.ERROR_AUDIO -> VoiceListeningState.Error(
                messageFa = "خطا در دریافت صدا از میکروفون؛ لطفاً دوباره امتحان کنید.",
                action = VoiceErrorAction.SWITCH_TO_TEXT_INPUT,
            )
            else -> VoiceListeningState.Error(
                messageFa = "تشخیص گفتار سیستم ناموفق بود (کد $errorCode).",
                action = VoiceErrorAction.SWITCH_TO_TEXT_INPUT,
            )
        }

        fun classifyVoskError(rawMessage: String): VoiceListeningState.Error {
            val trimmed = rawMessage.trim()
            return when {
                trimmed.contains("مجوز") || trimmed.contains("permission", ignoreCase = true) -> VoiceListeningState.Error(
                    messageFa = "مجوز میکروفون صادر نشده است.",
                    action = VoiceErrorAction.OPEN_MIC_PERMISSION_SETTINGS,
                )
                trimmed.contains("نصب نشده") -> VoiceListeningState.Error(
                    messageFa = "مدل آفلاین Vosk هنوز نصب نشده است.",
                    action = VoiceErrorAction.OPEN_VOSK_SETTINGS,
                )
                trimmed.contains("میکروفون") -> VoiceListeningState.Error(
                    messageFa = "میکروفون دستگاه مشغول یا در دسترس نیست.",
                    action = VoiceErrorAction.RETRY_LISTENING,
                )
                trimmed.contains("صدایی") -> VoiceListeningState.Error(
                    messageFa = "صدایی تشخیص داده نشد؛ لطفاً دوباره بگویید.",
                    action = VoiceErrorAction.RETRY_LISTENING,
                )
                else -> VoiceListeningState.Error(
                    messageFa = trimmed.ifBlank { "خطا در موتور صوتی آفلاین." },
                    action = VoiceErrorAction.SWITCH_TO_TEXT_INPUT,
                )
            }
        }
    }
}
