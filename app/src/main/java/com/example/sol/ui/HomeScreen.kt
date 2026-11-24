package com.example.sol.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.sol.calendar.JalaliCalendar
import com.example.sol.device.BatteryStatus
import com.example.sol.knowledge.SlmRuntimeState
import com.example.sol.memory.LocationEntity
import com.example.sol.memory.SolPreferences
import com.example.sol.memory.SolRoleEntity
import com.example.sol.tasks.ActiveTaskState
import com.example.sol.tts.PiperTtsStatus
import com.example.sol.voice.ActiveAsrBackend
import com.example.sol.voice.VoiceErrorAction
import com.example.sol.voice.VoiceListeningState
import com.example.sol.voice.VoskModelStatus

private val QUICK_COMMANDS = listOf(
    "امروز چندم برج است؟",
    "تایمر ۱۰ دقیقه",
    "۲۵ درصد ۸۰۰ چند میشه؟",
    "فهرست قابلیت‌ها",
    "به اینترنت برو کتاب Atomic Habits را پیدا کن و با توضیحات صوتی من به حافظه و نقشت اضافه کن",
    "از حافظه در پس‌زمینه پشتیبان بگیر",
    "هر وقت می‌گم دفتر منظورم شرکت کارنو صنعت ماکیان است",
    "وضعیت شرکت را به علی اطلاع بده و بعد نتیجه را به من گزارش کن",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    capabilitiesCount: Int,
    preferences: SolPreferences,
    batteryStatus: BatteryStatus,
    listeningState: VoiceListeningState,
    partialSpeechText: String,
    micRms: Float,
    activeAsrBackend: ActiveAsrBackend,
    voskModelStatus: VoskModelStatus,
    piperStatus: PiperTtsStatus,
    isSpeaking: Boolean,
    isFetchingBook: Boolean,
    slmRuntimeState: SlmRuntimeState,
    isSlmGenerating: Boolean,
    activeRoles: List<SolRoleEntity>,
    messages: List<ChatMessageItem>,
    inputText: String,
    activeTask: ActiveTaskState?,
    savedLocations: List<LocationEntity>,
    onInputChange: (String) -> Unit,
    onSendCommand: () -> Unit,
    onQuickCommand: (String) -> Unit,
    onMicClick: () -> Unit,
    onWakeWordClick: () -> Unit,
    onStopVoiceListening: () -> Unit,
    onVoiceErrorAction: (VoiceErrorAction) -> Unit,
    onClearVoiceError: () -> Unit,
    onSpeakMessage: (String) -> Unit,
    onStopSpeaking: () -> Unit,
    onDownloadPiperModel: () -> Unit,
    onStopSlmGeneration: () -> Unit,
    onResetSlmConversation: () -> Unit,
    onConfirmCurrentLocation: (String) -> Unit,
    onDismissTaskCard: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onOpenBookRoleStudio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showLocationDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val todayJalali = remember { JalaliCalendar.fromMillis().formatFullFa() }
    val haptic = LocalHapticFeedback.current
    var wasVoiceActive by remember { mutableStateOf(false) }

    LaunchedEffect(listeningState, preferences.voiceCueFeedbackEnabled) {
        val isNowActive = listeningState is VoiceListeningState.Listening ||
            listeningState is VoiceListeningState.WakeWordArmed
        if (preferences.voiceCueFeedbackEnabled && isNowActive != wasVoiceActive) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        wasVoiceActive = isNowActive
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val activeRoleLabel = remember(activeRoles) { activeRoles.firstOrNull()?.roleName }
    val savedLocationSuggestions = remember(savedLocations) { savedLocations.map { it.fullName } }

    if (showLocationDialog) {
        ConfirmCurrentLocationDialog(
            currentLocation = preferences.confirmedCurrentLocation,
            savedLocationSuggestions = savedLocationSuggestions,
            onSave = onConfirmCurrentLocation,
            onDismiss = { showLocationDialog = false },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        MaterialTheme.colorScheme.background,
                    )
                )
            ),
    ) {
        // Sleek Hero Header Card using img_sol_hero_banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(22.dp)),
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_sol_hero_banner_1795044139786),
                contentDescription = stringResource(R.string.home_hero_art_cd),
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xCC051226),
                                Color(0xDF051226),
                            )
                        )
                    ),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.home_brand_title),
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            text = activeRoleLabel?.let { stringResource(R.string.home_active_role_fmt, it) }
                                ?: stringResource(R.string.home_default_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.88f),
                            shape = RoundedCornerShape(50),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CalendarMonth,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(13.dp),
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(todayJalali, style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(50),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (batteryStatus.isCharging) {
                                        Icons.Default.BatteryChargingFull
                                    } else {
                                        Icons.Default.BatteryStd
                                    },
                                    contentDescription = stringResource(R.string.cd_battery_status),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp),
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("${batteryStatus.levelPercent}%", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clickable(onClick = onOpenCapabilities)
                            .testTag("open_capabilities_button"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = stringResource(R.string.home_capabilities_button_fmt, capabilitiesCount),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.88f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clickable(onClick = onOpenBookRoleStudio)
                            .testTag("open_book_role_button"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = stringResource(R.string.home_book_role_button),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { showLocationDialog = true }
                        .testTag("current_location_pill"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = stringResource(R.string.cd_current_location),
                                tint = if (preferences.confirmedCurrentLocation.isNotBlank()) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (preferences.confirmedCurrentLocation.isNotBlank()) {
                                    stringResource(R.string.home_confirmed_location_fmt, preferences.confirmedCurrentLocation)
                                } else {
                                    stringResource(R.string.home_unconfirmed_location)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = stringResource(R.string.action_edit),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        if (isFetchingBook) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // Interactive Voice Orb Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SolVoiceOrb(
                    listeningState = listeningState,
                    isSpeaking = isSpeaking,
                    rmsLevel = micRms,
                    onClick = onMicClick,
                )

                if (voskModelStatus is VoskModelStatus.Installed) {
                    Surface(
                        color = if (listeningState is VoiceListeningState.WakeWordArmed) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable(onClick = onWakeWordClick)
                            .testTag("wake_word_button"),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Hearing,
                                contentDescription = stringResource(R.string.cd_wake_word),
                                tint = if (listeningState is VoiceListeningState.WakeWordArmed) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (listeningState is VoiceListeningState.WakeWordArmed) {
                                    stringResource(R.string.home_wake_waiting)
                                } else {
                                    stringResource(R.string.home_wake_idle)
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                text = stringResource(R.string.home_wake_fg_only),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (listeningState is VoiceListeningState.Listening ||
                listeningState is VoiceListeningState.Processing ||
                listeningState is VoiceListeningState.WakeWordArmed
            ) {
                Spacer(modifier = Modifier.height(6.dp))
                FilledTonalButton(
                    onClick = onStopVoiceListening,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("stop_voice_button"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = stringResource(R.string.stop_listening),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.home_stop_cancel_listening), style = MaterialTheme.typography.labelMedium)
                }
            }

            val backendLabel = if (activeAsrBackend == ActiveAsrBackend.VOSK_OFFLINE) {
                stringResource(R.string.home_backend_vosk)
            } else {
                stringResource(R.string.home_backend_system)
            }
            val voiceStatusText = when (listeningState) {
                is VoiceListeningState.Listening ->
                    if (partialSpeechText.isNotBlank()) "«$partialSpeechText»" else stringResource(R.string.home_listening_prompt_fmt, backendLabel)
                is VoiceListeningState.WakeWordArmed ->
                    stringResource(R.string.home_wake_armed_fmt, preferences.wakeWordTimeoutMinutes)
                is VoiceListeningState.Processing -> stringResource(R.string.home_processing_speech)
                is VoiceListeningState.Error -> listeningState.messageFa
                VoiceListeningState.Idle -> when {
                    isSpeaking -> stringResource(R.string.home_sol_speaking)
                    else -> stringResource(R.string.home_voice_idle_fmt, backendLabel)
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = if (activeAsrBackend == ActiveAsrBackend.VOSK_OFFLINE) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(13.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = voiceStatusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (listeningState is VoiceListeningState.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = if (listeningState is VoiceListeningState.Error) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (listeningState is VoiceListeningState.Error) {
                    TextButton(
                        onClick = { onVoiceErrorAction(listeningState.action) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("voice_error_action_button"),
                    ) {
                        Text(listeningState.action.labelFa, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = onClearVoiceError,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.action_close), style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (isSpeaking) {
                    TextButton(
                        onClick = onStopSpeaking,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeOff,
                            contentDescription = stringResource(R.string.home_stop_sound),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.home_stop_sound), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            when (piperStatus) {
                is PiperTtsStatus.Downloading -> {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("tts_downloading_card"),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = piperStatus.stageFa,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            LinearProgressIndicator(
                                progress = { piperStatus.progressPercent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                is PiperTtsStatus.NotInstalled, is PiperTtsStatus.Error -> {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("tts_missing_guide_card"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = (piperStatus as? PiperTtsStatus.Error)?.messageFa
                                        ?: stringResource(R.string.home_tts_missing_guide),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(
                                onClick = onDownloadPiperModel,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.home_download_piper_voice), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                else -> Unit
            }

            if (activeAsrBackend == ActiveAsrBackend.ANDROID_SPEECH_RECOGNIZER || preferences.privateModeEnabled) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (activeAsrBackend == ActiveAsrBackend.ANDROID_SPEECH_RECOGNIZER) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.testTag("online_asr_badge"),
                        ) {
                            Text(
                                text = stringResource(R.string.home_online_asr_warning),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (preferences.privateModeEnabled) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.testTag("private_mode_badge"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VisibilityOff,
                                    contentDescription = stringResource(R.string.cd_private_mode_active),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(13.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.home_private_mode_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                    }
                }
            }

            if (isSlmGenerating || slmRuntimeState is SlmRuntimeState.Ready) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    if (isSlmGenerating) {
                        Text(
                            text = stringResource(R.string.home_slm_writing),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(
                            onClick = onStopSlmGeneration,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("stop_slm_button"),
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.home_slm_stop), style = MaterialTheme.typography.labelSmall)
                        }
                    } else if (slmRuntimeState is SlmRuntimeState.Ready) {
                        Text(
                            text = stringResource(R.string.home_slm_ready_fmt, slmRuntimeState.activeBackend.name),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        TextButton(
                            onClick = onResetSlmConversation,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.home_slm_new_chat), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // Quick Persian Command Chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(QUICK_COMMANDS, key = { it }) { sample ->
                AssistChip(
                    onClick = { onQuickCommand(sample) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = {
                        Text(
                            text = sample,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                )
            }
        }

        if (activeTask != null) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                ActiveTaskProgressCard(
                    task = activeTask,
                    onDismiss = onDismissTaskCard,
                )
            }
        }

        // Conversation Transcript
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("conversation_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                ChatBubbleCard(
                    item = msg,
                    onSpeakClick = { onSpeakMessage(msg.text) },
                    onChoiceClick = onQuickCommand,
                )
            }
        }

        // Command Input Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    placeholder = { Text(stringResource(R.string.command_input_placeholder)) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("command_input"),
                    maxLines = 3,
                    shape = RoundedCornerShape(18.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSendCommand() }),
                )

                FilledIconButton(
                    onClick = onMicClick,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("mic_button"),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (listeningState is VoiceListeningState.Listening) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        contentColor = if (listeningState is VoiceListeningState.Listening) {
                            MaterialTheme.colorScheme.onSecondary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    ),
                ) {
                    Icon(
                        imageVector = if (listeningState is VoiceListeningState.Listening) {
                            Icons.Default.Stop
                        } else {
                            Icons.Default.Mic
                        },
                        contentDescription = stringResource(R.string.cd_mic_button),
                    )
                }

                FilledIconButton(
                    onClick = onSendCommand,
                    enabled = inputText.isNotBlank(),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("send_command_button"),
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.send_command),
                    )
                }
            }
        }
    }
}
