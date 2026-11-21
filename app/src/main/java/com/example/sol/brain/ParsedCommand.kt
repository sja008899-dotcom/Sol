package com.example.sol.brain

enum class SolAction {
    GREETING,
    IDENTITY,
    TIME_DATE,
    HELP,
    LEARN_ALIAS,
    LEARN_FACT,
    FORGET_MEMORY,
    LIST_MEMORY,
    SAVE_PERSON,
    SAVE_LOCATION,
    SET_CURRENT_LOCATION,
    GET_CURRENT_LOCATION,
    CALL,
    SEND_SMS,
    MULTI_STEP_NOTIFY,
    OPEN_APP,
    OPEN_SETTINGS,
    DEVICE_STATUS,
    ADJUST_VOLUME,
    RUN_ROUTINE,
    SCHEDULE_REMINDER,
    BACKGROUND_CONTROL,
    WEB_BOOK_LEARN_ROLE,
    TEACH_ROLE_VOICE,
    CONSULT_ROLE_BOOK,
    SHOW_CAPABILITIES,
    TIMER_STOPWATCH,
    FLASHLIGHT,
    CREATE_CALENDAR_EVENT,
    CALCULATOR,
    NOTES_TODO,
    MEDIA_CONTROL,
    SEARCH_WEB,
    UNKNOWN,
}

sealed interface SlotValue {
    data class Text(val value: String) : SlotValue
    data class Number(val value: Double) : SlotValue
    data class DurationMinutes(val minutes: Long) : SlotValue
    data class Contact(
        val name: String,
        val phone: String? = null,
        val relation: String? = null,
    ) : SlotValue
    data class Location(
        val reference: String,
        val resolvedName: String? = null,
    ) : SlotValue
    data class Schedule(
        val value: com.example.sol.calendar.ParsedJalaliSchedule,
    ) : SlotValue
    data class Steps(val items: List<String>) : SlotValue
    data class Flag(val enabled: Boolean) : SlotValue
}

data class ParsedCommand(
    val action: SolAction,
    val rawText: String,
    val normalizedText: String,
    val contact: String? = null,
    val message: String? = null,
    val speaker: String = "SOL",
    val locationReference: String? = null,
    val targetApp: String? = null,
    val settingTarget: String? = null,
    val aliasKey: String? = null,
    val aliasValue: String? = null,
    val relation: String? = null,
    val phone: String? = null,
    val volumeDelta: Int = 0,
    val routineName: String? = null,
    val delayMinutes: Long = 1L,
    val backgroundJobId: String? = null,
    val bookQuery: String? = null,
    val searchQuery: String? = null,
    val roleName: String? = null,
    val jalaliSchedule: com.example.sol.calendar.ParsedJalaliSchedule? = null,
    val requireConfirmation: Boolean = true,
    val steps: List<String> = emptyList(),
    val slots: Map<String, SlotValue> = emptyMap(),
    val confidence: Float = 1.0f,
    val matchedRuleId: String? = null,
    val hasExplicitTimeSlot: Boolean = true,
)

data class IntentCandidate(
    val matcherId: String,
    val action: SolAction,
    val score: Float,
    val command: ParsedCommand,
    val evidence: List<String> = emptyList(),
)

data class BrainExecutionResult(
    val replyText: String,
    val parsedCommand: ParsedCommand,
    val pendingTask: PendingSolTask? = null,
    val speakReply: Boolean = true,
)

sealed interface PendingSolTask {
    data class Communicate(
        val preferSms: Boolean,
        val contactQuery: String,
        val messageBody: String,
        val locationReference: String?,
        val isMultiStepReport: Boolean,
    ) : PendingSolTask

    data class InternetBookRoleFetch(
        val bookQuery: String,
        val requestedRoleHint: String,
        val runInBackground: Boolean,
    ) : PendingSolTask

    data class ConfirmWebSearch(
        val query: String,
    ) : PendingSolTask

    data class OpenCapabilitiesCatalog(
        val focusBackgroundOnly: Boolean = false,
    ) : PendingSolTask

    data class RunLocalSlmChat(
        val userPrompt: String,
        val systemInstruction: String,
        val fallbackReplyText: String,
        val hasBookGrounding: Boolean,
        val allowIntentProposal: Boolean = false,
    ) : PendingSolTask
}
