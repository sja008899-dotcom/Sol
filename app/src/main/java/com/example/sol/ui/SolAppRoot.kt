package com.example.sol.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.sol.calendar.JalaliCalendar
import com.example.sol.tasks.CommunicationChannel
import com.example.sol.ui.vm.BackupViewModel
import com.example.sol.ui.vm.ChatViewModel
import com.example.sol.ui.vm.ContactsViewModel
import com.example.sol.ui.vm.HistoryViewModel
import com.example.sol.ui.vm.MemoryViewModel
import com.example.sol.ui.vm.SettingsViewModel
import com.example.sol.ui.vm.SlmViewModel
import com.example.sol.voice.VoiceErrorAction
import com.example.sol.voice.VoiceListeningState
import com.example.ui.theme.SolTheme
import kotlinx.coroutines.launch

const val ROUTE_HOME = "home"
const val ROUTE_MEMORY = "memory"
const val ROUTE_CONTACTS = "contacts"
const val ROUTE_HISTORY = "history"
const val ROUTE_SETTINGS = "settings"

private data class SolNavItem(
    val route: String,
    val labelResId: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val NAV_ITEMS = listOf(
    SolNavItem(ROUTE_HOME, R.string.nav_home, Icons.Filled.Mic, Icons.Outlined.Mic),
    SolNavItem(ROUTE_MEMORY, R.string.nav_memory, Icons.Filled.Psychology, Icons.Outlined.Psychology),
    SolNavItem(ROUTE_CONTACTS, R.string.nav_contacts, Icons.Filled.Contacts, Icons.Outlined.Contacts),
    SolNavItem(ROUTE_HISTORY, R.string.nav_history, Icons.Filled.History, Icons.Outlined.History),
    SolNavItem(ROUTE_SETTINGS, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun SolAppRoot(
    viewModel: SolViewModel,
    externalVoiceTriggerNonce: Int = 0,
) {
    val chatViewModel: ChatViewModel = viewModel()
    val memoryViewModel: MemoryViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()
    val backupViewModel: BackupViewModel = viewModel()
    val slmViewModel: SlmViewModel = viewModel()

    val context = LocalContext.current
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val showCapabilitiesSheet by viewModel.showCapabilitiesSheet.collectAsStateWithLifecycle()
    val statusBannerMessage by viewModel.statusBannerMessage.collectAsStateWithLifecycle()
    val hasMicPermission by viewModel.hasMicPermission.collectAsStateWithLifecycle()
    val hasContactsPermission by viewModel.hasContactsPermission.collectAsStateWithLifecycle()
    val hasNotificationPermission by viewModel.hasNotificationPermission.collectAsStateWithLifecycle()

    // Lightweight global modal states only
    val listeningState by chatViewModel.listeningState.collectAsStateWithLifecycle()
    val pendingConfirmation by chatViewModel.pendingConfirmation.collectAsStateWithLifecycle()
    val isListeningForConfirm by chatViewModel.isListeningForConfirm.collectAsStateWithLifecycle()
    val confirmVoiceFeedback by chatViewModel.confirmVoiceFeedback.collectAsStateWithLifecycle()
    val pendingWebSearch by chatViewModel.pendingWebSearch.collectAsStateWithLifecycle()
    val pendingBookFetch by memoryViewModel.pendingBookFetch.collectAsStateWithLifecycle()
    val voiceRoleSession by memoryViewModel.voiceRoleSession.collectAsStateWithLifecycle()
    val pendingBackupRequest by backupViewModel.pendingBackupRequest.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(statusBannerMessage) {
        statusBannerMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusBanner()
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refreshPermissions()
        if (granted) {
            when {
                pendingConfirmation != null -> chatViewModel.startVoiceConfirmationListening()
                voiceRoleSession != null -> memoryViewModel.listenVoiceExplanationForRole()
                else -> chatViewModel.startVoiceListening()
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.mic_permission_denied_msg))
            }
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refreshPermissions()
        if (!granted) {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.contacts_permission_denied_msg))
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refreshPermissions()
    }

    val localBookPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            memoryViewModel.importLocalBookFile(uri)
        }
    }

    val voskZipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            settingsViewModel.importVoskModelZip(uri)
        }
    }

    val slmModelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            slmViewModel.importLocalSlmModel(uri)
        }
    }

    val piperFilesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            settingsViewModel.importPiperFiles(uris)
        }
    }

    val speakerModelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            settingsViewModel.importSpeakerModel(uri)
        }
    }

    val backupExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            backupViewModel.requestBackupPassword(BackupDialogMode.EXPORT, uri)
        }
    }

    val backupImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            backupViewModel.requestBackupPassword(BackupDialogMode.IMPORT, uri)
        }
    }

    val handleMicClick: () -> Unit = {
        if (listeningState is VoiceListeningState.Listening || listeningState is VoiceListeningState.WakeWordArmed) {
            chatViewModel.stopVoiceListening()
        } else if (hasMicPermission) {
            chatViewModel.startVoiceListening()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val handleWakeWordClick: () -> Unit = {
        if (listeningState is VoiceListeningState.WakeWordArmed) {
            chatViewModel.stopVoiceListening()
        } else if (hasMicPermission) {
            chatViewModel.startWakeWordGuard()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val handleConfirmVoiceClick: () -> Unit = {
        if (hasMicPermission) {
            chatViewModel.startVoiceConfirmationListening()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val handleRoleVoiceClick: () -> Unit = {
        if (hasMicPermission) {
            memoryViewModel.listenVoiceExplanationForRole()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                chatViewModel.onAppBackgrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(externalVoiceTriggerNonce) {
        if (externalVoiceTriggerNonce > 0) {
            if (hasMicPermission) {
                chatViewModel.startVoiceListening()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    SolTheme(darkTheme = preferences.darkTheme) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            val navController = rememberNavController()
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route ?: ROUTE_HOME

            val navigateTo: (String) -> Unit = { route ->
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }

            pendingConfirmation?.let { confirmation ->
                CommunicationConfirmationDialog(
                    confirmation = confirmation,
                    isListeningVoiceConfirm = isListeningForConfirm,
                    voiceConfirmFeedback = confirmVoiceFeedback,
                    onStartVoiceConfirm = handleConfirmVoiceClick,
                    onStopVoiceConfirm = chatViewModel::stopVoiceConfirmationListening,
                    onSelectCandidate = chatViewModel::selectConfirmationCandidate,
                    onSelectChannel = chatViewModel::updateConfirmationChannel,
                    onMessageChange = chatViewModel::updateConfirmationMessage,
                    onConfirm = chatViewModel::confirmAndExecutePending,
                    onCancel = chatViewModel::cancelPendingConfirmation,
                )
            }

            pendingBookFetch?.let { bookReq ->
                InternetBookFetchDialog(
                    pending = bookReq,
                    onUpdateQuery = memoryViewModel::updatePendingBookQuery,
                    onConfirmFetch = memoryViewModel::confirmInternetBookFetch,
                    onPickLocalFile = {
                        localBookPickerLauncher.launch(
                            arrayOf(
                                "application/pdf",
                                "application/epub+zip",
                                "text/plain",
                                "text/html",
                                "application/octet-stream",
                            )
                        )
                    },
                    onDismiss = memoryViewModel::dismissPendingBookFetch,
                )
            }

            pendingWebSearch?.let { searchReq ->
                WebSearchConfirmationDialog(
                    pending = searchReq,
                    onUpdateQuery = chatViewModel::updatePendingWebSearchQuery,
                    onConfirm = chatViewModel::confirmWebSearch,
                    onDismiss = chatViewModel::dismissPendingWebSearch,
                )
            }

            voiceRoleSession?.let { session ->
                VoiceRoleTeachingDialog(
                    session = session,
                    onUpdateDraft = memoryViewModel::updateVoiceRoleDraft,
                    onStartVoiceExplanation = handleRoleVoiceClick,
                    onStopVoiceExplanation = memoryViewModel::stopVoiceExplanationListening,
                    onSave = memoryViewModel::saveVoiceRoleTeaching,
                    onDismiss = memoryViewModel::dismissVoiceRoleSession,
                )
            }

            pendingBackupRequest?.let { req ->
                BackupPasswordDialog(
                    mode = req.mode,
                    onConfirm = backupViewModel::executeEncryptedBackup,
                    onDismiss = backupViewModel::dismissBackupPasswordDialog,
                )
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useNavRail = maxWidth >= 600.dp

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        if (!useNavRail && !showCapabilitiesSheet) {
                            NavigationBar(
                                windowInsets = WindowInsets.navigationBars,
                                containerColor = MaterialTheme.colorScheme.surface,
                            ) {
                                NAV_ITEMS.forEach { item ->
                                    val selected = currentRoute == item.route
                                    val label = stringResource(item.labelResId)
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { navigateTo(item.route) },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                                contentDescription = label,
                                            )
                                        },
                                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.testTag("nav_${item.route}"),
                                    )
                                }
                            }
                        }
                    },
                ) { innerPadding ->
                    if (showCapabilitiesSheet) {
                        val enabledPeriodicJobs by settingsViewModel.enabledPeriodicJobs.collectAsStateWithLifecycle()
                        val backgroundLogs by settingsViewModel.backgroundLogs.collectAsStateWithLifecycle()
                        CapabilitiesScreen(
                            capabilities = viewModel.capabilitiesCatalog,
                            enabledPeriodicJobs = enabledPeriodicJobs,
                            backgroundLogs = backgroundLogs,
                            hasNotificationPermission = hasNotificationPermission,
                            onRequestNotificationPermission = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            onRunBackgroundJob = { jobId ->
                                if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                chatViewModel.runBackgroundJobNow(jobId)
                            },
                            onTogglePeriodicJob = { jobId, enable ->
                                if (enable && !hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                settingsViewModel.togglePeriodicBackgroundJob(jobId, enable)
                            },
                            onClearBackgroundLogs = settingsViewModel::clearBackgroundLogs,
                            onOpenBookRoleStudio = { memoryViewModel.requestBookRoleStudio() },
                            onRunSampleCommand = { cmd -> chatViewModel.submitCommand(cmd) },
                            onBack = { viewModel.openCapabilitiesSheet(false) },
                            modifier = Modifier.padding(innerPadding),
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        ) {
                            if (useNavRail) {
                                NavigationRail(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ) {
                                    NAV_ITEMS.forEach { item ->
                                        val selected = currentRoute == item.route
                                        val label = stringResource(item.labelResId)
                                        NavigationRailItem(
                                            selected = selected,
                                            onClick = { navigateTo(item.route) },
                                            icon = {
                                                Icon(
                                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                                    contentDescription = label,
                                                )
                                            },
                                            label = { Text(label) },
                                            modifier = Modifier.testTag("nav_${item.route}"),
                                        )
                                    }
                                }
                            }

                            NavHost(
                                navController = navController,
                                startDestination = ROUTE_HOME,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .widthIn(max = 720.dp)
                                    .align(Alignment.CenterVertically),
                            ) {
                                composable(ROUTE_HOME) {
                                    val partialSpeechText by chatViewModel.partialSpeechText.collectAsStateWithLifecycle()
                                    val micRms by chatViewModel.micRms.collectAsStateWithLifecycle()
                                    val activeAsrBackend by chatViewModel.activeAsrBackend.collectAsStateWithLifecycle()
                                    val voskModelStatus by chatViewModel.voskModelStatus.collectAsStateWithLifecycle()
                                    val ttsAvailability by chatViewModel.ttsAvailability.collectAsStateWithLifecycle()
                                    val isSpeaking by chatViewModel.isSpeaking.collectAsStateWithLifecycle()
                                    val isFetchingBook by memoryViewModel.isFetchingBook.collectAsStateWithLifecycle()
                                    val slmRuntimeState by chatViewModel.slmRuntimeState.collectAsStateWithLifecycle()
                                    val isSlmGenerating by chatViewModel.isSlmGenerating.collectAsStateWithLifecycle()
                                    val activeRoles by chatViewModel.activeRoles.collectAsStateWithLifecycle()
                                    val messages by chatViewModel.messages.collectAsStateWithLifecycle()
                                    val inputText by chatViewModel.inputText.collectAsStateWithLifecycle()
                                    val activeTask by chatViewModel.activeTask.collectAsStateWithLifecycle()
                                    val savedLocations by chatViewModel.savedLocations.collectAsStateWithLifecycle()
                                    val batteryStatus by settingsViewModel.batteryStatus.collectAsStateWithLifecycle()

                                    HomeScreen(
                                        capabilitiesCount = viewModel.capabilitiesCatalog.size,
                                        preferences = preferences,
                                        batteryStatus = batteryStatus,
                                        listeningState = listeningState,
                                        partialSpeechText = partialSpeechText,
                                        micRms = micRms,
                                        activeAsrBackend = activeAsrBackend,
                                        voskModelStatus = voskModelStatus,
                                        ttsAvailability = ttsAvailability,
                                        isSpeaking = isSpeaking,
                                        isFetchingBook = isFetchingBook,
                                        slmRuntimeState = slmRuntimeState,
                                        isSlmGenerating = isSlmGenerating,
                                        activeRoles = activeRoles,
                                        messages = messages,
                                        inputText = inputText,
                                        activeTask = activeTask,
                                        savedLocations = savedLocations,
                                        onInputChange = chatViewModel::updateInputText,
                                        onSendCommand = { chatViewModel.submitCommand() },
                                        onQuickCommand = { cmd -> chatViewModel.submitCommand(cmd) },
                                        onMicClick = handleMicClick,
                                        onWakeWordClick = handleWakeWordClick,
                                        onStopVoiceListening = chatViewModel::stopVoiceListening,
                                        onVoiceErrorAction = { action ->
                                            when (action) {
                                                VoiceErrorAction.OPEN_MIC_PERMISSION_SETTINGS -> viewModel.openAppPermissionSettings()
                                                VoiceErrorAction.OPEN_VOSK_SETTINGS -> navigateTo(ROUTE_SETTINGS)
                                                VoiceErrorAction.RETRY_LISTENING -> {
                                                    chatViewModel.clearVoiceError()
                                                    handleMicClick()
                                                }
                                                VoiceErrorAction.SWITCH_TO_TEXT_INPUT -> chatViewModel.clearVoiceError()
                                                VoiceErrorAction.OPEN_TTS_OR_SYSTEM_SETTINGS -> settingsViewModel.openSystemTtsSettings()
                                            }
                                        },
                                        onClearVoiceError = chatViewModel::clearVoiceError,
                                        onSpeakMessage = chatViewModel::speakText,
                                        onStopSpeaking = chatViewModel::stopSpeaking,
                                        onOpenSystemTtsSettings = settingsViewModel::openSystemTtsSettings,
                                        onStopSlmGeneration = chatViewModel::stopSlmGeneration,
                                        onResetSlmConversation = chatViewModel::resetSlmConversation,
                                        onConfirmCurrentLocation = chatViewModel::confirmCurrentLocation,
                                        onDismissTaskCard = chatViewModel::dismissActiveTaskCard,
                                        onOpenCapabilities = { viewModel.openCapabilitiesSheet(true) },
                                        onOpenBookRoleStudio = { memoryViewModel.requestBookRoleStudio() },
                                    )
                                }

                                composable(ROUTE_MEMORY) {
                                    val memories by memoryViewModel.memories.collectAsStateWithLifecycle()
                                    val persons by memoryViewModel.persons.collectAsStateWithLifecycle()
                                    val locations by memoryViewModel.locations.collectAsStateWithLifecycle()
                                    val books by memoryViewModel.books.collectAsStateWithLifecycle()
                                    val roles by memoryViewModel.roles.collectAsStateWithLifecycle()
                                    val userUtteranceExamples by memoryViewModel.userUtteranceExamples.collectAsStateWithLifecycle()

                                    MemoryScreen(
                                        memories = memories,
                                        persons = persons,
                                        locations = locations,
                                        books = books,
                                        roles = roles,
                                        userExamples = userUtteranceExamples,
                                        confirmedCurrentLocation = preferences.confirmedCurrentLocation,
                                        onAddOrUpdateAlias = memoryViewModel::addOrUpdateAlias,
                                        onAddFact = memoryViewModel::addFact,
                                        onUpdateMemory = memoryViewModel::updateMemoryItem,
                                        onDeleteMemory = memoryViewModel::deleteMemoryItem,
                                        onDeleteUserExample = memoryViewModel::deleteUserUtteranceExample,
                                        onSavePerson = memoryViewModel::savePerson,
                                        onDeletePerson = memoryViewModel::deletePerson,
                                        onSaveLocation = memoryViewModel::saveLocation,
                                        onDeleteLocation = memoryViewModel::deleteLocation,
                                        onOpenBookRoleStudio = { memoryViewModel.requestBookRoleStudio() },
                                        onTeachVoiceForBook = memoryViewModel::openVoiceRoleTeachingForBook,
                                        onSpeakBook = chatViewModel::speakText,
                                        onDeleteBook = memoryViewModel::deleteBook,
                                        onToggleRoleActive = memoryViewModel::toggleRoleActive,
                                        onDeleteRole = memoryViewModel::deleteRole,
                                    )
                                }

                                composable(ROUTE_CONTACTS) {
                                    val contactsViewModel: ContactsViewModel = viewModel()
                                    val contacts by contactsViewModel.contacts.collectAsStateWithLifecycle()

                                    ContactsScreen(
                                        contacts = contacts,
                                        hasContactsPermission = hasContactsPermission,
                                        onRequestContactsPermission = {
                                            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                                        },
                                        onCallContact = { contact ->
                                            contactsViewModel.initiateContactAction(contact, CommunicationChannel.CALL)
                                        },
                                        onSmsContact = { contact ->
                                            contactsViewModel.initiateContactAction(contact, CommunicationChannel.SMS)
                                        },
                                        onSaveToMemory = contactsViewModel::saveContactToMemory,
                                    )
                                }

                                composable(ROUTE_HISTORY) {
                                    val historyViewModel: HistoryViewModel = viewModel()
                                    val history by historyViewModel.history.collectAsStateWithLifecycle()

                                    HistoryScreen(
                                        history = history,
                                        retentionMode = preferences.historyRetentionMode,
                                        privateModeEnabled = preferences.privateModeEnabled,
                                        onRetentionModeChange = historyViewModel::setHistoryRetentionMode,
                                        onPrivateModeChange = historyViewModel::setPrivateModeEnabled,
                                        onSpeakText = chatViewModel::speakText,
                                        onDeleteItem = historyViewModel::deleteHistoryEntry,
                                        onClearAll = historyViewModel::clearHistory,
                                        onDeleteAllData = viewModel::deleteAllUserData,
                                    )
                                }

                                composable(ROUTE_SETTINGS) {
                                    val ttsAvailability by chatViewModel.ttsAvailability.collectAsStateWithLifecycle()
                                    val voskModelStatus by chatViewModel.voskModelStatus.collectAsStateWithLifecycle()
                                    val piperStatus by settingsViewModel.piperStatus.collectAsStateWithLifecycle()
                                    val availableTtsEngines by settingsViewModel.availableTtsEngines.collectAsStateWithLifecycle()
                                    val activeTtsEngineLabel by settingsViewModel.activeTtsEngineLabel.collectAsStateWithLifecycle()
                                    val persianVoices by settingsViewModel.persianVoices.collectAsStateWithLifecycle()
                                    val vocabularyTermsCount by settingsViewModel.vocabularyTermsCount.collectAsStateWithLifecycle()
                                    val bookChunkCount by settingsViewModel.bookChunkCount.collectAsStateWithLifecycle()
                                    val slmStatus by slmViewModel.slmStatus.collectAsStateWithLifecycle()
                                    val slmRuntimeState by slmViewModel.slmRuntimeState.collectAsStateWithLifecycle()
                                    val ramSnapshot by settingsViewModel.ramSnapshot.collectAsStateWithLifecycle()
                                    val canScheduleExactAlarms by settingsViewModel.canScheduleExactAlarms.collectAsStateWithLifecycle()
                                    val scheduledAlarms by settingsViewModel.scheduledAlarms.collectAsStateWithLifecycle()
                                    val batteryStatus by settingsViewModel.batteryStatus.collectAsStateWithLifecycle()
                                    val volumeStatus by settingsViewModel.volumeStatus.collectAsStateWithLifecycle()
                                    val launchableApps by settingsViewModel.launchableApps.collectAsStateWithLifecycle()
                                    val securityAudit by settingsViewModel.securityAudit.collectAsStateWithLifecycle()
                                    val speakerIdState by settingsViewModel.speakerIdState.collectAsStateWithLifecycle()
                                    val isMicServiceRunning by settingsViewModel.isMicServiceRunning.collectAsStateWithLifecycle()

                                    SettingsScreen(
                                        preferences = preferences,
                                        ttsAvailability = ttsAvailability,
                                        piperStatus = piperStatus,
                                        availableTtsEngines = availableTtsEngines,
                                        activeTtsEngineLabel = activeTtsEngineLabel,
                                        persianVoices = persianVoices,
                                        voskModelStatus = voskModelStatus,
                                        vocabularyTermsCount = vocabularyTermsCount,
                                        bookChunkCount = bookChunkCount,
                                        slmStatus = slmStatus,
                                        slmRuntimeState = slmRuntimeState,
                                        ramSnapshot = ramSnapshot,
                                        canScheduleExactAlarms = canScheduleExactAlarms,
                                        scheduledAlarms = scheduledAlarms,
                                        batteryStatus = batteryStatus,
                                        volumeStatus = volumeStatus,
                                        launchableApps = launchableApps,
                                        securityAudit = securityAudit,
                                        onOwnerNameChange = settingsViewModel::setOwnerName,
                                        onVoicePersonaChange = settingsViewModel::setVoicePersona,
                                        onSelectTtsEngine = settingsViewModel::setPreferredTtsEngine,
                                        onSelectTtsVoice = settingsViewModel::setPreferredTtsVoice,
                                        onSpeechRateChange = settingsViewModel::setSpeechRate,
                                        onAutoSpeakChange = settingsViewModel::setAutoSpeak,
                                        onTestVoice = settingsViewModel::testVoiceSample,
                                        onOpenSystemTtsSettings = settingsViewModel::openSystemTtsSettings,
                                        onRecheckSystemTts = settingsViewModel::recheckSystemTts,
                                        onPickPiperFiles = {
                                            piperFilesPickerLauncher.launch(arrayOf("*/*"))
                                        },
                                        onDeletePiperModel = settingsViewModel::deletePiperModel,
                                        speakerIdState = speakerIdState,
                                        onRecordOwnerVoice = {
                                            if (hasMicPermission) {
                                                settingsViewModel.recordOwnerVoice()
                                            } else {
                                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        },
                                        onClearOwnerVoice = settingsViewModel::clearOwnerVoice,
                                        onPickSpeakerModel = {
                                            speakerModelPickerLauncher.launch(arrayOf("*/*"))
                                        },
                                        isMicServiceRunning = isMicServiceRunning,
                                        onScreenOffMicToggle = { enable ->
                                            if (enable && !hasMicPermission) {
                                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            } else {
                                                settingsViewModel.setScreenOffMicEnabled(enable)
                                            }
                                        },
                                        onContinuousListeningToggle = { enable ->
                                            if (!enable) chatViewModel.stopContinuousWakeSession()
                                            settingsViewModel.setContinuousListeningAfterWake(enable)
                                        },
                                        onVoskOfflineToggle = settingsViewModel::setVoskOfflineEnabled,
                                        onWakeWordToggle = settingsViewModel::setWakeWordEnabled,
                                        onVadSilenceTimeoutChange = settingsViewModel::setVadSilenceTimeoutMs,
                                        onMaxVoiceListenSecondsChange = settingsViewModel::setMaxVoiceListenSeconds,
                                        onWakeWordTimeoutMinutesChange = settingsViewModel::setWakeWordTimeoutMinutes,
                                        onVoiceCueFeedbackChange = settingsViewModel::setVoiceCueFeedbackEnabled,
                                        onDownloadVoskModel = settingsViewModel::downloadVoskPersianModel,
                                        onPickVoskZip = {
                                            voskZipPickerLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                                        },
                                        onUnloadVoskRam = settingsViewModel::unloadVoskModelFromRam,
                                        onDeleteVoskModel = settingsViewModel::deleteVoskModel,
                                        onSlmToggle = slmViewModel::setSlmSynthesisEnabled,
                                        onSlmPreferGpuToggle = slmViewModel::setSlmPreferGpu,
                                        onDownloadSlmModel = slmViewModel::downloadSlmModel,
                                        onCancelSlmDownload = slmViewModel::cancelSlmDownload,
                                        onPickSlmFile = {
                                            slmModelPickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                                        },
                                        onTestLoadSlmInRam = slmViewModel::testLoadSlmInRam,
                                        onUnloadSlmRam = slmViewModel::unloadSlmFromRam,
                                        onDeleteSlmModel = slmViewModel::deleteSlmModel,
                                        onAddQuickSettingsTile = settingsViewModel::requestAddQuickSettingsTile,
                                        onRequestExactAlarmPermission = settingsViewModel::requestExactAlarmPermission,
                                        onCancelScheduledAlarm = settingsViewModel::cancelScheduledAlarm,
                                        onExportEncryptedBackup = {
                                            val jDate = JalaliCalendar.fromMillis().formatShortFa().replace("/", "-")
                                            backupExportLauncher.launch("sol-backup-$jDate.solbackup")
                                        },
                                        onImportEncryptedBackup = {
                                            backupImportLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                                        },
                                        onTrimRamNow = backupViewModel::trimRamManually,
                                        onInvocationModeChange = settingsViewModel::setInvocationMode,
                                        onDeliveryPreferenceChange = settingsViewModel::setDeliveryPreference,
                                        onConfirmationPolicyChange = settingsViewModel::setConfirmationPolicy,
                                        onDarkThemeChange = settingsViewModel::setDarkTheme,
                                        onVolumePercentChange = settingsViewModel::setMediaVolumePercent,
                                        onOpenWifi = settingsViewModel::openWifiSettings,
                                        onOpenLocation = settingsViewModel::openLocationSettings,
                                        onOpenBluetooth = settingsViewModel::openBluetoothSettings,
                                        onOpenBattery = settingsViewModel::openBatterySettings,
                                        onLaunchApp = { label ->
                                            settingsViewModel.launchInstalledApp(label)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
