package com.example.sol.memory

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
    val slmPreferGpu: Boolean = true,
    val lastBackupAt: Long = 0L,
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
            slmPreferGpu = p[Keys.SLM_PREFER_GPU] ?: true,
            lastBackupAt = p[Keys.LAST_BACKUP_AT] ?: 0L,
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
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T {
        if (raw.isNullOrBlank()) return default
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
    }
}
