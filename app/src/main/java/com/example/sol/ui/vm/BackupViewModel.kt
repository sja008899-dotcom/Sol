package com.example.sol.ui.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SolApplication
import com.example.sol.ui.BackupDialogMode
import com.example.sol.ui.PendingBackupPasswordRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val c = (application as SolApplication).container

    private val _pendingBackupRequest = MutableStateFlow<PendingBackupPasswordRequest?>(null)
    val pendingBackupRequest: StateFlow<PendingBackupPasswordRequest?> = _pendingBackupRequest.asStateFlow()

    init {
        viewModelScope.launch {
            c.uiBridge.events.collect { ev ->
                if (ev is SolUiEvent.ResetChatAfterWipe) {
                    _pendingBackupRequest.value = null
                }
            }
        }
    }

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
                            c.uiBridge.postBanner(msg)
                        },
                        onFailure = { err ->
                            c.uiBridge.postBanner(err.message ?: "خطا در صدور پشتیبان")
                        }
                    )
                }
                BackupDialogMode.IMPORT -> {
                    c.encryptedBackupManager.importEncryptedBackup(req.uri, password, replaceOnImport).fold(
                        onSuccess = { stats ->
                            val sizeLabel = formatByteSizeFa(stats.fileSizeBytes)
                            val msg = "بازیابی رمزنگاری‌شده ($sizeLabel) موفق بود: ${stats.memoriesCount} حافظه، ${stats.booksCount} کتاب و ${stats.rolesCount} نقش وارد شدند."
                            c.uiBridge.postBanner(msg)
                            c.uiBridge.triggerRefreshSnapshots()
                            c.uiBridge.triggerRefreshContacts()
                        },
                        onFailure = { err ->
                            c.uiBridge.postBanner(err.message ?: "خطا در بازیابی پشتیبان")
                        }
                    )
                }
            }
        }
    }

    fun trimRamManually() {
        viewModelScope.launch(Dispatchers.IO) {
            c.memoryOptimizer.manualTrimNow(
                evictRagCache = { c.bookRagEngine.evictCache() },
                unloadVoskIfIdle = { c.voiceEngine.voskEngine.unloadModelFromRam("آزادسازی دستی RAM") },
                releaseSlmCache = { c.localSlmEngine.releaseSessionMemory() },
            )
            c.uiBridge.postBanner("کش کتاب‌ها، حافظهٔ صوتی و SLM برای گوشی ۴ گیگابایتی آزاد شد.")
        }
    }

    private fun formatByteSizeFa(bytes: Long): String {
        if (bytes <= 0L) return "۰ بایت"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }
}
