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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
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
            contentDescription = if (isListening) stringResource(R.string.stop_listening) else stringResource(R.string.speak_with_sol),
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
        title = {
            Text(
                text = stringResource(R.string.dialog_internet_book_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.dialog_internet_book_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onUpdateQuery(it, roleHint)
                    },
                    label = { Text(stringResource(R.string.dialog_internet_book_query_label)) },
                    placeholder = { Text(stringResource(R.string.dialog_internet_book_query_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = roleHint,
                    onValueChange = {
                        roleHint = it
                        onUpdateQuery(query, it)
                    },
                    label = { Text(stringResource(R.string.dialog_internet_book_role_label)) },
                    placeholder = { Text(stringResource(R.string.dialog_internet_book_role_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable { runBg = !runBg },
                ) {
                    Checkbox(checked = runBg, onCheckedChange = { runBg = it })
                    Text(stringResource(R.string.dialog_internet_book_bg_check), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onPickLocalFile()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.dialog_internet_book_pick_local))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmFetch(runBg) },
                enabled = query.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    if (runBg) {
                        stringResource(R.string.dialog_internet_book_btn_bg)
                    } else {
                        stringResource(R.string.dialog_internet_book_btn_fg)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun WebSearchConfirmationDialog(
    pending: PendingWebSearchConfirmation,
    onUpdateQuery: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(pending.query) { mutableStateOf(pending.query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = stringResource(R.string.dialog_web_search_title),
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.dialog_web_search_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.dialog_web_search_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onUpdateQuery(it)
                    },
                    label = { Text(stringResource(R.string.dialog_web_search_label)) },
                    placeholder = { Text(stringResource(R.string.dialog_web_search_hint)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("web_search_query_input"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = query.isNotBlank(),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_web_search_button"),
            ) {
                Text(stringResource(R.string.dialog_web_search_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("cancel_web_search_button"),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
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
        title = {
            Text(
                text = stringResource(R.string.dialog_voice_role_title),
                modifier = Modifier.semantics { heading() },
            )
        },
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
                                text = stringResource(R.string.dialog_voice_role_book_fmt, b.title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.dialog_voice_role_author_fmt, b.author),
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
                    label = { Text(stringResource(R.string.dialog_voice_role_name_label)) },
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Icon(
                        imageVector = if (session.isListeningForExplanation) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (session.isListeningForExplanation) {
                            stringResource(R.string.dialog_voice_role_listening)
                        } else {
                            stringResource(R.string.dialog_voice_role_start)
                        }
                    )
                }

                OutlinedTextField(
                    value = session.voiceExplanationDraft,
                    onValueChange = { onUpdateDraft(session.roleNameDraft, it) },
                    label = { Text(stringResource(R.string.dialog_voice_role_notes_label)) },
                    placeholder = { Text(stringResource(R.string.dialog_voice_role_notes_hint)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.dialog_voice_role_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.action_close)) }
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
                    modifier = Modifier.semantics { heading() },
                )
                if (task.isComplete) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_dismiss_task_report),
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
    val talkbackDialogDesc = stringResource(R.string.dialog_comm_talkback_desc)
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.semantics { contentDescription = talkbackDialogDesc },
        icon = {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = stringResource(R.string.cd_security_confirm),
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.dialog_comm_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
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
                        .heightIn(min = 48.dp)
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
                            contentDescription = stringResource(R.string.cd_voice_confirm),
                            tint = if (isListeningVoiceConfirm) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isListeningVoiceConfirm) {
                                    stringResource(R.string.dialog_comm_voice_listening)
                                } else {
                                    stringResource(R.string.dialog_comm_voice_prompt)
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
                            text = stringResource(R.string.dialog_comm_selected_contact),
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
                                text = stringResource(R.string.dialog_comm_relation_fmt, confirmation.selectedContact.relation),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                if (confirmation.candidateContacts.size > 1) {
                    Text(
                        text = stringResource(R.string.dialog_comm_multi_candidates),
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
                        label = { Text(stringResource(R.string.dialog_comm_channel_dialer)) },
                    )
                    FilterChip(
                        selected = confirmation.channel == CommunicationChannel.SMS,
                        onClick = { onSelectChannel(CommunicationChannel.SMS) },
                        leadingIcon = { Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text(stringResource(R.string.dialog_comm_channel_sms)) },
                    )
                }

                if (confirmation.channel == CommunicationChannel.SMS) {
                    OutlinedTextField(
                        value = confirmation.messageBody,
                        onValueChange = onMessageChange,
                        label = { Text(stringResource(R.string.dialog_comm_sms_label)) },
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
                            text = stringResource(R.string.home_confirmed_location_fmt, confirmation.confirmedCurrentLocation),
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
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_communication_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    if (confirmation.channel == CommunicationChannel.CALL) {
                        stringResource(R.string.dialog_comm_btn_call)
                    } else {
                        stringResource(R.string.dialog_comm_btn_sms)
                    }
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("cancel_communication_button"),
            ) {
                Text(stringResource(R.string.action_cancel))
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
    var restoreConfirmed by remember(mode) { mutableStateOf(mode == BackupDialogMode.EXPORT) }

    val minLength = if (mode == BackupDialogMode.EXPORT) 8 else 1
    val strengthLabel = when {
        password.isEmpty() -> ""
        password.length < 8 -> "ضعیف — حداقل ۸ کاراکتر لازم است"
        password.length >= 12 &&
            password.any { it.isDigit() } &&
            password.any { !it.isLetterOrDigit() } -> "قدرت رمز: قوی ✓"
        password.length >= 10 && password.any { it.isDigit() } -> "قدرت رمز: خوب"
        else -> "قدرت رمز: متوسط (افزودن عدد یا نشانه پیشنهاد می‌شود)"
    }

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
                text = if (mode == BackupDialogMode.EXPORT) {
                    stringResource(R.string.dialog_backup_export_title)
                } else {
                    stringResource(R.string.dialog_backup_import_title)
                },
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (mode == BackupDialogMode.EXPORT) {
                        stringResource(R.string.dialog_backup_export_body)
                    } else {
                        stringResource(R.string.dialog_backup_import_body)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = {
                        Text(
                            if (mode == BackupDialogMode.EXPORT) {
                                stringResource(R.string.dialog_backup_pw_export_label)
                            } else {
                                stringResource(R.string.dialog_backup_pw_import_label)
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("backup_password_input"),
                )
                if (mode == BackupDialogMode.EXPORT && strengthLabel.isNotBlank()) {
                    Text(
                        text = strengthLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (password.length < 8) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    )
                }
                if (mode == BackupDialogMode.IMPORT) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable { replaceExisting = !replaceExisting },
                    ) {
                        Checkbox(checked = replaceExisting, onCheckedChange = { replaceExisting = it })
                        Text(stringResource(R.string.dialog_backup_replace_check), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable { restoreConfirmed = !restoreConfirmed }
                            .testTag("confirm_backup_restore_checkbox"),
                    ) {
                        Checkbox(checked = restoreConfirmed, onCheckedChange = { restoreConfirmed = it })
                        Text(
                            stringResource(R.string.dialog_backup_confirm_check),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password, replaceExisting) },
                enabled = password.length >= minLength && restoreConfirmed,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_backup_password_button"),
            ) {
                Text(
                    if (mode == BackupDialogMode.EXPORT) {
                        stringResource(R.string.dialog_backup_btn_export)
                    } else {
                        stringResource(R.string.dialog_backup_btn_import)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.action_cancel)) }
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
        title = {
            Text(
                text = stringResource(R.string.dialog_location_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.dialog_location_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = locationText,
                    onValueChange = { locationText = it },
                    label = { Text(stringResource(R.string.dialog_location_label)) },
                    placeholder = { Text(stringResource(R.string.dialog_location_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (savedLocationSuggestions.isNotEmpty()) {
                    Text(stringResource(R.string.dialog_location_saved), style = MaterialTheme.typography.labelMedium)
                    savedLocationSuggestions.take(4).forEach { suggestion ->
                        TextButton(
                            onClick = { locationText = suggestion },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(suggestion)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(locationText)
                    onDismiss()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.dialog_location_save))
            }
        },
        dismissButton = {
            Row {
                if (currentLocation.isNotBlank()) {
                    TextButton(
                        onClick = {
                            onSave("")
                            onDismiss()
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.action_clear))
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatBubbleCard(
    item: ChatMessageItem,
    onSpeakClick: () -> Unit,
    onChoiceClick: (String) -> Unit = {},
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
                        text = if (isUser) stringResource(R.string.speaker_user_label) else stringResource(R.string.speaker_sol_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!isUser) {
                        IconButton(
                            onClick = onSpeakClick,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = stringResource(R.string.cd_speak_sol_reply),
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
                if (!isUser && item.choices.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item.choices.forEach { choice ->
                            val tag = "dialogue_choice_${choice.id}"
                            when {
                                choice.isDestructive -> {
                                    OutlinedButton(
                                        onClick = { onChoiceClick(choice.submitText) },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error,
                                        ),
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag(tag),
                                    ) {
                                        Text(choice.labelFa)
                                    }
                                }
                                choice.isPrimary -> {
                                    Button(
                                        onClick = { onChoiceClick(choice.submitText) },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag(tag),
                                    ) {
                                        Text(choice.labelFa)
                                    }
                                }
                                else -> {
                                    OutlinedButton(
                                        onClick = { onChoiceClick(choice.submitText) },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag(tag),
                                    ) {
                                        Text(choice.labelFa)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
