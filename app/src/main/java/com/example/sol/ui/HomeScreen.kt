package com.example.sol.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import com.example.sol.voice.ActiveAsrBackend
import com.example.sol.voice.TtsAvailability
import com.example.sol.voice.VoiceListeningState
import com.example.sol.voice.VoskModelStatus

private val QUICK_COMMANDS = listOf(
    "امروز چندم برج است؟",
    "چهارشنبه ساعت ۹ صبح یادآوری کن جلسه دارم",
    "دو روز دیگه به علی پیام یادآوری بفرست",
    "۲۰ قابلیت",
    "به اینترنت برو کتاب Atomic Habits را پیدا کن و با توضیحات صوتی من به حافظه و نقشت اضافه کن",
    "از حافظه در پس‌زمینه پشتیبان بگیر",
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
    activeAsrBackend: ActiveAsrBackend,
    voskModelStatus: VoskModelStatus,
    ttsAvailability: TtsAvailability,
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
    onClearVoiceError: () -> Unit,
    onSpeakMessage: (String) -> Unit,
    onStopSpeaking: () -> Unit,
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
        // Sleek Hero Header Card using img_sol_hero_banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(22.dp)),
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_sol_hero_banner_1795044139786),
                contentDescription = "پس‌زمینه هنری Sol",
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "S O L  v2",
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = activeRoles.firstOrNull()?.let { "نقش فعال: ${it.roleName}" }
                                ?: "دستیار شخصی فارسی • آفلاین و مستقل",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
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
                                    contentDescription = "باتری",
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
                                text = "۲۰ قابلیت و پس‌زمینه",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.88f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
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
                                text = "کتاب (PDF/EPUB) و نقش",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
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
                        .clickable { showLocationDialog = true }
                        .testTag("current_location_pill"),
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
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = "موقعیت فعلی",
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
                            .clickable(onClick = onWakeWordClick)
                            .testTag("wake_word_button"),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Hearing,
                                contentDescription = "بیدارباش صوتی Sol",
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
                                    "منتظر «Sol»…"
                                } else {
                                    "بیدارباش «Sol»"
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            val backendLabel = if (activeAsrBackend == ActiveAsrBackend.VOSK_OFFLINE) {
                "Vosk ۱۰۰٪ آفلاین"
            } else {
                "گفتار سیستم"
            }
            val voiceStatusText = when (listeningState) {
                is VoiceListeningState.Listening ->
                    if (partialSpeechText.isNotBlank()) "«$partialSpeechText»" else "در حال شنیدن فارسی ($backendLabel)…"
                is VoiceListeningState.WakeWordArmed ->
                    "گوش‌به‌زنگ کلمهٔ بیدارباش «Sol» یا «سول» هستم (آفلاین)…"
                is VoiceListeningState.Processing -> "در حال پردازش گفتار…"
                is VoiceListeningState.Error -> listeningState.messageFa
                VoiceListeningState.Idle -> when {
                    isSpeaking -> "Sol در حال صحبت است…"
                    else -> "موتور صوتی: $backendLabel • برای فرمان صوتی دایره را لمس کنید"
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

            if (activeAsrBackend == ActiveAsrBackend.ANDROID_SPEECH_RECOGNIZER || preferences.privateModeEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (activeAsrBackend == ActiveAsrBackend.ANDROID_SPEECH_RECOGNIZER) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.testTag("online_asr_badge"),
                        ) {
                            Text(
                                text = "تشخیص گفتار سیستم (احتمال ارسال صدا به اینترنت — برای ۱۰۰٪ آفلاین Vosk را نصب کنید)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 1,
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
                                    contentDescription = "حالت خصوصی فعال",
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(13.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "حالت خصوصی",
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
                            text = "هوش آفلاین در حال نگارش پاسخ…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(
                            onClick = onStopSlmGeneration,
                            modifier = Modifier.testTag("stop_slm_button"),
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("توقف تولید", style = MaterialTheme.typography.labelSmall)
                        }
                    } else if (slmRuntimeState is SlmRuntimeState.Ready) {
                        Text(
                            text = "SLM آماده (${slmRuntimeState.activeBackend.name})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        TextButton(onClick = onResetSlmConversation) {
                            Text("گفت‌وگوی تازه", style = MaterialTheme.typography.labelSmall)
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
