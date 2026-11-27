package com.example.sol.knowledge

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class InferenceEngine(
    private val context: Context,
    private val modelStore: ModelStore,
    private val runtimeState: MutableStateFlow<SlmRuntimeState>,
    private val beforeModelLoad: suspend (requiredRamMb: Long) -> Result<Unit> = { Result.success(Unit) },
) {
    private val slmExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Sol-LiteRT-LM").apply {
            priority = Thread.NORM_PRIORITY
        }
    }
    private val slmDispatcher = slmExecutor.asCoroutineDispatcher()
    private val slmScope = CoroutineScope(SupervisorJob() + slmDispatcher)
    private val lifecycleMutex = Mutex()

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var conversation: Conversation? = null

    @Volatile
    private var loadedModelPath: String = ""

    @Volatile
    var activeBackend: SlmActiveBackend = SlmActiveBackend.NONE
        private set

    @Volatile
    private var loadedSystemInstructionHash: Int = 0

    @Volatile
    private var lastFallbackNote: String = ""

    private val isBusyGenerating = AtomicBoolean(false)

    private enum class GenerationStopReason {
        NONE,
        USER_CANCELLED,
        TOKEN_LIMIT,
        TIMEOUT,
    }

    fun isLoadedInRam(): Boolean = engine != null

    fun isGenerating(): Boolean = isBusyGenerating.get()

    fun refreshRuntimeStatus(preferredPath: String = "", noteFa: String = "") {
        if (runtimeState.value is SlmRuntimeState.Downloading ||
            runtimeState.value is SlmRuntimeState.Loading ||
            isBusyGenerating.get()
        ) {
            return
        }
        val availMb = modelStore.readAvailableRamMb()
        val activeEngine = engine
        val loadedFile = if (loadedModelPath.isNotBlank()) File(loadedModelPath) else null
        if (activeEngine != null && loadedFile != null && loadedFile.exists()) {
            runtimeState.value = SlmRuntimeState.Ready(
                modelName = loadedFile.name,
                modelPath = loadedFile.absolutePath,
                fileSizeMb = (loadedFile.length() / (1024L * 1024L)).toInt().coerceAtLeast(1),
                activeBackend = activeBackend,
                availableRamMb = availMb,
                lastFallbackNoteFa = lastFallbackNote,
                sha256Hex = modelStore.readSha256Sidecar(loadedFile),
            )
            return
        }

        val file = modelStore.findActiveModelFile(preferredPath)
        if (file == null) {
            runtimeState.value = SlmRuntimeState.NotInstalled
        } else {
            val sizeMb = (file.length() / (1024L * 1024L)).toInt().coerceAtLeast(1)
            val minRam = SlmModelCatalog.minRequiredRamForFile(file.name)
            runtimeState.value = SlmRuntimeState.InstalledUnloaded(
                modelName = file.name,
                modelPath = file.absolutePath,
                fileSizeMb = sizeMb,
                minRamMb = minRam,
                availableRamMb = availMb,
                lastNoteFa = noteFa,
                sha256Hex = modelStore.readSha256Sidecar(file),
            )
        }
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
        maxNewTokens: Int = LocalSlmEngine.DEFAULT_MAX_NEW_TOKENS,
        timeoutMs: Long = LocalSlmEngine.DEFAULT_GENERATION_TIMEOUT_MS,
        forceFreshConversation: Boolean = false,
        onPartialCleanText: (String) -> Unit,
    ): Result<String> = withContext(slmDispatcher) {
        lifecycleMutex.withLock {
            val loadRes = loadOrReuseLocked(
                preferredPath = preferredPath,
                preferGpu = preferGpu,
                systemInstruction = systemInstruction,
                forceFreshConversation = forceFreshConversation,
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
            val safeMaxTokens = maxNewTokens.coerceIn(32, MAX_ALLOWED_NEW_TOKENS)
            val safeTimeoutMs = timeoutMs.coerceIn(2_000L, MAX_ALLOWED_TIMEOUT_MS)

            val stopReason = AtomicReference(GenerationStopReason.NONE)
            isBusyGenerating.set(true)
            runtimeState.value = SlmRuntimeState.Generating(
                modelName = modelFile.name,
                activeBackend = activeBackend,
            )

            val rawBuilder = StringBuilder()
            var lastEmitted = ""
            try {
                val completedInTime = withTimeoutOrNull(safeTimeoutMs) {
                    conv.sendMessageAsync(effectivePrompt).collect { chunk ->
                        if (!isActive || !isBusyGenerating.get()) {
                            if (stopReason.get() == GenerationStopReason.NONE) {
                                stopReason.set(GenerationStopReason.USER_CANCELLED)
                            }
                            throw CancellationException("تولید متوقف شد")
                        }
                        rawBuilder.append(chunk.toString())
                        val cleanSoFar = stripThinkingTags(rawBuilder.toString(), hideUnclosedThinkWhileStreaming = true)
                        if (cleanSoFar.isNotBlank() && cleanSoFar != lastEmitted) {
                            lastEmitted = cleanSoFar
                            onPartialCleanText(cleanSoFar)
                        }
                        if (estimateTokenCount(cleanSoFar) >= safeMaxTokens) {
                            stopReason.set(GenerationStopReason.TOKEN_LIMIT)
                            runCatching { conv.cancelProcess() }
                            throw CancellationException("سقف توکن پاسخ")
                        }
                    }
                    true
                }
                if (completedInTime == null) {
                    stopReason.set(GenerationStopReason.TIMEOUT)
                    runCatching { conv.cancelProcess() }
                }

                val finalClean = stripThinkingTags(rawBuilder.toString(), hideUnclosedThinkWhileStreaming = false)
                when (stopReason.get()) {
                    GenerationStopReason.TIMEOUT -> {
                        if (finalClean.isNotBlank()) Result.success(finalClean)
                        else Result.failure(IllegalStateException("زمان پردازش مدل زبانی به پایان رسید."))
                    }
                    GenerationStopReason.USER_CANCELLED -> {
                        if (finalClean.isNotBlank()) Result.success("$finalClean\n(توقف توسط کاربر)")
                        else Result.failure(CancellationException("تولید توسط کاربر متوقف شد"))
                    }
                    GenerationStopReason.TOKEN_LIMIT, GenerationStopReason.NONE -> {
                        if (finalClean.isBlank()) {
                            Result.failure(IllegalStateException("مدل خروجی معتبری تولید نکرد؛ دوباره امتحان کنید یا گفت‌وگو را تازه کنید."))
                        } else {
                            Result.success(finalClean)
                        }
                    }
                }
            } catch (ce: CancellationException) {
                val partial = stripThinkingTags(rawBuilder.toString(), hideUnclosedThinkWhileStreaming = false)
                when (stopReason.get()) {
                    GenerationStopReason.TOKEN_LIMIT -> {
                        if (partial.isNotBlank()) Result.success(partial)
                        else Result.failure(IllegalStateException("مدل خروجی معتبری تولید نکرد."))
                    }
                    GenerationStopReason.TIMEOUT -> {
                        if (partial.isNotBlank()) Result.success(partial)
                        else Result.failure(IllegalStateException("زمان پردازش مدل زبانی به پایان رسید."))
                    }
                    else -> {
                        if (partial.isNotBlank()) Result.success("$partial\n(توقف توسط کاربر)")
                        else Result.failure(ce)
                    }
                }
            } catch (t: Throwable) {
                Result.failure(IllegalStateException("خطا هنگام پردازش مدل زبانی: ${t.localizedMessage ?: "نامشخص"}", t))
            } finally {
                isBusyGenerating.set(false)
                refreshRuntimeStatus(loadedModelPath)
            }
        }
    }

    suspend fun proposeIntent(
        userUtterance: String,
        preferredPath: String,
        preferGpu: Boolean,
        timeoutMs: Long = LocalSlmEngine.INTENT_PROPOSAL_TIMEOUT_MS,
    ): Result<SlmIntentProposal?> {
        val sysPrompt = SlmPromptBuilder.buildIntentProposalSystemInstruction()
        val firstRes = generateReplyStream(
            userPrompt = SlmPromptBuilder.buildIntentProposalPrompt(userUtterance),
            systemInstruction = sysPrompt,
            preferredPath = preferredPath,
            preferGpu = preferGpu,
            maxNewTokens = LocalSlmEngine.DEFAULT_MAX_NEW_TOKENS,
            timeoutMs = timeoutMs,
            forceFreshConversation = true,
            onPartialCleanText = {},
        )
        if (firstRes.isFailure) {
            return Result.failure(firstRes.exceptionOrNull() ?: IllegalStateException("خطای پیشنهاد فرمان"))
        }
        val firstParsed = SlmIntentProposalValidator.parseOrNull(firstRes.getOrNull().orEmpty())
        if (firstParsed != null) {
            resetConversation()
            return Result.success(firstParsed)
        }

        val retryRes = generateReplyStream(
            userPrompt = SlmPromptBuilder.buildIntentProposalRetryPrompt(userUtterance),
            systemInstruction = sysPrompt,
            preferredPath = preferredPath,
            preferGpu = preferGpu,
            maxNewTokens = LocalSlmEngine.DEFAULT_MAX_NEW_TOKENS,
            timeoutMs = timeoutMs,
            forceFreshConversation = true,
            onPartialCleanText = {},
        )
        resetConversation()
        if (retryRes.isFailure) {
            return Result.success(null)
        }
        val secondParsed = SlmIntentProposalValidator.parseOrNull(retryRes.getOrNull().orEmpty())
        return Result.success(secondParsed)
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

    suspend fun unloadBlocking() = withContext(slmDispatcher) {
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
        val file = modelStore.findActiveModelFile(preferredPath)
        if (file == null) {
            releaseLocked("")
            runtimeState.value = SlmRuntimeState.NotInstalled
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

        releaseLocked("")

        val requiredRamMb = SlmModelCatalog.minRequiredRamForFile(file.name)
        val prepRes = beforeModelLoad(requiredRamMb)
        if (prepRes.isFailure) {
            val availNow = modelStore.readAvailableRamMb()
            val errMsg = prepRes.exceptionOrNull()?.localizedMessage
                ?: "آماده‌سازی RAM برای مدل زبانی ممکن نشد."
            runtimeState.value = SlmRuntimeState.LowRamBlocked(
                modelName = file.name,
                requiredRamMb = requiredRamMb,
                availableRamMb = availNow,
            )
            return Result.failure(IllegalStateException(errMsg, prepRes.exceptionOrNull()))
        }

        val availRamMb = modelStore.readAvailableRamMb()
        if (!ModelStore.hasEnoughRamForModel(availRamMb, requiredRamMb)) {
            runtimeState.value = SlmRuntimeState.LowRamBlocked(
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
        runtimeState.value = SlmRuntimeState.Loading(
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
            }
        }

        if (initializedEngine == null) {
            val msg = "راه‌اندازی مدل LiteRT-LM ناموفق بود: ${firstError?.localizedMessage ?: "خطای حافظه یا فرمت فایل"}"
            runtimeState.value = SlmRuntimeState.Error(msg, hasInstalledModel = true)
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
            runtimeState.value = SlmRuntimeState.Error(msg, hasInstalledModel = true)
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
                    topK = 20,
                    topP = 0.85,
                    temperature = 0.45,
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

    companion object {
        private const val MAX_ALLOWED_NEW_TOKENS = 320
        private const val MAX_ALLOWED_TIMEOUT_MS = 60_000L

        private val COMPLETED_THINK_REGEX = Regex("(?is)<think>.*?</think>")
        private val EXCESS_NEWLINES_REGEX = Regex("\\n{3,}")
        private val TOKEN_SPLIT_REGEX = Regex("\\s+")

        fun estimateTokenCount(text: String): Int {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return 0
            val wordUnits = trimmed.split(TOKEN_SPLIT_REGEX).size
            val charEstimate = (trimmed.length + 2) / 3
            return maxOf(wordUnits, charEstimate)
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
