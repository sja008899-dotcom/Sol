package com.example.sol.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sol.contacts.ResolvedContact
import com.example.sol.tasks.ActiveTaskState
import com.example.sol.tasks.CommunicationChannel
import com.example.sol.tasks.PendingCommunicationConfirmation
import com.example.sol.tasks.TaskStepStatus
import com.example.sol.voice.VoiceListeningState

@Composable
fun SolVoiceOrb(
    listeningState: VoiceListeningState,
    isSpeaking: Boolean,
    rmsLevel: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isListening = listeningState is VoiceListeningState.Listening
    val isProcessing = listeningState is VoiceListeningState.Processing

    val infiniteTransition = rememberInfiniteTransition(label = "sol_orb_pulse")
    val ambientPulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ambient_pulse",
    )

    val dynamicScale by animateFloatAsState(
        targetValue = when {
            isListening -> 1f + (rmsLevel * 0.28f)
            isSpeaking -> 1.08f
            else -> ambientPulse
        },
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 320f),
        label = "orb_scale",
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val orbCoreColor by animateColorAsState(
        targetValue = when {
            isListening -> secondaryColor
            isSpeaking -> MaterialTheme.colorScheme.tertiary
            else -> primaryColor
        },
        label = "orb_core_color",
    )

    Box(
        modifier = modifier
            .size(96.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag("voice_orb_button"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.34f * dynamicScale

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        orbCoreColor.copy(alpha = if (isListening) 0.40f else 0.22f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = size.minDimension * 0.50f,
                ),
                radius = size.minDimension * 0.50f,
                center = center,
            )

            drawCircle(
                color = orbCoreColor.copy(alpha = if (isListening) 0.58f else 0.32f),
                radius = baseRadius * 1.24f,
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        orbCoreColor.copy(alpha = 0.96f),
                        orbCoreColor.copy(alpha = 0.46f),
                    ),
                    center = center,
                    radius = baseRadius,
                ),
                radius = baseRadius,
                center = center,
            )
        }

        Icon(
            imageVector = when {
                isListening -> Icons.Default.Stop
                isSpeaking -> Icons.Default.GraphicEq
                isProcessing -> Icons.Default.HourglassTop
                else -> Icons.Default.Mic
            },
            contentDescription = if (isListening) "توقف شنود" else "صحبت با Sol",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
fun InternetBookFetchDialog(
    pending: PendingBookFetchConfirmation,
    onUpdateQuery: (String, String) -> Unit,
    onConfirmFetch: (runInBackground: Boolean) -> Unit,
    onPickLocalFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(pending.query) { mutableStateOf(pending.query) }
    var roleHint by remember(pending.roleHint) { mutableStateOf(pending.roleHint) }
    var runBg by remember(pending.runInBackground) { mutableStateOf(pending.runInBackground) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("تأیید اتصال اختیاری به اینترنت") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "طبق قانون امنیتی Sol، اتصال به اینترنت فقط با تأیید شما برای جست‌وجو در ویکی‌پدیا فارسی، کتابخانه‌های عمومی (Gutendex / Open Library) یا خواندن لینک وب انجام می‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onUpdateQuery(it, roleHint)
                    },
                    label = { Text("موضوع، نام کتاب یا لینک وب (URL)") },
                    placeholder = { Text("مثلاً: هوش مصنوعی، Atomic Habits یا https://...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = roleHint,
                    onValueChange = {
                        roleHint = it
                        onUpdateQuery(query, it)
                    },
                    label = { Text("نقش پیشنهادی برای Sol (اختیاری)") },
                    placeholder = { Text("مثلاً: مربی بهره‌وری و عادت‌های روزانه") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { runBg = !runBg },
                ) {
                    Checkbox(checked = runBg, onCheckedChange = { runBg = it })
                    Text("دریافت در پس‌زمینه (WorkManager) با اعلان", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onPickLocalFile()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("انتخاب فایل PDF / EPUB / HTML / TXT از گوشی")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmFetch(runBg) },
                enabled = query.isNotBlank(),
            ) {
                Text(if (runBg) "زمان‌بندی در پس‌زمینه" else "تأیید و دریافت")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("لغو") }
        },
    )
}

@Composable
fun VoiceRoleTeachingDialog(
    session: VoiceRoleTeachingSession,
    onUpdateDraft: (roleName: String, explanation: String) -> Unit,
    onStartVoiceExplanation: () -> Unit,
    onStopVoiceExplanation: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        title = { Text("آموزش نقش و حافظه با صدای شما") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                session.book?.let { b ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "کتاب ذخیره‌شده: ${b.title}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "نویسنده: ${b.author}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = b.summaryText.take(160) + if (b.summaryText.length > 160) "…" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = session.roleNameDraft,
                    onValueChange = { onUpdateDraft(it, session.voiceExplanationDraft) },
                    label = { Text("نام نقش جدید Sol") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = {
                        if (session.isListeningForExplanation) onStopVoiceExplanation() else onStartVoiceExplanation()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (session.isListeningForExplanation) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        contentColor = if (session.isListeningForExplanation) {
                            MaterialTheme.colorScheme.onSecondary
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = if (session.isListeningForExplanation) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (session.isListeningForExplanation) {
                            "در حال شنود توضیح صوتی شما… (توقف)"
                        } else {
                            "بیان توضیح صوتی شما درباره کتاب و نقش"
                        }
                    )
                }

                OutlinedTextField(
                    value = session.voiceExplanationDraft,
                    onValueChange = { onUpdateDraft(session.roleNameDraft, it) },
                    label = { Text("توضیحات صوتی / متنی شما برای Sol") },
                    placeholder = { Text("مثلاً: بر اساس این کتاب، هر روز صبح برنامه کاری من را اولویت‌بندی کن…") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text("ذخیره در حافظه و فعال‌سازی نقش")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("بستن") }
        },
    )
}

@Composable
fun ActiveTaskProgressCard(
    task: ActiveTaskState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = task.titleFa,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (task.isComplete) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "بستن گزارش مراحل",
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            task.steps.forEach { step ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon = when (step.status) {
                        TaskStepStatus.DONE -> Icons.Default.CheckCircle
                        TaskStepStatus.FAILED -> Icons.Default.ErrorOutline
                        TaskStepStatus.WAITING_CONFIRMATION -> Icons.Default.Security
                        else -> Icons.Default.HourglassTop
                    }
                    val tint = when (step.status) {
                        TaskStepStatus.DONE -> MaterialTheme.colorScheme.tertiary
                        TaskStepStatus.FAILED -> MaterialTheme.colorScheme.error
                        TaskStepStatus.WAITING_CONFIRMATION -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${step.index}. ${step.titleFa}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        if (step.detailFa.isNotBlank()) {
                            Text(
                                text = step.detailFa,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CommunicationConfirmationDialog(
    confirmation: PendingCommunicationConfirmation,
    isListeningVoiceConfirm: Boolean,
    voiceConfirmFeedback: String,
    onStartVoiceConfirm: () -> Unit,
    onStopVoiceConfirm: () -> Unit,
    onSelectCandidate: (ResolvedContact) -> Unit,
    onSelectChannel: (CommunicationChannel) -> Unit,
    onMessageChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = "تأیید امنیتی Sol",
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        title = {
            Text(
                text = "تأیید عملیات ارتباطی",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    color = if (isListeningVoiceConfirm) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (isListeningVoiceConfirm) onStopVoiceConfirm() else onStartVoiceConfirm()
                        }
                        .testTag("voice_confirm_toggle"),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (isListeningVoiceConfirm) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = "تأیید صوتی",
                            tint = if (isListeningVoiceConfirm) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isListeningVoiceConfirm) {
                                    "در حال شنود: بگویید «تأیید» یا «لغو»…"
                                } else {
                                    "تأیید یا لغو صوتی (برای گفتن «تأیید» / «لغو» بزنید)"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (voiceConfirmFeedback.isNotBlank()) {
                                Text(
                                    text = voiceConfirmFeedback,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "مخاطب انتخاب‌شده:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${confirmation.selectedContact.name}  •  ${confirmation.selectedContact.phone}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (confirmation.selectedContact.relation.isNotBlank()) {
                            Text(
                                text = "نسبت: ${confirmation.selectedContact.relation}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                if (confirmation.candidateContacts.size > 1) {
                    Text(
                        text = "چند مخاطب پیدا شد؛ در صورت نیاز تغییر دهید:",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    confirmation.candidateContacts.take(4).forEach { candidate ->
                        FilterChip(
                            selected = candidate.id == confirmation.selectedContact.id,
                            onClick = { onSelectCandidate(candidate) },
                            label = { Text("${candidate.name} (${candidate.phone})") },
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = confirmation.channel == CommunicationChannel.CALL,
                        onClick = { onSelectChannel(CommunicationChannel.CALL) },
                        leadingIcon = { Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("شماره‌گیر تماس") },
                    )
                    FilterChip(
                        selected = confirmation.channel == CommunicationChannel.SMS,
                        onClick = { onSelectChannel(CommunicationChannel.SMS) },
                        leadingIcon = { Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("پیامک متنی") },
                    )
                }

                if (confirmation.channel == CommunicationChannel.SMS) {
                    OutlinedTextField(
                        value = confirmation.messageBody,
                        onValueChange = onMessageChange,
                        label = { Text("متن پیام با معرفی شفاف Sol") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("confirm_sms_body_input"),
                        minLines = 3,
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = confirmation.callScriptPreview,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                if (confirmation.confirmedCurrentLocation.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "موقعیت فعلی تأییدشده: ${confirmation.confirmedCurrentLocation}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.testTag("confirm_communication_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(if (confirmation.channel == CommunicationChannel.CALL) "تأیید و باز کردن شماره‌گیر" else "تأیید و ارسال در پیامک")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.testTag("cancel_communication_button"),
            ) {
                Text("لغو")
            }
        },
    )
}

@Composable
fun BackupPasswordDialog(
    mode: BackupDialogMode,
    onConfirm: (password: String, replaceExisting: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var replaceExisting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                if (mode == BackupDialogMode.EXPORT) {
                    "رمزگذاری فایل پشتیبان (.solbackup)"
                } else {
                    "رمزگشایی و بازیابی (.solbackup)"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (mode == BackupDialogMode.EXPORT) {
                        "کل حافظه، مترادف‌ها، مخاطبین، کتاب‌ها (همراه با بخش‌های BM25) و نقش‌ها با AES-256-GCM رمزنگاری می‌شوند."
                    } else {
                        "رمز فایل پشتیبان را وارد کنید تا داده‌های رمزدار روی این گوشی بازیابی شوند."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("رمز عبور پشتیبان (حداقل ۴ کاراکتر)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (mode == BackupDialogMode.IMPORT) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { replaceExisting = !replaceExisting },
                    ) {
                        Checkbox(checked = replaceExisting, onCheckedChange = { replaceExisting = it })
                        Text("جایگزینی کامل به جای ادغام با حافظه فعلی", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password, replaceExisting) },
                enabled = password.length >= 4,
            ) {
                Text(if (mode == BackupDialogMode.EXPORT) "رمزنگاری و ذخیره" else "رمزگشایی و بازیابی")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("لغو") }
        },
    )
}

@Composable
fun ConfirmCurrentLocationDialog(
    currentLocation: String,
    savedLocationSuggestions: List<String>,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var locationText by remember(currentLocation) { mutableStateOf(currentLocation) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تأیید موقعیت فعلی") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "موقعیت فعلی فقط با تأیید دستی شما ثبت می‌شود و با مکان‌های ذخیره‌شده اشتباه گرفته نمی‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = locationText,
                    onValueChange = { locationText = it },
                    label = { Text("الان کجا هستید؟") },
                    placeholder = { Text("مثلاً: شرکت کارنو صنعت ماکیان") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (savedLocationSuggestions.isNotEmpty()) {
                    Text("مکان‌های ذخیره‌شده:", style = MaterialTheme.typography.labelMedium)
                    savedLocationSuggestions.take(4).forEach { suggestion ->
                        TextButton(onClick = { locationText = suggestion }) {
                            Text(suggestion)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(locationText)
                onDismiss()
            }) {
                Text("ثبت موقعیت فعلی")
            }
        },
        dismissButton = {
            Row {
                if (currentLocation.isNotBlank()) {
                    TextButton(onClick = {
                        onSave("")
                        onDismiss()
                    }) {
                        Text("پاک کردن")
                    }
                }
                TextButton(onClick = onDismiss) { Text("بستن") }
            }
        },
    )
}

@Composable
fun ChatBubbleCard(
    item: ChatMessageItem,
    onSpeakClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUser = item.isUser
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
            border = if (!isUser) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isUser) "شما" else "Sol",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!isUser) {
                        IconButton(
                            onClick = onSpeakClick,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "پخش صوتی پاسخ Sol",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
