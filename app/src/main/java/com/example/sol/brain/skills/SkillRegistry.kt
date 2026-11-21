package com.example.sol.brain.skills

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.sol.brain.BrainExecutionResult
import com.example.sol.brain.ParsedCommand
import com.example.sol.brain.SolAction
import com.example.sol.device.SettingsController
import com.example.sol.memory.PreferencesRepository

class SkillRegistry(
    private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val skills: List<SolSkill>,
    private val openPermissionSettings: (() -> Boolean)? = null,
) {
    private val skillMap: Map<SolAction, SolSkill> = buildMap {
        for (skill in skills) {
            for (action in skill.intents) {
                put(action, skill)
            }
        }
    }

    private val byIdMap: Map<String, SolSkill> = skills.associateBy { it.id }

    fun allSkills(): List<SolSkill> = skills

    fun getSkillById(id: String): SolSkill? = byIdMap[id]

    fun skillForAction(action: SolAction): SolSkill? = skillMap[action]

    fun isRegistered(action: SolAction): Boolean = action in skillMap

    suspend fun execute(command: ParsedCommand): BrainExecutionResult {
        val handler = skillMap[command.action] ?: skillMap[SolAction.UNKNOWN]
            ?: return BrainExecutionResult(
                replyText = "مهارت مربوط به این فرمان ثبت نشده است.",
                parsedCommand = command,
            )

        val missingPermissions = handler.requiredPermissions.filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            val opened = openPermissionSettings?.invoke()
                ?: SettingsController(context).openAppDetailsSettings()
            val labels = missingPermissions.joinToString("، ", transform = ::permissionLabelFa)
            val settingsNote = if (opened) {
                "صفحهٔ تنظیمات مجوزهای برنامه برایتان باز شد تا آن را فعال کنید."
            } else {
                "لطفاً از بخش تنظیمات گوشی، مجوز مورد نیاز را به برنامه بدهید."
            }
            return BrainExecutionResult(
                replyText = "برای اجرای «${handler.titleFa}» مجوز «$labels» لازم است و هنوز داده نشده. $settingsNote",
                parsedCommand = command,
            )
        }

        val ctx = SkillContext(
            context = context,
            preferences = preferencesRepository.getSnapshot(),
        )
        val result = handler.execute(command, ctx)
        return BrainExecutionResult(
            replyText = result.replyText,
            parsedCommand = result.parsedCommand,
            pendingTask = result.pendingTask,
            speakReply = result.speakReply,
        )
    }

    companion object {
        fun permissionLabelFa(permission: String): String = when (permission) {
            Manifest.permission.RECORD_AUDIO -> "میکروفون (RECORD_AUDIO)"
            Manifest.permission.READ_CONTACTS -> "مخاطبین (READ_CONTACTS)"
            Manifest.permission.POST_NOTIFICATIONS -> "اعلان‌ها (POST_NOTIFICATIONS)"
            Manifest.permission.CAMERA -> "دوربین (CAMERA)"
            else -> permission.substringAfterLast('.')
        }
    }
}
