package com.example.sol.memory

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.solPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "sol_preferences")

enum class VoicePersona(val labelFa: String, val pitch: Float) {
    FEMALE_CALM("زنانه، طبیعی و آرام", 1.06f),
    MALE_FORMAL("مردانه، رسمی و جدی", 0.88f),
    NEUTRAL_FRIENDLY("خنثی و دوستانه", 1.0f),
}

enum class InvocationMode(val labelFa: String) {
    BUTTON_AND_VOICE("دکمه میکروفون و فرمان صوتی"),
    WAKE_WORD_IN_APP("واژه «Sol» داخل برنامه"),
    BOTH("هر دو روش"),
}

enum class DeliveryPreference(val labelFa: String) {
    CALL_PREFERRED("تماس تلفنی"),
    SMS_PREFERRED("پیامک متنی"),
    BOTH("هر دو روش (انتخاب در تأیید)"),
}

enum class ConfirmationPolicy(val labelFa: String) {
    ALWAYS_COMMUNICATION("تماس و پیام همیشه با تأیید من"),
    ROUTINE_AUTO_SENSITIVE_CONFIRM("کارهای معمول خودکار؛ حساس با تأیید"),
}

enum class HistoryRetentionMode(val labelFa: String) {
    OFF("خاموش"),
    SEVEN_DAYS("۷ روز"),
    TWO_HUNDRED_ITEMS("۲۰۰ مورد اخیر"),
}

data class SolPreferences(
    val ownerName: String = "کاربر",
    val voicePersona: VoicePersona = VoicePersona.FEMALE_CALM,
    val preferredTtsEnginePackage: String = "",
    val preferredTtsVoiceName: String = "",
    val speechRate: Float = 1.0f,
    val autoSpeak: Boolean = true,
    val invocationMode: InvocationMode = InvocationMode.BOTH,
    val deliveryPreference: DeliveryPreference = DeliveryPreference.BOTH,
    val confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.ALWAYS_COMMUNICATION,
    val darkTheme: Boolean = true,
    // Strictly separated from saved locations (Security Rule #4)
    val confirmedCurrentLocation: String = "",
    val confirmedCurrentLocationUpdatedAt: Long = 0L,
    // v2 features
    val voskOfflineEnabled: Boolean = true,
    val wakeWordEnabled: Boolean = false,
    val slmSynthesisEnabled: Boolean = false,
    val slmModelPath: String = "",
    val slmPreferGpu: Boolean = false,
    val lastBackupAt: Long = 0L,
    // M2 Privacy honesty & retention controls
    val historyRetentionMode: HistoryRetentionMode = HistoryRetentionMode.TWO_HUNDRED_ITEMS,
    val privateModeEnabled: Boolean = false,
    // M6 Voice pipeline end-pointing & wake-word controls
    val vadSilenceTimeoutMs: Long = 1350L,
    val maxVoiceListenSeconds: Int = 15,
    val wakeWordTimeoutMinutes: Int = 3,
    val voiceCueFeedbackEnabled: Boolean = true,
    // N2 Screen-off foreground microphone preference
    val screenOffMicEnabled: Boolean = false,
    // N4 Continuous listening after wake-word completion
    val continuousListeningAfterWake: Boolean = false,
    // M8 F2 Reliability diagnostics
    val lastReminderDeliveredAt: Long = 0L,
    val lastReminderDeliveredTitle: String = "",
    // Two-tier TOFU & user-confirmed custom model integrity pins
    val trustedVoskSha256: String = "",
    val trustedVoskBytes: Long = 0L,
    val trustedPiperOnnxSha256: String = "",
    val trustedPiperConfigSha256: String = "",
)

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val OWNER_NAME = stringPreferencesKey("owner_name")
        val VOICE_PERSONA = stringPreferencesKey("voice_persona")
        val TTS_ENGINE_PACKAGE = stringPreferencesKey("tts_engine_package")
        val TTS_VOICE_NAME = stringPreferencesKey("tts_voice_name")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val AUTO_SPEAK = booleanPreferencesKey("auto_speak")
        val INVOCATION_MODE = stringPreferencesKey("invocation_mode")
        val DELIVERY_PREF = stringPreferencesKey("delivery_pref")
        val CONFIRMATION_POLICY = stringPreferencesKey("confirmation_policy")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val CONFIRMED_LOCATION = stringPreferencesKey("confirmed_current_location")
        val CONFIRMED_LOCATION_TIME = longPreferencesKey("confirmed_current_location_time")
        val VOSK_OFFLINE_ENABLED = booleanPreferencesKey("vosk_offline_enabled")
        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val SLM_SYNTHESIS_ENABLED = booleanPreferencesKey("slm_synthesis_enabled")
        val SLM_MODEL_PATH = stringPreferencesKey("slm_model_path")
        val SLM_PREFER_GPU = booleanPreferencesKey("slm_prefer_gpu")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val HISTORY_RETENTION_MODE = stringPreferencesKey("history_retention_mode")
        val PRIVATE_MODE_ENABLED = booleanPreferencesKey("private_mode_enabled")
        val VAD_SILENCE_TIMEOUT_MS = longPreferencesKey("vad_silence_timeout_ms")
        val MAX_VOICE_LISTEN_SECONDS = intPreferencesKey("max_voice_listen_seconds")
        val WAKE_WORD_TIMEOUT_MINUTES = intPreferencesKey("wake_word_timeout_minutes")
        val VOICE_CUE_FEEDBACK_ENABLED = booleanPreferencesKey("voice_cue_feedback_enabled")
        val SCREEN_OFF_MIC_ENABLED = booleanPreferencesKey("screen_off_mic_enabled")
        val CONTINUOUS_LISTENING_AFTER_WAKE = booleanPreferencesKey("continuous_listening_after_wake")
        val LAST_REMINDER_DELIVERED_AT = longPreferencesKey("last_reminder_delivered_at")
        val LAST_REMINDER_DELIVERED_TITLE = stringPreferencesKey("last_reminder_delivered_title")
        val TRUSTED_VOSK_SHA256 = stringPreferencesKey("trusted_vosk_sha256")
        val TRUSTED_VOSK_BYTES = longPreferencesKey("trusted_vosk_bytes")
        val TRUSTED_PIPER_ONNX_SHA256 = stringPreferencesKey("trusted_piper_onnx_sha256")
        val TRUSTED_PIPER_CONFIG_SHA256 = stringPreferencesKey("trusted_piper_config_sha256")
    }

    val preferences: Flow<SolPreferences> = context.solPrefsDataStore.data.map { p ->
        SolPreferences(
            ownerName = p[Keys.OWNER_NAME] ?: "کاربر",
            voicePersona = enumOrDefault(p[Keys.VOICE_PERSONA], VoicePersona.FEMALE_CALM),
            preferredTtsEnginePackage = p[Keys.TTS_ENGINE_PACKAGE] ?: "",
            preferredTtsVoiceName = p[Keys.TTS_VOICE_NAME] ?: "",
            speechRate = p[Keys.SPEECH_RATE] ?: 1.0f,
            autoSpeak = p[Keys.AUTO_SPEAK] ?: true,
            invocationMode = enumOrDefault(p[Keys.INVOCATION_MODE], InvocationMode.BOTH),
            deliveryPreference = enumOrDefault(p[Keys.DELIVERY_PREF], DeliveryPreference.BOTH),
            confirmationPolicy = enumOrDefault(
                p[Keys.CONFIRMATION_POLICY],
                ConfirmationPolicy.ALWAYS_COMMUNICATION
            ),
            darkTheme = p[Keys.DARK_THEME] ?: true,
            confirmedCurrentLocation = p[Keys.CONFIRMED_LOCATION] ?: "",
            confirmedCurrentLocationUpdatedAt = p[Keys.CONFIRMED_LOCATION_TIME] ?: 0L,
            voskOfflineEnabled = p[Keys.VOSK_OFFLINE_ENABLED] ?: true,
            wakeWordEnabled = p[Keys.WAKE_WORD_ENABLED] ?: false,
            slmSynthesisEnabled = p[Keys.SLM_SYNTHESIS_ENABLED] ?: false,
            slmModelPath = p[Keys.SLM_MODEL_PATH] ?: "",
            slmPreferGpu = p[Keys.SLM_PREFER_GPU] ?: false,
            lastBackupAt = p[Keys.LAST_BACKUP_AT] ?: 0L,
            historyRetentionMode = enumOrDefault(
                p[Keys.HISTORY_RETENTION_MODE],
                HistoryRetentionMode.TWO_HUNDRED_ITEMS
            ),
            privateModeEnabled = p[Keys.PRIVATE_MODE_ENABLED] ?: false,
            vadSilenceTimeoutMs = (p[Keys.VAD_SILENCE_TIMEOUT_MS] ?: 1350L).coerceIn(700L, 3500L),
            maxVoiceListenSeconds = (p[Keys.MAX_VOICE_LISTEN_SECONDS] ?: 15).coerceIn(8, 45),
            wakeWordTimeoutMinutes = (p[Keys.WAKE_WORD_TIMEOUT_MINUTES] ?: 3).coerceIn(1, 10),
            voiceCueFeedbackEnabled = p[Keys.VOICE_CUE_FEEDBACK_ENABLED] ?: true,
            screenOffMicEnabled = p[Keys.SCREEN_OFF_MIC_ENABLED] ?: false,
            continuousListeningAfterWake = p[Keys.CONTINUOUS_LISTENING_AFTER_WAKE] ?: false,
            lastReminderDeliveredAt = p[Keys.LAST_REMINDER_DELIVERED_AT] ?: 0L,
            lastReminderDeliveredTitle = p[Keys.LAST_REMINDER_DELIVERED_TITLE] ?: "",
            trustedVoskSha256 = p[Keys.TRUSTED_VOSK_SHA256] ?: "",
            trustedVoskBytes = p[Keys.TRUSTED_VOSK_BYTES] ?: 0L,
            trustedPiperOnnxSha256 = p[Keys.TRUSTED_PIPER_ONNX_SHA256] ?: "",
            trustedPiperConfigSha256 = p[Keys.TRUSTED_PIPER_CONFIG_SHA256] ?: "",
        )
    }

    suspend fun getSnapshot(): SolPreferences = preferences.first()

    suspend fun setOwnerName(name: String) {
        context.solPrefsDataStore.edit { it[Keys.OWNER_NAME] = name.trim().ifBlank { "کاربر" } }
    }

    suspend fun setVoicePersona(persona: VoicePersona) {
        context.solPrefsDataStore.edit { it[Keys.VOICE_PERSONA] = persona.name }
    }

    suspend fun setPreferredTtsEnginePackage(packageName: String) {
        context.solPrefsDataStore.edit { it[Keys.TTS_ENGINE_PACKAGE] = packageName.trim() }
    }

    suspend fun setPreferredTtsVoiceName(voiceName: String) {
        context.solPrefsDataStore.edit { it[Keys.TTS_VOICE_NAME] = voiceName }
    }

    suspend fun setSpeechRate(rate: Float) {
        context.solPrefsDataStore.edit { it[Keys.SPEECH_RATE] = rate.coerceIn(0.6f, 1.5f) }
    }

    suspend fun setAutoSpeak(enabled: Boolean) {
        context.solPrefsDataStore.edit { it[Keys.AUTO_SPEAK] = enabled }
    }

    suspend fun setInvocationMode(mode: InvocationMode) {
        context.solPrefsDataStore.edit { it[Keys.INVOCATION_MODE] = mode.name }
    }

    suspend fun setDeliveryPreference(pref: DeliveryPreference) {
        context.solPrefsDataStore.edit { it[Keys.DELIVERY_PREF] = pref.name }
    }

    suspend fun setConfirmationPolicy(policy: ConfirmationPolicy) {
        context.solPrefsDataStore.edit { it[Keys.CONFIRMATION_POLICY] = policy.name }
    }

    suspend fun setDarkTheme(dark: Boolean) {
        context.solPrefsDataStore.edit { it[Keys.DARK_THEME] = dark }
    }

    suspend fun confirmCurrentLocation(locationLabel: String) {
        context.solPrefsDataStore.edit {
            it[Keys.CONFIRMED_LOCATION] = locationLabel.trim()
            it[Keys.CONFIRMED_LOCATION_TIME] = System.currentTimeMillis()
        }
    }

    suspend fun clearCurrentLocation() {
        context.solPrefsDataStore.edit {
            it[Keys.CONFIRMED_LOCATION] = ""
            it[Keys.CONFIRMED_LOCATION_TIME] = 0L
        }
    }

    suspend fun setVoskOfflineEnabled(enabled: Boolean) {
        context.solPrefsDataStore.edit { it[Keys.VOSK_OFFLINE_ENABLED] = enabled }
    }

    suspend fun setWakeWordEnabled(enabled: Boolean) {
        context.solPrefsDataStore.edit { it[Keys.WAKE_WORD_ENABLED] = enabled }
    }

    suspend fun setSlmSynthesisEnabled(enabled: Boolean) {
        context.solPrefsDataStore.edit { it[Keys.SLM_SYNTHESIS_ENABLED] = enabled }
    }

    suspend fun setSlmModelPath(path: String) {
        context.solPrefsDataStore.edit { it[Keys.SLM_MODEL_PATH] = path }
    }

    suspend fun setSlmPreferGpu(preferGpu: Boolean) {
        context.solPrefsDataStore.edit { it[Keys.SLM_PREFER_GPU] = preferGpu }
    }

    suspend fun recordBackupTimestamp(timestamp: Long = System.currentTimeMillis()) {
        context.solPrefsDataStore.edit { it[Keys.LAST_BACKUP_AT] = timestamp }
    }

    suspend fun setHistoryRetentionMode(mode: HistoryRetentionMode) {
        context.solPrefsDataStore.edit { it[Keys.HISTORY_RETENTION_MODE] = mode.name }
    }

    suspend fun setPrivateModeEnabled(enabled: Boolean) {
        context.solPrefsDataStore.edit { it[Keys.PRIVATE_MODE_ENABLED] = enabled }
    }

    suspend fun setVadSilenceTimeoutMs(timeoutMs: Long) {
        context.solPrefsDataStore.edit { it[Keys.VAD_SILENCE_TIMEOUT_MS] = timeoutMs.coerceIn(700L, 3500L) }
    }

    suspend fun setMaxVoiceListenSeconds(seconds: Int) {
        context.solPrefsDataStore.edit { it[Keys.MAX_VOICE_LISTEN_SECONDS] = seconds.coerceIn(8, 45) }
    }

    suspend fun setWakeWordTimeoutMinutes(minutes: Int) {
        context.solPrefsDataStore.edit { it[Keys.WAKE_WORD_TIMEOUT_MINUTES] = minutes.coerceIn(1, 10) }
    }

    suspend fun setVoiceCueFeedbackEnabled(enabled: Boolean) {
        context.solPrefsDataStore.edit { it[Keys.VOICE_CUE_FEEDBACK_ENABLED] = enabled }
    }

    suspend fun setScreenOffMicEnabled(enabled: Boolean) {
        context.solPrefsDataStore.edit { it[Keys.SCREEN_OFF_MIC_ENABLED] = enabled }
    }

    suspend fun setContinuousListeningAfterWake(enabled: Boolean) {
        context.solPrefsDataStore.edit { it[Keys.CONTINUOUS_LISTENING_AFTER_WAKE] = enabled }
    }

    suspend fun recordLastReminderDelivered(
        title: String,
        deliveredAt: Long = System.currentTimeMillis(),
    ) {
        context.solPrefsDataStore.edit {
            it[Keys.LAST_REMINDER_DELIVERED_AT] = deliveredAt
            it[Keys.LAST_REMINDER_DELIVERED_TITLE] = title.trim()
        }
    }

    suspend fun setTrustedVoskModel(sha256: String, bytes: Long) {
        context.solPrefsDataStore.edit {
            it[Keys.TRUSTED_VOSK_SHA256] = sha256.trim().lowercase()
            it[Keys.TRUSTED_VOSK_BYTES] = bytes.coerceAtLeast(0L)
        }
    }

    suspend fun clearTrustedVoskModel() {
        context.solPrefsDataStore.edit {
            it[Keys.TRUSTED_VOSK_SHA256] = ""
            it[Keys.TRUSTED_VOSK_BYTES] = 0L
        }
    }

    suspend fun setTrustedPiperHashes(onnxSha256: String, configSha256: String) {
        context.solPrefsDataStore.edit {
            it[Keys.TRUSTED_PIPER_ONNX_SHA256] = onnxSha256.trim().lowercase()
            it[Keys.TRUSTED_PIPER_CONFIG_SHA256] = configSha256.trim().lowercase()
        }
    }

    suspend fun clearTrustedPiperHashes() {
        context.solPrefsDataStore.edit {
            it[Keys.TRUSTED_PIPER_ONNX_SHA256] = ""
            it[Keys.TRUSTED_PIPER_CONFIG_SHA256] = ""
        }
    }

    suspend fun resetAllPreferences() {
        context.solPrefsDataStore.edit { it.clear() }
    }

    suspend fun restoreSnapshot(restored: SolPreferences) {
        context.solPrefsDataStore.edit { p ->
            p[Keys.OWNER_NAME] = restored.ownerName
            p[Keys.VOICE_PERSONA] = restored.voicePersona.name
            p[Keys.TTS_ENGINE_PACKAGE] = restored.preferredTtsEnginePackage
            p[Keys.TTS_VOICE_NAME] = restored.preferredTtsVoiceName
            p[Keys.SPEECH_RATE] = restored.speechRate
            p[Keys.AUTO_SPEAK] = restored.autoSpeak
            p[Keys.INVOCATION_MODE] = restored.invocationMode.name
            p[Keys.DELIVERY_PREF] = restored.deliveryPreference.name
            p[Keys.CONFIRMATION_POLICY] = restored.confirmationPolicy.name
            p[Keys.DARK_THEME] = restored.darkTheme
            p[Keys.CONFIRMED_LOCATION] = restored.confirmedCurrentLocation
            p[Keys.CONFIRMED_LOCATION_TIME] = restored.confirmedCurrentLocationUpdatedAt
            p[Keys.VOSK_OFFLINE_ENABLED] = restored.voskOfflineEnabled
            p[Keys.WAKE_WORD_ENABLED] = restored.wakeWordEnabled
            p[Keys.SLM_SYNTHESIS_ENABLED] = restored.slmSynthesisEnabled
            p[Keys.SLM_MODEL_PATH] = restored.slmModelPath
            p[Keys.SLM_PREFER_GPU] = restored.slmPreferGpu
            p[Keys.HISTORY_RETENTION_MODE] = restored.historyRetentionMode.name
            p[Keys.PRIVATE_MODE_ENABLED] = restored.privateModeEnabled
            p[Keys.VAD_SILENCE_TIMEOUT_MS] = restored.vadSilenceTimeoutMs.coerceIn(700L, 3500L)
            p[Keys.MAX_VOICE_LISTEN_SECONDS] = restored.maxVoiceListenSeconds.coerceIn(8, 45)
            p[Keys.WAKE_WORD_TIMEOUT_MINUTES] = restored.wakeWordTimeoutMinutes.coerceIn(1, 10)
            p[Keys.VOICE_CUE_FEEDBACK_ENABLED] = restored.voiceCueFeedbackEnabled
            p[Keys.SCREEN_OFF_MIC_ENABLED] = restored.screenOffMicEnabled
            p[Keys.CONTINUOUS_LISTENING_AFTER_WAKE] = restored.continuousListeningAfterWake
            p[Keys.LAST_REMINDER_DELIVERED_AT] = restored.lastReminderDeliveredAt
            p[Keys.LAST_REMINDER_DELIVERED_TITLE] = restored.lastReminderDeliveredTitle
            p[Keys.TRUSTED_VOSK_SHA256] = restored.trustedVoskSha256
            p[Keys.TRUSTED_VOSK_BYTES] = restored.trustedVoskBytes
            p[Keys.TRUSTED_PIPER_ONNX_SHA256] = restored.trustedPiperOnnxSha256
            p[Keys.TRUSTED_PIPER_CONFIG_SHA256] = restored.trustedPiperConfigSha256
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T {
        if (raw.isNullOrBlank()) return default
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
    }
}
