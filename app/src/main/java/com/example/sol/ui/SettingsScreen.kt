package com.example.sol.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sol.device.BatteryStatus
import com.example.sol.device.InstalledAppShortcut
import com.example.sol.memory.ConfirmationPolicy
import com.example.sol.memory.DeliveryPreference
import com.example.sol.memory.InvocationMode
import com.example.sol.memory.SolPreferences
import com.example.sol.memory.VoicePersona
import com.example.sol.security.SecurityAuditItem
import com.example.sol.voice.AudioVolumeStatus
import com.example.sol.voice.TtsAvailability
import com.example.sol.voice.TtsVoiceInfo
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    preferences: SolPreferences,
    ttsAvailability: TtsAvailability,
    persianVoices: List<TtsVoiceInfo>,
    batteryStatus: BatteryStatus,
    volumeStatus: AudioVolumeStatus,
    launchableApps: List<InstalledAppShortcut>,
    securityAudit: List<SecurityAuditItem>,
    onOwnerNameChange: (String) -> Unit,
    onVoicePersonaChange: (VoicePersona) -> Unit,
    onSelectTtsVoice: (String) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onAutoSpeakChange: (Boolean) -> Unit,
    onTestVoice: () -> Unit,
    onInvocationModeChange: (InvocationMode) -> Unit,
    onDeliveryPreferenceChange: (DeliveryPreference) -> Unit,
    onConfirmationPolicyChange: (ConfirmationPolicy) -> Unit,
    onDarkThemeChange: (Boolean) -> Unit,
    onVolumePercentChange: (Int) -> Unit,
    onOpenWifi: () -> Unit,
    onOpenLocation: () -> Unit,
    onOpenBluetooth: () -> Unit,
    onOpenBattery: () -> Unit,
    onLaunchApp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var ownerDraft by remember(preferences.ownerName) { mutableStateOf(preferences.ownerName) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                text = "تنظیمات شخصیت و کنترل گوشی",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "پیکربندی صدای فارسی، روش ارتباط، کنترل امکانات مجاز گوشی و ممیزی امنیتی Sol",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 1. Owner Name & Theme
        item {
            SectionCard(title = "هویت شما و ظاهر برنامه") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = ownerDraft,
                        onValueChange = { ownerDraft = it },
                        label = { Text("نام شما برای معرفی Sol") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { onOwnerNameChange(ownerDraft) }) {
                        Text("ثبت")
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("تم شبانه Sol (تاریک)", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = preferences.darkTheme, onCheckedChange = onDarkThemeChange)
                }
            }
        }

        // 2. Voice & Speech Engine Settings
        item {
            SectionCard(title = "۱. صدای Sol و گفتار فارسی") {
                Text(
                    text = ttsAvailability.labelFa,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ttsAvailability == TtsAvailability.READY_PERSIAN) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("سبک صدای Sol:", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoicePersona.entries.forEach { persona ->
                        FilterChip(
                            selected = preferences.voicePersona == persona,
                            onClick = { onVoicePersonaChange(persona) },
                            label = { Text(persona.labelFa) },
                        )
                    }
                }

                if (persianVoices.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("صداهای فارسی نصب‌شده روی گوشی:", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        persianVoices.forEach { v ->
                            FilterChip(
                                selected = preferences.preferredTtsVoiceName == v.name,
                                onClick = { onSelectTtsVoice(v.name) },
                                label = {
                                    val tag = if (v.isOffline) "آفلاین" else "شبکه"
                                    Text("${v.localeTag} ($tag)")
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("سرعت گفتار", style = MaterialTheme.typography.bodyMedium)
                    Text("${(preferences.speechRate * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                }
                Slider(
                    value = preferences.speechRate,
                    onValueChange = onSpeechRateChange,
                    valueRange = 0.65f..1.4f,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("پاسخ صوتی خودکار", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = preferences.autoSpeak, onCheckedChange = onAutoSpeakChange)
                }

                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(onClick = onTestVoice) {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تست صدای Sol")
                }
            }
        }

        // 3. Invocation, Delivery & Confirmation Policy
        item {
            SectionCard(title = "۲ تا ۴. نحوه فراخوانی، ارسال پیام و سطح اختیار") {
                Text("چگونه صدایش کنی:", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InvocationMode.entries.forEach { mode ->
                        FilterChip(
                            selected = preferences.invocationMode == mode,
                            onClick = { onInvocationModeChange(mode) },
                            label = { Text(mode.labelFa) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("نحوه انتقال پیام به مخاطب:", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeliveryPreference.entries.forEach { pref ->
                        FilterChip(
                            selected = preferences.deliveryPreference == pref,
                            onClick = { onDeliveryPreferenceChange(pref) },
                            label = { Text(pref.labelFa) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("سطح اختیار و تأیید:", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConfirmationPolicy.entries.forEach { policy ->
                        FilterChip(
                            selected = preferences.confirmationPolicy == policy,
                            onClick = { onConfirmationPolicyChange(policy) },
                            label = { Text(policy.labelFa) },
                        )
                    }
                }
            }
        }

        // 4. Device Controller
        item {
            SectionCard(title = "کنترل امکانات مجاز گوشی (Device Controller)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "باتری: ${batteryStatus.levelPercent}% • ${batteryStatus.chargingSourceLabel} • ${batteryStatus.temperatureCelsius}°C",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                val volPct = ((volumeStatus.mediaCurrent.toFloat() / volumeStatus.mediaMax.toFloat()) * 100f).roundToInt()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("صدای رسانه (${volumeStatus.mediaCurrent}/${volumeStatus.mediaMax})", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("$volPct%", style = MaterialTheme.typography.labelMedium)
                }
                Slider(
                    value = volPct.toFloat(),
                    onValueChange = { onVolumePercentChange(it.roundToInt()) },
                    valueRange = 0f..100f,
                )

                Text("میانبر تنظیمات سیستم:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AssistChip(
                        onClick = onOpenWifi,
                        leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("تنظیمات وای‌فای") },
                    )
                    AssistChip(
                        onClick = onOpenLocation,
                        leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("تنظیمات مکان") },
                    )
                    AssistChip(
                        onClick = onOpenBluetooth,
                        leadingIcon = { Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("بلوتوث") },
                    )
                    AssistChip(
                        onClick = onOpenBattery,
                        leadingIcon = { Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("مصرف باتری") },
                    )
                }

                if (launchableApps.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("اجرای سریع برنامه‌ها:", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        launchableApps.take(8).forEach { app ->
                            AssistChip(
                                onClick = { onLaunchApp(app.label) },
                                leadingIcon = {
                                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                                },
                                label = { Text(app.label) },
                            )
                        }
                    }
                }
            }
        }

        // 5. Security & Privacy Audit (7 Rules)
        item {
            SectionCard(title = "قوانین امنیتی و حریم خصوصی Sol") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    securityAudit.forEach { item ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = if (item.isCompliant) Icons.Default.CheckCircle else Icons.Default.Security,
                                contentDescription = null,
                                tint = if (item.isCompliant) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = item.titleFa,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = item.detailFa,
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
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}
