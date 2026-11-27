package com.example.sol.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SolApplication
import com.example.sol.brain.PendingSolTask
import com.example.sol.contacts.ResolvedContact
import com.example.sol.dialogue.DialogueUiChoice
import com.example.sol.knowledge.SlmRuntimeState
import com.example.sol.memory.LocationEntity
import com.example.sol.memory.SolPreferences
import com.example.sol.memory.SolRoleEntity
import com.example.sol.tasks.ActiveTaskState
import com.example.sol.tasks.CommunicationChannel
import com.example.sol.tasks.PendingCommunicationConfirmation
import com.example.sol.ui.ChatMessageItem
import com.example.sol.ui.PendingWebSearchConfirmation
import com.example.sol.tts.PiperTtsStatus
import com.example.sol.voice.ActiveAsrBackend
import com.example.sol.voice.VoiceListeningState
import com.example.sol.voice.VoskModelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val c = (application as SolApplication).container

    val preferences: StateFlow<SolPreferences> = c.preferencesRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SolPreferences())

    // Only collect lightweight active role summary & location names needed by Home header
    val activeRoles: StateFlow<List<SolRoleEntity>> = c.knowledgeRepository.roles
        .map { list -> list.filter { it.isActive } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedLocations: StateFlow<List<LocationEntity>> = c.locationRepository.allLocations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val listeningState: StateFlow<VoiceListeningState> = c.voiceEngine.state
    val partialSpeechText: StateFlow<String> = c.voiceEngine.partialText
    val micRms: StateFlow<Float> = c.voiceEngine.rmsDb
    val activeAsrBackend: StateFlow<ActiveAsrBackend> = c.voiceEngine.activeBackend
    val voskModelStatus: StateFlow<VoskModelStatus> = c.voiceEngine.voskEngine.modelStatus
    val piperStatus: StateFlow<PiperTtsStatus> = c.ttsManager.piperStatus
    val isSpeaking: StateFlow<Boolean> = c.ttsManager.isPiperSpeaking

    val activeTask: StateFlow<ActiveTaskState?> = c.taskManager.activeTask
    val pendingConfirmation: StateFlow<PendingCommunicationConfirmation?> = c.confirmationManager.pending
    val slmRuntimeState: StateFlow<SlmRuntimeState> = c.localSlmEngine.runtimeState

    private val _isListeningForConfirm = MutableStateFlow(false)
    val isListeningForConfirm: StateFlow<Boolean> = _isListeningForConfirm.asStateFlow()

    private val _confirmVoiceFeedback = MutableStateFlow("")
    val confirmVoiceFeedback: StateFlow<String> = _confirmVoiceFeedback.asStateFlow()

    private val _pendingWebSearch = MutableStateFlow<PendingWebSearchConfirmation?>(null)
    val pendingWebSearch: StateFlow<PendingWebSearchConfirmation?> = _pendingWebSearch.asStateFlow()

    private val _isSearchingWeb = MutableStateFlow(false)
    val isSearchingWeb: StateFlow<Boolean> = _isSearchingWeb.asStateFlow()

    private val _isSlmGenerating = MutableStateFlow(false)
    val isSlmGenerating: StateFlow<Boolean> = _isSlmGenerating.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _messages = MutableStateFlow(
        listOf(
            ChatMessageItem(
                id = 1L,
                isUser = false,
                text = "سلام. من Sol v2 هستم — مجهز به تقویم شمسی دقیق و AlarmManager، موتور آفلاین فارسی Vosk، جست‌وجوی معنایی BM25 روی PDF/EPUB/کتاب‌ها، ویجت صفحه اصلی، دکمه نوار بالای گوشی و پشتیبان رمزنگاری‌شده .solbackup.",
                actionBadge = "آماده",
            )
        )
    )
    val messages: StateFlow<List<ChatMessageItem>> = _messages.asStateFlow()

    @Volatile
    private var isContinuousWakeSessionActive = false
    private var continuousRearmJob: Job? = null

    @Volatile
    private var activeStreamingSpeechSession: com.example.sol.tts.StreamingSpeechSession? = null

    init {
        viewModelScope.launch {
            com.example.sol.voice.MicForegroundService.wakeEvents.collect {
                if (c.voiceEngine.canStartAutomatedWakeWord()) {
                    startVoiceListening(fromWakeWord = true)
                } else if (preferences.value.screenOffMicEnabled && c.voiceEngine.voskEngine.isModelInstalled()) {
                    com.example.sol.voice.MicForegroundService.resume(getApplication())
                }
            }
        }
        viewModelScope.launch {
            c.uiBridge.events.collect { ev ->
                when (ev) {
                    is SolUiEvent.AppendBotMessage -> {
                        appendMessage(
                            isUser = false,
                            text = ev.text,
                            actionBadge = ev.actionBadge,
                            choices = ev.choices,
                        )
                        if (ev.speakIfAutoSpeak && preferences.value.autoSpeak) {
                            speakText(ev.text)
                        }
                    }
                    is SolUiEvent.ResetChatAfterWipe -> {
                        isContinuousWakeSessionActive = false
                        continuousRearmJob?.cancel()
                        _pendingWebSearch.value = null
                        _isSlmGenerating.value = false
                        _messages.value = listOf(
                            ChatMessageItem(
                                id = System.nanoTime(),
                                isUser = false,
                                text = "همهٔ داده‌ها، حافظه‌ها، کتاب‌ها، نقش‌ها، سوابق و تنظیمات Sol با موفقیت پاک شدند.",
                                actionBadge = "پاک‌سازی کامل",
                            )
                        )
                    }
                    is SolUiEvent.OpenWebSearchDialog -> {
                        _pendingWebSearch.value = PendingWebSearchConfirmation(query = ev.query)
                    }
                    else -> Unit
                }
            }
        }
    }

    fun updateInputText(newText: String) {
        _inputText.value = newText
    }

    fun submitCommand(
        textOverride: String? = null,
        isVoiceCommand: Boolean = false,
        audioPcm16k: FloatArray? = null,
    ) {
        val raw = (textOverride ?: _inputText.value).trim()
        if (raw.isBlank()) return
        if (textOverride == null) _inputText.value = ""

        _messages.value = _messages.value.map { msg ->
            if (msg.choices.isEmpty()) msg else msg.copy(choices = emptyList())
        }
        appendMessage(isUser = true, text = raw)

        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                c.commandBrain.processDialogue(
                    rawInput = raw,
                    audioPcm16k = audioPcm16k,
                    isVoiceCommand = isVoiceCommand,
                )
            }
            if (result.replyText.isNotBlank()) {
                appendMessage(
                    isUser = false,
                    text = result.replyText,
                    actionBadge = result.parsedCommand.action.name,
                    choices = result.actionChoices,
                )
                val currentPrefs = preferences.value
                withContext(Dispatchers.IO) {
                    c.historyRepository.recordIfAllowed(
                        userText = raw,
                        solText = result.replyText,
                        intentAction = result.parsedCommand.action.name,
                        retentionMode = currentPrefs.historyRetentionMode,
                        privateModeEnabled = currentPrefs.privateModeEnabled,
                    )
                }
                if (result.speakReply && currentPrefs.autoSpeak) {
                    speakText(result.replyText)
                }
            }
            if (result.speakerNoticeFa != null) {
                c.uiBridge.postBanner(result.speakerNoticeFa)
            }
            c.uiBridge.triggerRefreshSnapshots()

            when (val pending = result.pendingTask) {
                is PendingSolTask.Communicate -> {
                    c.taskManager.startPendingTask(pending) { statusMsg ->
                        appendMessage(isUser = false, text = statusMsg, actionBadge = "TASK")
                        if (preferences.value.autoSpeak) speakText(statusMsg)
                    }
                }
                is PendingSolTask.InternetBookRoleFetch -> {
                    c.uiBridge.triggerOpenBookFetch(
                        query = pending.bookQuery,
                        roleHint = pending.requestedRoleHint,
                        runInBackground = pending.runInBackground,
                    )
                }
                is PendingSolTask.ConfirmWebSearch -> {
                    _pendingWebSearch.value = PendingWebSearchConfirmation(query = pending.query)
                }
                is PendingSolTask.OpenCapabilitiesCatalog -> {
                    c.uiBridge.triggerOpenCapabilities()
                }
                is PendingSolTask.RunLocalSlmChat -> {
                    runLocalSlmStreamingReply(
                        rawInput = raw,
                        actionName = result.parsedCommand.action.name,
                        task = pending,
                    )
                }
                null -> Unit
            }

            if (isVoiceCommand && isContinuousWakeSessionActive) {
                scheduleContinuousWakeRearmIfEligible(hasPendingTask = result.pendingTask != null)
            }
        }
    }

    private fun runLocalSlmStreamingReply(
        rawInput: String,
        actionName: String,
        task: PendingSolTask.RunLocalSlmChat,
    ) {
        if (_isSlmGenerating.value) {
            c.localSlmEngine.stopGeneration()
        }
        val placeholderId = appendMessage(
            isUser = false,
            text = "⏳ در حال بارگذاری و پردازش آفلاین با مدل زبانی…",
            actionBadge = "SLM_OFFLINE",
        )
        _isSlmGenerating.value = true

        viewModelScope.launch {
            try {
                val prefs = preferences.value

                if (task.allowIntentProposal) {
                    val proposal = c.localSlmEngine.proposeIntent(
                        userUtterance = rawInput,
                        preferredPath = prefs.slmModelPath,
                        preferGpu = prefs.slmPreferGpu,
                    ).getOrNull()
                    if (proposal != null) {
                        val routed = c.dialogueManager.handleValidatedSlmProposal(rawInput, proposal)
                        val replyText = routed.replyText.ifBlank { "درخواست شما تحلیل شد." }
                        updateMessage(
                            messageId = placeholderId,
                            updatedText = replyText,
                            actionBadge = "${routed.parsedCommand.action.name}_SLM",
                            choices = routed.actionChoices,
                        )
                        c.historyRepository.recordIfAllowed(
                            userText = rawInput,
                            solText = replyText,
                            intentAction = "${routed.parsedCommand.action.name}_SLM_PROPOSED",
                            retentionMode = prefs.historyRetentionMode,
                            privateModeEnabled = prefs.privateModeEnabled,
                        )
                        if (routed.speakReply && prefs.autoSpeak) {
                            speakText(replyText)
                        }
                        when (val nextPending = routed.pendingTask) {
                            is PendingSolTask.Communicate -> {
                                c.taskManager.startPendingTask(nextPending) { statusMsg ->
                                    appendMessage(isUser = false, text = statusMsg, actionBadge = "TASK")
                                    if (preferences.value.autoSpeak) speakText(statusMsg)
                                }
                            }
                            is PendingSolTask.InternetBookRoleFetch -> {
                                c.uiBridge.triggerOpenBookFetch(
                                    query = nextPending.bookQuery,
                                    roleHint = nextPending.requestedRoleHint,
                                    runInBackground = nextPending.runInBackground,
                                )
                            }
                            is PendingSolTask.ConfirmWebSearch -> {
                                _pendingWebSearch.value = PendingWebSearchConfirmation(query = nextPending.query)
                            }
                            is PendingSolTask.OpenCapabilitiesCatalog -> {
                                c.uiBridge.triggerOpenCapabilities()
                            }
                            is PendingSolTask.RunLocalSlmChat, null -> Unit
                        }
                        c.uiBridge.triggerRefreshSnapshots()
                        return@launch
                    }
                }

                activeStreamingSpeechSession?.cancel()
                activeStreamingSpeechSession = null
                val streamSession = if (prefs.autoSpeak) {
                    c.ttsManager.beginStreamingSpeech(
                        speechRate = prefs.speechRate,
                        persona = prefs.voicePersona,
                    ).also { activeStreamingSpeechSession = it }
                } else {
                    null
                }
                val speechBuffer = com.example.sol.tts.SlmSpeechStreamBuffer()

                val res = c.localSlmEngine.generateReplyStream(
                    userPrompt = com.example.sol.knowledge.SlmPromptBuilder.buildChatUserPrompt(task.userPrompt),
                    systemInstruction = task.systemInstruction,
                    preferredPath = prefs.slmModelPath,
                    preferGpu = prefs.slmPreferGpu,
                ) { partialClean ->
                    updateMessageText(placeholderId, partialClean)
                    if (streamSession != null) {
                        for (sentence in speechBuffer.appendAndDrainReadySentences(partialClean)) {
                            streamSession.enqueue(sentence)
                        }
                    }
                }

                res.fold(
                    onSuccess = { finalText ->
                        val tagged = if (task.hasBookGrounding) {
                            "$finalText\n\n(مبتنی بر کتاب‌های آفلاین شما)"
                        } else {
                            finalText
                        }
                        updateMessageText(placeholderId, tagged)
                        c.historyRepository.recordIfAllowed(
                            userText = rawInput,
                            solText = finalText,
                            intentAction = "${actionName}_SLM",
                            retentionMode = prefs.historyRetentionMode,
                            privateModeEnabled = prefs.privateModeEnabled,
                        )
                        if (streamSession != null) {
                            speechBuffer.flushRemaining(finalText)?.let { tail ->
                                streamSession.enqueue(tail)
                            }
                            streamSession.finish()
                            activeStreamingSpeechSession = null
                        } else if (prefs.autoSpeak) {
                            speakText(finalText)
                        }
                    },
                    onFailure = { err ->
                        streamSession?.cancel()
                        activeStreamingSpeechSession = null
                        val reason = err.localizedMessage ?: "امکان اجرای مدل زبانی در این لحظه وجود ندارد."
                        val fallbackBlock = if (task.fallbackReplyText.isNotBlank()) {
                            "⚠️ اجرای مدل زبانی ممکن نشد ($reason).\n\n${task.fallbackReplyText}"
                        } else {
                            "⚠️ اجرای مدل زبانی آفلاین ممکن نشد: $reason"
                        }
                        updateMessageText(placeholderId, fallbackBlock)
                        if (prefs.autoSpeak) {
                            speakText(fallbackBlock)
                        }
                    }
                )
            } catch (t: Throwable) {
                activeStreamingSpeechSession?.cancel()
                activeStreamingSpeechSession = null
                throw t
            } finally {
                _isSlmGenerating.value = false
                c.uiBridge.triggerRefreshSnapshots()
            }
        }
    }

    fun requestWebSearch(defaultQuery: String = "") {
        _pendingWebSearch.value = PendingWebSearchConfirmation(query = defaultQuery)
    }

    fun updatePendingWebSearchQuery(query: String) {
        _pendingWebSearch.value = _pendingWebSearch.value?.copy(query = query)
    }

    fun dismissPendingWebSearch() {
        _pendingWebSearch.value = null
    }

    fun confirmWebSearch() {
        val req = _pendingWebSearch.value ?: return
        _pendingWebSearch.value = null
        val q = req.query.trim()
        if (q.isBlank()) return

        _isSearchingWeb.value = true
        viewModelScope.launch {
            try {
                val res = c.webSearchClient.search(q)
                res.fold(
                    onSuccess = { items ->
                        val msg = com.example.sol.network.WebSearchClient.formatResultsFa(q, items)
                        appendMessage(isUser = false, text = msg, actionBadge = "WEB_SEARCH")
                        val prefs = preferences.value
                        c.historyRepository.recordIfAllowed(
                            userText = "جست‌وجوی وب: $q",
                            solText = msg,
                            intentAction = "SEARCH_WEB",
                            retentionMode = prefs.historyRetentionMode,
                            privateModeEnabled = prefs.privateModeEnabled,
                        )
                        if (prefs.autoSpeak) speakText(msg)
                    },
                    onFailure = { err ->
                        val errMsg = err.message ?: "جست‌وجوی وب ناموفق بود."
                        appendMessage(isUser = false, text = errMsg, actionBadge = "WEB_SEARCH_ERROR")
                    }
                )
            } finally {
                _isSearchingWeb.value = false
            }
        }
    }

    fun startVoiceListening(fromWakeWord: Boolean = false) {
        if (!fromWakeWord) {
            isContinuousWakeSessionActive = false
            continuousRearmJob?.cancel()
        }
        viewModelScope.launch {
            c.refreshVocabularyBias()
            if (!c.networkPolicyCoordinator.isOfflineStrict() &&
                !c.voiceEngine.voskEngine.isModelInstalled() &&
                c.voiceEngine.voskEngine.modelStatus.value !is VoskModelStatus.Downloading &&
                c.hasInternetConnection()
            ) {
                withContext(Dispatchers.IO) {
                    c.voiceEngine.voskEngine.downloadPersianSmallModelSync(allowHttpsWithoutPinnedHash = true)
                    c.voiceEngine.refreshBackend(preferences.value.voskOfflineEnabled)
                }
            }
        }
        val prefs = preferences.value
        c.voiceEngine.startListening(
            preferVosk = prefs.voskOfflineEnabled,
            vadSilenceMs = prefs.vadSilenceTimeoutMs,
            maxDurationMs = prefs.maxVoiceListenSeconds * 1000L,
            playCue = prefs.voiceCueFeedbackEnabled,
        ) { recognizedText ->
            _inputText.value = recognizedText
            val capturedPcm = c.voiceEngine.consumeLastCapturedPcm16k()
            submitCommand(
                textOverride = recognizedText,
                isVoiceCommand = true,
                audioPcm16k = capturedPcm,
            )
        }
    }

    fun stopVoiceListening() {
        isContinuousWakeSessionActive = false
        continuousRearmJob?.cancel()
        c.voiceEngine.stopListening()
    }

    fun startWakeWordGuard(fromContinuousLoop: Boolean = false) {
        val prefs = preferences.value
        isContinuousWakeSessionActive = prefs.continuousListeningAfterWake
        viewModelScope.launch {
            c.refreshVocabularyBias()
        }
        c.voiceEngine.startWakeWordListening(
            wakeTimeoutMs = prefs.wakeWordTimeoutMinutes * 60_000L,
            allowBargeIn = !fromContinuousLoop,
            playCue = !fromContinuousLoop && prefs.voiceCueFeedbackEnabled,
            onTimeout = { timeoutMsg ->
                isContinuousWakeSessionActive = false
                c.uiBridge.postBanner(timeoutMsg)
            },
        ) {
            appendMessage(isUser = false, text = "واژه بیدارباش «Sol» شنیده شد — در حال گوش‌دادن به فرمان شما…", actionBadge = "WAKE_WORD")
            startVoiceListening(fromWakeWord = true)
        }
    }

    private fun scheduleContinuousWakeRearmIfEligible(hasPendingTask: Boolean) {
        continuousRearmJob?.cancel()
        val prefs = preferences.value
        if (!prefs.continuousListeningAfterWake || !isContinuousWakeSessionActive || hasPendingTask) {
            return
        }
        if (pendingConfirmation.value != null || _pendingWebSearch.value != null || _isSlmGenerating.value) return

        continuousRearmJob = viewModelScope.launch {
            delay(350L)
            var waitedMs = 0L
            while (isActive && !c.voiceEngine.canStartAutomatedWakeWord() && waitedMs < 20_000L) {
                delay(250L)
                waitedMs += 250L
            }
            if (!isActive || !isContinuousWakeSessionActive || !preferences.value.continuousListeningAfterWake) {
                return@launch
            }
            val stillBlocked = pendingConfirmation.value != null ||
                _pendingWebSearch.value != null ||
                _isSlmGenerating.value ||
                c.voiceEngine.state.value is VoiceListeningState.Listening ||
                c.voiceEngine.state.value is VoiceListeningState.WakeWordArmed
            if (!stillBlocked && c.voiceEngine.canStartAutomatedWakeWord()) {
                startWakeWordGuard(fromContinuousLoop = true)
            }
        }
    }

    fun clearVoiceError() = c.voiceEngine.clearError()

    fun onAppBackgrounded() {
        isContinuousWakeSessionActive = false
        continuousRearmJob?.cancel()
        _isListeningForConfirm.value = false
        c.voiceEngine.stopListening()
    }

    fun stopContinuousWakeSession() {
        isContinuousWakeSessionActive = false
        continuousRearmJob?.cancel()
    }

    fun speakText(text: String) {
        val prefs = preferences.value
        c.ttsManager.speak(
            text = text,
            speechRate = prefs.speechRate,
            persona = prefs.voicePersona,
        )
    }

    fun stopSpeaking() = c.ttsManager.stop()

    fun stopSlmGeneration() {
        c.localSlmEngine.stopGeneration()
        activeStreamingSpeechSession?.cancel()
        activeStreamingSpeechSession = null
        _isSlmGenerating.value = false
    }

    fun resetSlmConversation() {
        c.localSlmEngine.resetConversation()
        c.uiBridge.postBanner("حافظهٔ گفت‌وگوی فعلی مدل تازه شد (مدل در RAM باقی ماند).")
    }

    fun confirmCurrentLocation(label: String) {
        viewModelScope.launch {
            if (label.isBlank()) c.preferencesRepository.clearCurrentLocation()
            else c.preferencesRepository.confirmCurrentLocation(label)
        }
    }

    fun runBackgroundJobNow(jobId: String) {
        viewModelScope.launch {
            val report = c.backgroundTaskCoordinator.triggerImmediateBackgroundJob(jobId)
            appendMessage(isUser = false, text = report, actionBadge = "BACKGROUND")
        }
    }

    fun startVoiceConfirmationListening() {
        val prefs = preferences.value
        _isListeningForConfirm.value = true
        _confirmVoiceFeedback.value = "بگویید «تأیید» یا «لغو»…"
        c.voiceEngine.startConfirmationVoiceListening(
            preferVosk = prefs.voskOfflineEnabled,
            vadSilenceMs = prefs.vadSilenceTimeoutMs,
            maxDurationMs = prefs.maxVoiceListenSeconds * 1000L,
            playCue = prefs.voiceCueFeedbackEnabled,
        ) { decision, spoken ->
            _isListeningForConfirm.value = false
            when (decision) {
                true -> {
                    _confirmVoiceFeedback.value = "شنیده شد: «$spoken» ⟵ در حال تأیید"
                    confirmAndExecutePending()
                }
                false -> {
                    _confirmVoiceFeedback.value = "شنیده شد: «$spoken» ⟵ لغو شد"
                    cancelPendingConfirmation()
                }
                null -> {
                    _confirmVoiceFeedback.value = if (spoken.isBlank()) {
                        "صدایی شنیده نشد؛ لطفاً «تأیید» یا «لغو» بگویید."
                    } else {
                        "شنیده شد «$spoken» — لطفاً واضح‌تر بگویید «تأیید» یا «لغو»."
                    }
                }
            }
        }
    }

    fun stopVoiceConfirmationListening() {
        _isListeningForConfirm.value = false
        c.voiceEngine.stopListening()
    }

    fun selectConfirmationCandidate(contact: ResolvedContact) = c.confirmationManager.selectCandidate(contact)
    fun updateConfirmationChannel(channel: CommunicationChannel) = c.confirmationManager.updateChannel(channel)
    fun updateConfirmationMessage(text: String) = c.confirmationManager.updateMessageBody(text)

    fun confirmAndExecutePending() {
        stopVoiceConfirmationListening()
        _confirmVoiceFeedback.value = ""
        c.dialogueManager.clearPendingState()
        val pending = c.confirmationManager.pending.value ?: return
        viewModelScope.launch {
            c.taskManager.confirmAndExecute(pending) { report ->
                appendMessage(isUser = false, text = report, actionBadge = "CONFIRMED")
                if (preferences.value.autoSpeak) speakText(report)
            }
        }
    }

    fun cancelPendingConfirmation() {
        stopVoiceConfirmationListening()
        _confirmVoiceFeedback.value = ""
        c.dialogueManager.clearPendingState()
        c.taskManager.cancelConfirmation { msg ->
            appendMessage(isUser = false, text = msg, actionBadge = "CANCELLED")
        }
    }

    fun dismissActiveTaskCard() = c.taskManager.dismissTaskCard()

    private fun appendMessage(
        isUser: Boolean,
        text: String,
        actionBadge: String = "",
        choices: List<DialogueUiChoice> = emptyList(),
    ): Long {
        val msgId = System.nanoTime()
        val next = ChatMessageItem(
            id = msgId,
            isUser = isUser,
            text = text,
            actionBadge = actionBadge,
            choices = choices,
        )
        _messages.value = _messages.value + next
        return msgId
    }

    private fun updateMessageText(messageId: Long, updatedText: String) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == messageId) msg.copy(text = updatedText) else msg
        }
    }

    private fun updateMessage(
        messageId: Long,
        updatedText: String,
        actionBadge: String,
        choices: List<DialogueUiChoice> = emptyList(),
    ) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == messageId) {
                msg.copy(text = updatedText, actionBadge = actionBadge, choices = choices)
            } else {
                msg
            }
        }
    }

    override fun onCleared() {
        c.voiceEngine.stopListening()
        c.ttsManager.stop()
        c.localSlmEngine.releaseSessionMemory()
        super.onCleared()
    }
}
