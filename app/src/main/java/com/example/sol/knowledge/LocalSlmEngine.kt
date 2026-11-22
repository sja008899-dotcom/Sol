package com.example.sol.knowledge

import android.content.Context
import android.net.Uri
import com.example.sol.network.SolHttpClient
import com.example.sol.network.SolStorageGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Facade for Offline On-Device LLM Runtime powered by Google LiteRT-LM (`com.google.ai.edge.litertlm`),
 * delegating storage to [ModelStore], verified downloads to [ModelDownloader],
 * and thread-safe single-executor inference to [InferenceEngine].
 */
class LocalSlmEngine(
    context: Context,
    httpClient: SolHttpClient = SolHttpClient(),
    availableBytesProvider: (File) -> Long = SolStorageGuard::readAvailableBytes,
) {
    private val _runtimeState = MutableStateFlow<SlmRuntimeState>(SlmRuntimeState.NotInstalled)
    val runtimeState: StateFlow<SlmRuntimeState> = _runtimeState.asStateFlow()

    private val modelStore = ModelStore(context.applicationContext, availableBytesProvider)
    private val inferenceEngine = InferenceEngine(context.applicationContext, modelStore, _runtimeState)
    private val modelDownloader = ModelDownloader(
        modelStore = modelStore,
        httpClient = httpClient,
        availableBytesProvider = availableBytesProvider,
        runtimeState = _runtimeState,
        onUnloadBeforeDownload = { reason -> inferenceEngine.unloadFromRam(reason) },
        onRefreshStatus = { preferred, note -> inferenceEngine.refreshRuntimeStatus(preferred, note) },
    )

    init {
        refreshRuntimeStatus("")
    }

    fun findActiveModelFile(preferredPath: String = ""): File? =
        modelStore.findActiveModelFile(preferredPath)

    fun hasInstalledModel(preferredPath: String = ""): Boolean =
        modelStore.hasInstalledModel(preferredPath)

    fun isLoadedInRam(): Boolean = inferenceEngine.isLoadedInRam()

    fun isGenerating(): Boolean = inferenceEngine.isGenerating()

    fun refreshRuntimeStatus(preferredPath: String = "", noteFa: String = "") {
        inferenceEngine.refreshRuntimeStatus(preferredPath, noteFa)
    }

    fun getStatus(enabled: Boolean, modelPath: String): SlmStatus {
        val availMb = modelStore.readAvailableRamMb()
        val modelFile = modelStore.findActiveModelFile(modelPath)
        val hasFile = modelFile != null
        val loaded = inferenceEngine.isLoadedInRam()
        val generating = inferenceEngine.isGenerating()
        val activeBackend = inferenceEngine.activeBackend
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

    fun downloadModel(spec: SlmModelSpec, onSavedPath: (String) -> Unit) =
        modelDownloader.downloadModel(spec, onSavedPath)

    internal suspend fun downloadModelSync(spec: SlmModelSpec, onSavedPath: (String) -> Unit): Result<File> =
        modelDownloader.downloadModelSync(spec, onSavedPath)

    fun cancelDownload() = modelDownloader.cancelDownload()

    fun importLocalModel(uri: Uri, onResult: (Result<File>) -> Unit) =
        modelDownloader.importLocalModel(uri, onResult)

    suspend fun deleteModel(preferredPath: String) {
        inferenceEngine.unloadBlocking()
        withContext(Dispatchers.IO) {
            modelStore.deleteAllModels(preferredPath)
        }
        refreshRuntimeStatus("")
    }

    suspend fun loadModel(
        preferredPath: String,
        preferGpu: Boolean,
        systemInstruction: String,
    ): Result<SlmActiveBackend> = inferenceEngine.loadModel(preferredPath, preferGpu, systemInstruction)

    suspend fun generateReplyStream(
        userPrompt: String,
        systemInstruction: String,
        preferredPath: String,
        preferGpu: Boolean,
        isQwen3Hint: Boolean? = null,
        maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS,
        timeoutMs: Long = DEFAULT_GENERATION_TIMEOUT_MS,
        forceFreshConversation: Boolean = false,
        onPartialCleanText: (String) -> Unit,
    ): Result<String> = inferenceEngine.generateReplyStream(
        userPrompt = userPrompt,
        systemInstruction = systemInstruction,
        preferredPath = preferredPath,
        preferGpu = preferGpu,
        isQwen3Hint = isQwen3Hint,
        maxNewTokens = maxNewTokens,
        timeoutMs = timeoutMs,
        forceFreshConversation = forceFreshConversation,
        onPartialCleanText = onPartialCleanText,
    )

    suspend fun proposeIntent(
        userUtterance: String,
        preferredPath: String,
        preferGpu: Boolean,
        timeoutMs: Long = INTENT_PROPOSAL_TIMEOUT_MS,
    ): Result<SlmIntentProposal?> = inferenceEngine.proposeIntent(
        userUtterance = userUtterance,
        preferredPath = preferredPath,
        preferGpu = preferGpu,
        timeoutMs = timeoutMs,
    )

    fun stopGeneration() = inferenceEngine.stopGeneration()

    fun resetConversation() = inferenceEngine.resetConversation()

    fun unloadFromRam(reasonFa: String = "مدل زبانی از RAM آزاد شد") =
        inferenceEngine.unloadFromRam(reasonFa)

    fun releaseSessionMemory() {
        unloadFromRam("آزادسازی خودکار مدل زبانی در onTrimMemory برای حفظ ۴GB RAM")
    }

    companion object {
        const val DEFAULT_MAX_NEW_TOKENS = 160
        const val DEFAULT_GENERATION_TIMEOUT_MS = 30_000L
        const val INTENT_PROPOSAL_TIMEOUT_MS = 15_000L

        fun estimateTokenCount(text: String): Int = InferenceEngine.estimateTokenCount(text)

        fun buildSystemInstruction(
            ownerName: String,
            confirmedLocation: String,
            activeRoleName: String?,
            activeRoleInstructions: String?,
            memoryHighlights: List<String>,
            groundedBookChunks: List<RankedBookChunk>,
            voiceNotes: String,
        ): String = SlmPromptBuilder.buildChatSystemInstruction(
            ownerName = ownerName,
            confirmedLocation = confirmedLocation,
            activeRoleName = activeRoleName,
            activeRoleInstructions = activeRoleInstructions,
            memoryHighlights = memoryHighlights,
            groundedBookChunks = groundedBookChunks,
            voiceNotes = voiceNotes,
        )

        fun hasEnoughRamForModel(availableRamMb: Long, minRequiredRamMb: Long): Boolean =
            ModelStore.hasEnoughRamForModel(availableRamMb, minRequiredRamMb)

        fun stripThinkingTags(rawText: String, hideUnclosedThinkWhileStreaming: Boolean = true): String =
            InferenceEngine.stripThinkingTags(rawText, hideUnclosedThinkWhileStreaming)
    }
}
