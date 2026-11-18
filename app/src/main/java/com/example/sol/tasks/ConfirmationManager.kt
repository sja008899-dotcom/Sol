package com.example.sol.tasks

import com.example.sol.contacts.ResolvedContact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CommunicationChannel(val labelFa: String) {
    CALL("تماس تلفنی"),
    SMS("پیامک متنی"),
}

data class PendingCommunicationConfirmation(
    val id: Long = System.currentTimeMillis(),
    val channel: CommunicationChannel,
    val candidateContacts: List<ResolvedContact>,
    val selectedContact: ResolvedContact,
    val messageBody: String,
    val callScriptPreview: String,
    val confirmedCurrentLocation: String,
    val isMultiStepReport: Boolean,
)

class ConfirmationManager {
    private val _pending = MutableStateFlow<PendingCommunicationConfirmation?>(null)
    val pending: StateFlow<PendingCommunicationConfirmation?> = _pending.asStateFlow()

    fun requestConfirmation(confirmation: PendingCommunicationConfirmation) {
        _pending.value = confirmation
    }

    fun selectCandidate(contact: ResolvedContact) {
        _pending.value = _pending.value?.copy(selectedContact = contact)
    }

    fun updateChannel(channel: CommunicationChannel) {
        _pending.value = _pending.value?.copy(channel = channel)
    }

    fun updateMessageBody(text: String) {
        _pending.value = _pending.value?.copy(messageBody = text)
    }

    fun dismiss() {
        _pending.value = null
    }
}
