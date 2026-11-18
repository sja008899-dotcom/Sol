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
    UNKNOWN,
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
    val requireConfirmation: Boolean = true,
    val steps: List<String> = emptyList(),
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
}
