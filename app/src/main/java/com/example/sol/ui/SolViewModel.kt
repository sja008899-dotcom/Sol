package com.example.sol.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SolApplication
import com.example.sol.memory.SolPreferences
import com.example.sol.ui.vm.SolUiEvent
import com.example.sol.widget.SolWidgetStateMirror
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Slim root coordinator ViewModel scoped to MainActivity.
 * Screen-specific state and Room Flow subscriptions live inside feature ViewModels in com.example.sol.ui.vm.
 */
class SolViewModel(application: Application) : AndroidViewModel(application) {
    private val c = (application as SolApplication).container

    val preferences: StateFlow<SolPreferences> = c.preferencesRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SolPreferences())

    val capabilitiesCatalog: List<com.example.sol.knowledge.SolCapabilityItem> get() = c.capabilitiesCatalog

    private val _showCapabilitiesSheet = MutableStateFlow(false)
    val showCapabilitiesSheet: StateFlow<Boolean> = _showCapabilitiesSheet.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    private val _hasMicPermission = MutableStateFlow(c.permissionManager.hasMicrophonePermission())
    val hasMicPermission: StateFlow<Boolean> = _hasMicPermission.asStateFlow()

    private val _hasContactsPermission = MutableStateFlow(c.permissionManager.hasContactsPermission())
    val hasContactsPermission: StateFlow<Boolean> = _hasContactsPermission.asStateFlow()

    private val _hasNotificationPermission = MutableStateFlow(c.permissionManager.hasNotificationPermission())
    val hasNotificationPermission: StateFlow<Boolean> = _hasNotificationPermission.asStateFlow()

    private var initialTtsSyncDone = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = c.preferencesRepository.getSnapshot()
            c.historyRepository.enforceRetention(prefs.historyRetentionMode)
        }
        viewModelScope.launch {
            c.uiBridge.events.collect { ev ->
                when (ev) {
                    is SolUiEvent.Banner -> _statusBannerMessage.value = ev.message
                    is SolUiEvent.OpenCapabilities -> _showCapabilitiesSheet.value = true
                    else -> Unit
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            combine(
                c.preferencesRepository.preferences,
                c.knowledgeRepository.roles
                    .map { list -> list.firstOrNull { it.isActive }?.roleName.orEmpty() }
                    .distinctUntilChanged(),
            ) { prefs, activeRole ->
                prefs to activeRole
            }.collect { (prefs, activeRole) ->
                SolWidgetStateMirror.saveAndRefresh(
                    context = getApplication(),
                    confirmedLocation = prefs.confirmedCurrentLocation,
                    activeRoleName = activeRole,
                )
                c.voiceEngine.refreshBackend(prefs.voskOfflineEnabled)
                if (!initialTtsSyncDone) {
                    initialTtsSyncDone = true
                    if (prefs.preferredTtsEnginePackage.isNotBlank()) {
                        c.persianTts.connectToPhoneTts(prefs.preferredTtsEnginePackage)
                    }
                }
            }
        }
    }

    fun refreshPermissions() {
        _hasMicPermission.value = c.permissionManager.hasMicrophonePermission()
        _hasContactsPermission.value = c.permissionManager.hasContactsPermission()
        _hasNotificationPermission.value = c.permissionManager.hasNotificationPermission()
        c.uiBridge.triggerRefreshSnapshots()
    }

    fun clearStatusBanner() {
        _statusBannerMessage.value = null
    }

    fun openCapabilitiesSheet(open: Boolean) {
        _showCapabilitiesSheet.value = open
    }

    fun openAppPermissionSettings() {
        c.deviceController.settingsController.openAppDetailsSettings()
    }

    fun deleteAllUserData() = viewModelScope.launch {
        c.voiceEngine.stopListening()
        com.example.sol.voice.MicForegroundService.stop(getApplication())
        c.ttsManager.stop()
        c.localSlmEngine.stopGeneration()
        withContext(Dispatchers.IO) {
            c.localSlmEngine.unloadFromRam("پاک‌سازی کامل داده‌های کاربر")
            c.confirmationManager.dismiss()
            c.taskManager.dismissTaskCard()
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
            c.speakerIdManager.deleteAllSpeakerData()
        }
        c.uiBridge.triggerResetChatAfterWipe()
        refreshPermissions()
        c.uiBridge.postBanner("همهٔ داده‌ها، حافظه‌ها، کتاب‌ها، نقش‌ها و تنظیمات Sol پاک شدند.")
    }
}
