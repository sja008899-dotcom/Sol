package com.example.sol.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.sol.calendar.JalaliCalendar
import com.example.sol.device.StorageStatus
import com.example.sol.knowledge.SlmModelSpec
import com.example.sol.security.RamUsageSnapshot
import java.util.Locale

@Composable
fun ReliabilityScreen(
    isIgnoringBatteryOptimizations: Boolean,
    canScheduleExactAlarms: Boolean,
    areNotificationsEnabled: Boolean,
    lastReminderDeliveredAt: Long,
    lastReminderDeliveredTitle: String,
    ramSnapshot: RamUsageSnapshot,
    storageStatus: StorageStatus,
    recommendedSlmModel: SlmModelSpec,
    onOpenBatteryOptimizationSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onScheduleSelfTestReminder: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val lastReminderLabel = remember(lastReminderDeliveredAt, lastReminderDeliveredTitle) {
        if (lastReminderDeliveredAt > 0L) {
            val jalali = JalaliCalendar.formatDateTimeFa(lastReminderDeliveredAt)
            val title = lastReminderDeliveredTitle.ifBlank { "یادآور Sol" }
            jalali to title
        } else {
            null
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("reliability_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "reliability_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("reliability_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.reliability_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.reliability_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 1. Battery Optimization Card (PowerManager.isIgnoringBatteryOptimizations)
        item(key = "reliability_battery") {
            ReliabilityCard(
                title = stringResource(R.string.reliability_battery_card_title),
                icon = Icons.Default.BatteryChargingFull,
                isHealthy = isIgnoringBatteryOptimizations,
                statusText = stringResource(
                    if (isIgnoringBatteryOptimizations) {
                        R.string.reliability_battery_exempt
                    } else {
                        R.string.reliability_battery_restricted
                    }
                ),
                explanationText = stringResource(R.string.reliability_battery_explain),
            ) {
                Button(
                    onClick = onOpenBatteryOptimizationSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("reliability_battery_button"),
                ) {
                    Text(stringResource(R.string.reliability_battery_button))
                }
            }
        }

        // 2. Exact Alarm Card (Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        item(key = "reliability_exact_alarm") {
            ReliabilityCard(
                title = stringResource(R.string.reliability_exact_alarm_card_title),
                icon = Icons.Default.Alarm,
                isHealthy = canScheduleExactAlarms,
                statusText = stringResource(
                    if (canScheduleExactAlarms) {
                        R.string.reliability_exact_alarm_granted
                    } else {
                        R.string.reliability_exact_alarm_denied
                    }
                ),
                explanationText = stringResource(R.string.reliability_exact_alarm_explain),
            ) {
                OutlinedButton(
                    onClick = onOpenExactAlarmSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("reliability_exact_alarm_button"),
                ) {
                    Text(stringResource(R.string.reliability_exact_alarm_button))
                }
            }
        }

        // 3. Notifications Status Card
        item(key = "reliability_notifications") {
            ReliabilityCard(
                title = stringResource(R.string.reliability_notif_card_title),
                icon = Icons.Default.NotificationsActive,
                isHealthy = areNotificationsEnabled,
                statusText = stringResource(
                    if (areNotificationsEnabled) {
                        R.string.reliability_notif_enabled
                    } else {
                        R.string.reliability_notif_disabled
                    }
                ),
                explanationText = null,
            ) {
                OutlinedButton(
                    onClick = onOpenNotificationSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("reliability_notification_button"),
                ) {
                    Text(stringResource(R.string.reliability_notif_button))
                }
            }
        }

        // 4. Samsung One UI Core 4.1 Step-by-Step Guide
        item(key = "reliability_samsung_guide") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reliability_samsung_guide_card"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.reliability_samsung_guide_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.reliability_samsung_guide_steps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // 5. Last Reminder Diagnostic & 1-Minute Self-Test
        item(key = "reliability_self_test") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.reliability_last_reminder_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (lastReminderLabel != null) {
                            stringResource(
                                R.string.reliability_last_reminder_fmt,
                                lastReminderLabel.first,
                                lastReminderLabel.second,
                            )
                        } else {
                            stringResource(R.string.reliability_last_reminder_none)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.reliability_self_test_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onScheduleSelfTestReminder,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("reliability_self_test_button"),
                    ) {
                        Text(stringResource(R.string.reliability_self_test_button))
                    }
                }
            }
        }

        // 6. RAM / Disk & Recommended SLM Model Card
        item(key = "reliability_hardware") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reliability_hardware_card"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.reliability_hardware_card_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            R.string.reliability_ram_fmt,
                            ramSnapshot.availableDeviceRamMb,
                            ramSnapshot.totalDeviceRamMb,
                            ramSnapshot.appHeapUsedMb,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = String.format(
                            Locale.US,
                            "فضای خالی دیسک: %.1f گیگابایت از %.1f گیگابایت",
                            storageStatus.freeGb,
                            storageStatus.totalGb,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.reliability_recommended_model_fmt,
                                recommendedSlmModel.titleFa,
                                recommendedSlmModel.approxSizeMb,
                                recommendedSlmModel.minFreeRamMb,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReliabilityCard(
    title: String,
    icon: ImageVector,
    isHealthy: Boolean,
    statusText: String,
    explanationText: String?,
    actionContent: @Composable () -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isHealthy) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = if (isHealthy) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isHealthy) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (!explanationText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = explanationText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            actionContent()
        }
    }
}
