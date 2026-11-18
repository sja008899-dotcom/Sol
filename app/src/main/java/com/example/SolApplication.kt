package com.example

import android.app.Application
import com.example.sol.background.BackgroundTaskCoordinator
import com.example.sol.background.SolNotificationHelper
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
    val preferencesRepository by lazy { PreferencesRepository(application) }

    val memoryOptimizer by lazy { SolMemoryOptimizer(application) }

    val audioManager by lazy { SolAudioManager(application) }
    val voiceEngine by lazy { VoiceEngine(application) }
    val persianTts by lazy { PersianTTS(application) }

    val deviceController by lazy { DeviceController(application, audioManager) }
    val contactManager by lazy { ContactManager(application, personRepository) }

    val callScriptManager by lazy { CallScriptManager() }
    val callManager by lazy { CallManager(application) }
    val messageManager by lazy { MessageManager(application) }

    // Heavy parsers, RAG, SLM, exact alarms, and crypto backups are lazy-loaded for 4GB RAM efficiency
    val bookWebFetcher by lazy { BookWebFetcher(application) }
    val bookRagEngine by lazy { BookRagEngine(knowledgeRepository) }
    val localSlmEngine by lazy { LocalSlmEngine(application) }
    val exactAlarmScheduler by lazy { ExactAlarmScheduler(application, knowledgeRepository) }
    val encryptedBackupManager by lazy {
        EncryptedBackupManager(
            context = application,
            memoryRepository = memoryRepository,
            personRepository = personRepository,
            locationRepository = locationRepository,
            knowledgeRepository = knowledgeRepository,
            preferencesRepository = preferencesRepository,
        )
    }

    val backgroundTaskCoordinator by lazy { BackgroundTaskCoordinator(application, knowledgeRepository) }

    val learningEngine by lazy { LearningEngine(memoryRepository, personRepository, locationRepository) }
    val intentParser by lazy { IntentParser(EntityExtractor()) }
    val commandBrain by lazy {
        CommandBrain(
            context = application,
            parser = intentParser,
            learningEngine = learningEngine,
            memoryRepository = memoryRepository,
            locationRepository = locationRepository,
            knowledgeRepository = knowledgeRepository,
            preferencesRepository = preferencesRepository,
            deviceController = deviceController,
            backgroundCoordinator = backgroundTaskCoordinator,
            bookRagEngine = bookRagEngine,
            localSlmEngine = localSlmEngine,
            exactAlarmScheduler = exactAlarmScheduler,
        )
    }

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
}

class SolApplication : Application() {
    lateinit var container: SolAppContainer
        private set

    override fun onCreate() {
        super.onCreate()
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

    override fun onTerminate() {
        if (::container.isInitialized) {
            container.persianTts.shutdown()
            container.voiceEngine.stopListening()
        }
        super.onTerminate()
    }
}
