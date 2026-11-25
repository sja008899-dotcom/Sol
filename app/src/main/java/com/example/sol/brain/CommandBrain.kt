package com.example.sol.brain

import com.example.sol.dialogue.DialogueManager
import com.example.sol.dialogue.DialogueResult
import com.example.sol.speaker.SpeakerIdManager
import com.example.sol.speaker.SpeakerVerificationOutcome
import com.example.sol.text.PersianTextNormalizer

/**
 * Thin NLU -> Dialogue -> Skill orchestrator with optional N5 owner speaker gate.
 * When N5 Speaker ID is inactive (missing model or owner enrollment), Fail-Safe mode allows normal command execution.
 */
class CommandBrain(
    private val dialogueManager: DialogueManager,
    private val speakerIdManager: SpeakerIdManager? = null,
) {
    suspend fun process(
        rawInput: String,
        audioPcm16k: FloatArray? = null,
        isVoiceCommand: Boolean = audioPcm16k != null,
    ): BrainExecutionResult {
        val result = processDialogue(
            rawInput = rawInput,
            audioPcm16k = audioPcm16k,
            isVoiceCommand = isVoiceCommand,
        )
        return BrainExecutionResult(
            replyText = result.replyText,
            parsedCommand = result.parsedCommand,
            pendingTask = result.pendingTask,
            speakReply = result.speakReply,
            speakerNoticeFa = result.speakerNoticeFa,
        )
    }

    suspend fun processDialogue(
        rawInput: String,
        audioPcm16k: FloatArray? = null,
        isVoiceCommand: Boolean = audioPcm16k != null,
    ): DialogueResult {
        var speakerNotice: String? = null
        if (isVoiceCommand && speakerIdManager != null && speakerIdManager.isActive) {
            when (val outcome = speakerIdManager.verifyVoicePcm(audioPcm16k)) {
                is SpeakerVerificationOutcome.RejectedNonOwner -> {
                    val blockedCmd = ParsedCommand(
                        action = SolAction.UNKNOWN,
                        rawText = rawInput,
                        normalizedText = PersianTextNormalizer.normalizeForMatching(rawInput),
                        requireConfirmation = true,
                    )
                    return DialogueResult(
                        replyText = outcome.politeMessageFa,
                        parsedCommand = blockedCmd,
                        pendingTask = null,
                        speakReply = true,
                    )
                }
                is SpeakerVerificationOutcome.AudioUnavailable -> {
                    speakerNotice = outcome.reasonFa
                }
                is SpeakerVerificationOutcome.VerifiedOwner,
                SpeakerVerificationOutcome.InactiveFailSafe -> Unit
            }
        }
        val result = dialogueManager.handleTurn(rawInput)
        return if (speakerNotice != null) {
            result.copy(speakerNoticeFa = speakerNotice)
        } else {
            result
        }
    }
}
