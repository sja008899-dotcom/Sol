package com.example.sol.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SolApplication
import com.example.sol.memory.HistoryEntity
import com.example.sol.memory.HistoryRetentionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val c = (application as SolApplication).container

    // Lazily collected only when HistoryScreen is active
    val history: StateFlow<List<HistoryEntity>> = c.historyRepository.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setHistoryRetentionMode(mode: HistoryRetentionMode) = viewModelScope.launch(Dispatchers.IO) {
        c.preferencesRepository.setHistoryRetentionMode(mode)
        c.historyRepository.enforceRetention(mode)
        c.uiBridge.postBanner("سیاست نگهداری سوابق روی «${mode.labelFa}» تنظیم شد.")
        c.uiBridge.triggerRefreshSnapshots()
    }

    fun setPrivateModeEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        c.preferencesRepository.setPrivateModeEnabled(enabled)
        c.uiBridge.postBanner(
            if (enabled) {
                "حالت خصوصی فعال شد؛ گفتگوها در سوابق ذخیره نمی‌شوند."
            } else {
                "حالت خصوصی غیرفعال شد."
            }
        )
        c.uiBridge.triggerRefreshSnapshots()
    }

    fun deleteHistoryEntry(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { c.historyRepository.deleteEntry(id) }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) { c.historyRepository.clearAll() }
    }
}
