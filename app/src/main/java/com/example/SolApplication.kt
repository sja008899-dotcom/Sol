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
import com.example.sol.tasks.ConfirmationManager
import com.example.sol.tasks.TaskManager
import com.example.sol.voice.PersianTTS
import com.example.sol.voice.SolAudioManager
import com.example.sol.voice.VoiceEngine

class SolAppContainer(private val application: Application) {
    val database: MemoryDatabase by lazy { MemoryDatabase.getInstance(application) }
    val dao by lazy { database.memoryDao() }

    val memoryRepository by lazy { MemoryRepository(dao) }
    val personRepository by lazy { PersonRepository(dao) }
    val locationRepository by lazy { LocationRepository(dao) }
    val historyRepository by lazy { HistoryRepository(dao) }
    val knowledgeRepository by lazy { KnowledgeRepository(dao) }
    val noteTodoRepository by lazy { NoteTodoRepository(dao) }
    val preferencesRepository by lazy { PreferencesRepository(application) }

    val memoryOptimizer by lazy { SolMemoryOptimizer(application) }

    val audioManager by lazy { SolAudioManager(application) }
    val persianTts by lazy { PersianTTS(application, audioManager) }
    val ttsManager by lazy { com.example.sol.tts.TtsManager(application, persianTts, audioManager) }
    val speakerIdManager by lazy { com.example.sol.speaker.SpeakerIdManager(application) }
    val voiceEngine by lazy {
        VoiceEngine(
            context = application,
            audioManager = audioManager,
            persianTts = persianTts,
            isExternalSpeaking = { ttsManager.isPiperSpeaking.value },
            onStopExternalSpeech = { ttsManager.stop() },
        )
    }

    val deviceController by lazy { DeviceController(application, audioManager) }
    val contactManager by lazy { ContactManager(application, personRepository) }

    val callScriptManager by lazy { CallScriptManager() }
    val callManager by lazy { CallManager(application) }
    val messageManager by lazy { MessageManager(application) }

    // Heavy parsers, RAG, SLM, exact alarms, and crypto backups are lazy-loaded for 4GB RAM efficiency
    val bookWebFetcher by lazy { BookWebFetcher(application) }
    val webSearchClient by lazy { com.example.sol.network.WebSearchClient() }
    val bookRagEngine by lazy { BookRagEngine(knowledgeRepository) }
    val localSlmEngine by lazy { LocalSlmEngine(application) }
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
}

class SolApplication : Application() {
    lateinit var container: SolAppContainer
        private set

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
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::container.isInitialized) {
            container.memoryOptimizer.handleTrimMemory(
                level = level,
                evictRagCache = { container.bookRagEngine.evictCache() },
                unloadVoskIfIdle = { container.voiceEngine.voskEngine.unloadModelFromRam() },
                releaseSlmCache = { container.localSlmEngine.releaseSessionMemory() },
            )
        }
    }
}
