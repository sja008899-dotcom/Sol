package com.example.sol.brain.skills

import com.example.sol.brain.BrainExecutionResult
import com.example.sol.brain.ParsedCommand
import com.example.sol.brain.SolAction

class SkillRegistry(skills: List<SolSkill>) {
    private val skillMap: Map<SolAction, SolSkill> = buildMap {
        for (skill in skills) {
            for (action in skill.supportedActions) {
                put(action, skill)
            }
        }
    }

    suspend fun execute(command: ParsedCommand): BrainExecutionResult {
        val handler = skillMap[command.action] ?: skillMap[SolAction.UNKNOWN]
            ?: return BrainExecutionResult(
                replyText = "مهارت مربوط به این فرمان ثبت نشده است.",
                parsedCommand = command,
            )
        return handler.execute(command)
    }

    fun isRegistered(action: SolAction): Boolean = action in skillMap
}
