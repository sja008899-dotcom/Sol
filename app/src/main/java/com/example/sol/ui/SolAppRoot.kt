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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.sol.tasks.CommunicationChannel
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
    val labelFa: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val NAV_ITEMS = listOf(
    SolNavItem(ROUTE_HOME, "دستیار", Icons.Filled.Mic, Icons.Outlined.Mic),
    SolNavItem(ROUTE_MEMORY, "حافظه", Icons.Filled.Psychology, Icons.Outlined.Psychology),
    SolNavItem(ROUTE_CONTACTS, "مخاطبین", Icons.Filled.Contacts, Icons.Outlined.Contacts),
    SolNavItem(ROUTE_HISTORY, "سوابق", Icons.Filled.History, Icons.Outlined.History),
    SolNavItem(ROUTE_SETTINGS, "تنظیمات", Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun SolAppRoot(viewModel: SolViewModel) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val persons by viewModel.persons.collectAsStateWithLifecycle()
    val locations by viewModel.locations.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val roles by viewModel.roles.collectAsStateWithLifecycle()
    val backgroundLogs by viewModel.backgroundLogs.collectAsStateWithLifecycle()
    val enabledPeriodicJobs by viewModel.enabledPeriodicJobs.collectAsStateWithLifecycle()

    val listeningState by viewModel.listeningState.collectAsStateWithLifecycle()
    val partialSpeechText by viewModel.partialSpeechText.collectAsStateWithLifecycle()
    val micRms by viewModel.micRms.collectAsStateWithLifecycle()
    val ttsAvailability by viewModel.ttsAvailability.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val persianVoices by viewModel.persianVoices.collectAsStateWithLifecycle()
    val activeTask by viewModel.activeTask.collectAsStateWithLifecycle()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsStateWithLifecycle()
    val pendingBookFetch by viewModel.pendingBookFetch.collectAsStateWithLifecycle()
    val isFetchingBook by viewModel.isFetchingBook.collectAsStateWithLifecycle()
    val voiceRoleSession by viewModel.voiceRoleSession.collectAsStateWithLifecycle()
    val showCapabilitiesSheet by viewModel.showCapabilitiesSheet.collectAsStateWithLifecycle()

    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val batteryStatus by viewModel.batteryStatus.collectAsStateWithLifecycle()
    val volumeStatus by viewModel.volumeStatus.collectAsStateWithLifecycle()
    val launchableApps by viewModel.launchableApps.collectAsStateWithLifecycle()
    val securityAudit by viewModel.securityAudit.collectAsStateWithLifecycle()
    val hasMicPermission by viewModel.hasMicPermission.collectAsStateWithLifecycle()
    val hasContactsPermission by viewModel.hasContactsPermission.collectAsStateWithLifecycle()
    val hasNotificationPermission by viewModel.hasNotificationPermission.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refreshSystemSnapshots()
        if (granted) {
            if (voiceRoleSession != null) {
                viewModel.listenVoiceExplanationForRole()
            } else {
                viewModel.startVoiceListening()
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("مجوز میکروفون رد شد؛ می‌توانید فرمان را تایپ کنید.")
            }
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refreshSystemSnapshots()
        if (!granted) {
            scope.launch {
                snackbarHostState.showSnackbar("دسترسی به مخاطبین داده نشد؛ مخاطبین حافظه Sol همچنان فعال هستند.")
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refreshSystemSnapshots()
    }

    val localBookPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importLocalBookFile(uri)
        }
    }

    val handleMicClick: () -> Unit = {
        if (listeningState is VoiceListeningState.Listening) {
            viewModel.stopVoiceListening()
        } else if (hasMicPermission) {
            viewModel.startVoiceListening()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val handleRoleVoiceClick: () -> Unit = {
        if (hasMicPermission) {
            viewModel.listenVoiceExplanationForRole()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
                    onSelectCandidate = viewModel::selectConfirmationCandidate,
                    onSelectChannel = viewModel::updateConfirmationChannel,
                    onMessageChange = viewModel::updateConfirmationMessage,
                    onConfirm = viewModel::confirmAndExecutePending,
                    onCancel = viewModel::cancelPendingConfirmation,
                )
            }

            pendingBookFetch?.let { bookReq ->
                InternetBookFetchDialog(
                    pending = bookReq,
                    onUpdateQuery = viewModel::updatePendingBookQuery,
                    onConfirmFetch = viewModel::confirmInternetBookFetch,
                    onPickLocalFile = {
                        localBookPickerLauncher.launch(arrayOf("text/plain", "text/*"))
                    },
                    onDismiss = viewModel::dismissPendingBookFetch,
                )
            }

            voiceRoleSession?.let { session ->
                VoiceRoleTeachingDialog(
                    session = session,
                    onUpdateDraft = viewModel::updateVoiceRoleDraft,
                    onStartVoiceExplanation = handleRoleVoiceClick,
                    onStopVoiceExplanation = viewModel::stopVoiceExplanationListening,
                    onSave = viewModel::saveVoiceRoleTeaching,
                    onDismiss = viewModel::dismissVoiceRoleSession,
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
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { navigateTo(item.route) },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                                contentDescription = item.labelFa,
                                            )
                                        },
                                        label = { Text(item.labelFa, style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.testTag("nav_${item.route}"),
                                    )
                                }
                            }
                        }
                    },
                ) { innerPadding ->
                    if (showCapabilitiesSheet) {
                        CapabilitiesScreen(
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
                                viewModel.runBackgroundJobNow(jobId)
                            },
                            onTogglePeriodicJob = { jobId, enable ->
                                if (enable && !hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                viewModel.togglePeriodicBackgroundJob(jobId, enable)
                            },
                            onClearBackgroundLogs = viewModel::clearBackgroundLogs,
                            onOpenBookRoleStudio = { viewModel.requestBookRoleStudio() },
                            onRunSampleCommand = { cmd -> viewModel.submitCommand(cmd) },
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
                                        NavigationRailItem(
                                            selected = selected,
                                            onClick = { navigateTo(item.route) },
                                            icon = {
                                                Icon(
                                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                                    contentDescription = item.labelFa,
                                                )
                                            },
                                            label = { Text(item.labelFa) },
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
                                    HomeScreen(
                                        preferences = preferences,
                                        batteryStatus = batteryStatus,
                                        listeningState = listeningState,
                                        partialSpeechText = partialSpeechText,
                                        micRms = micRms,
                                        ttsAvailability = ttsAvailability,
                                        isSpeaking = isSpeaking,
                                        isFetchingBook = isFetchingBook,
                                        activeRoles = roles.filter { it.isActive },
                                        messages = messages,
                                        inputText = inputText,
                                        activeTask = activeTask,
                                        savedLocations = locations,
                                        onInputChange = viewModel::updateInputText,
                                        onSendCommand = { viewModel.submitCommand() },
                                        onQuickCommand = { cmd -> viewModel.submitCommand(cmd) },
                                        onMicClick = handleMicClick,
                                        onClearVoiceError = viewModel::clearVoiceError,
                                        onSpeakMessage = viewModel::speakText,
                                        onStopSpeaking = viewModel::stopSpeaking,
                                        onConfirmCurrentLocation = viewModel::confirmCurrentLocation,
                                        onDismissTaskCard = viewModel::dismissActiveTaskCard,
                                        onOpenCapabilities = { viewModel.openCapabilitiesSheet(true) },
                                        onOpenBookRoleStudio = { viewModel.requestBookRoleStudio() },
                                    )
                                }

                                composable(ROUTE_MEMORY) {
                                    MemoryScreen(
                                        memories = memories,
                                        persons = persons,
                                        locations = locations,
                                        books = books,
                                        roles = roles,
                                        confirmedCurrentLocation = preferences.confirmedCurrentLocation,
                                        onAddOrUpdateAlias = viewModel::addOrUpdateAlias,
                                        onAddFact = viewModel::addFact,
                                        onUpdateMemory = viewModel::updateMemoryItem,
                                        onDeleteMemory = viewModel::deleteMemoryItem,
                                        onSavePerson = viewModel::savePerson,
                                        onDeletePerson = viewModel::deletePerson,
                                        onSaveLocation = viewModel::saveLocation,
                                        onDeleteLocation = viewModel::deleteLocation,
                                        onOpenBookRoleStudio = { viewModel.requestBookRoleStudio() },
                                        onTeachVoiceForBook = viewModel::openVoiceRoleTeachingForBook,
                                        onSpeakBook = viewModel::speakText,
                                        onDeleteBook = viewModel::deleteBook,
                                        onToggleRoleActive = viewModel::toggleRoleActive,
                                        onDeleteRole = viewModel::deleteRole,
                                    )
                                }

                                composable(ROUTE_CONTACTS) {
                                    ContactsScreen(
                                        contacts = contacts,
                                        hasContactsPermission = hasContactsPermission,
                                        onRequestContactsPermission = {
                                            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                                        },
                                        onCallContact = { contact ->
                                            viewModel.initiateContactAction(contact, CommunicationChannel.CALL)
                                        },
                                        onSmsContact = { contact ->
                                            viewModel.initiateContactAction(contact, CommunicationChannel.SMS)
                                        },
                                        onSaveToMemory = { contact ->
                                            viewModel.savePerson(
                                                name = contact.name,
                                                phone = contact.phone,
                                                relation = contact.relation,
                                                callScriptNote = contact.callScriptNote,
                                            )
                                            scope.launch {
                                                snackbarHostState.showSnackbar("${contact.name} در حافظه Sol ذخیره شد")
                                            }
                                        },
                                    )
                                }

                                composable(ROUTE_HISTORY) {
                                    HistoryScreen(
                                        history = history,
                                        onSpeakText = viewModel::speakText,
                                        onDeleteItem = viewModel::deleteHistoryEntry,
                                        onClearAll = viewModel::clearHistory,
                                    )
                                }

                                composable(ROUTE_SETTINGS) {
                                    SettingsScreen(
                                        preferences = preferences,
                                        ttsAvailability = ttsAvailability,
                                        persianVoices = persianVoices,
                                        batteryStatus = batteryStatus,
                                        volumeStatus = volumeStatus,
                                        launchableApps = launchableApps,
                                        securityAudit = securityAudit,
                                        onOwnerNameChange = viewModel::setOwnerName,
                                        onVoicePersonaChange = viewModel::setVoicePersona,
                                        onSelectTtsVoice = viewModel::setPreferredTtsVoice,
                                        onSpeechRateChange = viewModel::setSpeechRate,
                                        onAutoSpeakChange = viewModel::setAutoSpeak,
                                        onTestVoice = viewModel::testVoiceSample,
                                        onInvocationModeChange = viewModel::setInvocationMode,
                                        onDeliveryPreferenceChange = viewModel::setDeliveryPreference,
                                        onConfirmationPolicyChange = viewModel::setConfirmationPolicy,
                                        onDarkThemeChange = viewModel::setDarkTheme,
                                        onVolumePercentChange = viewModel::setMediaVolumePercent,
                                        onOpenWifi = viewModel::openWifiSettings,
                                        onOpenLocation = viewModel::openLocationSettings,
                                        onOpenBluetooth = viewModel::openBluetoothSettings,
                                        onOpenBattery = viewModel::openBatterySettings,
                                        onLaunchApp = { label ->
                                            viewModel.launchInstalledApp(label)
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
