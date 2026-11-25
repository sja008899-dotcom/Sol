package com.example.sol.dialogue

import com.example.sol.brain.ClarifyIntentOption
import com.example.sol.brain.ParsedCommand
import com.example.sol.brain.PendingSolTask
import com.example.sol.brain.SolAction
import com.example.sol.contacts.ResolvedContact

enum class ConfirmationKind(val labelFa: String) {
    CALL("تماس تلفنی"),
    SEND_SMS("ارسال پیامک"),
    MULTI_STEP_NOTIFY("اطلاع‌رسانی چندمرحله‌ای"),
    FORGET_MEMORY("حذف از حافظه"),
    RESTORE_BACKUP("بازیابی فایل پشتیبان"),
}

data class DialogueUiChoice(
    val id: String,
    val labelFa: String,
    val submitText: String,
    val isPrimary: Boolean = false,
    val isDestructive: Boolean = false,
)

data class DialogueTurn(
    val userText: String,
    val normalizedText: String,
    val action: SolAction,
    val resolvedContact: ResolvedContact? = null,
    val slotSummary: Map<String, String> = emptyMap(),
    val solReply: String = "",
    val timestampMs: Long = System.currentTimeMillis(),
)

sealed interface PendingDialogueState {
    data class MissingSlot(
        val action: SolAction,
        val missingSlotName: String,
        val partialCommand: ParsedCommand,
        val promptFa: String,
    ) : PendingDialogueState

    data class AwaitingContactChoice(
        val action: SolAction,
        val partialCommand: ParsedCommand,
        val candidates: List<ResolvedContact>,
        val promptFa: String,
    ) : PendingDialogueState

    data class AwaitingIntentClarification(
        val originalText: String,
        val options: List<ClarifyIntentOption>,
        val promptFa: String,
    ) : PendingDialogueState

    data class AwaitingConfirmation(
        val kind: ConfirmationKind,
        val command: ParsedCommand,
        val targetSummaryFa: String,
        val communicateTask: PendingSolTask.Communicate? = null,
        val onConfirmedBackupCallback: (suspend () -> String)? = null,
    ) : PendingDialogueState
}

data class DialogueResult(
    val replyText: String,
    val parsedCommand: ParsedCommand,
    val pendingTask: PendingSolTask? = null,
    val speakReply: Boolean = true,
    val actionChoices: List<DialogueUiChoice> = emptyList(),
    val nluConfidenceLabel: String = "",
    val taughtExampleSaved: Boolean = false,
    val speakerNoticeFa: String? = null,
)
