package com.example

import android.app.Application
import android.os.StrictMode
import com.example.sol.background.BackgroundTaskCoordinator
import com.example.sol.background.SolNotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.sol.brain.CommandBrain
import com.example.sol.brain.EntityExtractor
import com.example.sol.brain.IntentParser
import com.example.sol.brain.LearningEngine
import com.example.sol.calendar.ExactAlarmScheduler
import com.example.sol.communication.CallManager
import com.example.sol.communication.CallScriptManager
import com.example.sol.communication.MessageManager
import com.example.sol.contacts.ContactManager
import com.example.sol.device.DeviceController
import com.example.sol.knowledge.BookRagEngine
import com.example.sol.knowledge.BookWebFetcher
import com.example.sol.knowledge.LocalSlmEngine
import com.example.sol.memory.HistoryRepository
import com.example.sol.memory.KnowledgeRepository
import com.example.sol.memory.LocationRepository
import com.example.sol.memory.MemoryDatabase
import com.example.sol.memory.MemoryRepository
import com.example.sol.memory.NoteTodoRepository
import com.example.sol.memory.PersonRepository
import com.example.sol.memory.PreferencesRepository
import com.example.sol.security.EncryptedBackupManager
import com.example.sol.security.PermissionManager
import com.example.sol.security.PrivacyManager
import com.example.sol.security.SolMemoryOptimizer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.app.ActivityManager
import android.content.Context
import com.example.sol.network.DuckDuckGoSearchProvider
import com.example.sol.network.SolHttpClient
import com.example.sol.network.WebSearchClient
import com.example.sol.runtime.ModelAcquireResult
import com.example.sol.runtime.ModelResourceCoordinator
import com.example.sol.runtime.SolModelKind
import com.example.sol.runtime.SolModelResource
import com.example.sol.runtime.SolNetworkPolicy
import com.example.sol.runtime.SolNetworkPolicyCoordinator
import com.example.sol.tasks.ConfirmationManager
import com.example.sol.tasks.TaskManager
import com.example.sol.voice.SolAudioManager
import com.example.sol.voice.VoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SolAppContainer(private val application: Application) {
    val database: MemoryDatabase by lazy { MemoryDatabase.getInstance(application) }
    val dao by lazy { database.memoryDao() }

    val memoryRepository by lazy { MemoryRepository(dao) }
    val personRepository by lazy { PersonRepository(dao) }
    val locationRepository by lazy { LocationRepository(dao) }
    val historyRepository by lazy { HistoryRepository(dao) }
    val knowledgeRepository by lazy { KnowledgeRepository(dao) }
    val noteTodoRepository by lazy { NoteTodoRepository(dao) }
    val preferencesRepository: PreferencesRepository by lazy { PreferencesRepository(application) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val memoryOptimizer: SolMemoryOptimizer by lazy { SolMemoryOptimizer(application) }

    val networkPolicyCoordinator: SolNetworkPolicyCoordinator by lazy {
        SolNetworkPolicyCoordinator(
            initialPolicy = SolNetworkPolicy.USER_APPROVED_NETWORK,
        ).also { coord ->
            appScope.launch {
                val snap = runCatching { preferencesRepository.getSnapshot() }.getOrNull()
                if (snap != null) {
                    coord.setPolicy(
                        if (snap.offlineStrictMode) SolNetworkPolicy.OFFLINE_STRICT
                        else SolNetworkPolicy.USER_APPROVED_NETWORK
                    )
                }
                preferencesRepository.preferences.collect { prefs ->
                    coord.setPolicy(
                        if (prefs.offlineStrictMode) SolNetworkPolicy.OFFLINE_STRICT
                        else SolNetworkPolicy.USER_APPROVED_NETWORK
                    )
                }
            }
        }
    }

    val httpClient: SolHttpClient by lazy {
        SolHttpClient(networkPolicyCoordinator = networkPolicyCoordinator)
    }

    val modelResourceCoordinator: ModelResourceCoordinator by lazy {
        ModelResourceCoordinator(
            availableRamMbProvider = {
                val am = application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val info = ActivityManager.MemoryInfo()
                if (am != null) {
                    am.getMemoryInfo(info)
                    info.availMem / (1024L * 1024L)
                } else {
                    -1L
                }
            },
        ).apply {
            register(object : SolModelResource {
                override val kind = SolModelKind.VOSK_ASR
                override val minRequiredRamMb = 250L
                override fun isLoaded(): Boolean = voskEngine.isModelLoadedInRam()
                override fun isBusy(): Boolean = voskEngine.isBusy()
                override suspend fun load(): Result<Unit> = Result.success(Unit)
                override suspend fun unload() = voskEngine.unloadModelFromRam("آزادسازی خودکار Vosk توسط هماهنگ‌کننده حافظه")
            })
            register(object : SolModelResource {
                override val kind = SolModelKind.PIPER_TTS
                override val minRequiredRamMb = 220L
                override fun isLoaded(): Boolean = ttsManager.isModelLoadedInRam()
                override fun isBusy(): Boolean = ttsManager.isBusy()
                override suspend fun load(): Result<Unit> =
                    if (ttsManager.refreshInstalledPiper()) Result.success(Unit)
                    else Result.failure(IllegalStateException("مدل Piper نصب نیست."))
                override suspend fun unload() = ttsManager.unloadFromRamIfIdle()
            })
            register(object : SolModelResource {
                override val kind = SolModelKind.SPEAKER_ID
                override val minRequiredRamMb = 180L
                override fun isLoaded(): Boolean = speakerIdManager.embedder.isReady
                override fun isBusy(): Boolean = speakerIdManager.isBusy()
                override suspend fun load(): Result<Unit> = Result.success(Unit)
                override suspend fun unload() = speakerIdManager.unloadModelIfIdle()
            })
            register(object : SolModelResource {
                override val kind = SolModelKind.LOCAL_SLM
                override val minRequiredRamMb = 1_100L
                override fun isLoaded(): Boolean = localSlmEngine.isLoadedInRam()
                override fun isBusy(): Boolean = localSlmEngine.isGenerating()
                override suspend fun load(): Result<Unit> = Result.success(Unit)
                override suspend fun unload() {
                    localSlmEngine.unloadBlockingIfIdle()
                }
            })
        }
    }

    val micArbiter: com.example.sol.voice.MicArbiter by lazy {
        com.example.sol.voice.MicArbiter(
            pauseForegroundMic = {
                com.example.sol.voice.MicForegroundService.pauseAndAwaitRelease(application)
            },
            resumeForegroundMic = {
                val shouldResume = preferencesRepository.getSnapshot().screenOffMicEnabled &&
                    voskEngine.isModelInstalled() &&
                    com.example.sol.voice.MicForegroundService.isPaused.value
                if (shouldResume) {
                    com.example.sol.voice.MicForegroundService.resume(application)
                }
            },
        )
    }

    val voskEngine: com.example.sol.voice.VoskVoiceEngine by lazy {
        com.example.sol.voice.VoskVoiceEngine(
            context = application,
            httpClient = httpClient,
            trustedSha256Provider = { preferencesRepository.getSnapshot().trustedVoskSha256.ifBlank { null } },
            onTofuSha256Pinned = { sha, bytes -> preferencesRepository.setTrustedVoskModel(sha, bytes) },
            onClearTofuPin = { preferencesRepository.clearTrustedVoskModel() },
            micArbiter = micArbiter,
        )
    }

    val audioManager: SolAudioManager by lazy { SolAudioManager(application) }
    val ttsManager: com.example.sol.tts.TtsManager by lazy {
        com.example.sol.tts.TtsManager(
            context = application,
            audioManager = audioManager,
            httpClient = httpClient,
            trustedCustomHashesProvider = {
                val p = preferencesRepository.getSnapshot()
                if (p.trustedPiperOnnxSha256.isNotBlank() && p.trustedPiperConfigSha256.isNotBlank()) {
                    p.trustedPiperOnnxSha256 to p.trustedPiperConfigSha256
                } else {
                    null
                }
            },
            onSaveTrustedCustomHashes = { onnxSha, cfgSha ->
                preferencesRepository.setTrustedPiperHashes(onnxSha, cfgSha)
            },
            onClearTrustedCustomHashes = {
                preferencesRepository.clearTrustedPiperHashes()
            },
        )
    }
    val speakerIdManager: com.example.sol.speaker.SpeakerIdManager by lazy {
        com.example.sol.speaker.SpeakerIdManager(
            context = application,
            micArbiter = micArbiter,
            httpClient = httpClient,
        )
    }
    val voiceEngine: VoiceEngine by lazy {
        VoiceEngine(
            context = application,
            voskEngine = voskEngine,
            audioManager = audioManager,
            isExternalSpeaking = { ttsManager.isPiperSpeaking.value },
            onStopExternalSpeech = { ttsManager.stop() },
            micArbiter = micArbiter,
            isOfflineStrict = { networkPolicyCoordinator.isOfflineStrict() },
        )
    }

    val deviceController by lazy { DeviceController(application, audioManager) }
    val contactManager by lazy { ContactManager(application, personRepository) }

    val callScriptManager by lazy { CallScriptManager() }
    val callManager by lazy { CallManager(application) }
    val messageManager by lazy { MessageManager(application) }

    // Heavy parsers, RAG, SLM, exact alarms, and crypto backups are lazy-loaded for 4GB RAM efficiency
    val bookWebFetcher by lazy { BookWebFetcher(application, httpClient = httpClient) }
    val webSearchClient by lazy { WebSearchClient(DuckDuckGoSearchProvider(httpClient)) }
    val bookRagEngine by lazy { BookRagEngine(knowledgeRepository) }
    val localSlmEngine by lazy {
        LocalSlmEngine(
            context = application,
            httpClient = httpClient,
            beforeModelLoad = { requiredRamMb ->
                when (val prep = modelResourceCoordinator.prepareBeforeSlmLoad(requiredRamMb)) {
                    is ModelAcquireResult.Acquired -> Result.success(Unit)
                    is ModelAcquireResult.Busy -> Result.failure(IllegalStateException(prep.messageFa))
                    is ModelAcquireResult.InsufficientMemory -> Result.failure(IllegalStateException(prep.messageFa))
                    is ModelAcquireResult.Failed -> Result.failure(IllegalStateException(prep.reasonFa))
                }
            },
        )
    }
    val exactAlarmScheduler by lazy { ExactAlarmScheduler(application, knowledgeRepository, preferencesRepository) }
    val timerController by lazy { com.example.sol.calendar.TimerController(application, exactAlarmScheduler, knowledgeRepository) }
    val encryptedBackupManager by lazy {
        EncryptedBackupManager(
            context = application,
            database = database,
            memoryRepository = memoryRepository,
            personRepository = personRepository,
            locationRepository = locationRepository,
            knowledgeRepository = knowledgeRepository,
            preferencesRepository = preferencesRepository,
            noteTodoRepository = noteTodoRepository,
        )
    }

    val backgroundTaskCoordinator by lazy {
        BackgroundTaskCoordinator(application, knowledgeRepository, exactAlarmScheduler)
    }

    val learningEngine by lazy { LearningEngine(memoryRepository, personRepository, locationRepository) }
    val userUtteranceRepository by lazy { com.example.sol.memory.UserUtteranceRepository(dao) }
    val colloquialLexicon by lazy { com.example.sol.text.ColloquialLexicon.fromContext(application) }
    val entityExtractor by lazy { EntityExtractor() }
    val intentParser by lazy { IntentParser(entityExtractor, colloquialLexicon) }
    val nluConfig by lazy { com.example.sol.brain.NluConfig() }
    val statisticalClassifier by lazy {
        com.example.sol.brain.StatisticalIntentClassifier(
            context = application,
            config = nluConfig,
            lexicon = colloquialLexicon,
        )
    }
    val hybridNluEngine by lazy {
        com.example.sol.brain.HybridNluEngine(
            intentParser = intentParser,
            statisticalClassifier = statisticalClassifier,
            extractor = entityExtractor,
            lexicon = colloquialLexicon,
            config = nluConfig,
        )
    }
    val skillRegistry: com.example.sol.brain.skills.SkillRegistry by lazy {
        val consultSkill = com.example.sol.brain.skills.ConsultRoleBookSkill(
            memoryRepository = memoryRepository,
            preferencesRepository = preferencesRepository,
            bookRagEngine = bookRagEngine,
            localSlmEngine = localSlmEngine,
        )
        com.example.sol.brain.skills.SkillRegistry(
            context = application,
            preferencesRepository = preferencesRepository,
            openPermissionSettings = { deviceController.settingsController.openAppDetailsSettings() },
            skills = listOf(
                com.example.sol.brain.skills.GreetingSkill(knowledgeRepository),
                com.example.sol.brain.skills.IdentitySkill(knowledgeRepository),
                com.example.sol.brain.skills.CapabilitiesSkill { capabilitiesCatalog.size },
                com.example.sol.brain.skills.HelpSkill(),
                com.example.sol.brain.skills.AliasMemorySkill(learningEngine),
                com.example.sol.brain.skills.FactMemorySkill(
                    learningEngine = learningEngine,
                    memoryRepository = memoryRepository,
                    locationRepository = locationRepository,
                    knowledgeRepository = knowledgeRepository,
                    preferencesRepository = preferencesRepository,
                ),
                com.example.sol.brain.skills.PersonMemorySkill(learningEngine),
                com.example.sol.brain.skills.LocationMemorySkill(
                    context = application,
                    learningEngine = learningEngine,
                    locationRepository = locationRepository,
                    knowledgeRepository = knowledgeRepository,
                    preferencesRepository = preferencesRepository,
                ),
                com.example.sol.brain.skills.TimeDateQaSkill(),
                com.example.sol.brain.skills.ReminderAlarmSkill(backgroundTaskCoordinator, exactAlarmScheduler),
                com.example.sol.brain.skills.BackgroundControlSkill(backgroundTaskCoordinator),
                com.example.sol.brain.skills.WebBookRoleSkill(),
                com.example.sol.brain.skills.WebSearchSkill(),
                com.example.sol.brain.skills.TeachRoleVoiceSkill(application, knowledgeRepository),
                consultSkill,
                com.example.sol.brain.skills.CallSkill(learningEngine),
                com.example.sol.brain.skills.SmsAndNotifySkill(learningEngine),
                com.example.sol.brain.skills.DeviceStatusSkill(deviceController),
                com.example.sol.brain.skills.VolumeControlSkill(deviceController),
                com.example.sol.brain.skills.OpenSettingsSkill(deviceController),
                com.example.sol.brain.skills.OpenAppSkill(deviceController),
                com.example.sol.brain.skills.RoutineSkill(deviceController),
                com.example.sol.brain.skills.TimerStopwatchSkill(timerController),
                com.example.sol.brain.skills.FlashlightSkill(deviceController),
                com.example.sol.brain.skills.CalendarEventSkill(deviceController),
                com.example.sol.brain.skills.CalculatorSkill(),
                com.example.sol.brain.skills.NotesTodoSkill(noteTodoRepository),
                com.example.sol.brain.skills.MediaControlSkill(deviceController),
                com.example.sol.brain.skills.UnknownFallbackSkill(
                    learningEngine = learningEngine,
                    memoryRepository = memoryRepository,
                    locationRepository = locationRepository,
                    bookRagEngine = bookRagEngine,
                    localSlmEngine = localSlmEngine,
                    consultSkill = consultSkill,
                ),
            ),
        )
    }
    val capabilitiesCatalog: List<com.example.sol.knowledge.SolCapabilityItem> by lazy {
        com.example.sol.knowledge.SolCapabilitiesCatalog.buildCatalog(skillRegistry)
    }
    val dialogueManager by lazy {
        com.example.sol.dialogue.DialogueManager(
            hybridNlu = hybridNluEngine,
            statisticalClassifier = statisticalClassifier,
            userUtteranceRepository = userUtteranceRepository,
            contactManager = contactManager,
            skillRegistry = skillRegistry,
            extractor = entityExtractor,
        )
    }
    val commandBrain by lazy { CommandBrain(dialogueManager, speakerIdManager) }

    val confirmationManager by lazy { ConfirmationManager() }
    val taskManager by lazy {
        TaskManager(
            contactManager = contactManager,
            locationRepository = locationRepository,
            preferencesRepository = preferencesRepository,
            callScriptManager = callScriptManager,
            callManager = callManager,
            messageManager = messageManager,
            historyRepository = historyRepository,
            confirmationManager = confirmationManager,
        )
    }

    val permissionManager by lazy { PermissionManager(application) }
    val privacyManager by lazy { PrivacyManager(permissionManager) }
    val uiBridge by lazy { com.example.sol.ui.vm.SolUiBridge() }

    suspend fun refreshVocabularyBias(): Int = withContext(Dispatchers.IO) {
        val terms = buildList {
            memoryRepository.getAllOnce().forEach { m ->
                add(m.keyText.removePrefix("alias:"))
                add(m.valueText)
            }
            personRepository.getAllOnce().forEach { p -> add(p.name) }
            locationRepository.getAllOnce().forEach { l ->
                add(l.alias)
                add(l.fullName)
            }
            knowledgeRepository.getAllBooksOnce().forEach { b -> add(b.title) }
        }
        voiceEngine.updateVocabularyBias(terms)
        voiceEngine.voskEngine.vocabularyBias.currentTermsCount()
    }

    fun hasInternetConnection(): Boolean {
        val cm = application.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Self-contained voice bootstrap: whenever internet is reachable, automatically
     * downloads and verifies Sol's internal Piper Persian TTS, CAM++ speaker embedder,
     * and Vosk Persian offline ASR without requiring user file hunting or phone TTS engines.
     */
    suspend fun autoEnsureEssentialModelsIfOnline(
        forceRetryOnError: Boolean = false,
    ): Unit = withContext(Dispatchers.IO) {
        val prefs = preferencesRepository.getSnapshot()
        networkPolicyCoordinator.setPolicy(
            if (prefs.offlineStrictMode) SolNetworkPolicy.OFFLINE_STRICT
            else SolNetworkPolicy.USER_APPROVED_NETWORK
        )
        if (networkPolicyCoordinator.isOfflineStrict()) {
            if (forceRetryOnError) {
                uiBridge.postBanner("حالت آفلاین سخت‌گیرانه فعال است؛ هیچ دانلود خودکاری انجام نشد.")
            }
            return@withContext
        }
        if (!hasInternetConnection()) {
            if (forceRetryOnError) {
                uiBridge.postBanner("اینترنت متصل نیست؛ پس از اتصال، مدل‌های صوتی به‌صورت خودکار دانلود می‌شوند.")
            }
            return@withContext
        }

        val needsPiper = !ttsManager.isInstalledAndReady &&
            (forceRetryOnError || ttsManager.piperStatus.value is com.example.sol.tts.PiperTtsStatus.NotInstalled)
        if (needsPiper && ttsManager.piperStatus.value !is com.example.sol.tts.PiperTtsStatus.Downloading) {
            ttsManager.downloadOfficialPiperModelSync()
            uiBridge.triggerRefreshSnapshots()
        }

        val speakerState = speakerIdManager.uiState.value
        val needsSpeaker = !speakerIdManager.isModelCompatible() && !speakerState.isDownloadingModel
        if (needsSpeaker) {
            speakerIdManager.ensureAndDownloadSpeakerModel()
            uiBridge.triggerRefreshSnapshots()
        }

        val needsVosk = !voskEngine.isModelInstalled() &&
            (forceRetryOnError || voskEngine.modelStatus.value is com.example.sol.voice.VoskModelStatus.NotInstalled)
        if (needsVosk && voskEngine.modelStatus.value !is com.example.sol.voice.VoskModelStatus.Downloading) {
            voskEngine.downloadPersianSmallModelSync(allowHttpsWithoutPinnedHash = true)
            voiceEngine.refreshBackend(preferencesRepository.getSnapshot().voskOfflineEnabled)
            uiBridge.triggerRefreshSnapshots()
        }
    }
}

class SolApplication : Application() {
    lateinit var container: SolAppContainer
        private set

    fun isContainerInitialized(): Boolean = ::container.isInitialized

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
        }
        SolNotificationHelper.ensureChannels(this)
        container = SolAppContainer(this)
        container.networkPolicyCoordinator
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::container.isInitialized) {
            container.memoryOptimizer.handleTrimMemory(
                level = level,
                evictRagCache = { container.bookRagEngine.evictCache() },
                unloadVoskIfIdle = { container.voiceEngine.voskEngine.unloadModelFromRam() },
                releaseSlmCache = { container.localSlmEngine.releaseSessionMemory() },
                unloadPiperIfIdle = { container.ttsManager.unloadFromRamIfIdle() },
            )
            CoroutineScope(Dispatchers.IO).launch {
                container.modelResourceCoordinator.trimOnMemoryPressure(level)
            }
        }
    }
}
