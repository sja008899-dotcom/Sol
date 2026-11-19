package com.example.sol.knowledge

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.sol.network.SolHttpClient
import com.example.sol.network.SolNetworkException
import com.example.sol.network.SolStorageGuard
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
) {
    fun hasVerifiedIntegrityMetadata(): Boolean =
        SolHttpClient.isValidSha256Hex(sha256) && expectedBytes > 0L
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
            descriptionFa = "مدل کوانتیزه Mixed-INT4 با پشتیبانی خوب از فارسی؛ سبک‌ترین و پایدارترین گزینه واقعی برای گوشی‌های ۴ گیگابایتی.",
            fileName = "qwen3_0.6b_torchao_mixed_int4_runtime_bmm_kv2048_prefill256.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/qwen3_0.6b_torchao_mixed_int4_runtime_bmm_kv2048_prefill256.litertlm",
            sha256 = QWEN3_0_6B_INT4_SHA256,
            expectedBytes = QWEN3_0_6B_INT4_BYTES,
            approxSizeMb = 498,
            minFreeRamMb = 620L,
            requiredFreeDiskMb = 1647L,
            isRecommendedFor4Gb = true,
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

/**
 * Real Offline On-Device LLM Runtime for Android powered by Google LiteRT-LM (`com.google.ai.edge.litertlm`).
 *
 * Strict Honesty & 4GB RAM Android 12 Design:
 * - Never fakes neural responses when no `.litertlm` model file exists on device.
 * - Lazy-loaded only on explicit test or real inference (never on App start).
 * - Dedicated single-thread executor + Mutex enforces LiteRT-LM threading & close order rules:
 *   cancelProcess() -> drain streaming flow -> close conversation -> close engine.
 * - Checks free system RAM before loading and releases automatically on `onTrimMemory`.
 */
class LocalSlmEngine(
    private val context: Context,
    private val httpClient: SolHttpClient = SolHttpClient(),
    private val availableBytesProvider: (File) -> Long = SolStorageGuard::readAvailableBytes,
) {

    private val slmExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Sol-LiteRT-LM").apply {
            priority = Thread.NORM_PRIORITY
        }
    }
    private val slmDispatcher = slmExecutor.asCoroutineDispatcher()
    private val slmScope = CoroutineScope(SupervisorJob() + slmDispatcher)
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()

    private val _runtimeState = MutableStateFlow<SlmRuntimeState>(SlmRuntimeState.NotInstalled)
    val runtimeState: StateFlow<SlmRuntimeState> = _runtimeState.asStateFlow()

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var conversation: Conversation? = null

    @Volatile
    private var loadedModelPath: String = ""

    @Volatile
    private var activeBackend: SlmActiveBackend = SlmActiveBackend.NONE

    @Volatile
    private var loadedSystemInstructionHash: Int = 0

    @Volatile
    private var lastFallbackNote: String = ""

    @Volatile
    private var activeDownloadJob: Job? = null

    @Volatile
    private var activeInferenceJob: Job? = null

    private val isBusyGenerating = AtomicBoolean(false)
    private val downloadCancelled = AtomicBoolean(false)

    private val modelsDir: File
        get() = File(context.filesDir, MODELS_DIR_NAME)

    init {
        refreshRuntimeStatus("")
    }

    fun findActiveModelFile(preferredPath: String = ""): File? {
        if (preferredPath.isNotBlank()) {
            val f = File(preferredPath)
            if (f.isFile && f.length() >= MIN_VALID_MODEL_BYTES) return f
        }
        val dir = modelsDir
        if (!dir.isDirectory) return null
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".litertlm", ignoreCase = true) && it.length() >= MIN_VALID_MODEL_BYTES }
            ?.maxByOrNull { it.lastModified() }
    }

    fun hasInstalledModel(preferredPath: String = ""): Boolean = findActiveModelFile(preferredPath) != null

    fun isLoadedInRam(): Boolean = engine != null

    fun isGenerating(): Boolean = isBusyGenerating.get()

    fun refreshRuntimeStatus(preferredPath: String = "", noteFa: String = "") {
        if (_runtimeState.value is SlmRuntimeState.Downloading ||
            _runtimeState.value is SlmRuntimeState.Loading ||
            isBusyGenerating.get()
        ) {
            return
        }
        val availMb = readAvailableRamMb()
        val activeEngine = engine
        val loadedFile = if (loadedModelPath.isNotBlank()) File(loadedModelPath) else null
        if (activeEngine != null && loadedFile != null && loadedFile.exists()) {
            _runtimeState.value = SlmRuntimeState.Ready(
                modelName = loadedFile.name,
                modelPath = loadedFile.absolutePath,
                fileSizeMb = (loadedFile.length() / (1024L * 1024L)).toInt().coerceAtLeast(1),
                activeBackend = activeBackend,
                availableRamMb = availMb,
                lastFallbackNoteFa = lastFallbackNote,
            )
            return
        }

        val file = findActiveModelFile(preferredPath)
        if (file == null) {
            _runtimeState.value = SlmRuntimeState.NotInstalled
        } else {
            val sizeMb = (file.length() / (1024L * 1024L)).toInt().coerceAtLeast(1)
            val minRam = SlmModelCatalog.minRequiredRamForFile(file.name)
            _runtimeState.value = SlmRuntimeState.InstalledUnloaded(
                modelName = file.name,
                modelPath = file.absolutePath,
                fileSizeMb = sizeMb,
                minRamMb = minRam,
                availableRamMb = availMb,
                lastNoteFa = noteFa,
            )
        }
    }

    fun getStatus(enabled: Boolean, modelPath: String): SlmStatus {
        val availMb = readAvailableRamMb()
        val modelFile = findActiveModelFile(modelPath)
        val hasFile = modelFile != null
        val loaded = engine != null
        val generating = isBusyGenerating.get()
        val state = _runtimeState.value

        val label = when {
            state is SlmRuntimeState.Downloading ->
                "در حال دریافت «${state.modelTitleFa}»: ${state.progressPercent}٪ (${state.downloadedMb}/${state.totalMb}MB)"
            state is SlmRuntimeState.Loading ->
                "در حال بارگذاری واقعی «${state.modelName}» روی ${state.targetBackendFa}…"
            state is SlmRuntimeState.LowRamBlocked ->
                "بارگذاری متوقف شد: RAM آزاد (${state.availableRamMb}MB) کمتر از حداقل لازم (${state.requiredRamMb}MB) است."
            !hasFile ->
                if (enabled) {
                    "فایل مدل آفلاین (.litertlm) نصب نیست؛ فقط هستهٔ سریع قانون‌محور و جست‌وجوی BM25 فعال است."
                } else {
                    "مدل زبانی آفلاین (فاز ۳) خاموش و نصب‌نشده است (هستهٔ سریع قانون‌محور + BM25 فعال)."
                }
            !enabled ->
                "مدل «${modelFile!!.name}» روی حافظه موجود است ولی سوئیچ هوش زبانی خاموش است."
            generating ->
                "در حال تولید پاسخ عصبی آفلاین با ${modelFile!!.name} (${activeBackend.labelFa})…"
            loaded ->
                "مدل واقعی «${modelFile!!.name}» آماده در RAM روی ${activeBackend.labelFa} (RAM آزاد: ${availMb}MB)"
            else ->
                "مدل «${modelFile!!.name}» آمادهٔ بارگذاری تنبل است (RAM آزاد: ${availMb}MB؛ هنگام اولین گفتگو بارگذاری می‌شود)."
        }

        return SlmStatus(
            isEnabled = enabled,
            modelPath = modelFile?.absolutePath ?: modelPath,
            hasModelFile = hasFile,
            isLoadedInRam = loaded,
            isGenerating = generating,
            activeBackend = activeBackend,
            availableRamMb = availMb,
            statusLabelFa = label,
        )
    }

    fun downloadModel(
        spec: SlmModelSpec,
        onSavedPath: (String) -> Unit,
    ) {
        if (activeDownloadJob?.isActive == true) return
        downloadCancelled.set(false)
        activeDownloadJob = downloadScope.launch {
            downloadModelSync(spec, onSavedPath)
        }
    }

    internal suspend fun downloadModelSync(
        spec: SlmModelSpec,
        onSavedPath: (String) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val destDir = modelsDir.apply { mkdirs() }
        val finalFile = File(destDir, spec.fileName)
        val partFile = File(destDir, "${spec.fileName}.part")

        try {
            // Rule 7 fail-safe: refuse to download/activate without verified SHA-256 and exact byte count
            if (!spec.hasVerifiedIntegrityMetadata()) {
                partFile.delete()
                throw SolNetworkException.IntegrityMetadataMissingException()
            }

            // Disk guard: size * 1.25 + 1 GB
            val diskCheck = SolStorageGuard.checkFreeSpace(
                targetPath = destDir,
                payloadBytes = spec.expectedBytes,
                availableBytesProvider = availableBytesProvider,
            )
            if (!diskCheck.hasEnoughSpace) {
                throw SolNetworkException.InsufficientDiskSpaceException(
                    availableMb = diskCheck.availableMb,
                    requiredMb = diskCheck.requiredMb,
                )
            }

            // Unload existing model from RAM first if swapping models on 4GB device
            unloadFromRam("آزادسازی حافظه پیش از دانلود مدل")

            _runtimeState.value = SlmRuntimeState.Downloading(
                modelTitleFa = spec.titleFa,
                progressPercent = 1,
                downloadedMb = 0L,
                totalMb = (spec.expectedBytes / (1024L * 1024L)).coerceAtLeast(1L),
            )

            httpClient.downloadVerifiedFile(
                urlString = spec.downloadUrl,
                partFile = partFile,
                expectedBytes = spec.expectedBytes,
                expectedSha256 = spec.sha256,
                allowedHostSuffixes = setOf("huggingface.co", "hf.co"),
                isCancelled = { downloadCancelled.get() },
                onProgress = { pct, doneBytes, totalBytes ->
                    _runtimeState.value = SlmRuntimeState.Downloading(
                        modelTitleFa = spec.titleFa,
                        progressPercent = pct,
                        downloadedMb = doneBytes / (1024L * 1024L),
                        totalMb = (totalBytes / (1024L * 1024L)).coerceAtLeast(1L),
                    )
                },
            )

            if (downloadCancelled.get()) {
                partFile.delete()
                refreshRuntimeStatus("", "دانلود مدل لغو شد")
                return@withContext Result.failure(SolNetworkException.CancelledException())
            }

            if (partFile.length() < MIN_VALID_MODEL_BYTES) {
                partFile.delete()
                error("فایل دریافت‌شده معتبر یا کامل نیست (حجم کمتر از حد مجاز).")
            }

            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }

            onSavedPath(finalFile.absolutePath)
            refreshRuntimeStatus(
                preferredPath = finalFile.absolutePath,
                noteFa = "مدل «${spec.titleFa}» پس از تأیید SHA-256 آمادهٔ اجراست.",
            )
            Result.success(finalFile)
        } catch (t: Throwable) {
            if (t !is SolNetworkException.TransportException && t !is SolNetworkException.HttpStatusException) {
                // On integrity mismatch or cancellation, remove .part; keep recoverable .part only on transient transport interruptions
                if (t is SolNetworkException.IntegrityMismatchException ||
                    t is SolNetworkException.IntegrityMetadataMissingException ||
                    t is SolNetworkException.CancelledException
                ) {
                    partFile.delete()
                }
            }
            if (downloadCancelled.get() || t is CancellationException) {
                partFile.delete()
                refreshRuntimeStatus("", "دانلود توسط کاربر متوقف شد")
            } else {
                val msg = if (t is SolNetworkException) {
                    t.userMessageFa
                } else {
                    "خطا در دانلود مدل: ${t.localizedMessage ?: "بررسی اتصال اینترنت و فضای حافظه"}"
                }
                _runtimeState.value = SlmRuntimeState.Error(
                    messageFa = msg,
                    hasInstalledModel = hasInstalledModel(),
                )
            }
            Result.failure(t)
        }
    }

    fun cancelDownload() {
        downloadCancelled.set(true)
        activeDownloadJob?.cancel()
        activeDownloadJob = null
    }

    fun importLocalModel(
        uri: Uri,
        onResult: (Result<File>) -> Unit,
    ) {
        if (activeDownloadJob?.isActive == true) {
            onResult(Result.failure(IllegalStateException("در حال حاضر یک دانلود فعال است.")))
            return
        }
        downloadScope.launch {
            val res = runCatching {
                val displayName = resolveDisplayName(uri)
                require(displayName.endsWith(".litertlm", ignoreCase = true)) {
                    "فقط فایل‌های مدل با پسوند .litertlm پشتیبانی می‌شوند (انتخاب‌شده: $displayName)."
                }
                val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val destDir = modelsDir.apply { mkdirs() }
                val finalFile = File(destDir, safeName)
                val partFile = File(destDir, "$safeName.part")

                val declaredSize = resolveFileSize(uri)
                if (declaredSize > 0L) {
                    val diskCheck = SolStorageGuard.checkFreeSpace(destDir, declaredSize, availableBytesProvider)
                    require(diskCheck.hasEnoughSpace) {
                        "فضای آزاد ذخیره‌سازی کافی نیست (آزاد: ${diskCheck.availableMb}MB، موردنیاز با حاشیه امن: ${diskCheck.requiredMb}MB)."
                    }
                }

                _runtimeState.value = SlmRuntimeState.Downloading(
                    modelTitleFa = "کپی فایل محلی $safeName",
                    progressPercent = 10,
                    downloadedMb = 0L,
                    totalMb = ((declaredSize.coerceAtLeast(1L)) / (1024L * 1024L)),
                )

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        var lastPct = -1
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            copied += read
                            if (declaredSize > 0L) {
                                val pct = ((copied * 100L) / declaredSize).toInt().coerceIn(5, 99)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    _runtimeState.value = SlmRuntimeState.Downloading(
                                        modelTitleFa = "کپی فایل محلی $safeName",
                                        progressPercent = pct,
                                        downloadedMb = copied / (1024L * 1024L),
                                        totalMb = declaredSize / (1024L * 1024L),
                                    )
                                }
                            }
                        }
                    }
                } ?: error("دسترسی به فایل انتخاب‌شده ممکن نشد.")

                if (partFile.length() < MIN_VALID_MODEL_BYTES) {
                    partFile.delete()
                    error("حجم فایل انتخاب‌شده بسیار کم است و نمی‌تواند یک مدل .litertlm معتبر باشد.")
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                finalFile
            }
            res.fold(
                onSuccess = { file ->
                    refreshRuntimeStatus(file.absolutePath, "مدل محلی با موفقیت وارد شد")
                    onResult(Result.success(file))
                },
                onFailure = { err ->
                    _runtimeState.value = SlmRuntimeState.Error(
                        messageFa = err.localizedMessage ?: "وارد کردن فایل .litertlm ناموفق بود.",
                        hasInstalledModel = hasInstalledModel(),
                    )
                    onResult(Result.failure(err))
                },
            )
        }
    }

    suspend fun deleteModel(preferredPath: String) {
        unloadBlocking()
        withContext(Dispatchers.IO) {
            findActiveModelFile(preferredPath)?.delete()
            modelsDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".litertlm", ignoreCase = true) || file.name.endsWith(".part")) {
                    file.delete()
                }
            }
        }
        refreshRuntimeStatus("")
    }

    suspend fun loadModel(
        preferredPath: String,
        preferGpu: Boolean,
        systemInstruction: String,
    ): Result<SlmActiveBackend> = withContext(slmDispatcher) {
        lifecycleMutex.withLock {
            loadOrReuseLocked(preferredPath, preferGpu, systemInstruction, forceFreshConversation = false)
        }
    }

    suspend fun generateReplyStream(
        userPrompt: String,
        systemInstruction: String,
        preferredPath: String,
        preferGpu: Boolean,
        isQwen3Hint: Boolean? = null,
        onPartialCleanText: (String) -> Unit,
    ): Result<String> = withContext(slmDispatcher) {
        lifecycleMutex.withLock {
            val loadRes = loadOrReuseLocked(
                preferredPath = preferredPath,
                preferGpu = preferGpu,
                systemInstruction = systemInstruction,
                forceFreshConversation = false,
            )
            if (loadRes.isFailure) {
                return@withLock Result.failure(loadRes.exceptionOrNull() ?: IllegalStateException("مدل آماده نیست"))
            }

            val conv = conversation ?: return@withLock Result.failure(
                IllegalStateException("نشست مدل آماده نشد.")
            )
            val modelFile = File(loadedModelPath)
            val useNoThinkPrefix = isQwen3Hint ?: modelFile.name.contains("qwen3", ignoreCase = true)
            val effectivePrompt = if (useNoThinkPrefix) "/no_think\n$userPrompt" else userPrompt

            isBusyGenerating.set(true)
            _runtimeState.value = SlmRuntimeState.Generating(
                modelName = modelFile.name,
                activeBackend = activeBackend,
            )

            val rawBuilder = StringBuilder()
            var lastEmitted = ""
            try {
                conv.sendMessageAsync(effectivePrompt).collect { chunk ->
                    if (!isActive || !isBusyGenerating.get()) {
                        throw CancellationException("تولید توسط کاربر متوقف شد")
                    }
                    rawBuilder.append(chunk.toString())
                    val cleanSoFar = stripThinkingTags(rawBuilder.toString(), hideUnclosedThinkWhileStreaming = true)
                    if (cleanSoFar.isNotBlank() && cleanSoFar != lastEmitted) {
                        lastEmitted = cleanSoFar
                        onPartialCleanText(cleanSoFar)
                    }
                }
                val finalClean = stripThinkingTags(rawBuilder.toString(), hideUnclosedThinkWhileStreaming = false)
                if (finalClean.isBlank()) {
                    return@withLock Result.failure(IllegalStateException("مدل خروجی معتبری تولید نکرد؛ دوباره امتحان کنید یا گفت‌وگو را تازه کنید."))
                }
                Result.success(finalClean)
            } catch (ce: CancellationException) {
                val partial = stripThinkingTags(rawBuilder.toString(), hideUnclosedThinkWhileStreaming = false)
                if (partial.isNotBlank()) {
                    Result.success("$partial\n(توقف توسط کاربر)")
                } else {
                    Result.failure(ce)
                }
            } catch (t: Throwable) {
                Result.failure(IllegalStateException("خطا هنگام پردازش مدل زبانی: ${t.localizedMessage ?: "نامشخص"}", t))
            } finally {
                isBusyGenerating.set(false)
                refreshRuntimeStatus(loadedModelPath)
            }
        }
    }

    fun stopGeneration() {
        isBusyGenerating.set(false)
        slmScope.launch {
            runCatching { conversation?.cancelProcess() }
        }
    }

    fun resetConversation() {
        if (isBusyGenerating.get()) return
        slmScope.launch {
            lifecycleMutex.withLock {
                runCatching { conversation?.close() }
                conversation = null
                loadedSystemInstructionHash = 0
            }
        }
    }

    fun unloadFromRam(reasonFa: String = "مدل زبانی از RAM آزاد شد") {
        slmScope.launch {
            lifecycleMutex.withLock {
                releaseLocked(reasonFa)
            }
        }
    }

    fun releaseSessionMemory() {
        unloadFromRam("آزادسازی خودکار مدل زبانی در onTrimMemory برای حفظ ۴GB RAM")
    }

    private suspend fun unloadBlocking() = withContext(slmDispatcher) {
        lifecycleMutex.withLock {
            releaseLocked("بستن کامل مدل زبانی")
        }
    }

    private suspend fun loadOrReuseLocked(
        preferredPath: String,
        preferGpu: Boolean,
        systemInstruction: String,
        forceFreshConversation: Boolean,
    ): Result<SlmActiveBackend> {
        val file = findActiveModelFile(preferredPath)
        if (file == null) {
            releaseLocked("")
            _runtimeState.value = SlmRuntimeState.NotInstalled
            return Result.failure(
                IllegalStateException("فایل مدل آفلاین (.litertlm) نصب نیست. ابتدا از بخش «تنظیمات ← هوش زبانی آفلاین» مدل را دانلود یا انتخاب کنید.")
            )
        }

        val currentEngine = engine
        val sameModelAlreadyLoaded = currentEngine != null && loadedModelPath == file.absolutePath
        if (sameModelAlreadyLoaded) {
            val needNewConversation = forceFreshConversation ||
                conversation == null ||
                loadedSystemInstructionHash != systemInstruction.hashCode()
            if (needNewConversation) {
                runCatching { conversation?.close() }
                conversation = null
                conversation = createPersianConversation(currentEngine!!, systemInstruction)
                loadedSystemInstructionHash = systemInstruction.hashCode()
            }
            refreshRuntimeStatus(file.absolutePath)
            return Result.success(activeBackend)
        }

        // Unload any prior model before checking available RAM for the new model
        releaseLocked("")

        val availRamMb = readAvailableRamMb()
        val requiredRamMb = SlmModelCatalog.minRequiredRamForFile(file.name)
        if (!hasEnoughRamForModel(availRamMb, requiredRamMb)) {
            _runtimeState.value = SlmRuntimeState.LowRamBlocked(
                modelName = file.name,
                requiredRamMb = requiredRamMb,
                availableRamMb = availRamMb,
            )
            return Result.failure(
                IllegalStateException(
                    "حافظه RAM آزاد کافی نیست (${availRamMb}MB موجود؛ حداقل ${requiredRamMb}MB لازم است). ابتدا کش را آزاد کنید یا مدل سبک‌تر 0.6B INT4 را انتخاب کنید."
                )
            )
        }

        val targetBackendLabel = if (preferGpu) "GPU (با پشتیبان CPU)" else "CPU"
        _runtimeState.value = SlmRuntimeState.Loading(
            modelName = file.name,
            targetBackendFa = targetBackendLabel,
        )

        lastFallbackNote = ""
        val cachePath = context.cacheDir.path
        var initializedEngine: Engine? = null
        var chosenBackend = SlmActiveBackend.NONE
        var firstError: Throwable? = null

        val candidateBackends = if (preferGpu) {
            listOf(SlmActiveBackend.GPU to Backend.GPU(), SlmActiveBackend.CPU to Backend.CPU())
        } else {
            listOf(SlmActiveBackend.CPU to Backend.CPU())
        }

        for ((kind, backendObj) in candidateBackends) {
            try {
                val cfg = EngineConfig(
                    modelPath = file.absolutePath,
                    backend = backendObj,
                    cacheDir = cachePath,
                )
                val attemptEngine = Engine(cfg)
                attemptEngine.initialize()
                initializedEngine = attemptEngine
                chosenBackend = kind
                if (preferGpu && kind == SlmActiveBackend.CPU && firstError != null) {
                    lastFallbackNote = "GPU روی این تراشه در دسترس نبود؛ به‌صورت خودکار روی CPU اجرا شد."
                }
                break
            } catch (t: Throwable) {
                firstError = t
                // Do NOT call attemptEngine.close() if initialize() threw (LiteRT-LM contract)
            }
        }

        if (initializedEngine == null) {
            val msg = "راه‌اندازی مدل LiteRT-LM ناموفق بود: ${firstError?.localizedMessage ?: "خطای حافظه یا فرمت فایل"}"
            _runtimeState.value = SlmRuntimeState.Error(msg, hasInstalledModel = true)
            return Result.failure(IllegalStateException(msg, firstError))
        }

        return try {
            val conv = createPersianConversation(initializedEngine, systemInstruction)
            engine = initializedEngine
            conversation = conv
            loadedModelPath = file.absolutePath
            activeBackend = chosenBackend
            loadedSystemInstructionHash = systemInstruction.hashCode()
            refreshRuntimeStatus(file.absolutePath)
            Result.success(chosenBackend)
        } catch (t: Throwable) {
            runCatching { initializedEngine.close() }
            engine = null
            conversation = null
            loadedModelPath = ""
            activeBackend = SlmActiveBackend.NONE
            val msg = "ساخت نشست گفتگو ناموفق بود: ${t.localizedMessage ?: "نامشخص"}"
            _runtimeState.value = SlmRuntimeState.Error(msg, hasInstalledModel = true)
            Result.failure(IllegalStateException(msg, t))
        }
    }

    private fun createPersianConversation(
        targetEngine: Engine,
        systemInstruction: String,
    ): Conversation {
        return targetEngine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemInstruction),
                samplerConfig = SamplerConfig(
                    topK = 24,
                    topP = 0.90,
                    temperature = 0.65,
                ),
            )
        )
    }

    private suspend fun releaseLocked(reasonFa: String) {
        if (isBusyGenerating.get()) {
            isBusyGenerating.set(false)
            repeat(15) {
                runCatching { conversation?.cancelProcess() }
                if (!isBusyGenerating.get()) return@repeat
                delay(80)
            }
        }
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
        loadedModelPath = ""
        activeBackend = SlmActiveBackend.NONE
        loadedSystemInstructionHash = 0
        lastFallbackNote = ""
        if (reasonFa.isNotBlank()) {
            refreshRuntimeStatus("", reasonFa)
        }
    }

    // Legacy direct HttpURLConnection downloader removed; SolHttpClient handles HTTPS redirects, Range resume & SHA-256.

    private fun resolveDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "model.litertlm"
    }

    private fun resolveFileSize(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx)
            }
        }
        return -1L
    }

    private fun readAvailableRamMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return -1L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024L * 1024L)
    }

    companion object {
        private const val MODELS_DIR_NAME = "slm_models"
        private const val MIN_VALID_MODEL_BYTES = 1L * 1024L * 1024L

        private val COMPLETED_THINK_REGEX = Regex("(?is)<think>.*?</think>")
        private val EXCESS_NEWLINES_REGEX = Regex("\\n{3,}")

        fun buildSystemInstruction(
            ownerName: String,
            confirmedLocation: String,
            activeRoleName: String?,
            activeRoleInstructions: String?,
            memoryHighlights: List<String>,
            groundedBookChunks: List<RankedBookChunk>,
            voiceNotes: String,
        ): String = buildString {
            appendLine("تو «Sol» هستی؛ یک دستیار شخصی فارسی، محترم، دقیق و همراه فکری روی گوشی کاربر.")
            appendLine("نام کاربر: $ownerName")
            if (confirmedLocation.isNotBlank()) {
                appendLine("موقعیت فعلی تأییدشده کاربر: $confirmedLocation")
            }
            if (!activeRoleName.isNullOrBlank()) {
                appendLine("نقش فعال فعلی تو: $activeRoleName")
                if (!activeRoleInstructions.isNullOrBlank()) {
                    appendLine("دستورالعمل نقش فعال: ${activeRoleInstructions.take(350)}")
                }
            }
            if (memoryHighlights.isNotEmpty()) {
                appendLine("حافظه شخصی ذخیره‌شده کاربر (فقط به این موارد استناد کن و چیزی از خودت نساز):")
                memoryHighlights.take(8).forEach { appendLine("- $it") }
            }
            if (groundedBookChunks.isNotEmpty()) {
                appendLine("بخش‌های مرتبط از کتابخانه آفلاین کاربر:")
                groundedBookChunks.take(2).forEach { hit ->
                    appendLine("- از «${hit.chunk.bookTitle}»: ${hit.chunk.contentText.take(360)}")
                }
            }
            if (voiceNotes.isNotBlank()) {
                appendLine("یادداشت صوتی کاربر درباره کتاب: ${voiceNotes.take(220)}")
            }
            appendLine("قواعد حتمی پاسخ‌گویی:")
            appendLine("۱. فقط به زبان فارسی روان، محترمانه، کاربردی و بدون پرگویی پاسخ بده.")
            appendLine("۲. اگر اطلاعاتی در حافظه بالا نیست، حدس نزن و صادقانه بگو که نمی‌دانی یا در حافظه ثبت نشده.")
            appendLine("۳. در گفت‌وگوی همدلانه و همفکری، شنوندهٔ محترمی باش؛ ولی هرگز نقش پزشک، روان‌پزشک یا متخصص مجوزدار بالینی را ادعا نکن و در بحران‌های حاد به تماس با نزدیکان یا مراکز تخصصی راهنمایی کن.")
            appendLine("۴. خروجی را کوتاه، واضح و مناسب خواندن با صدای گوشی (TTS) بنویس.")
        }.trim()

        fun hasEnoughRamForModel(availableRamMb: Long, minRequiredRamMb: Long): Boolean {
            if (availableRamMb < 0L) return true
            return availableRamMb >= minRequiredRamMb
        }

        fun stripThinkingTags(rawText: String, hideUnclosedThinkWhileStreaming: Boolean = true): String {
            var cleaned = COMPLETED_THINK_REGEX.replace(rawText, "")
            if (hideUnclosedThinkWhileStreaming) {
                val openIdx = cleaned.indexOf("<think>", ignoreCase = true)
                if (openIdx >= 0) {
                    cleaned = cleaned.substring(0, openIdx)
                }
            } else {
                cleaned = cleaned.replace("<think>", "", ignoreCase = true)
                    .replace("</think>", "", ignoreCase = true)
            }
            cleaned = cleaned.removePrefix("/no_think").trim()
            return EXCESS_NEWLINES_REGEX.replace(cleaned, "\n\n").trim()
        }
    }
}
