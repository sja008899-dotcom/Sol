package com.example.sol.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import com.example.sol.memory.PersianNormalizer
import com.example.sol.voice.AudioVolumeStatus
import com.example.sol.voice.SolAudioManager

data class BatteryStatus(
    val levelPercent: Int,
    val isCharging: Boolean,
    val temperatureCelsius: Float,
    val chargingSourceLabel: String,
)

data class InstalledAppShortcut(
    val label: String,
    val packageName: String,
)

class SettingsController(private val context: Context) {
    fun openWifiSettings(): Boolean {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        return launchSettingsIntent(intent)
    }

    fun openLocationSettings(): Boolean = launchSettingsIntent(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

    fun openBluetoothSettings(): Boolean = launchSettingsIntent(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    fun openSoundSettings(): Boolean = launchSettingsIntent(Intent(Settings.ACTION_SOUND_SETTINGS))

    fun openAppSettings(): Boolean = launchSettingsIntent(Intent(Settings.ACTION_SETTINGS))

    fun openBatterySettings(): Boolean = launchSettingsIntent(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))

    private fun launchSettingsIntent(intent: Intent): Boolean {
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}

class AppLauncher(private val context: Context) {

    @Volatile
    private var cachedApps: List<InstalledAppShortcut>? = null

    @Volatile
    private var cacheTimestampMs: Long = 0L

    companion object {
        private const val CACHE_TTL_MS = 60_000L

        private val APP_SYNONYMS: Map<String, List<String>> = mapOf(
            "ماشین حساب" to listOf("calculator", "calc"),
            "تقویم" to listOf("calendar"),
            "پیام" to listOf("messages", "messaging", "sms"),
            "گالری" to listOf("gallery", "photos"),
            "موسیقی" to listOf("music", "player"),
            "موزیک" to listOf("music", "player"),
            "واتساپ" to listOf("whatsapp"),
            "تلگرام" to listOf("telegram"),
            "اینستاگرام" to listOf("instagram"),
            "نشان" to listOf("neshan"),
            "بلد" to listOf("balad"),
            "اسنپ" to listOf("snapp"),
            "دیوار" to listOf("divar"),
            "کروم" to listOf("chrome"),
            "مرورگر" to listOf("browser", "chrome", "firefox"),
            "یادداشت" to listOf("notes", "keep"),
        )
    }

    fun invalidateAppsCache() {
        cachedApps = null
        cacheTimestampMs = 0L
    }

    fun getLaunchableApps(forceRefresh: Boolean = false): List<InstalledAppShortcut> {
        val now = System.currentTimeMillis()
        val existing = cachedApps
        if (!forceRefresh && existing != null && (now - cacheTimestampMs) < CACHE_TTL_MS) {
            return existing
        }
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }
        val fresh = resolved
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
                if (label.isBlank()) return@mapNotNull null
                InstalledAppShortcut(label = label, packageName = pkg)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
        cachedApps = fresh
        cacheTimestampMs = now
        return fresh
    }

    fun findBestAppMatch(query: String, apps: List<InstalledAppShortcut> = getLaunchableApps()): InstalledAppShortcut? {
        val norm = PersianNormalizer.normalize(query)
        if (norm.isBlank()) return null

        val expandedKeywords = buildList {
            add(norm)
            for ((faKey, synonyms) in APP_SYNONYMS) {
                val normKey = PersianNormalizer.normalize(faKey)
                if (norm.contains(normKey)) {
                    addAll(synonyms)
                }
            }
        }.distinct()

        // 1. Exact label match
        apps.firstOrNull { app ->
            val appNorm = PersianNormalizer.normalize(app.label)
            expandedKeywords.any { it == appNorm }
        }?.let { return it }

        // 2. Containment match
        apps.firstOrNull { app ->
            val appNorm = PersianNormalizer.normalize(app.label)
            val pkgNorm = app.packageName.lowercase()
            expandedKeywords.any { kw ->
                appNorm.contains(kw) || kw.contains(appNorm) || (kw.length >= 4 && pkgNorm.contains(kw))
            }
        }?.let { return it }

        // 3. Fuzzy Levenshtein <= 1 for tokens/labels with length >= 4
        return apps.firstOrNull { app ->
            val appNorm = PersianNormalizer.normalize(app.label)
            val appTokens = appNorm.split(' ').filter { it.length >= 4 }
            expandedKeywords.filter { it.length >= 4 }.any { kw ->
                (appNorm.length >= 4 && com.example.sol.contacts.ContactResolver.levenshteinDistance(kw, appNorm) <= 1) ||
                    appTokens.any { tok -> com.example.sol.contacts.ContactResolver.levenshteinDistance(kw, tok) <= 1 }
            }
        }
    }

    fun launchAppByQuery(query: String): String? {
        val norm = PersianNormalizer.normalize(query)

        // Try well-known Persian system intents first
        when {
            norm.contains("دوربین") || norm.contains("camera") -> {
                val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(intent) }.isSuccess) return "دوربین"
            }
            norm.contains("ساعت") || norm.contains("آلارم") || norm.contains("زنگ") || norm.contains("clock") -> {
                val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(intent) }.isSuccess) return "ساعت و آلارم"
            }
            norm.contains("تنظیمات") || norm.contains("settings") -> {
                val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(intent) }.isSuccess) return "تنظیمات گوشی"
            }
            norm.contains("تلفن") || norm.contains("شماره گیر") -> {
                val intent = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(intent) }.isSuccess) return "شماره‌گیر تلفن"
            }
        }

        val match = findBestAppMatch(query) ?: return null
        val launchIntent = context.packageManager.getLaunchIntentForPackage(match.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return null

        return if (runCatching { context.startActivity(launchIntent) }.isSuccess) match.label else null
    }
}

class DeviceController(
    private val context: Context,
    private val audioManager: SolAudioManager,
) {
    val settingsController = SettingsController(context)
    val appLauncher = AppLauncher(context)

    fun getBatteryStatus(): BatteryStatus {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val percent = if (level >= 0) (level * 100) / scale else 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "شارژر برق"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "بی‌سیم"
            else -> if (isCharging) "در حال شارژ" else "باتری"
        }
        return BatteryStatus(
            levelPercent = percent,
            isCharging = isCharging,
            temperatureCelsius = tempTenths / 10f,
            chargingSourceLabel = source,
        )
    }

    fun getVolumeStatus(): AudioVolumeStatus = audioManager.getVolumeStatus()

    fun adjustMediaVolume(delta: Int): AudioVolumeStatus = audioManager.adjustMediaVolume(delta)

    fun setMediaVolumePercent(percent: Int): AudioVolumeStatus = audioManager.setMediaVolumePercent(percent)

    fun buildDeviceSummaryFa(): String {
        val bat = getBatteryStatus()
        val vol = getVolumeStatus()
        val chargeText = if (bat.isCharging) "در حال شارژ (${bat.chargingSourceLabel})" else "بدون اتصال شارژر"
        return "باتری ${bat.levelPercent}٪ ($chargeText، دما ${bat.temperatureCelsius}°C) • صدای رسانه ${vol.mediaCurrent}/${vol.mediaMax} • حالت زنگ: ${vol.ringerModeLabel}"
    }
}
