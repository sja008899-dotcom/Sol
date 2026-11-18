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
import com.example.sol.memory.BackgroundJobLogEntity
import com.example.sol.memory.BookKnowledgeEntity
import com.example.sol.memory.ConfirmationPolicy
import com.example.sol.memory.DeliveryPreference
import com.example.sol.memory.HistoryEntity
import com.example.sol.memory.InvocationMode
import com.example.sol.memory.LocationEntity
import com.example.sol.memory.MemoryFactEntity
import com.example.sol.memory.PersonEntity
import com.example.sol.memory.SolPreferences
import com.example.sol.memory.SolRoleEntity
import com.example.sol.memory.VoicePersona
import com.example.sol.security.SecurityAuditItem
import com.example.sol.tasks.ActiveTaskState
import com.example.sol.tasks.CommunicationChannel
import com.example.sol.tasks.PendingCommunicationConfirmation
import com.example.sol.voice.AudioVolumeStatus
import com.example.sol.voice.TtsAvailability
import com.example.sol.voice.TtsVoiceInfo
import com.example.sol.voice.VoiceListeningState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatMessageItem(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    val actionBadge: String = "",
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

    val enabledPeriodicJobs: StateFlow<Set<String>> = c.backgroundTaskCoordinator.enabledPeriodicJobs

    val listeningState: StateFlow<VoiceListeningState> = c.voiceEngine.state
    val partialSpeechText: StateFlow<String> = c.voiceEngine.partialText
    val micRms: StateFlow<Float> = c.voiceEngine.rmsDb
    val ttsAvailability: StateFlow<TtsAvailability> = c.persianTts.availability
    val isSpeaking: StateFlow<Boolean> = c.persianTts.isSpeaking
    val persianVoices: StateFlow<List<TtsVoiceInfo>> = c.persianTts.persianVoices

    val activeTask: StateFlow<ActiveTaskState?> = c.taskManager.activeTask
    val pendingConfirmation: StateFlow<PendingCommunicationConfirmation?> = c.confirmationManager.pending

    private val _pendingBookFetch = MutableStateFlow<PendingBookFetchConfirmation?>(null)
    val pendingBookFetch: StateFlow<PendingBookFetchConfirmation?> = _pendingBookFetch.asStateFlow()

    private val _isFetchingBook = MutableStateFlow(false)
    val isFetchingBook: StateFlow<Boolean> = _isFetchingBook.asStateFlow()

    private val _voiceRoleSession = MutableStateFlow<VoiceRoleTeachingSession?>(null)
    val voiceRoleSession: StateFlow<VoiceRoleTeachingSession?> = _voiceRoleSession.asStateFlow()

    private val _showCapabilitiesSheet = MutableStateFlow(false)
    val showCapabilitiesSheet: StateFlow<Boolean> = _showCapabilitiesSheet.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _messages = MutableStateFlow(
        listOf(
            ChatMessageItem(
                id = 1L,
                isUser = false,
                text = "سلام. من Sol هستم — دستیار شخصی شما با ۲۰ قابلیت (۱۰ اجرای پس‌زمینه، جست‌وجوی کتاب با تأیید و آموزش نقش با صدای شما).\nمی‌توانی دکمه «۲۰ قابلیت» را بزنی، تایپ کنی یا با میکروفون صحبت کنی.",
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
    }

    fun refreshSystemSnapshots() {
        _hasMicPermission.value = c.permissionManager.hasMicrophonePermission()
        _hasContactsPermission.value = c.permissionManager.hasContactsPermission()
        _hasNotificationPermission.value = c.permissionManager.hasNotificationPermission()
        _batteryStatus.value = c.deviceController.getBatteryStatus()
        _volumeStatus.value = c.deviceController.getVolumeStatus()
        _securityAudit.value = c.privacyManager.getSecurityAudit(
            preferences.value.confirmationPolicy == ConfirmationPolicy.ALWAYS_COMMUNICATION
        )
        viewModelScope.launch {
            _contacts.value = c.contactManager.loadMergedContacts()
            _launchableApps.value = c.deviceController.appLauncher.getLaunchableApps().take(12)
        }
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

        appendMessage(isUser = true, text = raw)

        viewModelScope.launch {
            val result = c.commandBrain.process(raw)
            appendMessage(
                isUser = false,
                text = result.replyText,
                actionBadge = result.parsedCommand.action.name,
            )
            c.historyRepository.record(
                userText = raw,
                solText = result.replyText,
                intentAction = result.parsedCommand.action.name,
            )
            if (result.speakReply && preferences.value.autoSpeak) {
                speakText(result.replyText)
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
                null -> Unit
            }
        }
    }

    // --- Advanced Web Book + Voice Role Learning ---
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
            val msg = "دریافت کتاب «${req.query}» در پس‌زمینه زمان‌بندی شد؛ پس از دریافت در حافظه ذخیره و با اعلان اطلاع‌رسانی می‌شود."
            appendMessage(isUser = false, text = msg, actionBadge = "BG_BOOK")
            if (preferences.value.autoSpeak) speakText(msg)
            return
        }

        _isFetchingBook.value = true
        viewModelScope.launch {
            val result = c.bookWebFetcher.fetchBookFromInternet(req.query)
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
                    val savedBook = entity.copy(id = savedId)
                    val msg = "📘 کتاب «${fetched.title}» اثر ${fetched.author} دریافت و در حافظه محلی ذخیره شد.\nحالا می‌توانید با توضیحات صوتی خودتان، نقش جدید Sol را بر اساس این کتاب آموزش دهید."
                    appendMessage(isUser = false, text = msg, actionBadge = "BOOK_SAVED")
                    c.historyRepository.record(
                        userText = "دریافت کتاب ${req.query}",
                        solText = msg,
                        intentAction = "WEB_BOOK_LEARN_ROLE",
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
                    // Still allow user to teach voice role/notes offline!
                    _voiceRoleSession.value = VoiceRoleTeachingSession(
                        book = null,
                        roleNameDraft = req.roleHint.ifBlank { "نقش مبتنی بر ${req.query}" },
                    )
                }
            )
        }
    }

    fun importLocalBookFile(uri: Uri, titleHint: String = "") {
        viewModelScope.launch {
            val result = c.bookWebFetcher.importLocalTextBook(uri, titleHint)
            result.fold(
                onSuccess = { book ->
                    val id = c.knowledgeRepository.saveBook(book)
                    val saved = book.copy(id = id)
                    val msg = "فایل کتاب «${saved.title}» به صورت آفلاین خوانده و در حافظه ذخیره شد."
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
        c.voiceEngine.startListening { spoken ->
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
            val confirmText = "یاد گرفتم! نقش «$roleName» با توضیحات شما${session.book?.let { " و کتاب «${it.title}»" } ?: ""} در حافظه و شخصیت کاری Sol ثبت و فعال شد."
            appendMessage(isUser = false, text = confirmText, actionBadge = "ROLE_LEARNED")
            c.historyRepository.record(
                userText = "آموزش نقش $roleName",
                solText = confirmText,
                intentAction = "TEACH_ROLE_VOICE",
            )
            if (preferences.value.autoSpeak) speakText(confirmText)
        }
    }

    fun dismissVoiceRoleSession() {
        c.voiceEngine.stopListening()
        _voiceRoleSession.value = null
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
        viewModelScope.launch { c.knowledgeRepository.deleteBook(id) }
    }

    // --- Voice & TTS ---
    fun startVoiceListening() {
        c.voiceEngine.startListening { recognizedText ->
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

    // --- Confirmation & Communication ---
    fun selectConfirmationCandidate(contact: ResolvedContact) = c.confirmationManager.selectCandidate(contact)
    fun updateConfirmationChannel(channel: CommunicationChannel) = c.confirmationManager.updateChannel(channel)
    fun updateConfirmationMessage(text: String) = c.confirmationManager.updateMessageBody(text)

    fun confirmAndExecutePending() {
        val pending = c.confirmationManager.pending.value ?: return
        viewModelScope.launch {
            c.taskManager.confirmAndExecute(pending) { report ->
                appendMessage(isUser = false, text = report, actionBadge = "CONFIRMED")
                if (preferences.value.autoSpeak) speakText(report)
            }
        }
    }

    fun cancelPendingConfirmation() {
        c.taskManager.cancelConfirmation { msg ->
            appendMessage(isUser = false, text = msg, actionBadge = "CANCELLED")
        }
    }

    fun dismissActiveTaskCard() = c.taskManager.dismissTaskCard()

    fun initiateContactAction(contact: ResolvedContact, channel: CommunicationChannel) {
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

    private fun appendMessage(isUser: Boolean, text: String, actionBadge: String = "") {
        val next = ChatMessageItem(
            id = System.nanoTime(),
            isUser = isUser,
            text = text,
            actionBadge = actionBadge,
        )
        _messages.value = _messages.value + next
    }
}
