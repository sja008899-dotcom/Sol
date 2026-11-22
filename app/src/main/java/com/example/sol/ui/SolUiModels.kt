package com.example.sol.ui

import android.net.Uri
import com.example.sol.dialogue.DialogueUiChoice
import com.example.sol.memory.BookKnowledgeEntity

data class ChatMessageItem(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    val actionBadge: String = "",
    val choices: List<DialogueUiChoice> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis(),
)

data class PendingBookFetchConfirmation(
    val query: String,
    val roleHint: String,
    val runInBackground: Boolean,
)

data class PendingWebSearchConfirmation(
    val query: String,
)

data class VoiceRoleTeachingSession(
    val book: BookKnowledgeEntity?,
    val roleNameDraft: String,
    val voiceExplanationDraft: String = "",
    val isListeningForExplanation: Boolean = false,
)

enum class BackupDialogMode { EXPORT, IMPORT }

data class PendingBackupPasswordRequest(
    val mode: BackupDialogMode,
    val uri: Uri,
)
