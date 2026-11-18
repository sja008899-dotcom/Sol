package com.example

import android.app.Application
import com.example.sol.background.BackgroundTaskCoordinator
import com.example.sol.background.SolNotificationHelper
import com.example.sol.brain.CommandBrain
import com.example.sol.brain.EntityExtractor
import com.example.sol.brain.IntentParser
import com.example.sol.brain.LearningEngine
import com.example.sol.communication.CallManager
import com.example.sol.communication.CallScriptManager
import com.example.sol.communication.MessageManager
import com.example.sol.contacts.ContactManager
import com.example.sol.device.DeviceController
import com.example.sol.knowledge.BookWebFetcher
import com.example.sol.memory.HistoryRepository
import com.example.sol.memory.KnowledgeRepository
import com.example.sol.memory.LocationRepository
import com.example.sol.memory.MemoryDatabase
import com.example.sol.memory.MemoryRepository
import com.example.sol.memory.PersonRepository
import com.example.sol.memory.PreferencesRepository
import com.example.sol.security.PermissionManager
import com.example.sol.security.PrivacyManager
import com.example.sol.tasks.ConfirmationManager
import com.example.sol.tasks.TaskManager
import com.example.sol.voice.PersianTTS
import com.example.sol.voice.SolAudioManager
import com.example.sol.voice.VoiceEngine

class SolAppContainer(application: Application) {
    val database = MemoryDatabase.getInstance(application)
    val dao = database.memoryDao()

    val memoryRepository = MemoryRepository(dao)
    val personRepository = PersonRepository(dao)
    val locationRepository = LocationRepository(dao)
    val historyRepository = HistoryRepository(dao)
    val knowledgeRepository = KnowledgeRepository(dao)
    val preferencesRepository = PreferencesRepository(application)

    val audioManager = SolAudioManager(application)
    val voiceEngine = VoiceEngine(application)
    val persianTts = PersianTTS(application)

    val deviceController = DeviceController(application, audioManager)
    val contactManager = ContactManager(application, personRepository)

    val callScriptManager = CallScriptManager()
    val callManager = CallManager(application)
    val messageManager = MessageManager(application)

    val bookWebFetcher = BookWebFetcher(application)
    val backgroundTaskCoordinator = BackgroundTaskCoordinator(application, knowledgeRepository)

    val learningEngine = LearningEngine(memoryRepository, personRepository, locationRepository)
    val intentParser = IntentParser(EntityExtractor())
    val commandBrain = CommandBrain(
        parser = intentParser,
        learningEngine = learningEngine,
        memoryRepository = memoryRepository,
        locationRepository = locationRepository,
        knowledgeRepository = knowledgeRepository,
        preferencesRepository = preferencesRepository,
        deviceController = deviceController,
        backgroundCoordinator = backgroundTaskCoordinator,
    )

    val confirmationManager = ConfirmationManager()
    val taskManager = TaskManager(
        contactManager = contactManager,
        locationRepository = locationRepository,
        preferencesRepository = preferencesRepository,
        callScriptManager = callScriptManager,
        callManager = callManager,
        messageManager = messageManager,
        historyRepository = historyRepository,
        confirmationManager = confirmationManager,
    )

    val permissionManager = PermissionManager(application)
    val privacyManager = PrivacyManager(permissionManager)
}

class SolApplication : Application() {
    lateinit var container: SolAppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        SolNotificationHelper.ensureChannels(this)
        container = SolAppContainer(this)
    }

    override fun onTerminate() {
        container.persianTts.shutdown()
        super.onTerminate()
    }
}
