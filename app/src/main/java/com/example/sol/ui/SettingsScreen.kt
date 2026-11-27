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
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.sol.device.BatteryStatus
import com.example.sol.device.InstalledAppShortcut
import com.example.sol.knowledge.SlmModelCatalog
import com.example.sol.knowledge.SlmModelSpec
import com.example.sol.knowledge.SlmRuntimeState
import com.example.sol.knowledge.SlmStatus
import com.example.sol.memory.ConfirmationPolicy
import com.example.sol.memory.DeliveryPreference
import com.example.sol.memory.InvocationMode
import com.example.sol.memory.ScheduledAlarmEntity
import com.example.sol.memory.SolPreferences
import com.example.sol.memory.VoicePersona
import com.example.sol.security.RamUsageSnapshot
import com.example.sol.security.SecurityAuditItem
import com.example.sol.voice.AudioVolumeStatus
import com.example.sol.voice.VoskModelStatus
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    preferences: SolPreferences,
    piperStatus: com.example.sol.tts.PiperTtsStatus,
    voskModelStatus: VoskModelStatus,
    vocabularyTermsCount: Int,
    bookChunkCount: Int,
    slmStatus: SlmStatus,
    slmRuntimeState: SlmRuntimeState,
    ramSnapshot: RamUsageSnapshot,
    canScheduleExactAlarms: Boolean,
    scheduledAlarms: List<ScheduledAlarmEntity>,
    batteryStatus: BatteryStatus,
    volumeStatus: AudioVolumeStatus,
    launchableApps: List<InstalledAppShortcut>,
    securityAudit: List<SecurityAuditItem>,
    onOwnerNameChange: (String) -> Unit,
    onVoicePersonaChange: (VoicePersona) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onAutoSpeakChange: (Boolean) -> Unit,
    onTestVoice: () -> Unit,
    onDownloadPiperModel: () -> Unit,
    onPickPiperFiles: () -> Unit,
    onDeletePiperModel: () -> Unit,
    onAcceptPiperRisk: () -> Unit = {},
    onRejectPiperRisk: () -> Unit = {},
    speakerIdState: com.example.sol.speaker.SpeakerIdUiState,
    onDownloadSpeakerModel: () -> Unit,
    onRecordOwnerVoice: () -> Unit,
    onClearOwnerVoice: () -> Unit,
    onPickSpeakerModel: () -> Unit,
    onTestSpeakerMic: () -> Unit = {},
    onTestOwnerVoice: () -> Unit = {},
    onInstallAllVoiceModelsNow: () -> Unit = {},
    isMicServiceRunning: Boolean,
    onScreenOffMicToggle: (Boolean) -> Unit,
    onContinuousListeningToggle: (Boolean) -> Unit,
    onVoskOfflineToggle: (Boolean) -> Unit,
    onWakeWordToggle: (Boolean) -> Unit,
    onVadSilenceTimeoutChange: (Long) -> Unit,
    onMaxVoiceListenSecondsChange: (Int) -> Unit,
    onWakeWordTimeoutMinutesChange: (Int) -> Unit,
    onVoiceCueFeedbackChange: (Boolean) -> Unit,
    onDownloadVoskModel: () -> Unit,
    onPickVoskZip: () -> Unit,
    onUnloadVoskRam: () -> Unit,
    onDeleteVoskModel: () -> Unit,
    onSlmToggle: (Boolean) -> Unit,
    onSlmPreferGpuToggle: (Boolean) -> Unit,
    onDownloadSlmModel: (SlmModelSpec) -> Unit,
    onCancelSlmDownload: () -> Unit,
    onPickSlmFile: () -> Unit,
    onTestLoadSlmInRam: () -> Unit,
    onUnloadSlmRam: () -> Unit,
    onDeleteSlmModel: () -> Unit,
    onAddQuickSettingsTile: () -> Unit,
    onRequestExactAlarmPermission: () -> Unit,
    onCancelScheduledAlarm: (ScheduledAlarmEntity) -> Unit,
    onExportEncryptedBackup: () -> Unit,
    onImportEncryptedBackup: () -> Unit,
    onTrimRamNow: () -> Unit,
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
    onOpenReliability: () -> Unit = {},
    lastNetworkEventFa: String = "",
    onOfflineStrictToggle: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var ownerDraft by remember(preferences.ownerName) { mutableStateOf(preferences.ownerName) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "settings_header") {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.settings_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 0. One UI Core / Background Reliability & Self-Test
        item(key = "settings_reliability") {
            SectionCard(title = stringResource(R.string.settings_reliability_card_title)) {
                Text(
                    text = stringResource(R.string.settings_reliability_card_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onOpenReliability,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("settings_reliability_button"),
                ) {
                    Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_reliability_btn))
                }
            }
        }

        // 1. Offline Vosk Persian ASR + Vocabulary Bias + Wake Word
        item(key = "settings_vosk") {
            SectionCard(title = "۱. گفتار ۱۰۰٪ آفلاین فارسی (Vosk + کلمه بیدارباش Sol)") {
                when (voskModelStatus) {
                    is VoskModelStatus.NotInstalled -> {
                        Text(
                            text = "مدل آفلاین فارسی Vosk (~۶۰ مگابایت) هنوز نصب نشده است. پس از دانلود مستقیم و ایمن (HTTPS) از مخزن رسمی Alpha Cephei، تشخیص گفتار بدون نیاز به اینترنت کار می‌کند.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = onDownloadVoskModel,
                                modifier = Modifier.testTag("download_vosk_button"),
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("دانلود مدل فارسی (~53MB)")
                            }
                            OutlinedButton(
                                onClick = onInstallAllVoiceModelsNow,
                                modifier = Modifier.testTag("install_all_voice_models_button"),
                            ) {
                                Text("نصب خودکار همهٔ بخش‌های صوتی")
                            }
                            OutlinedButton(onClick = onPickVoskZip) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("نصب از فایل ZIP گوشی")
                            }
                        }
                    }

                    is VoskModelStatus.Downloading -> {
                        Text(
                            text = voskModelStatus.stageFa,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { voskModelStatus.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    is VoskModelStatus.Installed -> {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "✅ مدل Vosk فارسی نصب است (${voskModelStatus.modelSizeMb}MB) • وضعیت RAM: ${if (voskModelStatus.isLoadedInRam) "بارگذاری‌شده" else "آزاد (Lazy Load)"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                                Text(
                                    text = "${voskModelStatus.lastNoteFa}\nلغت‌نامه تقویتی (Grammar/Bias): $vocabularyTermsCount واژهٔ شخصی از مخاطبین و مکان‌ها",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (voskModelStatus.sha256Hex.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "اثر انگشت فایل (SHA-256): ${voskModelStatus.sha256Hex.take(16)}…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(onClick = onUnloadVoskRam) {
                                Text("آزادسازی مدل از RAM")
                            }
                            OutlinedButton(onClick = onDeleteVoskModel) {
                                Text("حذف مدل از حافظه")
                            }
                        }
                    }

                    is VoskModelStatus.Error -> {
                        Text(
                            text = voskModelStatus.messageFa,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onDownloadVoskModel) { Text("تلاش مجدد") }
                            OutlinedButton(onClick = onPickVoskZip) { Text("انتخاب فایل ZIP") }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("اولویت با موتور آفلاین Vosk (در صورت نصب)", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = preferences.voskOfflineEnabled, onCheckedChange = onVoskOfflineToggle)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("بیدارباش «Sol» (فقط هنگام باز بودن برنامه)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "در پس‌زمینه و سکوت طولانی خودکار متوقف می‌شود تا باتری مصرف نشود.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = preferences.wakeWordEnabled, onCheckedChange = onWakeWordToggle)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("بوق و لرزش کوتاه شروع/پایان شنود", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = preferences.voiceCueFeedbackEnabled, onCheckedChange = onVoiceCueFeedbackChange)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("میکروفون همیشگی با صفحه خاموش (N2)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (isMicServiceRunning) {
                                "✅ سرویس Foreground میکروفون در حال اجراست (اعلان دائمی فعال؛ مصرف باتری بالاتر)"
                            } else {
                                "با روشن کردن این حالت، MicForegroundService با مجوز FOREGROUND_SERVICE_MICROPHONE در پس‌زمینه گوش‌به‌زنگ می‌ماند."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isMicServiceRunning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = isMicServiceRunning || preferences.screenOffMicEnabled,
                        onCheckedChange = onScreenOffMicToggle,
                        modifier = Modifier.testTag("screen_off_mic_switch"),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("شنود پیوسته پس از بیدارباش (N4)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "پس از اجرای هر فرمان صوتی و پایان پاسخ Sol، گوش‌دادن به واژه «Sol» خودکار ادامه می‌یابد (با دکمه توقف قطع می‌شود).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = preferences.continuousListeningAfterWake,
                        onCheckedChange = onContinuousListeningToggle,
                        modifier = Modifier.testTag("continuous_listening_switch"),
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("مهلت سکوت پایان جمله (VAD)", style = MaterialTheme.typography.bodySmall)
                    Text("${preferences.vadSilenceTimeoutMs} ms", style = MaterialTheme.typography.labelMedium)
                }
                Slider(
                    value = preferences.vadSilenceTimeoutMs.toFloat(),
                    onValueChange = { onVadSilenceTimeoutChange((it / 50f).roundToInt() * 50L) },
                    valueRange = 700f..3500f,
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("حداکثر مدت شنود هر فرمان", style = MaterialTheme.typography.bodySmall)
                    Text("${preferences.maxVoiceListenSeconds} ثانیه", style = MaterialTheme.typography.labelMedium)
                }
                Slider(
                    value = preferences.maxVoiceListenSeconds.toFloat(),
                    onValueChange = { onMaxVoiceListenSecondsChange(it.roundToInt()) },
                    valueRange = 8f..45f,
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("توقف خودکار بیدارباش در سکوت", style = MaterialTheme.typography.bodySmall)
                    Text("${preferences.wakeWordTimeoutMinutes} دقیقه", style = MaterialTheme.typography.labelMedium)
                }
                Slider(
                    value = preferences.wakeWordTimeoutMinutes.toFloat(),
                    onValueChange = { onWakeWordTimeoutMinutesChange(it.roundToInt()) },
                    valueRange = 1f..10f,
                )

                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("samsung_mic_guidance_card"),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "راهنمای میکروفون و باتری در گوشی‌های سامسونگ (One UI Core)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = "۱. دسترسی میکروفون را از نوار بالای گوشی (Quick Panel) روشن نگه دارید.\n۲. در تنظیمات باتری گوشی، Sol را در بخش «Never sleeping apps» قرار دهید.\n۳. اگر ضبط ناگهانی قطع شد، دکمه «تست سلامت میکروفون» را در بخش N5 بزنید.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        OutlinedButton(
                            onClick = onOpenBattery,
                            modifier = Modifier.testTag("samsung_mic_battery_button"),
                        ) {
                            Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("باز کردن تنظیمات باتری گوشی")
                        }
                    }
                }
            }
        }

        // 2. Quick Access: Widget, Quick Settings Tile, Exact Jalali Alarms
        item(key = "settings_quick_access") {
            SectionCard(title = "۲. ویجت صفحه اصلی، دکمه نوار بالا و یادآورهای دقیق شمسی") {
                Text(
                    text = "• ویجت صفحه اصلی: نمایش «موقعیت فعلی تأییدشده»، «نقش فعال» و تاریخ شمسی به همراه دکمهٔ مستقیم میکروفون از لانچر گوشی.\n• دکمه نوار اعلان بالای گوشی (Quick Settings Tile): از هر برنامه‌ای که هستید با کشیدن نوار بالا مستقیم با Sol صحبت کنید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onAddQuickSettingsTile,
                        modifier = Modifier.testTag("pin_qs_tile_button"),
                    ) {
                        Icon(Icons.Default.Widgets, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("افزودن دکمه به نوار بالای گوشی")
                    }

                    OutlinedButton(onClick = onRequestExactAlarmPermission) {
                        Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (canScheduleExactAlarms) {
                                "هشدار دقیق AlarmManager فعال ✓"
                            } else {
                                "فعال‌سازی دسترسی Alarm دقیق"
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (canScheduleExactAlarms) {
                        "وضعیت زمان‌بندی یادآورها: دقیق (AlarmManager) — بعد از راه‌اندازی مجدد گوشی هم خودکار بازیابی می‌شوند."
                    } else {
                        "وضعیت زمان‌بندی یادآورها: تقریبی (سازگار با Doze) — برای دقت ثانیه‌ای دسترسی Alarm دقیق را فعال کنید."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (canScheduleExactAlarms) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (scheduledAlarms.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("یادآورهای زمان‌بندی‌شده شمسی:", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    scheduledAlarms.take(6).forEach { alarm ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${alarm.titleFa} • ${alarm.jalaliLabel}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = "${alarm.messageBody} (${if (alarm.isExact) "دقیق" else "Doze"})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = { onCancelScheduledAlarm(alarm) },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "لغو یادآور",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Phase 3: Offline Neural LLM (LiteRT-LM) + BM25 RAG
        item(key = "settings_slm") {
            SectionCard(title = "۳. هوش زبانی آفلاین روی گوشی (فاز ۳: LiteRT-LM + BM25)") {
                Text(
                    text = "بخش‌های نمایه‌شدهٔ کتاب‌ها (PDF / EPUB / وب / متن) در Room: $bookChunkCount بخش آمادهٔ جست‌وجوی BM25",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("فعال‌سازی مدل زبانی آفلاین (فاز ۳)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = slmStatus.statusLabelFa,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = preferences.slmSynthesisEnabled,
                        onCheckedChange = onSlmToggle,
                        modifier = Modifier.testTag("slm_enable_switch"),
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("اولویت با شتاب‌دهنده گرافیکی GPU", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "پیش‌فرض خاموش (CPU) برای پایداری گوشی‌های ۴GB؛ در صورت نبود OpenCL خودکار روی CPU اجرا می‌شود.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = preferences.slmPreferGpu,
                        onCheckedChange = onSlmPreferGpuToggle,
                        modifier = Modifier.testTag("slm_prefer_gpu_switch"),
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                when (val state = slmRuntimeState) {
                    is SlmRuntimeState.Downloading -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "در حال دریافت: ${state.modelTitleFa} (${state.downloadedMb}/${state.totalMb} MB • ${state.progressPercent}٪)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                LinearProgressIndicator(
                                    progress = { state.progressPercent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedButton(onClick = onCancelSlmDownload) {
                                    Text("لغو دانلود")
                                }
                            }
                        }
                    }

                    is SlmRuntimeState.Loading -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "در حال بارگذاری واقعی «${state.modelName}» در RAM روی ${state.targetBackendFa}…",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }

                    is SlmRuntimeState.LowRamBlocked -> {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "⚠️ محدودیت RAM روی گوشی ۴ گیگابایتی",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = "برای بارگذاری «${state.modelName}» حداقل ${state.requiredRamMb}MB RAM آزاد لازم است اما اکنون ${state.availableRamMb}MB آزاد است.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(onClick = onTrimRamNow) {
                                        Text("آزادسازی فوری کش و Vosk")
                                    }
                                    OutlinedButton(onClick = onDeleteSlmModel) {
                                        Text("حذف این مدل")
                                    }
                                }
                            }
                        }
                    }

                    is SlmRuntimeState.Ready, is SlmRuntimeState.Generating -> {
                        val modelName = if (state is SlmRuntimeState.Ready) state.modelName else (state as SlmRuntimeState.Generating).modelName
                        val backend = if (state is SlmRuntimeState.Ready) state.activeBackend else (state as SlmRuntimeState.Generating).activeBackend
                        val fallbackNote = (state as? SlmRuntimeState.Ready)?.lastFallbackNoteFa.orEmpty()
                        val sha256Hex = (state as? SlmRuntimeState.Ready)?.sha256Hex.orEmpty()
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "✅ مدل عصبی در RAM آماده است: $modelName",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                                Text(
                                    text = "موتور اجرا: ${backend.labelFa} • RAM آزاد فعلی: ${ramSnapshot.availableDeviceRamMb}MB",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                if (sha256Hex.isNotBlank()) {
                                    Text(
                                        text = "اثر انگشت فایل (SHA-256): ${sha256Hex.take(16)}…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (fallbackNote.isNotBlank()) {
                                    Text(
                                        text = fallbackNote,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedButton(onClick = onUnloadSlmRam) {
                                        Text("آزادسازی از RAM")
                                    }
                                    OutlinedButton(onClick = onDeleteSlmModel) {
                                        Text("حذف مدل")
                                    }
                                }
                            }
                        }
                    }

                    is SlmRuntimeState.InstalledUnloaded -> {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "📦 فایل مدل نصب است (${state.fileSizeMb}MB): ${state.modelName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "حداقل RAM لازم: ${state.minRamMb}MB • RAM آزاد فعلی: ${state.availableRamMb}MB\nبرای فشار نیاوردن به گوشی ۴GB، مدل در حالت بیکاری آزاد می‌ماند و فقط هنگام نیاز بارگذاری می‌شود.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (state.sha256Hex.isNotBlank()) {
                                    Text(
                                        text = "اثر انگشت فایل (SHA-256): ${state.sha256Hex.take(16)}…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (state.lastNoteFa.isNotBlank()) {
                                    Text(
                                        text = state.lastNoteFa,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = onTestLoadSlmInRam,
                                        modifier = Modifier.testTag("test_load_slm_button"),
                                    ) {
                                        Text("بارگذاری و تست در RAM")
                                    }
                                    OutlinedButton(onClick = onPickSlmFile) {
                                        Text("تغییر فایل .litertlm")
                                    }
                                    OutlinedButton(onClick = onDeleteSlmModel) {
                                        Text("حذف")
                                    }
                                }
                            }
                        }
                    }

                    is SlmRuntimeState.NotInstalled, is SlmRuntimeState.Error -> {
                        if (state is SlmRuntimeState.Error) {
                            Text(
                                text = state.messageFa,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        Text(
                            text = "هیچ فایل مدل زبانی واقعی (.litertlm) نصب نیست. برای گفت‌وگوی آزاد، هم‌فکری و جمع‌بندی عصبی کاملاً آفلاین، یکی از مدل‌های زیر را دانلود کنید یا فایل .litertlm از حافظه گوشی انتخاب نمایید:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val deviceModels = remember(ramSnapshot.totalDeviceRamMb) {
                                SlmModelCatalog.modelsForDevice(ramSnapshot.totalDeviceRamMb)
                            }
                            deviceModels.forEach { spec ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = spec.titleFa,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (spec.isRecommendedFor4Gb) {
                                                MaterialTheme.colorScheme.tertiary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                        Text(
                                            text = "${spec.descriptionFa}\nحجم تقریبی: ~${spec.approxSizeMb}MB • حداقل RAM آزاد لازم: ${spec.minFreeRamMb}MB",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = "دریافت مستقیم از مخزن رسمی Hugging Face (HTTPS + بررسی خودکار ETag/حجم سرور در حین دانلود).",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Button(
                                            onClick = { onDownloadSlmModel(spec) },
                                            modifier = Modifier.testTag("download_slm_${spec.id}"),
                                        ) {
                                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("دانلود (${spec.approxSizeMb}MB)")
                                        }
                                    }
                                }
                            }
                            OutlinedButton(
                                onClick = onPickSlmFile,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("pick_local_slm_button"),
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("انتخاب فایل .litertlm از حافظه گوشی")
                            }
                        }
                    }
                }
            }
        }

        // 4. Encrypted Portable Backup (.solbackup) & 4GB RAM Optimization
        item(key = "settings_backup_ram") {
            SectionCard(title = "۴. پشتیبان‌گیری رمزنگاری‌شده (.solbackup) و مدیریت ۴GB RAM") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "RAM دستگاه: کل ${ramSnapshot.totalDeviceRamMb}MB • آزاد ${ramSnapshot.availableDeviceRamMb}MB • مصرف اپ ${ramSnapshot.appHeapUsedMb}MB",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            text = ramSnapshot.lastTrimActionFa,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onExportEncryptedBackup,
                        modifier = Modifier.testTag("export_solbackup_button"),
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("صدور پشتیبان رمزدار (.solbackup)")
                    }
                    OutlinedButton(
                        onClick = onImportEncryptedBackup,
                        modifier = Modifier.testTag("import_solbackup_button"),
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("بازیابی از .solbackup")
                    }
                    OutlinedButton(
                        onClick = onTrimRamNow,
                        modifier = Modifier.testTag("trim_ram_button"),
                    ) {
                        Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("آزادسازی فوری کش و RAM")
                    }
                }
            }
        }

        // 5. Owner Name & Theme
        item(key = "settings_identity_theme") {
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
                    Button(
                        onClick = { onOwnerNameChange(ownerDraft) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.action_register))
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

        // 6. Self-Contained Offline Persian Voice (Piper ONNX — zero system TTS dependency)
        item(key = "settings_tts_piper") {
            SectionCard(title = "صدای کاملاً خودکفا و آفلاین Sol (Piper ONNX)") {
                Text(
                    text = "Sol دارای موتور صدای مستقل و کاملاً آفلاین است و به هیچ‌یک از موتورهای صدای گوشی یا اینترنت نیازی ندارد.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        when (piperStatus) {
                            is com.example.sol.tts.PiperTtsStatus.NotInstalled -> {
                                Text(
                                    text = "صدای فارسی خودکفای Mana Piper (~۶۳ مگابایت) هنوز دریافت نشده است. با اولین نیاز به صحبت یا با لمس دکمه زیر، به‌صورت خودکار دانلود و با SHA-256 تأیید می‌شود.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            is com.example.sol.tts.PiperTtsStatus.Downloading -> {
                                Text(
                                    text = piperStatus.stageFa,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                LinearProgressIndicator(
                                    progress = { piperStatus.progressPercent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            is com.example.sol.tts.PiperTtsStatus.PartialSelection -> {
                                Text(
                                    text = piperStatus.noteFa,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            is com.example.sol.tts.PiperTtsStatus.NeedsRiskConfirmation -> {
                                Text(
                                    text = "⚠️ ${piperStatus.reasonFa}\n• SHA-256 مدل ONNX: ${piperStatus.onnxSha256.take(20)}…\n• SHA-256 تنظیمات JSON: ${piperStatus.configSha256.take(20)}…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = onAcceptPiperRisk,
                                        modifier = Modifier.testTag("accept_piper_risk_button"),
                                    ) {
                                        Text("با ریسک خودم قبول کن")
                                    }
                                    OutlinedButton(
                                        onClick = onRejectPiperRisk,
                                        modifier = Modifier.testTag("reject_piper_risk_button"),
                                    ) {
                                        Text("حذف")
                                    }
                                }
                            }
                            is com.example.sol.tts.PiperTtsStatus.Ready -> {
                                val fpText = if (piperStatus.sha256Summary.isNotBlank()) {
                                    "\nاثر انگشت فایل (SHA-256): ${piperStatus.sha256Summary}…"
                                } else {
                                    ""
                                }
                                Text(
                                    text = "✅ موتور خودکفای Piper فعال است (${piperStatus.sampleRate} Hz • ${piperStatus.sizeMb} MB).$fpText",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            is com.example.sol.tts.PiperTtsStatus.Error -> {
                                Text(
                                    text = piperStatus.messageFa,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (piperStatus !is com.example.sol.tts.PiperTtsStatus.Ready &&
                                piperStatus !is com.example.sol.tts.PiperTtsStatus.Downloading
                            ) {
                                Button(
                                    onClick = onDownloadPiperModel,
                                    modifier = Modifier.testTag("download_piper_button"),
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("دانلود خودکار صدای فارسی (~63MB)")
                                }
                            }
                            Button(
                                onClick = onTestVoice,
                                modifier = Modifier.testTag("test_voice_button"),
                            ) {
                                Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("تست صدای Sol")
                            }
                            OutlinedButton(
                                onClick = onPickPiperFiles,
                                modifier = Modifier.testTag("pick_piper_files_button"),
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("انتخاب دستی (.onnx و .json)")
                            }
                            if (piperStatus is com.example.sol.tts.PiperTtsStatus.Ready ||
                                piperStatus is com.example.sol.tts.PiperTtsStatus.PartialSelection ||
                                piperStatus is com.example.sol.tts.PiperTtsStatus.NeedsRiskConfirmation ||
                                piperStatus is com.example.sol.tts.PiperTtsStatus.Error
                            ) {
                                OutlinedButton(
                                    onClick = onDeletePiperModel,
                                    modifier = Modifier.testTag("delete_piper_button"),
                                ) {
                                    Text("حذف فایل‌های Piper")
                                }
                            }
                        }
                    }
                }

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
            }
        }

        // 6b. Owner Speaker Verification (N5 — Self-contained CAM++)
        item(key = "settings_speaker_id") {
            SectionCard(title = "شناسایی صدای مالک (N5 — مدل خودکفای CAM++)") {
                Text(
                    text = speakerIdState.statusLabelFa,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (speakerIdState.isActive) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (speakerIdState.isActive) FontWeight.SemiBold else FontWeight.Normal,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Sol از مدل تأییدشدهٔ CAM++ FP32 (~۲۸ مگابایت، با اثر انگشت SHA-256) استفاده می‌کند. با زدن «ضبط صدای من»، در صورت نبود یا ناسازگاری مدل ابتدا به‌صورت خودکار دانلود و سپس صدای شما با رمزنگاری AES-256 روی گوشی ثبت می‌شود.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (speakerIdState.isCustomUnverifiedModel) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("speaker_custom_unverified_badge"),
                    ) {
                        Text(
                            text = "⚠️ مدل سفارشی (تأییدنشده ناشر) فعال است؛ در بخش امنیت فقط به‌عنوان سیگنال کمکی استفاده می‌شود.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                if (speakerIdState.isDownloadingModel) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("speaker_model_downloading_card"),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "در حال دانلود و تأیید مدل شناسایی صدا (CAM++ • ${speakerIdState.downloadProgressPercent}٪)…",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            LinearProgressIndicator(
                                progress = { speakerIdState.downloadProgressPercent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                if (speakerIdState.isRecordingOwner) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("speaker_enrollment_progress_card"),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = if (speakerIdState.enrollmentStepIndex > 0) {
                                        "نمونهٔ ${speakerIdState.enrollmentStepIndex} از ${speakerIdState.enrollmentTotalSteps} • سطح صدا"
                                    } else {
                                        "سطح دریافت صدای میکروفون"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "${speakerIdState.remainingSeconds} ثانیه باقی‌مانده",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (speakerIdState.enrollmentPromptFa.isNotBlank()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("speaker_enrollment_prompt_card"),
                                ) {
                                    Text(
                                        text = "بگویید: «${speakerIdState.enrollmentPromptFa}»",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(10.dp),
                                    )
                                }
                            }
                            LinearProgressIndicator(
                                progress = { speakerIdState.liveRmsLevel.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("speaker_enrollment_rms_bar"),
                            )
                        }
                    }
                }
                if (speakerIdState.isActive && (speakerIdState.lastSimilarityScore != null || speakerIdState.lastVerificationSummaryFa.isNotBlank())) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val lastSim = speakerIdState.lastSimilarityScore
                    val thresholdPct = (speakerIdState.calibratedThreshold * 100f).roundToInt()
                    val simPct = lastSim?.let { (it * 100f).roundToInt().coerceIn(0, 100) }
                    val passed = speakerIdState.lastVerificationPassed
                    Surface(
                        color = when (passed) {
                            true -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                            false -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
                            null -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("speaker_similarity_result_card"),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (simPct != null) {
                                        "میزان شباهت صدا: $simPct٪"
                                    } else {
                                        "وضعیت احراز اخیر"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "آستانه کالیبره‌شده: $thresholdPct٪",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (lastSim != null) {
                                LinearProgressIndicator(
                                    progress = { lastSim.coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("speaker_similarity_progress_bar"),
                                )
                            }
                            if (speakerIdState.lastVerificationSummaryFa.isNotBlank()) {
                                Text(
                                    text = speakerIdState.lastVerificationSummaryFa,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                if (speakerIdState.feedbackMessageFa.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = if (speakerIdState.feedbackIsError) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = speakerIdState.feedbackMessageFa,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (speakerIdState.feedbackIsError) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            },
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onRecordOwnerVoice,
                        enabled = !speakerIdState.isRecordingOwner && !speakerIdState.isDownloadingModel,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("record_owner_voice_button"),
                    ) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            when {
                                speakerIdState.isDownloadingModel -> "در حال دانلود مدل…"
                                speakerIdState.isRecordingOwner -> "در حال ضبط…"
                                else -> "ضبط ۳ جملهٔ کالیبراسیون مالک"
                            }
                        )
                    }
                    if (speakerIdState.isActive) {
                        OutlinedButton(
                            onClick = onTestOwnerVoice,
                            enabled = !speakerIdState.isRecordingOwner && !speakerIdState.isDownloadingModel,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("test_owner_voice_button"),
                        ) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تست تطابق صدای مالک")
                        }
                    }
                    if (!speakerIdState.isModelInstalled && !speakerIdState.isDownloadingModel) {
                        OutlinedButton(
                            onClick = onDownloadSpeakerModel,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("download_speaker_model_button"),
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("دانلود مستقیم مدل CAM++ (~28MB)")
                        }
                    }
                    OutlinedButton(
                        onClick = onTestSpeakerMic,
                        enabled = !speakerIdState.isRecordingOwner && !speakerIdState.isDownloadingModel,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("test_speaker_mic_button"),
                    ) {
                        Text("تست سلامت میکروفون")
                    }
                    OutlinedButton(
                        onClick = onPickSpeakerModel,
                        enabled = !speakerIdState.isDownloadingModel,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("pick_speaker_model_button"),
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("انتخاب دستی (.onnx)")
                    }
                    if (speakerIdState.isOwnerEnrolled) {
                        OutlinedButton(
                            onClick = onClearOwnerVoice,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("clear_owner_voice_button"),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("پاک کردن صدای من")
                        }
                    }
                }
            }
        }

        // 7. Invocation, Delivery & Confirmation Policy
        item(key = "settings_policies") {
            SectionCard(title = "نحوه فراخوانی، ارسال پیام و سطح اختیار") {
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

        // 8. Device Controller
        item(key = "settings_device_controller") {
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
                val volPct = remember(volumeStatus.mediaCurrent, volumeStatus.mediaMax) {
                    val max = volumeStatus.mediaMax.coerceAtLeast(1)
                    ((volumeStatus.mediaCurrent.toFloat() / max.toFloat()) * 100f).roundToInt()
                }
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

        // 9. Security & Privacy Audit (7 Rules + Offline Strict Mode)
        item(key = "settings_security_audit") {
            SectionCard(title = "قوانین امنیتی و حریم خصوصی Sol") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = if (preferences.offlineStrictMode) {
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "حالت آفلاین سخت‌گیرانه (Offline Strict)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "مسدودسازی کامل اینترنت و جلوگیری از فالبک گفتار سیستم",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = preferences.offlineStrictMode,
                                onCheckedChange = onOfflineStrictToggle,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("offline_strict_switch"),
                            )
                        }
                    }
                    if (lastNetworkEventFa.isNotBlank()) {
                        Text(
                            text = "آخرین وضعیت شبکه: $lastNetworkEventFa",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("network_event_summary"),
                        )
                    }
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
                modifier = Modifier.semantics { heading() },
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}
