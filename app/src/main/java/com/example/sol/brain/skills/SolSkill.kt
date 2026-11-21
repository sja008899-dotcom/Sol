package com.example.sol.brain.skills

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.sol.brain.ParsedCommand
import com.example.sol.brain.PendingSolTask
import com.example.sol.brain.SolAction
import com.example.sol.memory.SolPreferences

enum class Sensitivity {
    NONE,
    CONFIRM,
}

enum class SkillCategory {
    BACKGROUND,
    ADVANCED_LEARNING,
    CORE_ASSISTANT,
}

data class SkillContext(
    val context: Context,
    val preferences: SolPreferences,
) {
    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

data class SkillResult(
    val replyText: String,
    val parsedCommand: ParsedCommand,
    val pendingTask: PendingSolTask? = null,
    val speakReply: Boolean = true,
)

interface SolSkill {
    val id: String
    val titleFa: String
    val descriptionFa: String
    val intents: Set<SolAction>
    val sampleCommandsFa: List<String>
    val sensitivity: Sensitivity
    val requiredPermissions: List<String>
    val category: SkillCategory get() = SkillCategory.CORE_ASSISTANT

    suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult
}
