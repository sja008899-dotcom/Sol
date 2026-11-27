package com.example.sol.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.example.sol.memory.PersianNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

enum class VoiceErrorAction(val labelFa: String) {
    OPEN_MIC_PERMISSION_SETTINGS("تنظیمات مجوز"),
    OPEN_VOSK_SETTINGS("نصب مدل آفلاین"),
    RETRY_LISTENING("تلاش مجدد"),
    SWITCH_TO_TEXT_INPUT("تایپ فرمان"),
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
    private val isExternalSpeaking: () -> Boolean = { false },
    private val onStopExternalSpeech: () -> Unit = {},
    private val micArbiter: MicArbiter? = null,
    private val isOfflineStrict: () -> Boolean = { false },
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionToken = AtomicLong(0L)
    private val hasSystemMicLease = AtomicBoolean(false)

    @Volatile
    private var launchJob: Job? = null

    @Volatile
    private var recognizer: SpeechRecognizer? = null

    @Volatile
    private var maxDurationRunnable: Runnable? = null

    private val systemPcmLock = Any()

    @Volatile
    private var systemPcmCaptureJob: Job? = null

    @Volatile
    private var systemCapturedPcm16k: FloatArray? = null

    private var systemPcmRing: FloatArray? = null
    private var systemPcmRingWritePos: Int = 0
    private var systemPcmRingCount: Int = 0

    private val _state = MutableStateFlow<VoiceListeningState>(VoiceListeningState.Idle)
    val state: StateFlow<VoiceListeningState> = _state.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _rmsDb = MutableStateFlow(0f)
    val rmsDb: StateFlow<Float> = _rmsDb.asStateFlow()

    private val _activeBackend = MutableStateFlow(resolvePreferredBackend(preferVosk = true))
    val activeBackend: StateFlow<ActiveAsrBackend> = _activeBackend.asStateFlow()

    fun resolvePreferredBackend(preferVosk: Boolean): ActiveAsrBackend {
        return if (isOfflineStrict() || (preferVosk && voskEngine.isModelInstalled())) {
            ActiveAsrBackend.VOSK_OFFLINE
        } else {
            ActiveAsrBackend.ANDROID_SPEECH_RECOGNIZER
        }
    }

    fun refreshBackend(preferVosk: Boolean) {
        _activeBackend.value = resolvePreferredBackend(preferVosk)
    }

    fun isAvailable(): Boolean =
        voskEngine.isModelInstalled() || (!isOfflineStrict() && SpeechRecognizer.isRecognitionAvailable(context))

    fun updateVocabularyBias(terms: List<String>) {
        voskEngine.vocabularyBias.updateTerms(terms)
    }

    /**
     * Barge-in: user starting to speak immediately stops Sol's TTS playback and releases speaking focus.
     */
    fun stopSpeechForBargeIn() {
        onStopExternalSpeech()
        audioManager.abandonSpeakingFocus()
    }

    /**
     * Prevents automated wake-word listeners from triggering on Sol's own voice output.
     */
    fun canStartAutomatedWakeWord(): Boolean = !isExternalSpeaking()

    fun consumeLastCapturedPcm16k(): FloatArray? {
        voskEngine.consumeLastCapturedPcm16k()?.let {
            synchronized(systemPcmLock) { systemCapturedPcm16k = null }
            return it
        }
        return synchronized(systemPcmLock) {
            val out = systemCapturedPcm16k
            systemCapturedPcm16k = null
            out
        }
    }

    fun startListening(
        preferVosk: Boolean = true,
        grammarJson: String? = null,
        vadSilenceMs: Long = VoskVoiceEngine.DEFAULT_VAD_SILENCE_MS,
        maxDurationMs: Long = VoskVoiceEngine.DEFAULT_MAX_LISTEN_DURATION_MS,
        playCue: Boolean = true,
        onFinalResult: (String) -> Unit,
    ) {
        stopSpeechForBargeIn()
        val myToken = sessionToken.incrementAndGet()
        teardownActiveSession(preserveState = true)

        _partialText.value = ""
        _rmsDb.value = 0f

        val safeVadMs = vadSilenceMs.coerceIn(VoskVoiceEngine.MIN_VAD_SILENCE_MS, VoskVoiceEngine.MAX_VAD_SILENCE_MS)
        val safeMaxDurationMs = maxDurationMs.coerceIn(5_000L, 45_000L)
        val strictOffline = isOfflineStrict()
        val useVosk = (preferVosk || strictOffline) && voskEngine.isModelInstalled()

        if (!useVosk && strictOffline) {
            _activeBackend.value = ActiveAsrBackend.VOSK_OFFLINE
            _state.value = VoiceListeningState.Error(
                messageFa = "حالت آفلاین سخت‌گیرانه فعال است و تشخیص گفتار سیستم مسدود شد؛ مدل آفلاین Vosk را نصب کنید یا فرمان را تایپ کنید.",
                action = VoiceErrorAction.OPEN_VOSK_SETTINGS,
            )
            return
        }

        if (!useVosk && !SpeechRecognizer.isRecognitionAvailable(context)) {
            _activeBackend.value = ActiveAsrBackend.ANDROID_SPEECH_RECOGNIZER
            _state.value = VoiceListeningState.Error(
                messageFa = "گفتار سیستم در دسترس نیست؛ مدل آفلاین Vosk را نصب کنید.",
                action = VoiceErrorAction.OPEN_VOSK_SETTINGS,
            )
            return
        }

        _activeBackend.value = if (useVosk) ActiveAsrBackend.VOSK_OFFLINE else ActiveAsrBackend.ANDROID_SPEECH_RECOGNIZER
        _state.value = VoiceListeningState.Listening

        launchJob = scope.launch {
            withContext(Dispatchers.IO) {
                if (MicForegroundService.isRunning.value || MicForegroundService.isMicActive.value) {
                    MicForegroundService.pauseAndAwaitRelease(context)
                }
            }
            if (sessionToken.get() != myToken) return@launch

            if (playCue) {
                audioManager.playVoiceCueAndSettle(isStart = true)
            }
            if (sessionToken.get() != myToken) return@launch

            if (useVosk) {
                audioManager.requestListeningFocus { stopListening() }
                voskEngine.startListening(
                    grammarJson = grammarJson,
                    isWakeWordMode = false,
                    vadSilenceMs = safeVadMs,
                    maxDurationMs = safeMaxDurationMs,
                    onPartial = { partial ->
                        if (sessionToken.get() == myToken) {
                            _partialText.value = partial
                        }
                    },
                    onRmsChanged = { rms ->
                        if (sessionToken.get() == myToken) {
                            _rmsDb.value = rms
                        }
                    },
                    onFinalResult = { finalTxt ->
                        if (sessionToken.get() == myToken) {
                            audioManager.abandonListeningFocus()
                            if (playCue) audioManager.playVoiceCue(isStart = false)
                            _rmsDb.value = 0f
                            _partialText.value = ""
                            _state.value = VoiceListeningState.Idle
                            onFinalResult(finalTxt)
                        }
                    },
                    onTimeout = { timeoutMsg ->
                        if (sessionToken.get() == myToken) {
                            audioManager.abandonListeningFocus()
                            if (playCue) audioManager.playVoiceCue(isStart = false)
                            _rmsDb.value = 0f
                            _state.value = VoiceListeningState.Error(
                                messageFa = timeoutMsg,
                                action = VoiceErrorAction.RETRY_LISTENING,
                            )
                        }
                    },
                    onError = { errMsg ->
                        if (sessionToken.get() == myToken) {
                            audioManager.abandonListeningFocus()
                            _rmsDb.value = 0f
                            _state.value = classifyVoskError(errMsg)
                        }
                    },
                )
            } else {
                startSystemSpeechRecognizer(
                    token = myToken,
                    safeVadMs = safeVadMs,
                    safeMaxDurationMs = safeMaxDurationMs,
                    playCue = playCue,
                    preferOfflineInSystem = true,
                    onFinalResult = onFinalResult,
                )
            }
        }
    }

    private suspend fun startSystemSpeechRecognizer(
        token: Long,
        safeVadMs: Long,
        safeMaxDurationMs: Long,
        playCue: Boolean,
        preferOfflineInSystem: Boolean,
        onFinalResult: (String) -> Unit,
    ) {
        if (isOfflineStrict()) {
            _activeBackend.value = ActiveAsrBackend.VOSK_OFFLINE
            _state.value = VoiceListeningState.Error(
                messageFa = "حالت آفلاین سخت‌گیرانه فعال است؛ استفاده از سرویس گفتار سیستم مسدود شد.",
                action = VoiceErrorAction.OPEN_VOSK_SETTINGS,
            )
            return
        }
        if (sessionToken.get() != token) return
        if (!hasSystemMicLease.get() && micArbiter != null) {
            withContext(Dispatchers.IO) {
                micArbiter.acquire(MicOwner.VOICE_COMMAND)
            }
            if (sessionToken.get() != token) {
                withContext(NonCancellable + Dispatchers.IO) {
                    micArbiter.release(MicOwner.VOICE_COMMAND)
                }
                return
            }
            hasSystemMicLease.set(true)
        }

        audioManager.requestListeningFocus { stopListening() }
        val instance = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        recognizer = instance
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
            if (preferOfflineInSystem) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, safeVadMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, safeVadMs)
        }

        instance.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (sessionToken.get() == token) {
                    _state.value = VoiceListeningState.Listening
                }
            }

            override fun onBeginningOfSpeech() {
                if (sessionToken.get() == token) {
                    _state.value = VoiceListeningState.Listening
                }
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (sessionToken.get() == token) {
                    _rmsDb.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (sessionToken.get() == token) {
                    _state.value = VoiceListeningState.Processing
                    _rmsDb.value = 0f
                }
            }

            override fun onError(error: Int) {
                if (sessionToken.get() != token) return
                discardParallelSystemPcmCapture()
                cancelMaxDurationTimer()
                cleanupRecognizer()
                val isLangUnavailable = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                if (preferOfflineInSystem && isLangUnavailable) {
                    launchJob = scope.launch {
                        // Brief teardown pause avoids Android SpeechRecognizer ERROR_RECOGNIZER_BUSY
                        delay(140L)
                        if (sessionToken.get() == token) {
                            startSystemSpeechRecognizer(
                                token = token,
                                safeVadMs = safeVadMs,
                                safeMaxDurationMs = safeMaxDurationMs,
                                playCue = playCue,
                                preferOfflineInSystem = false,
                                onFinalResult = onFinalResult,
                            )
                        }
                    }
                    return
                }
                audioManager.abandonListeningFocus()
                releaseSystemMicLeaseAsync()
                _state.value = classifySpeechRecognizerError(error)
                _rmsDb.value = 0f
            }

            override fun onResults(results: Bundle?) {
                if (sessionToken.get() != token) return
                finalizeParallelSystemPcmCapture()
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
                releaseSystemMicLeaseAsync()
                if (best.isNotBlank()) {
                    onFinalResult(best)
                } else {
                    synchronized(systemPcmLock) { systemCapturedPcm16k = null }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (sessionToken.get() != token) return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (partial.isNotBlank()) {
                    _partialText.value = voskEngine.vocabularyBias.applyBiasCorrection(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        scheduleMaxDurationTimer(safeMaxDurationMs) {
            if (sessionToken.get() == token &&
                (_state.value is VoiceListeningState.Listening || _state.value is VoiceListeningState.Processing)
            ) {
                stopListening()
                if (playCue) audioManager.playVoiceCue(isStart = false)
            }
        }

        runCatching {
            _state.value = VoiceListeningState.Listening
            instance.startListening(intent)
            startParallelSystemPcmCapture(token)
        }.onFailure { err ->
            discardParallelSystemPcmCapture()
            cancelMaxDurationTimer()
            audioManager.abandonListeningFocus()
            cleanupRecognizer()
            releaseSystemMicLeaseAsync()
            _state.value = VoiceListeningState.Error(
                messageFa = "شروع شنود ناموفق بود: ${err.localizedMessage ?: "خطای ناشناخته"}",
                action = VoiceErrorAction.SWITCH_TO_TEXT_INPUT,
            )
        }
    }

    /**
     * Dedicated lightweight wake-word ("Sol" / "سول") listener using Vosk grammar mode ONLY.
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
        val myToken = sessionToken.incrementAndGet()
        teardownActiveSession(preserveState = true)

        _activeBackend.value = ActiveAsrBackend.VOSK_OFFLINE
        _state.value = VoiceListeningState.WakeWordArmed

        launchJob = scope.launch {
            withContext(Dispatchers.IO) {
                if (MicForegroundService.isRunning.value || MicForegroundService.isMicActive.value) {
                    MicForegroundService.pauseAndAwaitRelease(context)
                }
            }
            if (sessionToken.get() != myToken) return@launch

            if (playCue) {
                audioManager.playVoiceCueAndSettle(isStart = true)
            }
            if (sessionToken.get() != myToken) return@launch

            audioManager.requestListeningFocus { stopListening() }
            voskEngine.startListening(
                grammarJson = voskEngine.vocabularyBias.buildWakeWordGrammarJson(),
                isWakeWordMode = true,
                maxDurationMs = wakeTimeoutMs.coerceAtLeast(60_000L),
                onPartial = {},
                onRmsChanged = { rms ->
                    if (sessionToken.get() == myToken) {
                        _rmsDb.value = rms * 0.4f
                    }
                },
                onFinalResult = {
                    if (sessionToken.get() == myToken) {
                        audioManager.abandonListeningFocus()
                        _state.value = VoiceListeningState.Idle
                        onWakeDetected()
                    }
                },
                onTimeout = { msg ->
                    if (sessionToken.get() == myToken) {
                        audioManager.abandonListeningFocus()
                        _rmsDb.value = 0f
                        _state.value = VoiceListeningState.Idle
                        onTimeout?.invoke(msg)
                    }
                },
                onError = { err ->
                    if (sessionToken.get() == myToken) {
                        audioManager.abandonListeningFocus()
                        _state.value = classifyVoskError(err)
                    }
                },
            )
        }
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
        sessionToken.incrementAndGet()
        teardownActiveSession(preserveState = false)
    }

    private fun teardownActiveSession(preserveState: Boolean) {
        launchJob?.cancel()
        launchJob = null
        discardParallelSystemPcmCapture()
        cancelMaxDurationTimer()
        voskEngine.stopListening()
        runCatching { recognizer?.stopListening() }
        cleanupRecognizer()
        releaseSystemMicLeaseAsync()
        audioManager.abandonListeningFocus()
        _rmsDb.value = 0f
        if (!preserveState &&
            (_state.value is VoiceListeningState.Listening ||
                _state.value is VoiceListeningState.Processing ||
                _state.value is VoiceListeningState.WakeWordArmed)
        ) {
            _state.value = VoiceListeningState.Idle
        }
    }

    /**
     * Best-effort lightweight 16kHz PCM capture while Android SpeechRecognizer runs.
     * On devices/ROMs where concurrent AudioRecord is blocked or silenced by OS mic policy,
     * this exits quietly without disrupting SpeechRecognizer, leaving [systemCapturedPcm16k] null
     * so N5 safely emits [SpeakerVerificationOutcome.AudioUnavailable].
     */
    @SuppressLint("MissingPermission")
    private fun startParallelSystemPcmCapture(token: Long) {
        discardParallelSystemPcmCapture()
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        synchronized(systemPcmLock) {
            systemPcmRing = FloatArray(SYSTEM_PCM_MAX_SAMPLES)
            systemPcmRingWritePos = 0
            systemPcmRingCount = 0
            systemCapturedPcm16k = null
        }

        systemPcmCaptureJob = scope.launch(Dispatchers.IO) {
            var recorder: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    16_000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(4096)
                recorder = runCatching {
                    SafeAudioCapture.createAudioRecorder(sampleRate = 16_000, bufferBytes = minBuf * 2)
                }.getOrNull()
                if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                    return@launch
                }
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    return@launch
                }

                val buf = ShortArray(1024)
                var consecutiveIdleReads = 0
                while (isActive && sessionToken.get() == token) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        consecutiveIdleReads = 0
                        synchronized(systemPcmLock) {
                            val ring = systemPcmRing ?: return@synchronized
                            for (i in 0 until read) {
                                ring[systemPcmRingWritePos] = buf[i] / 32768f
                                systemPcmRingWritePos = (systemPcmRingWritePos + 1) % ring.size
                                if (systemPcmRingCount < ring.size) systemPcmRingCount++
                            }
                        }
                    } else {
                        consecutiveIdleReads++
                        if (read < 0 || consecutiveIdleReads >= 25) {
                            break
                        }
                        delay(15L)
                    }
                }
            } catch (_: Throwable) {
                // Intentionally swallowed: parallel PCM capture must never disrupt SpeechRecognizer.
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
            }
        }
    }

    private fun finalizeParallelSystemPcmCapture() {
        systemPcmCaptureJob?.cancel()
        systemPcmCaptureJob = null
        synchronized(systemPcmLock) {
            val ring = systemPcmRing
            val count = systemPcmRingCount
            val writePos = systemPcmRingWritePos
            systemPcmRing = null
            systemPcmRingWritePos = 0
            systemPcmRingCount = 0

            if (ring == null || count < MIN_PARALLEL_PCM_SAMPLES) {
                systemCapturedPcm16k = null
                return
            }
            val out = FloatArray(count)
            val start = if (count < ring.size) 0 else writePos
            var sumSq = 0.0
            for (i in 0 until count) {
                val sample = ring[(start + i) % ring.size]
                out[i] = sample
                sumSq += sample.toDouble() * sample.toDouble()
            }
            val rms = sqrt(sumSq / count).toFloat()
            systemCapturedPcm16k = if (rms >= MIN_PARALLEL_PCM_RMS) out else null
        }
    }

    private fun discardParallelSystemPcmCapture() {
        systemPcmCaptureJob?.cancel()
        systemPcmCaptureJob = null
        synchronized(systemPcmLock) {
            systemPcmRing = null
            systemPcmRingWritePos = 0
            systemPcmRingCount = 0
            systemCapturedPcm16k = null
        }
    }

    private fun releaseSystemMicLeaseAsync() {
        if (hasSystemMicLease.compareAndSet(true, false) && micArbiter != null) {
            scope.launch(NonCancellable + Dispatchers.IO) {
                micArbiter.release(MicOwner.VOICE_COMMAND)
            }
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
        private const val SYSTEM_PCM_MAX_SAMPLES = 16_000 * 4
        private const val MIN_PARALLEL_PCM_SAMPLES = 6_400
        private const val MIN_PARALLEL_PCM_RMS = 0.003f

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
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> VoiceListeningState.Error(
                messageFa = "بستهٔ آفلاین فارسی سیستم نصب نیست؛ برای شنود آفلاین مدل Vosk را در تنظیمات دانلود کنید یا به اینترنت متصل شوید.",
                action = VoiceErrorAction.OPEN_VOSK_SETTINGS,
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
