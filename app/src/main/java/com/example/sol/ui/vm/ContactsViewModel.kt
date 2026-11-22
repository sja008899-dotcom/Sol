package com.example.sol.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SolApplication
import com.example.sol.contacts.ResolvedContact
import com.example.sol.tasks.CommunicationChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ContactsViewModel(application: Application) : AndroidViewModel(application) {
    private val c = (application as SolApplication).container

    private val _contacts = MutableStateFlow<List<ResolvedContact>>(emptyList())
    val contacts: StateFlow<List<ResolvedContact>> = _contacts.asStateFlow()

    init {
        refreshContacts()
        viewModelScope.launch {
            c.uiBridge.events.collect { ev ->
                if (ev is SolUiEvent.RefreshContacts || ev is SolUiEvent.RefreshSnapshots) {
                    refreshContacts()
                }
            }
        }
    }

    fun refreshContacts() {
        viewModelScope.launch(Dispatchers.IO) {
            _contacts.value = c.contactManager.loadMergedContacts()
        }
    }

    fun initiateContactAction(contact: ResolvedContact, channel: CommunicationChannel) {
        viewModelScope.launch {
            c.dialogueManager.clearPendingState()
            val prefs = c.preferencesRepository.getSnapshot()
            c.taskManager.requestDirectCommunication(
                contact = contact,
                channel = channel,
                ownerName = prefs.ownerName,
                confirmedLocation = prefs.confirmedCurrentLocation,
            )
        }
    }

    fun saveContactToMemory(contact: ResolvedContact) {
        viewModelScope.launch(Dispatchers.IO) {
            c.personRepository.savePerson(
                name = contact.name,
                phone = contact.phone,
                relation = contact.relation,
                callScriptNote = contact.callScriptNote,
                existingId = 0,
            )
            refreshContacts()
            c.uiBridge.postBanner("${contact.name} در حافظه Sol ذخیره شد")
        }
    }
}
