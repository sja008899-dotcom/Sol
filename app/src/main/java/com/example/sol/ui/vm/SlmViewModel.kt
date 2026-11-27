package com.example.sol.ui.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SolApplication
import com.example.sol.knowledge.LocalSlmEngine
import com.example.sol.knowledge.SlmActiveBackend
import com.example.sol.knowledge.SlmModelSpec
import com.example.sol.knowledge.SlmRuntimeState
import com.example.sol.knowledge.SlmStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SlmViewModel(application: Application) : AndroidViewModel(application) {
    private val c = (application as SolApplication).container

    private val _slmStatus = MutableStateFlow(
        SlmStatus(
            isEnabled = false,
            modelPath = "",
            hasModelFile = false,
            isLoadedInRam = false,
            isGenerating = false,
            activeBackend = SlmActiveBackend.NONE,
            availableRamMb = 0L,
            statusLabelFa = "در حال بررسی…",
        )
    )
    val slmStatus: StateFlow<SlmStatus> = _slmStatus.asStateFlow()

    val slmRuntimeState: StateFlow<SlmRuntimeState> = c.localSlmEngine.runtimeState

    private val _pendingSlmTierBConfirm = MutableStateFlow<SlmModelSpec?>(null)
    val pendingSlmTierBConfirm: StateFlow<SlmModelSpec?> = _pendingSlmTierBConfirm.asStateFlow()

    init {
        refreshSlmState()
        viewModelScope.launch {
            c.uiBridge.events.collect { ev ->
                if (ev is SolUiEvent.RefreshSnapshots || ev is SolUiEvent.ResetChatAfterWipe) {
                    refreshSlmState()
                }
            }
        }
    }

    fun refreshSlmState() {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = c.preferencesRepository.getSnapshot()
            c.localSlmEngine.refreshRuntimeStatus(prefs.slmModelPath)
            _slmStatus.value = c.localSlmEngine.getStatus(prefs.slmSynthesisEnabled, prefs.slmModelPath)
        }
    }

    fun setSlmSynthesisEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        c.preferencesRepository.setSlmSynthesisEnabled(enabled)
        if (!enabled) {
            c.localSlmEngine.unloadFromRam("غیرفعال‌سازی ماژول هوش زبانی")
        }
        refreshSlmState()
    }

    fun setSlmPreferGpu(preferGpu: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        c.preferencesRepository.setSlmPreferGpu(preferGpu)
        if (c.localSlmEngine.isLoadedInRam()) {
            c.localSlmEngine.unloadFromRam("تغییر شتاب‌دهنده سخت‌افزاری؛ در نوبت بعد اعمال می‌شود")
        }
        refreshSlmState()
    }

    fun requestDownloadSlmModel(spec: SlmModelSpec) {
        if (c.networkPolicyCoordinator.isOfflineStrict()) {
            c.uiBridge.postBanner("حالت آفلاین سخت‌گیرانه فعال است؛ دانلود مدل زبانی مسدود شد.")
            return
        }
        if (spec.hasVerifiedIntegrityMetadata()) {
            downloadSlmModel(spec, allowUnpinnedTierB = false)
        } else {
            _pendingSlmTierBConfirm.value = spec
        }
    }

    fun confirmSlmTierBDownload() {
        val spec = _pendingSlmTierBConfirm.value ?: return
        _pendingSlmTierBConfirm.value = null
        downloadSlmModel(spec, allowUnpinnedTierB = true)
    }

    fun dismissSlmTierBDownload() {
        _pendingSlmTierBConfirm.value = null
    }

    fun downloadSlmModel(
        spec: SlmModelSpec,
        allowUnpinnedTierB: Boolean = false,
    ) {
        if (c.networkPolicyCoordinator.isOfflineStrict()) {
            c.uiBridge.postBanner("حالت آفلاین سخت‌گیرانه فعال است؛ دانلود مدل مسدود شد.")
            return
        }
        if (!spec.hasVerifiedIntegrityMetadata() && !allowUnpinnedTierB) {
            _pendingSlmTierBConfirm.value = spec
            return
        }
        c.localSlmEngine.downloadModel(
            spec = spec,
            allowUnpinnedTierB = allowUnpinnedTierB,
        ) { savedPath ->
            viewModelScope.launch(Dispatchers.IO) {
                c.preferencesRepository.setSlmModelPath(savedPath)
                c.preferencesRepository.setSlmSynthesisEnabled(true)
                c.uiBridge.postBanner("مدل «${spec.titleFa}» دانلود و متصل شد.")
                refreshSlmState()
            }
        }
    }

    fun cancelSlmDownload() {
        c.localSlmEngine.cancelDownload()
        c.uiBridge.postBanner("دانلود مدل متوقف شد.")
    }

    fun importLocalSlmModel(uri: Uri) {
        c.localSlmEngine.importLocalModel(uri) { result ->
            result.fold(
                onSuccess = { savedFile ->
                    viewModelScope.launch(Dispatchers.IO) {
                        c.preferencesRepository.setSlmModelPath(savedFile.absolutePath)
                        c.preferencesRepository.setSlmSynthesisEnabled(true)
                        c.uiBridge.postBanner("فایل «${savedFile.name}» با موفقیت وارد و فعال شد.")
                        refreshSlmState()
                    }
                },
                onFailure = { err ->
                    c.uiBridge.postBanner(err.localizedMessage ?: "وارد کردن فایل مدل ناموفق بود.")
                }
            )
        }
    }

    fun testLoadSlmInRam() {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = c.preferencesRepository.getSnapshot()
            val activeRole = c.knowledgeRepository.getAllRolesOnce().firstOrNull { it.isActive }
            val sysPrompt = LocalSlmEngine.buildSystemInstruction(
                ownerName = prefs.ownerName,
                confirmedLocation = prefs.confirmedCurrentLocation,
                activeRoleName = activeRole?.roleName,
                activeRoleInstructions = activeRole?.instructions,
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
                    c.uiBridge.postBanner("مدل با موفقیت در RAM بارگذاری شد (${backend.labelFa}).")
                },
                onFailure = { err ->
                    c.uiBridge.postBanner(err.localizedMessage ?: "بارگذاری مدل در RAM ناموفق بود.")
                }
            )
            c.uiBridge.triggerRefreshSnapshots()
        }
    }

    fun unloadSlmFromRam() {
        viewModelScope.launch(Dispatchers.IO) {
            c.localSlmEngine.unloadFromRam("آزادسازی دستی مدل زبانی از RAM")
            c.memoryOptimizer.refresh("مدل زبانی از RAM آزاد شد")
            c.uiBridge.postBanner("مدل زبانی از RAM آزاد شد.")
            refreshSlmState()
        }
    }

    fun deleteSlmModel() {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = c.preferencesRepository.getSnapshot()
            c.localSlmEngine.deleteModel(prefs.slmModelPath)
            c.preferencesRepository.setSlmModelPath("")
            c.preferencesRepository.setSlmSynthesisEnabled(false)
            c.uiBridge.postBanner("فایل مدل زبانی از حافظه گوشی پاک شد.")
            c.uiBridge.triggerRefreshSnapshots()
        }
    }
}
