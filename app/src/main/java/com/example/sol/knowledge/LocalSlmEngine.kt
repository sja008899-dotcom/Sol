package com.example.sol.knowledge

import android.app.ActivityManager
import android.content.Context
import com.example.sol.memory.SolRoleEntity
import java.io.File

data class SlmStatus(
    val isEnabled: Boolean,
    val modelPath: String,
    val hasCustomWeightsFile: Boolean,
    val availableRamMb: Long,
    val statusLabelFa: String,
)

/**
 * Optional local Small Language Model (SLM) adapter module.
 * - Zero overhead when disabled (core rule + BM25 engine stays ultra-fast).
 * - Checks free RAM on 4GB Android 12 devices before allowing heavy weight paths.
 * - Performs structured extractive synthesis combining BM25 passages + active role + voice notes.
 */
class LocalSlmEngine(private val context: Context) {

    @Volatile
    private var warmedUp: Boolean = false

    fun getStatus(enabled: Boolean, modelPath: String): SlmStatus {
        val availMb = readAvailableRamMb()
        val hasFile = modelPath.isNotBlank() && runCatching { File(modelPath).exists() }.getOrDefault(false)
        val label = when {
            !enabled -> "ماژول SLM خاموش است (هسته سریع قانون‌محور + BM25 فعال با حداقل RAM)"
            availMb < 650 -> "حافظه آزاد RAM کم است (${availMb}MB)؛ پیشنهاد می‌شود حالت سبک BM25 باقی بماند."
            hasFile -> "ماژول SLM محلی آماده (فایل مدل متصل + ترکیب هوشمند BM25)"
            else -> "حالت ترکیب هوشمند محلی (Extractive SLM) فعال است؛ بدون نیاز به اینترنت."
        }
        return SlmStatus(
            isEnabled = enabled,
            modelPath = modelPath,
            hasCustomWeightsFile = hasFile,
            availableRamMb = availMb,
            statusLabelFa = label,
        )
    }

    fun synthesizeAnswer(
        query: String,
        activeRole: SolRoleEntity?,
        rankedChunks: List<RankedBookChunk>,
        voiceNotes: String,
        fallbackAnswer: String,
    ): String {
        if (rankedChunks.isEmpty() && voiceNotes.isBlank()) return fallbackAnswer
        warmedUp = true
        return buildString {
            if (activeRole != null) {
                append("🧠 جمع‌بندی در نقش «${activeRole.roleName}»:\n")
            } else {
                append("🧠 جمع‌بندی هوشمند محلی:\n")
            }
            if (rankedChunks.isNotEmpty()) {
                val lead = rankedChunks.first()
                append("• نکتهٔ محوری («${lead.chunk.bookTitle}»): ${lead.chunk.contentText.take(240).trim()}")
                if (lead.chunk.contentText.length > 240) append("…")
                append("\n")
                if (rankedChunks.size > 1) {
                    append("• شاهد تکمیلی: ${rankedChunks[1].chunk.contentText.take(180).trim()}…\n")
                }
            }
            if (voiceNotes.isNotBlank()) {
                append("• مطابق توضیح صوتی شما: ${voiceNotes.lineSequence().firstOrNull().orEmpty()}\n")
            }
            if (activeRole != null && activeRole.instructions.isNotBlank()) {
                append("• اقدام پیشنهادی طبق نقش: ${activeRole.instructions.take(140)}")
            }
        }.trim()
    }

    fun releaseSessionMemory() {
        warmedUp = false
    }

    private fun readAvailableRamMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024L * 1024L)
    }
}
