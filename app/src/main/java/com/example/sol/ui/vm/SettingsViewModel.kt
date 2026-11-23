package com.example.sol.ui.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SolApplication
import com.example.sol.device.BatteryStatus
import com.example.sol.device.InstalledAppShortcut
import com.example.sol.device.StorageStatus
import com.example.sol.knowledge.SlmModelCatalog
import com.example.sol.knowledge.SlmModelSpec
import com.example.sol.memory.BackgroundJobLogEntity
import com.example.sol.memory.ConfirmationPolicy
import com.example.sol.memory.DeliveryPreference
import com.example.sol.memory.InvocationMode
import com.example.sol.memory.ScheduledAlarmEntity
import com.example.sol.memory.VoicePersona
import com.example.sol.security.RamUsageSnapshot
import com.example.sol.security.SecurityAuditItem
import com.example.sol.voice.AudioVolumeStatus
import com.example.sol.voice.TtsEngineInfo
import com.example.sol.voice.TtsVoiceInfo
import com.example.sol.widget.SolQuickSettingsTileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val c = (application as SolApplication).container

    // Lazily collected only by SettingsScreen / CapabilitiesScreen when visible
    val bookChunkCount: StateFlow<Int> = c.knowledgeRepository.bookChunkCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val scheduledAlarms: StateFlow<List<ScheduledAlarmEntity>> = c.knowledgeRepository.scheduledAlarms
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val backgroundLogs: StateFlow<List<BackgroundJobLogEntity>> = c.knowledgeRepository.backgroundLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val enabledPeriodicJobs: StateFlow<Set<String>> = c.backgroundTaskCoordinator.enabledPeriodicJobs
    val canScheduleExactAlarms: StateFlow<Boolean> = c.exactAlarmScheduler.isExactModeActive
    val ramSnapshot: StateFlow<RamUsageSnapshot> = c.memoryOptimizer.ramSnapshot
    val piperStatus: StateFlow<com.example.sol.tts.PiperTtsStatus> = c.ttsManager.piperStatus
    val speakerIdState: StateFlow<com.example.sol.speaker.SpeakerIdUiState> = c.speakerIdManager.uiState
    val isMicServiceRunning: StateFlow<Boolean> = com.example.sol.voice.MicForegroundService.isRunning
    val persianVoices: StateFlow<List<TtsVoiceInfo>> = c.persianTts.persianVoices
    val availableTtsEngines: StateFlow<List<TtsEngineInfo>> = c.persianTts.availableEngines
    val activeTtsEngineLabel: StateFlow<String> = c.persianTts.activeEngineLabel

    private val _batteryStatus = MutableStateFlow(c.deviceController.getBatteryStatus())
    val batteryStatus: StateFlow<BatteryStatus> = _batteryStatus.asStateFlow()

    private val _volumeStatus = MutableStateFlow(c.deviceController.getVolumeStatus())
    val volumeStatus: StateFlow<AudioVolumeStatus> = _volumeStatus.asStateFlow()

    private val _launchableApps = MutableStateFlow<List<InstalledAppShortcut>>(emptyList())
    val launchableApps: StateFlow<List<InstalledAppShortcut>> = _launchableApps.asStateFlow()

    private val _securityAudit = MutableStateFlow<List<SecurityAuditItem>>(emptyList())
    val securityAudit: StateFlow<List<SecurityAuditItem>> = _securityAudit.asStateFlow()

    private val _vocabularyTermsCount = MutableStateFlow(0)
    val vocabularyTermsCount: StateFlow<Int> = _vocabularyTermsCount.asStateFlow()

    private val _isIgnoringBatteryOptimizations = MutableStateFlow(
        c.deviceController.settingsController.isIgnoringBatteryOptimizations()
    )
    val isIgnoringBatteryOptimizations: StateFlow<Boolean> = _isIgnoringBatteryOptimizations.asStateFlow()

    private val _areNotificationsEnabled = MutableStateFlow(
        c.deviceController.settingsController.areNotificationsEnabled()
    )
    val areNotificationsEnabled: StateFlow<Boolean> = _areNotificationsEnabled.asStateFlow()

    private val _storageStatus = MutableStateFlow(c.deviceController.getStorageStatus())
    val storageStatus: StateFlow<StorageStatus> = _storageStatus.asStateFlow()

    private val _recommendedSlmModel = MutableStateFlow(
        SlmModelCatalog.recommendedModelForDevice(ramSnapshot.value.totalDeviceRamMb)
    )
    val recommendedSlmModel: StateFlow<SlmModelSpec> = _recommendedSlmModel.asStateFlow()

    init {
        refreshSettingsSnapshots()
        viewModelScope.launch {
            c.uiBridge.events.collect { ev ->
                if (ev is SolUiEvent.RefreshSnapshots || ev is SolUiEvent.ResetChatAfterWipe) {
                    refreshSettingsSnapshots()
                }
            }
        }
    }

    fun refreshSettingsSnapshots() {
        viewModelScope.launch(Dispatchers.IO) {
            c.exactAlarmScheduler.refreshExactAlarmState()
            _batteryStatus.value = c.deviceController.getBatteryStatus()
            _volumeStatus.value = c.deviceController.getVolumeStatus()
            c.memoryOptimizer.refresh()
            c.voiceEngine.voskEngine.refreshInstalledStatus()
            _vocabularyTermsCount.value = c.voiceEngine.voskEngine.vocabularyBias.currentTermsCount()
            val prefs = c.preferencesRepository.getSnapshot()
            val ttsNeedsNet = c.persianTts.persianVoices.value
                .firstOrNull { it.name == prefs.preferredTtsVoiceName }
                ?.isOffline == false
            _securityAudit.value = c.privacyManager.getSecurityAudit(
                alwaysConfirmCommunication = prefs.confirmationPolicy == ConfirmationPolicy.ALWAYS_COMMUNICATION,
                activeAsrBackend = c.voiceEngine.activeBackend.value,
                voskInstalled = c.voiceEngine.voskEngine.isModelInstalled(),
                ttsRequiresNetwork = ttsNeedsNet,
                historyRetentionMode = prefs.historyRetentionMode,
                privateModeEnabled = prefs.privateModeEnabled,
            )
            _launchableApps.value = c.deviceController.appLauncher.getLaunchableApps().take(12)
            refreshReliabilityStatus()
        }
    }

    fun refreshReliabilityStatus() {
        c.exactAlarmScheduler.refreshExactAlarmState()
        val ram = c.memoryOptimizer.refresh()
        _isIgnoringBatteryOptimizations.value =
            c.deviceController.settingsController.isIgnoringBatteryOptimizations()
        _areNotificationsEnabled.value =
            c.deviceController.settingsController.areNotificationsEnabled()
        _storageStatus.value = c.deviceController.getStorageStatus()
        _recommendedSlmModel.value = SlmModelCatalog.recommendedModelForDevice(ram.totalDeviceRamMb)
    }

    fun openBatteryOptimizationSettings(): Boolean =
        c.deviceController.settingsController.openBatteryOptimizationSettings()

    fun openNotificationSettings(): Boolean =
        c.deviceController.settingsController.openNotificationSettings()

    fun scheduleOneMinuteSelfTestReminder() {
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = c.exactAlarmScheduler.scheduleJalaliAlarm(
                triggerAtMillis = System.currentTimeMillis() + 60_000L,
                titleFa = "تست خودکار یادآور Sol",
                messageBody = "این اعلان تأیید می‌کند که یادآور ۱ دقیقه‌ای با موفقیت تحویل داده شد.",
                minLeadMillis = 60_000L,
            )
            refreshReliabilityStatus()
            val modeText = if (outcome.isExactGranted) "دقیق (Exact Alarm)" else "تقریبی (Doze)"
            c.uiBridge.postBanner("یادآور آزمایشی برای ${outcome.jalaliDateTimeFa} ($modeText) ثبت شد.")
        }
    }

    // --- Vosk Offline Voice ---
    fun downloadVoskPersianModel() = c.voiceEngine.voskEngine.downloadPersianSmallModel()
    fun importVoskModelZip(uri: Uri) = c.voiceEngine.voskEngine.importLocalModelZip(uri)
    fun unloadVoskModelFromRam() {
        viewModelScope.launch(Dispatchers.IO) {
            c.voiceEngine.voskEngine.unloadModelFromRam("آزادسازی دستی مدل صوتی از RAM")
            c.memoryOptimizer.refresh("مدل Vosk از حافظه موقت آزاد شد")
        }
    }
    fun deleteVoskModel() {
        viewModelScope.launch(Dispatchers.IO) {
            c.voiceEngine.voskEngine.deleteInstalledModel()
            c.memoryOptimizer.refresh()
        }
    }

    // --- Piper TTS, System TTS, Speaker ID ---
    fun importPiperFiles(uris: List<Uri>) {
        viewModelScope.launch {
            c.ttsManager.importPiperUris(uris).fold(
                onSuccess = { msg -> c.uiBridge.postBanner(msg) },
                onFailure = { err -> c.uiBridge.postBanner(err.message ?: "انتخاب فایل‌های Piper ناموفق بود.") },
            )
        }
    }

    fun deletePiperModel() {
        viewModelScope.launch(Dispatchers.IO) {
            c.ttsManager.disableAndDeletePiper()
            c.uiBridge.postBanner("فایل‌های موتور خودکفای Piper حذف شدند.")
        }
    }

    fun recordOwnerVoice() {
        viewModelScope.launch {
            c.voiceEngine.stopListening()
            c.ttsManager.stop()
            c.speakerIdManager.recordAndEnrollOwner().fold(
                onSuccess = { msg -> c.uiBridge.postBanner(msg) },
                onFailure = { err -> c.uiBridge.postBanner(err.message ?: "ضبط صدای مالک ناموفق بود.") },
            )
        }
    }

    fun testSpeakerMicrophone() {
        viewModelScope.launch {
            c.voiceEngine.stopListening()
            c.ttsManager.stop()
            c.speakerIdManager.testMicrophoneHealth().fold(
                onSuccess = { msg -> c.uiBridge.postBanner(msg) },
                onFailure = { err -> c.uiBridge.postBanner(err.message ?: "تست میکروفون ناموفق بود.") },
            )
        }
    }

    fun clearOwnerVoice() {
        viewModelScope.launch(Dispatchers.IO) {
            c.speakerIdManager.clearOwnerProfile()
            c.uiBridge.postBanner("نمونهٔ صدای مالک پاک شد (N5 غیرفعال).")
        }
    }

    fun importSpeakerModel(uri: Uri) {
        viewModelScope.launch {
            c.speakerIdManager.importSpeakerModel(uri).fold(
                onSuccess = { msg -> c.uiBridge.postBanner(msg) },
                onFailure = { err -> c.uiBridge.postBanner(err.message ?: "وارد کردن مدل گوینده ناموفق بود.") },
            )
        }
    }

    fun testVoiceSample() {
        viewModelScope.launch {
            val prefs = c.preferencesRepository.getSnapshot()
            c.ttsManager.speak(
                text = "سلام ${prefs.ownerName}. من Sol دستیار شخصی شما هستم.",
                speechRate = prefs.speechRate,
                persona = prefs.voicePersona,
                preferredVoiceName = prefs.preferredTtsVoiceName,
            )
        }
    }

    fun openSystemTtsSettings() = c.persianTts.openSystemTtsSettings()

    fun recheckSystemTts() {
        viewModelScope.launch(Dispatchers.IO) {
            c.ttsManager.refreshInstalledPiper()
            val prefs = c.preferencesRepository.getSnapshot()
            c.persianTts.recheckSystemVoices(prefs.preferredTtsEnginePackage)
            c.uiBridge.postBanner("در حال بررسی موتورهای صدای گوشی و Piper…")
        }
    }

    fun setPreferredTtsEngine(packageName: String) {
        viewModelScope.launch {
            c.preferencesRepository.setPreferredTtsEnginePackage(packageName)
            c.preferencesRepository.setPreferredTtsVoiceName("")
            c.persianTts.connectToPhoneTts(packageName)
            val label = availableTtsEngines.value.firstOrNull { it.packageName == packageName }?.label
                ?: if (packageName.isBlank()) "تشخیص خودکار (SherpaTTS / پیش‌فرض گوشی)" else packageName
            c.uiBridge.postBanner("موتور صدای Sol به «$label» متصل شد.")
        }
    }

    // --- Alarms, Tile, Background Jobs ---
    fun requestAddQuickSettingsTile() {
        SolQuickSettingsTileService.promptPinTile(getApplication()) { msg ->
            c.uiBridge.postBanner(msg)
        }
    }

    fun requestExactAlarmPermission() = c.exactAlarmScheduler.openExactAlarmSettings()

    fun cancelScheduledAlarm(alarm: ScheduledAlarmEntity) {
        viewModelScope.launch {
            c.exactAlarmScheduler.cancelAlarm(alarm)
            c.uiBridge.postBanner("یادآور «${alarm.titleFa}» لغو شد.")
        }
    }

    fun togglePeriodicBackgroundJob(jobId: String, enable: Boolean) {
        c.backgroundTaskCoordinator.togglePeriodicJob(jobId, enable)
    }

    fun clearBackgroundLogs() {
        viewModelScope.launch(Dispatchers.IO) { c.knowledgeRepository.clearBackgroundLogs() }
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
    fun setOwnerName(name: String) = viewModelScope.launch(Dispatchers.IO) { c.preferencesRepository.setOwnerName(name) }
    fun setVoicePersona(p: VoicePersona) = viewModelScope.launch(Dispatchers.IO) { c.preferencesRepository.setVoicePersona(p) }
    fun setPreferredTtsVoice(name: String) = viewModelScope.launch(Dispatchers.IO) { c.preferencesRepository.setPreferredTtsVoiceName(name) }
    fun setSpeechRate(rate: Float) = viewModelScope.launch(Dispatchers.IO) { c.preferencesRepository.setSpeechRate(rate) }
    fun setAutoSpeak(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) { c.preferencesRepository.setAutoSpeak(enabled) }
    fun setInvocationMode(m: InvocationMode) = viewModelScope.launch(Dispatchers.IO) { c.preferencesRepository.setInvocationMode(m) }
    fun setDeliveryPreference(d: DeliveryPreference) = viewModelScope.launch(Dispatchers.IO) { c.preferencesRepository.setDeliveryPreference(d) }
    fun setConfirmationPolicy(p: ConfirmationPolicy) = viewModelScope.launch(Dispatchers.IO) {
        c.preferencesRepository.setConfirmationPolicy(p)
        refreshSettingsSnapshots()
    }
    fun setDarkTheme(dark: Boolean) = viewModelScope.launch(Dispatchers.IO) { c.preferencesRepository.setDarkTheme(dark) }
    fun setVoskOfflineEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) { c.preferencesRepository.setVoskOfflineEnabled(enabled) }
    fun setWakeWordEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) { c.preferencesRepository.setWakeWordEnabled(enabled) }
    fun setVadSilenceTimeoutMs(timeoutMs: Long) = viewModelScope.launch(Dispatchers.IO) { c.preferencesRepository.setVadSilenceTimeoutMs(timeoutMs) }
    fun setMaxVoiceListenSeconds(seconds: Int) = viewModelScope.launch(Dispatchers.IO) { c.preferencesRepository.setMaxVoiceListenSeconds(seconds) }
    fun setWakeWordTimeoutMinutes(minutes: Int) = viewModelScope.launch(Dispatchers.IO) { c.preferencesRepository.setWakeWordTimeoutMinutes(minutes) }
    fun setVoiceCueFeedbackEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) { c.preferencesRepository.setVoiceCueFeedbackEnabled(enabled) }
    fun setContinuousListeningAfterWake(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        c.preferencesRepository.setContinuousListeningAfterWake(enabled)
    }
    fun setScreenOffMicEnabled(enabled: Boolean) = viewModelScope.launch {
        if (enabled) {
            val started = com.example.sol.voice.MicForegroundService.start(getApplication())
            started.fold(
                onSuccess = {
                    c.preferencesRepository.setScreenOffMicEnabled(true)
                    c.uiBridge.postBanner("میکروفون همیشگی با صفحه خاموش (Foreground Service) فعال شد.")
                },
                onFailure = { err ->
                    c.preferencesRepository.setScreenOffMicEnabled(false)
                    c.uiBridge.postBanner(err.message ?: "شروع سرویس میکروفون همیشگی ممکن نشد.")
                },
            )
        } else {
            com.example.sol.voice.MicForegroundService.stop(getApplication())
            c.preferencesRepository.setScreenOffMicEnabled(false)
            c.uiBridge.postBanner("سرویس میکروفون همیشگی متوقف شد.")
        }
    }
}
