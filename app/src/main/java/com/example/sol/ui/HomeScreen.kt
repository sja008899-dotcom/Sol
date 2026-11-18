package com.example.sol.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.sol.device.BatteryStatus
import com.example.sol.memory.LocationEntity
import com.example.sol.memory.SolPreferences
import com.example.sol.tasks.ActiveTaskState
import com.example.sol.voice.TtsAvailability
import com.example.sol.voice.VoiceListeningState

private val QUICK_COMMANDS = listOf(
    "سلام",
    "ساعت چنده؟",
    "وضعیت باتری",
    "حافظه‌ات چی یادت است؟",
    "هر وقت می‌گم دفتر منظورم شرکت کارنو صنعت ماکیان است",
    "وضعیت شرکت را به علی اطلاع بده و بعد نتیجه را به من گزارش کن",
)

@Composable
fun HomeScreen(
    preferences: SolPreferences,
    batteryStatus: BatteryStatus,
    listeningState: VoiceListeningState,
    partialSpeechText: String,
    micRms: Float,
    ttsAvailability: TtsAvailability,
    isSpeaking: Boolean,
    messages: List<ChatMessageItem>,
    inputText: String,
    activeTask: ActiveTaskState?,
    savedLocations: List<LocationEntity>,
    onInputChange: (String) -> Unit,
    onSendCommand: () -> Unit,
    onQuickCommand: (String) -> Unit,
    onMicClick: () -> Unit,
    onClearVoiceError: () -> Unit,
    onSpeakMessage: (String) -> Unit,
    onStopSpeaking: () -> Unit,
    onConfirmCurrentLocation: (String) -> Unit,
    onDismissTaskCard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showLocationDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    if (showLocationDialog) {
        ConfirmCurrentLocationDialog(
            currentLocation = preferences.confirmedCurrentLocation,
            savedLocationSuggestions = savedLocations.map { it.fullName },
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
        // Header + Status Strip
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "S O L",
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "دستیار شخصی آفلاین فارسی",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(50),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "آفلاین",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(50),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (batteryStatus.isCharging) {
                                        Icons.Default.BatteryChargingFull
                                    } else {
                                        Icons.Default.BatteryStd
                                    },
                                    contentDescription = "باتری",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "${batteryStatus.levelPercent}%",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Confirmed Current Location Pill (Strict Separation from Saved Locations)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLocationDialog = true }
                        .testTag("current_location_pill"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = "موقعیت فعلی",
                                tint = if (preferences.confirmedCurrentLocation.isNotBlank()) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (preferences.confirmedCurrentLocation.isNotBlank()) {
                                    "موقعیت فعلی تأییدشده: ${preferences.confirmedCurrentLocation}"
                                } else {
                                    "موقعیت فعلی ثبت‌نشده (برای تأیید دستی بزنید)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = "ویرایش",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // Interactive Voice Orb Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SolVoiceOrb(
                listeningState = listeningState,
                isSpeaking = isSpeaking,
                rmsLevel = micRms,
                onClick = onMicClick,
            )

            val voiceStatusText = when (listeningState) {
                is VoiceListeningState.Listening ->
                    if (partialSpeechText.isNotBlank()) "«$partialSpeechText»" else "در حال شنیدن صدای فارسی شما…"
                is VoiceListeningState.Processing -> "در حال پردازش گفتار…"
                is VoiceListeningState.Error -> listeningState.messageFa
                VoiceListeningState.Idle -> when {
                    isSpeaking -> "Sol در حال صحبت است…"
                    ttsAvailability == TtsAvailability.MISSING_PERSIAN_DATA ->
                        "پاسخ متنی فعال است (بستهٔ صدای فارسی TTS روی گوشی نصب نیست)"
                    else -> "برای فرمان صوتی دایره را لمس کنید یا در پایین بنویسید"
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                Text(
                    text = voiceStatusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (listeningState is VoiceListeningState.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (listeningState is VoiceListeningState.Error) {
                    TextButton(onClick = onClearVoiceError) {
                        Text("بستن", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (isSpeaking) {
                    TextButton(onClick = onStopSpeaking) {
                        Icon(
                            imageVector = Icons.Default.VolumeOff,
                            contentDescription = "توقف صدا",
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("توقف صدا", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Quick Persian Command Chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(QUICK_COMMANDS) { sample ->
                AssistChip(
                    onClick = { onQuickCommand(sample) },
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

        // Multi-Step Task Card (when running)
        if (activeTask != null) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
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
                    placeholder = { Text("فرمانت را به فارسی بنویس…") },
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
                        contentDescription = "دکمه میکروفون",
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
                        contentDescription = "ارسال فرمان",
                    )
                }
            }
        }
    }
}
