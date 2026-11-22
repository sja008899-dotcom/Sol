package com.example.sol.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.sol.knowledge.CapabilityCategory
import com.example.sol.knowledge.SolCapabilityItem
import com.example.sol.memory.BackgroundJobLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PERIODIC_SUPPORTED_IDS = setOf(
    "bg_daily_briefing",
    "bg_battery_guard",
    "bg_memory_backup",
    "bg_memory_indexer",
    "bg_location_checkin",
    "bg_history_cleanup",
)

@Composable
fun CapabilitiesScreen(
    capabilities: List<SolCapabilityItem>,
    enabledPeriodicJobs: Set<String>,
    backgroundLogs: List<BackgroundJobLogEntity>,
    hasNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onRunBackgroundJob: (String) -> Unit,
    onTogglePeriodicJob: (String, Boolean) -> Unit,
    onClearBackgroundLogs: () -> Unit,
    onOpenBookRoleStudio: () -> Unit,
    onRunSampleCommand: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val bgItems = remember(capabilities) {
        capabilities.filter { it.category == CapabilityCategory.BACKGROUND }
    }
    val advItems = remember(capabilities) {
        capabilities.filter { it.category == CapabilityCategory.ADVANCED_LEARNING }
    }
    val coreItems = remember(capabilities) {
        capabilities.filter { it.category == CapabilityCategory.CORE_ASSISTANT }
    }
    val visibleLogs = remember(backgroundLogs) { backgroundLogs.take(15) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Top Header with sleek hero artwork (heightIn scales safely with fontScale >= 1.3)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 144.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_sol_hero_banner_1795044139786),
                contentDescription = stringResource(R.string.capabilities_banner_cd),
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.25f),
                                MaterialTheme.colorScheme.background,
                            )
                        )
                    ),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.capabilities_title_fmt, capabilities.size),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(
                            R.string.capabilities_subtitle_fmt,
                            bgItems.size,
                            advItems.size,
                            coreItems.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasNotificationPermission) {
                item(key = "notif_permission_prompt") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    Icons.Default.NotificationsActive,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.capabilities_notif_prompt),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Button(
                                onClick = onRequestNotificationPermission,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.action_enable))
                            }
                        }
                    }
                }
            }

            // Section 1: Background Execution
            item(key = "section_banner_bg") {
                SectionBanner(
                    title = "بخش ۱: ${bgItems.size} اجرای پس‌زمینه (AlarmManager، WorkManager و سرویس صوتی)",
                    subtitle = "هر مورد را می‌توانید فوراً اجرا کنید یا اجرای دوره‌ای آن را روشن بگذارید.",
                )
            }
            items(bgItems, key = { it.id }) { item ->
                BackgroundCapabilityCard(
                    item = item,
                    supportsPeriodic = item.id in PERIODIC_SUPPORTED_IDS,
                    isPeriodicEnabled = item.id in enabledPeriodicJobs,
                    onRunNow = {
                        if (item.id == "bg_book_fetch_worker") {
                            onOpenBookRoleStudio()
                        } else {
                            onRunBackgroundJob(item.id)
                        }
                    },
                    onTogglePeriodic = { enabled -> onTogglePeriodicJob(item.id, enabled) },
                    onTryVoiceCommand = {
                        onBack()
                        onRunSampleCommand(item.sampleCommandFa)
                    },
                )
            }

            // Section 2: Advanced Web Book + Voice Role Learning
            item(key = "section_banner_adv") {
                Spacer(modifier = Modifier.height(4.dp))
                SectionBanner(
                    title = "بخش ۲: ${advItems.size} دستور پیشرفته — اینترنت + کتاب + یادگیری نقش صوتی",
                    subtitle = "با تأیید شما کتاب را از اینترنت یا فایل می‌گیرد و با توضیحات صوتی شما نقش جدید را در حافظه ثبت می‌کند.",
                )
            }
            items(advItems, key = { it.id }) { item ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${item.number}. ${item.titleFa}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(item.descriptionFa, style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "فرمان نمونه: «${item.sampleCommandFa}»",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onOpenBookRoleStudio) {
                                Text("شروع جست‌وجوی کتاب و آموزش نقش")
                            }
                            OutlinedButton(onClick = {
                                onBack()
                                onRunSampleCommand(item.sampleCommandFa)
                            }) {
                                Text("اجرای فرمان")
                            }
                        }
                    }
                }
            }

            // Section 3: Core Smart Assistant & Offline Utility Skills
            item(key = "section_banner_core") {
                Spacer(modifier = Modifier.height(4.dp))
                SectionBanner(
                    title = "بخش ۳: ${coreItems.size} مهارت هستهٔ هوشمند، حافظه و ابزار آفلاین",
                    subtitle = "روی هر قابلیت بزنید تا فرمان فارسی آن مستقیماً اجرا شود.",
                )
            }
            items(coreItems, key = { it.id }) { item ->
                CoreCapabilityCard(
                    item = item,
                    onRunSample = {
                        onBack()
                        onRunSampleCommand(item.sampleCommandFa)
                    },
                )
            }

            // Background Execution Live Logs
            item(key = "section_header_logs") {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.capabilities_logs_header),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (visibleLogs.isNotEmpty()) {
                        TextButton(
                            onClick = onClearBackgroundLogs,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.action_cleanup))
                        }
                    }
                }
            }
            if (visibleLogs.isEmpty()) {
                item(key = "logs_empty") {
                    Text(
                        text = stringResource(R.string.capabilities_logs_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(visibleLogs, key = { it.id }) { log ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "${log.titleFa} (${log.status})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = formatter.format(Date(log.executedAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (log.detailFa.isNotBlank()) {
                                Text(log.detailFa, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionBanner(title: String, subtitle: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BackgroundCapabilityCard(
    item: SolCapabilityItem,
    supportsPeriodic: Boolean,
    isPeriodicEnabled: Boolean,
    onRunNow: () -> Unit,
    onTogglePeriodic: (Boolean) -> Unit,
    onTryVoiceCommand: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${item.number}. ${item.titleFa}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (supportsPeriodic) {
                    Switch(
                        checked = isPeriodicEnabled,
                        onCheckedChange = onTogglePeriodic,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.descriptionFa,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onRunNow,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.capabilities_run_now))
                }
                OutlinedButton(
                    onClick = onTryVoiceCommand,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.capabilities_try_command))
                }
            }
        }
    }
}

@Composable
private fun CoreCapabilityCard(
    item: SolCapabilityItem,
    onRunSample: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${item.number}. ${item.titleFa}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = item.descriptionFa,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "نمونه: «${item.sampleCommandFa}»",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onRunSample,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.action_try))
            }
        }
    }
}
