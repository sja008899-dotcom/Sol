package com.example.sol.brain.skills

import com.example.sol.brain.BrainExecutionResult
import com.example.sol.brain.ParsedCommand
import com.example.sol.brain.SolAction

interface SolSkill {
    val supportedActions: Set<SolAction>
    suspend fun execute(command: ParsedCommand): BrainExecutionResult
}
