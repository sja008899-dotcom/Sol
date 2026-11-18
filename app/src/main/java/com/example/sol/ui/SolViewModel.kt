package com.example.sol.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SolApplication
import com.example.sol.brain.PendingSolTask
import com.example.sol.contacts.ResolvedContact
import com.example.sol.device.BatteryStatus
import com.example.sol.device.InstalledAppShortcut
import com.example.sol.memory.ConfirmationPolicy
import com.example.sol.memory.DeliveryPreference
import com.example.sol.memory.HistoryEntity
import com.example.sol.memory.InvocationMode
import com.example.sol.memory.LocationEntity
import com.example.sol.memory.MemoryFactEntity
import com.example.sol.memory.PersonEntity
import com.example.sol.memory.SolPreferences
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

    val listeningState: StateFlow<VoiceListeningState> = c.voiceEngine.state
    val partialSpeechText: StateFlow<String> = c.voiceEngine.partialText
    val micRms: StateFlow<Float> = c.voiceEngine.rmsDb
    val ttsAvailability: StateFlow<TtsAvailability> = c.persianTts.availability
    val isSpeaking: StateFlow<Boolean> = c.persianTts.isSpeaking
    val persianVoices: StateFlow<List<TtsVoiceInfo>> = c.persianTts.persianVoices

    val activeTask: StateFlow<ActiveTaskState?> = c.taskManager.activeTask
    val pendingConfirmation: StateFlow<PendingCommunicationConfirmation?> = c.confirmationManager.pending

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _messages = MutableStateFlow(
        listOf(
            ChatMessageItem(
                id = 1L,
                isUser = false,
                text = "سلام. من Sol هستم — دستیار مستقل و آفلاین فارسی شما.\nمی‌توانی تایپ کنی یا دکمهٔ میکروفون را بزنی. برای یادگیری بگو: «هر وقت می‌گم دفتر منظورم شرکت ... است».",
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

    init {
        refreshSystemSnapshots()
    }

    fun refreshSystemSnapshots() {
        _hasMicPermission.value = c.permissionManager.hasMicrophonePermission()
        _hasContactsPermission.value = c.permissionManager.hasContactsPermission()
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

            val pending = result.pendingTask
            if (pending is PendingSolTask.Communicate) {
                c.taskManager.startPendingTask(pending) { statusMsg ->
                    appendMessage(isUser = false, text = statusMsg, actionBadge = "TASK")
                    if (preferences.value.autoSpeak) speakText(statusMsg)
                }
            }
        }
    }

    fun startVoiceListening(autoSubmitAfterSpeech: Boolean = false) {
        c.voiceEngine.startListening { recognizedText ->
            if (autoSubmitAfterSpeech) {
                submitCommand(recognizedText)
            } else {
                // Fill editable input bar so user can review/edit or send immediately
                _inputText.value = recognizedText
                submitCommand(recognizedText)
            }
        }
    }

    fun stopVoiceListening() {
        c.voiceEngine.stopListening()
    }

    fun clearVoiceError() {
        c.voiceEngine.clearError()
    }

    fun speakText(text: String) {
        val prefs = preferences.value
        c.persianTts.speak(
            text = text,
            speechRate = prefs.speechRate,
            persona = prefs.voicePersona,
            preferredVoiceName = prefs.preferredTtsVoiceName,
        )
    }

    fun stopSpeaking() {
        c.persianTts.stop()
    }

    fun testVoiceSample() {
        val prefs = preferences.value
        speakText("سلام ${prefs.ownerName}. من Sol دستیار شخصی آفلاین شما هستم.")
    }

    // --- Confirmation & Communication ---
    fun selectConfirmationCandidate(contact: ResolvedContact) {
        c.confirmationManager.selectCandidate(contact)
    }

    fun updateConfirmationChannel(channel: CommunicationChannel) {
        c.confirmationManager.updateChannel(channel)
    }

    fun updateConfirmationMessage(text: String) {
        c.confirmationManager.updateMessageBody(text)
    }

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

    fun dismissActiveTaskCard() {
        c.taskManager.dismissTaskCard()
    }

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
        viewModelScope.launch {
            c.learningEngine.teachAlias(alias, meaning)
        }
    }

    fun addFact(fact: String) {
        if (fact.isBlank()) return
        viewModelScope.launch {
            c.learningEngine.teachFact(fact)
        }
    }

    fun updateMemoryItem(item: MemoryFactEntity, newValue: String) {
        if (newValue.isBlank()) return
        viewModelScope.launch {
            c.memoryRepository.updateMemory(item.copy(valueText = newValue.trim()))
        }
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
        viewModelScope.launch {
            c.locationRepository.saveLocation(alias, fullName, addressNote, existingId)
        }
    }

    fun deleteLocation(id: Long) {
        viewModelScope.launch { c.locationRepository.deleteLocation(id) }
    }

    fun confirmCurrentLocation(label: String) {
        viewModelScope.launch {
            if (label.isBlank()) {
                c.preferencesRepository.clearCurrentLocation()
            } else {
                c.preferencesRepository.confirmCurrentLocation(label)
            }
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
