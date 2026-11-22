package com.example.sol.ui.vm

import com.example.sol.dialogue.DialogueUiChoice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface SolUiEvent {
    data class Banner(val message: String) : SolUiEvent
    data class AppendBotMessage(
        val text: String,
        val actionBadge: String = "",
        val choices: List<DialogueUiChoice> = emptyList(),
        val speakIfAutoSpeak: Boolean = false,
    ) : SolUiEvent
    data object ResetChatAfterWipe : SolUiEvent
    data object OpenCapabilities : SolUiEvent
    data object RefreshSnapshots : SolUiEvent
    data object RefreshContacts : SolUiEvent
    data class OpenBookFetchDialog(
        val query: String,
        val roleHint: String,
        val runInBackground: Boolean,
    ) : SolUiEvent
    data class OpenWebSearchDialog(val query: String) : SolUiEvent
}

class SolUiBridge {
    private val _events = MutableSharedFlow<SolUiEvent>(extraBufferCapacity = 32)
    val events = _events.asSharedFlow()

    fun postBanner(message: String) {
        _events.tryEmit(SolUiEvent.Banner(message))
    }

    fun postBotMessage(
        text: String,
        actionBadge: String = "",
        choices: List<DialogueUiChoice> = emptyList(),
        speakIfAutoSpeak: Boolean = false,
    ) {
        _events.tryEmit(SolUiEvent.AppendBotMessage(text, actionBadge, choices, speakIfAutoSpeak))
    }

    fun triggerResetChatAfterWipe() {
        _events.tryEmit(SolUiEvent.ResetChatAfterWipe)
    }

    fun triggerOpenCapabilities() {
        _events.tryEmit(SolUiEvent.OpenCapabilities)
    }

    fun triggerRefreshSnapshots() {
        _events.tryEmit(SolUiEvent.RefreshSnapshots)
    }

    fun triggerRefreshContacts() {
        _events.tryEmit(SolUiEvent.RefreshContacts)
    }

    fun triggerOpenBookFetch(query: String, roleHint: String, runInBackground: Boolean) {
        _events.tryEmit(SolUiEvent.OpenBookFetchDialog(query, roleHint, runInBackground))
    }

    fun triggerOpenWebSearch(query: String) {
        _events.tryEmit(SolUiEvent.OpenWebSearchDialog(query))
    }
}
