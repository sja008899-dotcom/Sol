package com.example.sol.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SolApplication
import com.example.sol.brain.PendingSolTask
import com.example.sol.contacts.ResolvedContact
import com.example.sol.device.BatteryStatus
import com.example.sol.device.InstalledAppShortcut
import com.example.sol.dialogue.DialogueUiChoice
import com.example.sol.knowledge.LocalSlmEngine
import com.example.sol.knowledge.SlmModelSpec
import com.example.sol.knowledge.SlmRuntimeState
import com.example.sol.knowledge.SlmStatus
import com.example.sol.memory.BackgroundJobLogEntity
import com.example.sol.memory.BookKnowledgeEntity
import com.example.sol.memory.ConfirmationPolicy
import com.example.sol.memory.DeliveryPreference
import com.example.sol.memory.HistoryEntity
import com.example.sol.memory.HistoryRetentionMode
import com.example.sol.memory.InvocationMode
import com.example.sol.memory.LocationEntity
import com.example.sol.memory.MemoryFactEntity
import com.example.sol.memory.PersonEntity
import com.example.sol.memory.ScheduledAlarmEntity
import com.example.sol.memory.SolPreferences
import com.example.sol.memory.SolRoleEntity
import com.example.sol.memory.UserUtteranceExampleEntity
import com.example.sol.memory.VoicePersona
import com.example.sol.security.RamUsageSnapshot
import com.example.sol.security.SecurityAuditItem
import com.example.sol.tasks.ActiveTaskState
import com.example.sol.tasks.CommunicationChannel
import com.example.sol.tasks.PendingCommunicationConfirmation
import com.example.sol.voice.ActiveAsrBackend
import com.example.sol.voice.AudioVolumeStatus
import com.example.sol.voice.TtsAvailability
import com.example.sol.voice.TtsEngineInfo
import com.example.sol.voice.TtsVoiceInfo
import com.example.sol.voice.VoiceListeningState
import com.example.sol.voice.VoskModelStatus
import com.example.sol.widget.SolQuickSettingsTileService
import com.example.sol.widget.SolWidgetStateMirror
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class ChatMessageItem(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    val actionBadge: String = "",
    val choices: List<DialogueUiChoice> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis(),
)

data class PendingBookFetchConfirmation(
    val query: String,
    val roleHint: String,
    val runInBackground: Boolean,
)

data class VoiceRoleTeachingSession(
    val book: BookKnowledgeEntity?,
    val roleNameDraft: String,
    val voiceExplanationDraft: String = "",
    val isListeningForExplanation: Boolean = false,
)

enum class BackupDialogMode { EXPORT, IMPORT }

data class PendingBackupPasswordRequest(
    val mode: BackupDialogMode,
    val uri: Uri,
)

class SolViewModel(application: Application) : AndroidViewModel(application) {
    private val c = (application as SolApplication).container

    val preferences: StateFlow<SolPreferences> = c.preferencesRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SolPreferences())

    val memories: StateFlow<List<MemoryFactEntity>> = c.memoryRepository.allMemories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val persons: StateFlow<List<PersonEntity>> = c.personRepository.allPersons
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val locations: StateFlow<List<LocationEntity>> = c.locationRepository.allLocations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history: StateFlow<List<HistoryEntity>> = c.historyRepository.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val books: StateFlow<List<BookKnowledgeEntity>> = c.knowledgeRepository.books
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val roles: StateFlow<List<SolRoleEntity>> = c.knowledgeRepository.roles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val backgroundLogs: StateFlow<List<BackgroundJobLogEntity>> = c.knowledgeRepository.backgroundLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bookChunkCount: StateFlow<Int> = c.knowledgeRepository.bookChunkCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val scheduledAlarms: StateFlow<List<ScheduledAlarmEntity>> = c.knowledgeRepository.scheduledAlarms
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val userUtteranceExamples: StateFlow<List<UserUtteranceExampleEntity>> = c.userUtteranceRepository.allExamples
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val enabledPeriodicJobs: StateFlow<Set<String>> = c.backgroundTaskCoordinator.enabledPeriodicJobs
    val capabilitiesCatalog: List<com.example.sol.knowledge.SolCapabilityItem> get() = c.capabilitiesCatalog

    val listeningState: StateFlow<VoiceListeningState> = c.voiceEngine.state
    val partialSpeechText: StateFlow<String> = c.voiceEngine.partialText
    val micRms: StateFlow<Float> = c.voiceEngine.rmsDb
    val activeAsrBackend: StateFlow<ActiveAsrBackend> = c.voiceEngine.activeBackend
    val voskModelStatus: StateFlow<VoskModelStatus> = c.voiceEngine.voskEngine.modelStatus
    val ramSnapshot: StateFlow<RamUsageSnapshot> = c.memoryOptimizer.ramSnapshot

    val ttsAvailability: StateFlow<TtsAvailability> = c.persianTts.availability
    val isSpeaking: StateFlow<Boolean> = c.persianTts.isSpeaking
    val persianVoices: StateFlow<List<TtsVoiceInfo>> = c.persianTts.persianVoices
    val availableTtsEngines: StateFlow<List<TtsEngineInfo>> = c.persianTts.availableEngines
    val activeTtsEnginePackage: StateFlow<String> = c.persianTts.activeEnginePackage
    val activeTtsEngineLabel: StateFlow<String> = c.persianTts.activeEngineLabel

    val activeTask: StateFlow<ActiveTaskState?> = c.taskManager.activeTask
    val pendingConfirmation: StateFlow<PendingCommunicationConfirmation?> = c.confirmationManager.pending

    private val _isListeningForConfirm = MutableStateFlow(false)
    val isListeningForConfirm: StateFlow<Boolean> = _isListeningForConfirm.asStateFlow()

    private val _confirmVoiceFeedback = MutableStateFlow("")
    val confirmVoiceFeedback: StateFlow<String> = _confirmVoiceFeedback.asStateFlow()

    private val _pendingBookFetch = MutableStateFlow<PendingBookFetchConfirmation?>(null)
    val pendingBookFetch: StateFlow<PendingBookFetchConfirmation?> = _pendingBookFetch.asStateFlow()

    private val _isFetchingBook = MutableStateFlow(false)
    val isFetchingBook: StateFlow<Boolean> = _isFetchingBook.asStateFlow()

    private val _voiceRoleSession = MutableStateFlow<VoiceRoleTeachingSession?>(null)
    val voiceRoleSession: StateFlow<VoiceRoleTeachingSession?> = _voiceRoleSession.asStateFlow()

    private val _showCapabilitiesSheet = MutableStateFlow(false)
    val showCapabilitiesSheet: StateFlow<Boolean> = _showCapabilitiesSheet.asStateFlow()

    private val _pendingBackupRequest = MutableStateFlow<PendingBackupPasswordRequest?>(null)
    val pendingBackupRequest: StateFlow<PendingBackupPasswordRequest?> = _pendingBackupRequest.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    val canScheduleExactAlarms: StateFlow<Boolean> = c.exactAlarmScheduler.isExactModeActive

    private val _vocabularyTermsCount = MutableStateFlow(0)
    val vocabularyTermsCount: StateFlow<Int> = _vocabularyTermsCount.asStateFlow()

    private val _slmStatus = MutableStateFlow(c.localSlmEngine.getStatus(false, ""))
    val slmStatus: StateFlow<SlmStatus> = _slmStatus.asStateFlow()

    val slmRuntimeState: StateFlow<SlmRuntimeState> = c.localSlmEngine.runtimeState

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

    private val _contacts = MutableStateFlow<List<ResolvedContact>>(emptyList())
    val contacts: StateFlow<List<ResolvedContact>> = _contacts.asStateFlow()

    private val _batteryStatus = MutableStateFlow(c.deviceController.getBatteryStatus())
    val batteryStatus: StateFlow<BatteryStatus> = _batteryStatus.asStateFlow()

    private val _volumeStatus = MutableStateFlow(c.deviceController.getVolumeStatus())
    val volumeStatus: StateFlow<AudioVolumeStatus> = _volumeStatus.asStateFlow()

    private val _launchableApps = MutableStateFlow<List<InstalledAppShortcut>>(emptyList())
    val launchableApps: StateFlow<List<InstalledAppShortcut>> = _launchableApps.asStateFlow()

    private val _securityAudit = MutableStateFlow<List<SecurityAuditItem>>(emptyList())
    val securityAudit: StateFlow<List<SecurityAuditItem>> = _securityAudit.asStateFlow()

    private val _hasMicPermission = MutableStateFlow(c.permissionManager.hasMicrophonePermission())
    val hasMicPermission: StateFlow<Boolean> = _hasMicPermission.asStateFlow()

    private val _hasContactsPermission = MutableStateFlow(c.permissionManager.hasContactsPermission())
    val hasContactsPermission: StateFlow<Boolean> = _hasContactsPermission.asStateFlow()

    private val _hasNotificationPermission = MutableStateFlow(c.permissionManager.hasNotificationPermission())
    val hasNotificationPermission: StateFlow<Boolean> = _hasNotificationPermission.asStateFlow()

    init {
        refreshSystemSnapshots()
        observeDynamicSyncs()
        viewModelScope.launch {
            val prefs = c.preferencesRepository.getSnapshot()
            c.historyRepository.enforceRetention(prefs.historyRetentionMode)
        }
    }

    private var initialTtsSyncDone = false

    private fun observeDynamicSyncs() {
        // Keep Home Screen Widget, Quick Settings Tile, Voice & SLM status synchronized
        viewModelScope.launch {
            combine(preferences, roles, slmRuntimeState) { prefs, roleList, _ ->
                prefs to (roleList.firstOrNull { it.isActive }?.roleName.orEmpty())
            }.collect { (prefs, activeRole) ->
                SolWidgetStateMirror.saveAndRefresh(
                    context = getApplication(),
                    confirmedLocation = prefs.confirmedCurrentLocation,
                    activeRoleName = activeRole,
                )
                c.voiceEngine.refreshBackend(prefs.voskOfflineEnabled)
                c.localSlmEngine.refreshRuntimeStatus(prefs.slmModelPath)
                _slmStatus.value = c.localSlmEngine.getStatus(prefs.slmSynthesisEnabled, prefs.slmModelPath)
                if (!initialTtsSyncDone) {
                    initialTtsSyncDone = true
                    if (prefs.preferredTtsEnginePackage.isNotBlank()) {
                        c.persianTts.connectToPhoneTts(prefs.preferredTtsEnginePackage)
                    }
                }
            }
        }

        // Keep ASR Vocabulary Bias enriched with user Contacts, Locations, Aliases & Books
        viewModelScope.launch {
            combine(memories, persons, locations, books) { mems, ppl, locs, bks ->
                buildList {
                    mems.forEach { m ->
                        add(m.keyText.removePrefix("alias:"))
                        add(m.valueText)
                    }
                    ppl.forEach { p -> add(p.name) }
                    locs.forEach { l ->
                        add(l.alias)
                        add(l.fullName)
                    }
                    bks.forEach { b -> add(b.title) }
                }
            }.collect { terms ->
                c.voiceEngine.updateVocabularyBias(terms)
                _vocabularyTermsCount.value = c.voiceEngine.voskEngine.vocabularyBias.currentTermsCount()
            }
        }
        // Keep Security Audit synchronized with runtime ASR/TTS/Privacy states
        viewModelScope.launch {
            combine(preferences, activeAsrBackend, voskModelStatus, persianVoices) { prefs, asrBackend, _, voices ->
                val ttsNeedsNet = voices.firstOrNull { it.name == prefs.preferredTtsVoiceName }?.isOffline == false
                c.privacyManager.getSecurityAudit(
                    alwaysConfirmCommunication = prefs.confirmationPolicy == ConfirmationPolicy.ALWAYS_COMMUNICATION,
                    activeAsrBackend = asrBackend,
                    voskInstalled = c.voiceEngine.voskEngine.isModelInstalled(),
                    ttsRequiresNetwork = ttsNeedsNet,
                    historyRetentionMode = prefs.historyRetentionMode,
                    privateModeEnabled = prefs.privateModeEnabled,
                )
            }.collect { items ->
                _securityAudit.value = items
            }
        }
    }

    fun refreshSystemSnapshots() {
        _hasMicPermission.value = c.permissionManager.hasMicrophonePermission()
        _hasContactsPermission.value = c.permissionManager.hasContactsPermission()
        _hasNotificationPermission.value = c.permissionManager.hasNotificationPermission()
        c.exactAlarmScheduler.refreshExactAlarmState()
        _batteryStatus.value = c.deviceController.getBatteryStatus()
        _volumeStatus.value = c.deviceController.getVolumeStatus()
        val currentPrefs = preferences.value
        val ttsNeedsNet = persianVoices.value
            .firstOrNull { it.name == currentPrefs.preferredTtsVoiceName }
            ?.isOffline == false
        _securityAudit.value = c.privacyManager.getSecurityAudit(
            alwaysConfirmCommunication = currentPrefs.confirmationPolicy == ConfirmationPolicy.ALWAYS_COMMUNICATION,
            activeAsrBackend = activeAsrBackend.value,
            voskInstalled = c.voiceEngine.voskEngine.isModelInstalled(),
            ttsRequiresNetwork = ttsNeedsNet,
            historyRetentionMode = currentPrefs.historyRetentionMode,
            privateModeEnabled = currentPrefs.privateModeEnabled,
        )
        c.memoryOptimizer.refresh()
        c.voiceEngine.voskEngine.refreshInstalledStatus()
        c.localSlmEngine.refreshRuntimeStatus(currentPrefs.slmModelPath)
        _slmStatus.value = c.localSlmEngine.getStatus(
            currentPrefs.slmSynthesisEnabled,
            currentPrefs.slmModelPath,
        )
        viewModelScope.launch {
            _contacts.value = c.contactManager.loadMergedContacts()
            _launchableApps.value = c.deviceController.appLauncher.getLaunchableApps().take(12)
        }
    }

    fun clearStatusBanner() {
        _statusBannerMessage.value = null
    }

    fun updateInputText(newText: String) {
        _inputText.value = newText
    }

    fun openCapabilitiesSheet(open: Boolean) {
        _showCapabilitiesSheet.value = open
    }

    fun submitCommand(textOverride: String? = null) {
        val raw = (textOverride ?: _inputText.value).trim()
        if (raw.isBlank()) return
        if (textOverride == null) _inputText.value = ""

        _messages.value = _messages.value.map { msg ->
            if (msg.choices.isEmpty()) msg else msg.copy(choices = emptyList())
        }
        appendMessage(isUser = true, text = raw)

        viewModelScope.launch {
            val result = c.commandBrain.processDialogue(raw)
            if (result.replyText.isNotBlank()) {
                appendMessage(
                    isUser = false,
                    text = result.replyText,
                    actionBadge = result.parsedCommand.action.name,
                    choices = result.actionChoices,
                )
                val currentPrefs = preferences.value
                c.historyRepository.recordIfAllowed(
                    userText = raw,
                    solText = result.replyText,
                    intentAction = result.parsedCommand.action.name,
                    retentionMode = currentPrefs.historyRetentionMode,
                    privateModeEnabled = currentPrefs.privateModeEnabled,
                )
                if (result.speakReply && preferences.value.autoSpeak) {
                    speakText(result.replyText)
                }
            }
            refreshSystemSnapshots()

            when (val pending = result.pendingTask) {
                is PendingSolTask.Communicate -> {
                    c.taskManager.startPendingTask(pending) { statusMsg ->
                        appendMessage(isUser = false, text = statusMsg, actionBadge = "TASK")
                        if (preferences.value.autoSpeak) speakText(statusMsg)
                    }
                }
                is PendingSolTask.InternetBookRoleFetch -> {
                    _pendingBookFetch.value = PendingBookFetchConfirmation(
                        query = pending.bookQuery,
                        roleHint = pending.requestedRoleHint,
                        runInBackground = pending.runInBackground,
                    )
                }
                is PendingSolTask.OpenCapabilitiesCatalog -> {
                    _showCapabilitiesSheet.value = true
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
                val res = c.localSlmEngine.generateReplyStream(
                    userPrompt = task.userPrompt,
                    systemInstruction = task.systemInstruction,
                    preferredPath = prefs.slmModelPath,
                    preferGpu = prefs.slmPreferGpu,
                ) { partialClean ->
                    updateMessageText(placeholderId, partialClean)
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
                        if (prefs.autoSpeak) {
                            speakText(finalText)
                        }
                    },
                    onFailure = { err ->
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
            } finally {
                _isSlmGenerating.value = false
                refreshSystemSnapshots()
            }
        }
    }

    // --- Advanced Web / PDF / EPUB Book + BM25 + Voice Role Learning ---
    fun requestBookRoleStudio(defaultQuery: String = "", roleHint: String = "") {
        _pendingBookFetch.value = PendingBookFetchConfirmation(
            query = defaultQuery,
            roleHint = roleHint,
            runInBackground = false,
        )
    }

    fun updatePendingBookQuery(query: String, roleHint: String) {
        _pendingBookFetch.value = _pendingBookFetch.value?.copy(query = query, roleHint = roleHint)
    }

    fun dismissPendingBookFetch() {
        _pendingBookFetch.value = null
    }

    fun confirmInternetBookFetch(runInBackground: Boolean) {
        val req = _pendingBookFetch.value ?: return
        _pendingBookFetch.value = null

        if (runInBackground) {
            c.backgroundTaskCoordinator.enqueueBookFetchWorker(req.query)
            val msg = "دریافت کتاب «${req.query}» در پس‌زمینه زمان‌بندی شد؛ پس از دریافت در Room ذخیره و در موتور BM25 نمایه‌سازی می‌شود."
            appendMessage(isUser = false, text = msg, actionBadge = "BG_BOOK")
            if (preferences.value.autoSpeak) speakText(msg)
            return
        }

        _isFetchingBook.value = true
        viewModelScope.launch {
            val result = c.bookWebFetcher.fetchBookOrWebpageFromInternet(req.query)
            _isFetchingBook.value = false
            result.fold(
                onSuccess = { fetched ->
                    val entity = BookKnowledgeEntity(
                        title = fetched.title,
                        author = fetched.author,
                        subjects = fetched.subjects,
                        summaryText = fetched.summaryText,
                        sourceType = fetched.sourceType,
                        sourceUrl = fetched.sourceUrl,
                    )
                    val savedId = c.knowledgeRepository.saveBook(entity)
                    val chunksCount = c.bookRagEngine.indexBookContent(savedId, fetched.title, fetched.fullText)
                    val savedBook = entity.copy(id = savedId)
                    val msg = "📘 منبع «${fetched.title}» اثر ${fetched.author} ذخیره و به $chunksCount بخش معنایی (BM25) تبدیل شد.\nاکنون می‌توانید با توضیحات صوتی خود، نقش جدید Sol را آموزش دهید."
                    appendMessage(isUser = false, text = msg, actionBadge = "BOOK_SAVED")
                    val currentPrefs = preferences.value
                    c.historyRepository.recordIfAllowed(
                        userText = "دریافت کتاب/صفحه ${req.query}",
                        solText = msg,
                        intentAction = "WEB_BOOK_LEARN_ROLE",
                        retentionMode = currentPrefs.historyRetentionMode,
                        privateModeEnabled = currentPrefs.privateModeEnabled,
                    )
                    if (preferences.value.autoSpeak) speakText(msg)

                    _voiceRoleSession.value = VoiceRoleTeachingSession(
                        book = savedBook,
                        roleNameDraft = req.roleHint.ifBlank { "مشاور و راهنمای ${fetched.title}" },
                    )
                },
                onFailure = { err ->
                    val failMsg = err.message ?: "دریافت کتاب از اینترنت ناموفق بود."
                    appendMessage(isUser = false, text = failMsg, actionBadge = "BOOK_ERROR")
                    _voiceRoleSession.value = VoiceRoleTeachingSession(
                        book = null,
                        roleNameDraft = req.roleHint.ifBlank { "نقش مبتنی بر ${req.query}" },
                    )
                }
            )
        }
    }

    fun importLocalBookFile(uri: Uri, titleHint: String = "") {
        _isFetchingBook.value = true
        viewModelScope.launch {
            val result = c.bookWebFetcher.importLocalDocument(uri, titleHint)
            _isFetchingBook.value = false
            result.fold(
                onSuccess = { imported ->
                    val id = c.knowledgeRepository.saveBook(imported.entity)
                    val chunkCount = c.bookRagEngine.indexBookContent(id, imported.entity.title, imported.fullText)
                    val saved = imported.entity.copy(id = id)
                    val msg = "فایل «${saved.title}» به صورت آفلاین استخراج و به $chunkCount بخش برای جست‌وجوی BM25 نمایه‌سازی شد."
                    appendMessage(isUser = false, text = msg, actionBadge = "LOCAL_BOOK")
                    _voiceRoleSession.value = VoiceRoleTeachingSession(
                        book = saved,
                        roleNameDraft = "راهنمای ${saved.title}",
                    )
                },
                onFailure = { err ->
                    appendMessage(isUser = false, text = err.message ?: "خواندن فایل ناموفق بود.", actionBadge = "BOOK_ERROR")
                }
            )
        }
    }

    fun openVoiceRoleTeachingForBook(book: BookKnowledgeEntity?) {
        _voiceRoleSession.value = VoiceRoleTeachingSession(
            book = book,
            roleNameDraft = if (book != null) "مشاور ${book.title}" else "دستیار تخصصی من",
        )
    }

    fun updateVoiceRoleDraft(roleName: String, explanation: String) {
        _voiceRoleSession.value = _voiceRoleSession.value?.copy(
            roleNameDraft = roleName,
            voiceExplanationDraft = explanation,
        )
    }

    fun listenVoiceExplanationForRole() {
        _voiceRoleSession.value = _voiceRoleSession.value?.copy(isListeningForExplanation = true)
        c.voiceEngine.startListening(preferVosk = preferences.value.voskOfflineEnabled) { spoken ->
            val current = _voiceRoleSession.value
            if (current != null) {
                val merged = listOf(current.voiceExplanationDraft, spoken)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                _voiceRoleSession.value = current.copy(
                    voiceExplanationDraft = merged,
                    isListeningForExplanation = false,
                )
            }
        }
    }

    fun stopVoiceExplanationListening() {
        c.voiceEngine.stopListening()
        _voiceRoleSession.value = _voiceRoleSession.value?.copy(isListeningForExplanation = false)
    }

    fun saveVoiceRoleTeaching() {
        val session = _voiceRoleSession.value ?: return
        val roleName = session.roleNameDraft.trim().ifBlank { "نقش شخصی Sol" }
        val explanation = session.voiceExplanationDraft.trim()
        _voiceRoleSession.value = null

        viewModelScope.launch {
            session.book?.let { b ->
                if (explanation.isNotBlank()) {
                    c.knowledgeRepository.attachVoiceNoteToBook(b.id, explanation)
                    c.bookRagEngine.indexBookContent(
                        b.id,
                        b.title,
                        "${b.summaryText}\nتوضیح صوتی کاربر: $explanation",
                    )
                }
            }
            c.knowledgeRepository.saveRole(
                roleName = roleName,
                instructions = explanation.ifBlank { "بهره‌گیری از نکات کتاب ${session.book?.title.orEmpty()}" },
                linkedBookTitle = session.book?.title.orEmpty(),
                isActive = true,
            )
            if (explanation.isNotBlank()) {
                c.memoryRepository.remember(
                    key = "role_note:${System.currentTimeMillis()}",
                    value = "نقش «$roleName»: $explanation",
                    kind = "instruction",
                )
            }
            val confirmText = "یاد گرفتم! نقش «$roleName» با توضیحات شما${session.book?.let { " و کتاب «${it.title}»" } ?: ""} ثبت، نمایه‌سازی و روی ویجت فعال شد."
            appendMessage(isUser = false, text = confirmText, actionBadge = "ROLE_LEARNED")
            val currentPrefs = preferences.value
            c.historyRepository.recordIfAllowed(
                userText = "آموزش نقش $roleName",
                solText = confirmText,
                intentAction = "TEACH_ROLE_VOICE",
                retentionMode = currentPrefs.historyRetentionMode,
                privateModeEnabled = currentPrefs.privateModeEnabled,
            )
            if (preferences.value.autoSpeak) speakText(confirmText)
        }
    }

    fun dismissVoiceRoleSession() {
        c.voiceEngine.stopListening()
        _voiceRoleSession.value = null
    }

    // --- Vosk Offline Voice & Wake Word Controls ---
    fun downloadVoskPersianModel() {
        c.voiceEngine.voskEngine.downloadPersianSmallModel()
    }

    fun importVoskModelZip(uri: Uri) {
        c.voiceEngine.voskEngine.importLocalModelZip(uri)
    }

    fun unloadVoskModelFromRam() {
        c.voiceEngine.voskEngine.unloadModelFromRam("آزادسازی دستی مدل صوتی از RAM")
        c.memoryOptimizer.refresh("مدل Vosk از حافظه موقت آزاد شد")
    }

    fun deleteVoskModel() {
        c.voiceEngine.voskEngine.deleteInstalledModel()
        c.memoryOptimizer.refresh()
    }

    fun startWakeWordGuard() {
        c.voiceEngine.startWakeWordListening {
            appendMessage(isUser = false, text = "واژه بیدارباش «Sol» شنیده شد — در حال گوش‌دادن به فرمان شما…", actionBadge = "WAKE_WORD")
            startVoiceListening()
        }
    }

    // --- Encrypted Portable Backup (.solbackup) ---
    fun requestBackupPassword(mode: BackupDialogMode, uri: Uri) {
        _pendingBackupRequest.value = PendingBackupPasswordRequest(mode = mode, uri = uri)
    }

    fun dismissBackupPasswordDialog() {
        _pendingBackupRequest.value = null
    }

    fun executeEncryptedBackup(password: String, replaceOnImport: Boolean) {
        val req = _pendingBackupRequest.value ?: return
        _pendingBackupRequest.value = null
        viewModelScope.launch {
            when (req.mode) {
                BackupDialogMode.EXPORT -> {
                    c.encryptedBackupManager.exportEncryptedBackup(req.uri, password).fold(
                        onSuccess = { stats ->
                            val sizeLabel = formatByteSizeFa(stats.fileSizeBytes)
                            val msg = "پشتیبان رمزنگاری‌شده (.solbackup • $sizeLabel) صادر شد: ${stats.memoriesCount} حافظه، ${stats.personsCount} شخص، ${stats.booksCount} کتاب (${stats.chunksCount} بخش) و ${stats.rolesCount} نقش."
                            _statusBannerMessage.value = msg
                        },
                        onFailure = { err ->
                            _statusBannerMessage.value = err.message ?: "خطا در صدور پشتیبان"
                        }
                    )
                }
                BackupDialogMode.IMPORT -> {
                    c.encryptedBackupManager.importEncryptedBackup(req.uri, password, replaceOnImport).fold(
                        onSuccess = { stats ->
                            val sizeLabel = formatByteSizeFa(stats.fileSizeBytes)
                            val msg = "بازیابی رمزنگاری‌شده ($sizeLabel) موفق بود: ${stats.memoriesCount} حافظه، ${stats.booksCount} کتاب و ${stats.rolesCount} نقش وارد شدند."
                            _statusBannerMessage.value = msg
                            refreshSystemSnapshots()
                        },
                        onFailure = { err ->
                            _statusBannerMessage.value = err.message ?: "خطا در بازیابی پشتیبان"
                        }
                    )
                }
            }
        }
    }

    private fun formatByteSizeFa(bytes: Long): String {
        if (bytes <= 0L) return "۰ بایت"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format("%.1f MB", mb)
            kb >= 1.0 -> String.format("%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    // --- 4GB RAM Optimization & Quick Settings / Exact Alarms ---
    fun trimRamManually() {
        c.memoryOptimizer.manualTrimNow(
            evictRagCache = { c.bookRagEngine.evictCache() },
            unloadVoskIfIdle = { c.voiceEngine.voskEngine.unloadModelFromRam("آزادسازی دستی RAM") },
            releaseSlmCache = { c.localSlmEngine.releaseSessionMemory() },
        )
        _statusBannerMessage.value = "کش کتاب‌ها، حافظهٔ صوتی و SLM برای گوشی ۴ گیگابایتی آزاد شد."
    }

    fun requestAddQuickSettingsTile() {
        SolQuickSettingsTileService.promptPinTile(getApplication()) { msg ->
            _statusBannerMessage.value = msg
        }
    }

    fun requestExactAlarmPermission() {
        c.exactAlarmScheduler.openExactAlarmSettings()
    }

    fun cancelScheduledAlarm(alarm: ScheduledAlarmEntity) {
        viewModelScope.launch {
            c.exactAlarmScheduler.cancelAlarm(alarm)
            _statusBannerMessage.value = "یادآور «${alarm.titleFa}» لغو شد."
        }
    }

    // --- Background Jobs Controls ---
    fun runBackgroundJobNow(jobId: String) {
        viewModelScope.launch {
            val report = c.backgroundTaskCoordinator.triggerImmediateBackgroundJob(jobId)
            appendMessage(isUser = false, text = report, actionBadge = "BACKGROUND")
        }
    }

    fun togglePeriodicBackgroundJob(jobId: String, enable: Boolean) {
        c.backgroundTaskCoordinator.togglePeriodicJob(jobId, enable)
    }

    fun clearBackgroundLogs() {
        viewModelScope.launch { c.knowledgeRepository.clearBackgroundLogs() }
    }

    // --- Books & Roles CRUD ---
    fun toggleRoleActive(role: SolRoleEntity, active: Boolean) {
        viewModelScope.launch { c.knowledgeRepository.toggleRoleActive(role, active) }
    }

    fun deleteRole(id: Long) {
        viewModelScope.launch { c.knowledgeRepository.deleteRole(id) }
    }

    fun deleteBook(id: Long) {
        viewModelScope.launch {
            c.knowledgeRepository.deleteBook(id)
            c.bookRagEngine.evictCache()
        }
    }

    // --- Voice & TTS ---
    fun startVoiceListening() {
        c.voiceEngine.startListening(preferVosk = preferences.value.voskOfflineEnabled) { recognizedText ->
            _inputText.value = recognizedText
            submitCommand(recognizedText)
        }
    }

    fun stopVoiceListening() = c.voiceEngine.stopListening()
    fun clearVoiceError() = c.voiceEngine.clearError()

    fun speakText(text: String) {
        val prefs = preferences.value
        c.persianTts.speak(
            text = text,
            speechRate = prefs.speechRate,
            persona = prefs.voicePersona,
            preferredVoiceName = prefs.preferredTtsVoiceName,
        )
    }

    fun stopSpeaking() = c.persianTts.stop()

    fun testVoiceSample() {
        val prefs = preferences.value
        speakText("سلام ${prefs.ownerName}. من Sol دستیار شخصی شما هستم.")
    }

    fun openSystemTtsSettings() = c.persianTts.openSystemTtsSettings()

    fun recheckSystemTts() {
        c.persianTts.recheckSystemVoices(preferences.value.preferredTtsEnginePackage)
        _statusBannerMessage.value = "در حال بررسی و اتصال به موتورهای صدای نصب‌شده روی گوشی…"
    }

    fun setPreferredTtsEngine(packageName: String) {
        viewModelScope.launch {
            c.preferencesRepository.setPreferredTtsEnginePackage(packageName)
            c.preferencesRepository.setPreferredTtsVoiceName("")
            c.persianTts.connectToPhoneTts(packageName)
            val label = availableTtsEngines.value.firstOrNull { it.packageName == packageName }?.label
                ?: if (packageName.isBlank()) "تشخیص خودکار (SherpaTTS / پیش‌فرض گوشی)" else packageName
            _statusBannerMessage.value = "موتور صدای Sol به «$label» متصل شد."
        }
    }

    // --- Confirmation & Communication (+ Hands-Free Voice Confirm/Cancel) ---
    fun startVoiceConfirmationListening() {
        _isListeningForConfirm.value = true
        _confirmVoiceFeedback.value = "بگویید «تأیید» یا «لغو»…"
        c.voiceEngine.startConfirmationVoiceListening(preferVosk = preferences.value.voskOfflineEnabled) { decision, spoken ->
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

    fun initiateContactAction(contact: ResolvedContact, channel: CommunicationChannel) {
        c.dialogueManager.clearPendingState()
        val prefs = preferences.value
        c.taskManager.requestDirectCommunication(
            contact = contact,
            channel = channel,
            ownerName = prefs.ownerName,
            confirmedLocation = prefs.confirmedCurrentLocation,
        )
    }

    // --- Memory CRUD ---
    fun addOrUpdateAlias(alias: String, meaning: String) {
        if (alias.isBlank() || meaning.isBlank()) return
        viewModelScope.launch { c.learningEngine.teachAlias(alias, meaning) }
    }

    fun addFact(fact: String) {
        if (fact.isBlank()) return
        viewModelScope.launch { c.learningEngine.teachFact(fact) }
    }

    fun updateMemoryItem(item: MemoryFactEntity, newValue: String) {
        if (newValue.isBlank()) return
        viewModelScope.launch { c.memoryRepository.updateMemory(item.copy(valueText = newValue.trim())) }
    }

    fun deleteMemoryItem(id: Long) {
        viewModelScope.launch { c.memoryRepository.forgetById(id) }
    }

    fun deleteUserUtteranceExample(id: Long) {
        viewModelScope.launch {
            c.userUtteranceRepository.deleteExample(id)
            c.statisticalClassifier.removeUserExample(id)
            _statusBannerMessage.value = "مثال آموزشی از حافظه فراموش شد."
        }
    }

    fun savePerson(name: String, phone: String, relation: String, callScriptNote: String, existingId: Long = 0) {
        if (name.isBlank()) return
        viewModelScope.launch {
            c.personRepository.savePerson(name, phone, relation, callScriptNote, existingId)
            refreshSystemSnapshots()
        }
    }

    fun deletePerson(id: Long) {
        viewModelScope.launch {
            c.personRepository.deletePerson(id)
            refreshSystemSnapshots()
        }
    }

    fun saveLocation(alias: String, fullName: String, addressNote: String, existingId: Long = 0) {
        if (alias.isBlank() || fullName.isBlank()) return
        viewModelScope.launch { c.locationRepository.saveLocation(alias, fullName, addressNote, existingId) }
    }

    fun deleteLocation(id: Long) {
        viewModelScope.launch { c.locationRepository.deleteLocation(id) }
    }

    fun confirmCurrentLocation(label: String) {
        viewModelScope.launch {
            if (label.isBlank()) c.preferencesRepository.clearCurrentLocation()
            else c.preferencesRepository.confirmCurrentLocation(label)
        }
    }

    fun deleteHistoryEntry(id: Long) {
        viewModelScope.launch { c.historyRepository.deleteEntry(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { c.historyRepository.clearAll() }
    }

    // --- Device Controls ---
    fun setMediaVolumePercent(percent: Int) {
        _volumeStatus.value = c.deviceController.setMediaVolumePercent(percent)
    }

    fun openWifiSettings() = c.deviceController.settingsController.openWifiSettings()
    fun openLocationSettings() = c.deviceController.settingsController.openLocationSettings()
    fun openBluetoothSettings() = c.deviceController.settingsController.openBluetoothSettings()
    fun openBatterySettings() = c.deviceController.settingsController.openBatterySettings()
    fun launchInstalledApp(label: String) = c.deviceController.appLauncher.launchAppByQuery(label)

    // --- Preferences ---
    fun setOwnerName(name: String) = viewModelScope.launch { c.preferencesRepository.setOwnerName(name) }
    fun setVoicePersona(p: VoicePersona) = viewModelScope.launch { c.preferencesRepository.setVoicePersona(p) }
    fun setPreferredTtsVoice(name: String) = viewModelScope.launch { c.preferencesRepository.setPreferredTtsVoiceName(name) }
    fun setSpeechRate(rate: Float) = viewModelScope.launch { c.preferencesRepository.setSpeechRate(rate) }
    fun setAutoSpeak(enabled: Boolean) = viewModelScope.launch { c.preferencesRepository.setAutoSpeak(enabled) }
    fun setInvocationMode(m: InvocationMode) = viewModelScope.launch { c.preferencesRepository.setInvocationMode(m) }
    fun setDeliveryPreference(d: DeliveryPreference) = viewModelScope.launch { c.preferencesRepository.setDeliveryPreference(d) }
    fun setConfirmationPolicy(p: ConfirmationPolicy) = viewModelScope.launch {
        c.preferencesRepository.setConfirmationPolicy(p)
        refreshSystemSnapshots()
    }
    fun setDarkTheme(dark: Boolean) = viewModelScope.launch { c.preferencesRepository.setDarkTheme(dark) }
    fun setVoskOfflineEnabled(enabled: Boolean) = viewModelScope.launch { c.preferencesRepository.setVoskOfflineEnabled(enabled) }
    fun setWakeWordEnabled(enabled: Boolean) = viewModelScope.launch { c.preferencesRepository.setWakeWordEnabled(enabled) }
    fun setSlmSynthesisEnabled(enabled: Boolean) = viewModelScope.launch {
        c.preferencesRepository.setSlmSynthesisEnabled(enabled)
        if (!enabled) {
            c.localSlmEngine.unloadFromRam("غیرفعال‌سازی ماژول هوش زبانی")
        }
    }
    fun setSlmPreferGpu(preferGpu: Boolean) = viewModelScope.launch {
        c.preferencesRepository.setSlmPreferGpu(preferGpu)
        if (c.localSlmEngine.isLoadedInRam()) {
            c.localSlmEngine.unloadFromRam("تغییر شتاب‌دهنده سخت‌افزاری؛ در نوبت بعد اعمال می‌شود")
        }
    }
    fun setHistoryRetentionMode(mode: HistoryRetentionMode) = viewModelScope.launch {
        c.preferencesRepository.setHistoryRetentionMode(mode)
        c.historyRepository.enforceRetention(mode)
        _statusBannerMessage.value = "سیاست نگهداری سوابق روی «${mode.labelFa}» تنظیم شد."
        refreshSystemSnapshots()
    }
    fun setPrivateModeEnabled(enabled: Boolean) = viewModelScope.launch {
        c.preferencesRepository.setPrivateModeEnabled(enabled)
        _statusBannerMessage.value = if (enabled) {
            "حالت خصوصی فعال شد؛ گفتگوها در سوابق ذخیره نمی‌شوند."
        } else {
            "حالت خصوصی غیرفعال شد."
        }
        refreshSystemSnapshots()
    }
    fun deleteAllUserData() = viewModelScope.launch {
        c.voiceEngine.stopListening()
        c.persianTts.stop()
        c.localSlmEngine.stopGeneration()
        c.localSlmEngine.unloadFromRam("پاک‌سازی کامل داده‌های کاربر")
        c.confirmationManager.dismiss()
        c.taskManager.dismissTaskCard()
        _pendingBookFetch.value = null
        _voiceRoleSession.value = null
        _pendingBackupRequest.value = null

        c.knowledgeRepository.getAllAlarmsOnce().forEach { alarm ->
            c.exactAlarmScheduler.cancelAlarm(alarm)
        }
        c.knowledgeRepository.deleteAllUserData()
        c.dialogueManager.clearHistory()
        c.statisticalClassifier.syncUserExamples(emptyList())
        c.preferencesRepository.resetAllPreferences()
        c.bookRagEngine.evictCache()
        runCatching {
            File(getApplication<Application>().filesDir, "backups").deleteRecursively()
        }

        _messages.value = listOf(
            ChatMessageItem(
                id = System.nanoTime(),
                isUser = false,
                text = "همهٔ داده‌ها، حافظه‌ها، کتاب‌ها، نقش‌ها، سوابق و تنظیمات Sol با موفقیت پاک شدند.",
                actionBadge = "پاک‌سازی کامل",
            )
        )
        refreshSystemSnapshots()
        _statusBannerMessage.value = "همهٔ داده‌ها، حافظه‌ها، کتاب‌ها، نقش‌ها و تنظیمات Sol پاک شدند."
    }

    // --- Phase 3: Real On-Device SLM (LiteRT-LM) Controls ---
    fun downloadSlmModel(spec: SlmModelSpec) {
        c.localSlmEngine.downloadModel(spec) { savedPath ->
            viewModelScope.launch {
                c.preferencesRepository.setSlmModelPath(savedPath)
                c.preferencesRepository.setSlmSynthesisEnabled(true)
                _statusBannerMessage.value = "مدل «${spec.titleFa}» دانلود و متصل شد."
            }
        }
    }

    fun cancelSlmDownload() {
        c.localSlmEngine.cancelDownload()
        _statusBannerMessage.value = "دانلود مدل متوقف شد."
    }

    fun importLocalSlmModel(uri: Uri) {
        c.localSlmEngine.importLocalModel(uri) { result ->
            result.fold(
                onSuccess = { savedFile ->
                    viewModelScope.launch {
                        c.preferencesRepository.setSlmModelPath(savedFile.absolutePath)
                        c.preferencesRepository.setSlmSynthesisEnabled(true)
                        _statusBannerMessage.value = "فایل «${savedFile.name}» با موفقیت وارد و فعال شد."
                    }
                },
                onFailure = { err ->
                    _statusBannerMessage.value = err.localizedMessage ?: "وارد کردن فایل مدل ناموفق بود."
                }
            )
        }
    }

    fun testLoadSlmInRam() {
        viewModelScope.launch {
            val prefs = preferences.value
            val sysPrompt = LocalSlmEngine.buildSystemInstruction(
                ownerName = prefs.ownerName,
                confirmedLocation = prefs.confirmedCurrentLocation,
                activeRoleName = roles.value.firstOrNull { it.isActive }?.roleName,
                activeRoleInstructions = roles.value.firstOrNull { it.isActive }?.instructions,
                memoryHighlights = emptyList(),
                groundedBookChunks = emptyList(),
                voiceNotes = "",
            )
            val res = c.localSlmEngine.loadModel(
                preferredPath = prefs.slmModelPath,
                preferGpu = prefs.slmPreferGpu,
                systemInstruction = sysPrompt,
            )
            res.fold(
                onSuccess = { backend ->
                    _statusBannerMessage.value = "مدل با موفقیت در RAM بارگذاری شد (${backend.labelFa})."
                },
                onFailure = { err ->
                    _statusBannerMessage.value = err.localizedMessage ?: "بارگذاری مدل در RAM ناموفق بود."
                }
            )
            refreshSystemSnapshots()
        }
    }

    fun unloadSlmFromRam() {
        c.localSlmEngine.unloadFromRam("آزادسازی دستی مدل زبانی از RAM")
        c.memoryOptimizer.refresh("مدل زبانی از RAM آزاد شد")
        _statusBannerMessage.value = "مدل زبانی از RAM آزاد شد."
    }

    fun deleteSlmModel() {
        viewModelScope.launch {
            c.localSlmEngine.deleteModel(preferences.value.slmModelPath)
            c.preferencesRepository.setSlmModelPath("")
            c.preferencesRepository.setSlmSynthesisEnabled(false)
            _statusBannerMessage.value = "فایل مدل زبانی از حافظه گوشی پاک شد."
            refreshSystemSnapshots()
        }
    }

    fun stopSlmGeneration() {
        c.localSlmEngine.stopGeneration()
        _isSlmGenerating.value = false
    }

    fun resetSlmConversation() {
        c.localSlmEngine.resetConversation()
        _statusBannerMessage.value = "حافظهٔ گفت‌وگوی فعلی مدل تازه شد (مدل در RAM باقی ماند)."
    }

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

    override fun onCleared() {
        c.voiceEngine.stopListening()
        c.persianTts.stop()
        c.localSlmEngine.releaseSessionMemory()
        super.onCleared()
    }
}
