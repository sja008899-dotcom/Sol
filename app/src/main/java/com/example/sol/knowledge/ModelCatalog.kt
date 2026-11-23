package com.example.sol.knowledge

import com.example.sol.network.SolHttpClient

data class SlmModelSpec(
    val id: String,
    val titleFa: String,
    val descriptionFa: String,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val expectedBytes: Long,
    val approxSizeMb: Int,
    val minFreeRamMb: Long,
    val requiredFreeDiskMb: Long,
    val isRecommendedFor4Gb: Boolean,
    val fallbackDownloadUrls: List<String> = emptyList(),
) {
    fun hasVerifiedIntegrityMetadata(): Boolean =
        SolHttpClient.isValidSha256Hex(sha256) && expectedBytes > 0L

    fun estimatedDownloadBytes(): Long =
        if (expectedBytes > 0L) expectedBytes else approxSizeMb.toLong().coerceAtLeast(10L) * 1024L * 1024L
}

object SlmModelCatalog {
    // Samsung Galaxy A12 / 4 GB devices typically report ~3,600–4,096 MB totalMem; 6 GB devices report >= 5,400 MB
    private const val SIX_GB_TOTAL_RAM_THRESHOLD_MB = 5_400L

    // TODO(verify): verify exact SHA-256 and byte counts against official Hugging Face LFS metadata for each release
    const val QWEN3_0_6B_INT4_SHA256 = "TODO(verify)"
    const val QWEN3_0_6B_INT4_BYTES = -1L
    const val QWEN3_0_6B_INT8_SHA256 = "TODO(verify)"
    const val QWEN3_0_6B_INT8_BYTES = -1L
    const val QWEN2_5_1_5B_Q8_SHA256 = "TODO(verify)"
    const val QWEN2_5_1_5B_Q8_BYTES = -1L

    val models: List<SlmModelSpec> = listOf(
        SlmModelSpec(
            id = "qwen3_0_6b_int4",
            titleFa = "Qwen3-0.6B فشرده (پیشنهادی برای ۴GB RAM)",
            descriptionFa = "مدل کوانتیزه INT4 با پشتیبانی خوب از فارسی؛ سبک‌ترین و پایدارترین گزینه واقعی برای گوشی‌های ۴ گیگابایتی.",
            fileName = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            sha256 = QWEN3_0_6B_INT4_SHA256,
            expectedBytes = QWEN3_0_6B_INT4_BYTES,
            approxSizeMb = 498,
            minFreeRamMb = 620L,
            requiredFreeDiskMb = 1647L,
            isRecommendedFor4Gb = true,
            fallbackDownloadUrls = listOf(
                "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/qwen3_0.6b_torchao_mixed_int4_runtime_bmm_kv2048_prefill256.litertlm",
                "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            ),
        ),
        SlmModelSpec(
            id = "qwen3_0_6b_int8",
            titleFa = "Qwen3-0.6B استاندارد (INT8)",
            descriptionFa = "کیفیت جمله‌بندی کمی بهتر از INT4 اما با مصرف RAM و فضای ذخیره‌سازی بیشتر.",
            fileName = "Qwen3-0.6B.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            sha256 = QWEN3_0_6B_INT8_SHA256,
            expectedBytes = QWEN3_0_6B_INT8_BYTES,
            approxSizeMb = 586,
            minFreeRamMb = 760L,
            requiredFreeDiskMb = 1757L,
            isRecommendedFor4Gb = false,
            fallbackDownloadUrls = listOf(
                "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            ),
        ),
        SlmModelSpec(
            id = "qwen2_5_1_5b_q8",
            titleFa = "Qwen2.5-1.5B Instruct (نیازمند ۶GB+ RAM)",
            descriptionFa = "هوش و انسجام بالاتر در فارسی، مخصوص گوشی‌های با ۶ گیگابایت RAM یا بیشتر.",
            fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sha256 = QWEN2_5_1_5B_Q8_SHA256,
            expectedBytes = QWEN2_5_1_5B_Q8_BYTES,
            approxSizeMb = 1600,
            minFreeRamMb = 1400L,
            requiredFreeDiskMb = 3024L,
            isRecommendedFor4Gb = false,
        ),
    )

    fun modelsForDevice(totalDeviceRamMb: Long): List<SlmModelSpec> {
        val isSub6Gb = totalDeviceRamMb in 1L until SIX_GB_TOTAL_RAM_THRESHOLD_MB
        return if (isSub6Gb) {
            models.filter { it.minFreeRamMb < 1200L && !it.id.contains("1_5b") }
        } else {
            models
        }
    }

    fun recommendedModelForDevice(totalDeviceRamMb: Long): SlmModelSpec =
        modelsForDevice(totalDeviceRamMb).firstOrNull { it.isRecommendedFor4Gb }
            ?: models.first()

    fun minRequiredRamForFile(fileName: String): Long {
        val lower = fileName.lowercase()
        return when {
            lower.contains("1.5b") || lower.contains("2b") || lower.contains("3b") -> 1400L
            lower.contains("int4") || lower.contains("wi4") -> 620L
            else -> 720L
        }
    }
}

enum class SlmActiveBackend(val labelFa: String) {
    NONE("غیرفعال"),
    GPU("شتاب‌دهنده GPU (OpenCL)"),
    CPU("پردازنده مرکزی CPU"),
}

sealed interface SlmRuntimeState {
    data object NotInstalled : SlmRuntimeState
    data class Downloading(
        val modelTitleFa: String,
        val progressPercent: Int,
        val downloadedMb: Long,
        val totalMb: Long,
    ) : SlmRuntimeState

    data class InstalledUnloaded(
        val modelName: String,
        val modelPath: String,
        val fileSizeMb: Int,
        val minRamMb: Long,
        val availableRamMb: Long,
        val lastNoteFa: String = "",
    ) : SlmRuntimeState

    data class Loading(
        val modelName: String,
        val targetBackendFa: String,
    ) : SlmRuntimeState

    data class Ready(
        val modelName: String,
        val modelPath: String,
        val fileSizeMb: Int,
        val activeBackend: SlmActiveBackend,
        val availableRamMb: Long,
        val lastFallbackNoteFa: String = "",
    ) : SlmRuntimeState

    data class Generating(
        val modelName: String,
        val activeBackend: SlmActiveBackend,
    ) : SlmRuntimeState

    data class LowRamBlocked(
        val modelName: String,
        val requiredRamMb: Long,
        val availableRamMb: Long,
    ) : SlmRuntimeState

    data class Error(
        val messageFa: String,
        val hasInstalledModel: Boolean,
    ) : SlmRuntimeState
}

data class SlmStatus(
    val isEnabled: Boolean,
    val modelPath: String,
    val hasModelFile: Boolean,
    val isLoadedInRam: Boolean,
    val isGenerating: Boolean,
    val activeBackend: SlmActiveBackend,
    val availableRamMb: Long,
    val statusLabelFa: String,
)
