package com.example.sol.brain

import com.example.sol.dialogue.DialogueManager
import com.example.sol.dialogue.DialogueResult

/**
 * Thin NLU -> Dialogue -> Skill orchestrator.
 * All per-intent execution lives in [com.example.sol.brain.skills.SkillRegistry],
 * and multi-turn state, confirmations, disambiguation, and teaching live in [DialogueManager].
 */
class CommandBrain(
    private val dialogueManager: DialogueManager,
) {
    suspend fun process(rawInput: String): BrainExecutionResult {
        val result = dialogueManager.handleTurn(rawInput)
        return BrainExecutionResult(
            replyText = result.replyText,
            parsedCommand = result.parsedCommand,
            pendingTask = result.pendingTask,
            speakReply = result.speakReply,
        )
    }

    suspend fun processDialogue(rawInput: String): DialogueResult {
        return dialogueManager.handleTurn(rawInput)
    }
}
