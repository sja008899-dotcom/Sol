package com.example.sol.voice

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.sol.memory.VoicePersona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class TtsAvailability(val labelFa: String) {
    INITIALIZING("در حال اتصال به موتور صدای گوشی…"),
    READY_PERSIAN("متصل به صدای فارسی گوشی"),
    MISSING_PERSIAN_DATA("صدای فارسی در موتور فعلی پیدا نشد؛ موتور صدای گوشی (مثل SherpaTTS) را انتخاب کنید"),
    UNAVAILABLE("موتور گفتار روی گوشی در دسترس نیست"),
}

data class TtsVoiceInfo(
    val name: String,
    val localeTag: String,
    val isOffline: Boolean,
)

data class TtsEngineInfo(
    val packageName: String,
    val label: String,
    val isDefault: Boolean,
    val isSherpa: Boolean,
)

class PersianTTS(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext

    private var tts: TextToSpeech? = null
    private var currentlyBindingPackage: String = ""
    private var explicitPreferredPackage: String = ""
    private var pendingProbeQueue: ArrayDeque<String> = ArrayDeque()
    private var sherpaFallbackPackage: String? = null
    private var usingFallbackBind = false

    private val _availability = MutableStateFlow(TtsAvailability.INITIALIZING)
    val availability: StateFlow<TtsAvailability> = _availability.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _persianVoices = MutableStateFlow<List<TtsVoiceInfo>>(emptyList())
    val persianVoices: StateFlow<List<TtsVoiceInfo>> = _persianVoices.asStateFlow()

    private val _availableEngines = MutableStateFlow<List<TtsEngineInfo>>(emptyList())
    val availableEngines: StateFlow<List<TtsEngineInfo>> = _availableEngines.asStateFlow()

    private val _activeEnginePackage = MutableStateFlow("")
    val activeEnginePackage: StateFlow<String> = _activeEnginePackage.asStateFlow()

    private val _activeEngineLabel = MutableStateFlow("")
    val activeEngineLabel: StateFlow<String> = _activeEngineLabel.asStateFlow()

    init {
        connectToPhoneTts(preferredPackage = "")
    }

    /**
     * Connects to the phone's installed Android TTS engine.
     * - If [preferredPackage] is set, binds directly to that engine (e.g. SherpaTTS).
     * - Otherwise checks the system default engine first; if that doesn't support Persian,
     *   automatically probes all installed engines prioritizing SherpaTTS, then Google TTS, then others.
     */
    fun connectToPhoneTts(preferredPackage: String = explicitPreferredPackage) {
        explicitPreferredPackage = preferredPackage.trim()
        usingFallbackBind = false
        sherpaFallbackPackage = null
        pendingProbeQueue.clear()
        _availability.value = TtsAvailability.INITIALIZING
        _persianVoices.value = emptyList()

        bindEngine(explicitPreferredPackage)
    }

    private fun bindEngine(packageName: String) {
        currentlyBindingPackage = packageName
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = runCatching {
            if (packageName.isBlank()) {
                TextToSpeech(appContext, this)
            } else {
                TextToSpeech(appContext, this, packageName)
            }
        }.getOrElse {
            onEngineBindFailed(packageName)
            null
        }
    }

    override fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            onEngineBindFailed(currentlyBindingPackage)
            return
        }

        val installedInfos = snapshotEngines(engine)
        if (installedInfos.isNotEmpty()) {
            _availableEngines.value = installedInfos
        }

        val resolvedPackage = currentlyBindingPackage.ifBlank {
            runCatching { engine.defaultEngine.orEmpty() }.getOrDefault("")
        }
        val resolvedLabel = installedInfos.firstOrNull { it.packageName == resolvedPackage }?.label
            ?: resolvedPackage.ifBlank { "موتور پیش‌فرض گوشی" }

        if (isSherpaEngine(resolvedPackage, resolvedLabel) && sherpaFallbackPackage == null) {
            sherpaFallbackPackage = resolvedPackage
        }

        // First probe queued candidates if we were only discovering default engine
        if (explicitPreferredPackage.isBlank() && !usingFallbackBind && pendingProbeQueue.isEmpty()) {
            val otherCandidates = installedInfos
                .map { it.packageName }
                .filter { it.isNotBlank() && it != resolvedPackage }
                .sortedWith(
                    compareByDescending<String> { pkg ->
                        val lbl = installedInfos.firstOrNull { it.packageName == pkg }?.label.orEmpty()
                        when {
                            isSherpaEngine(pkg, lbl) -> 2
                            pkg == GOOGLE_TTS_PKG -> 1
                            else -> 0
                        }
                    }
                )
            // Keep candidate queue ready if the current engine doesn't support Persian
            pendingProbeQueue.addAll(otherCandidates)
        }

        val persianReady = configurePersianLanguageAndVoices(engine)
        val isSherpaOrExplicit = isSherpaEngine(resolvedPackage, resolvedLabel) ||
            (explicitPreferredPackage.isNotBlank() && resolvedPackage == explicitPreferredPackage)

        if (persianReady || (usingFallbackBind && isSherpaOrExplicit)) {
            attachProgressListener(engine)
            _activeEnginePackage.value = resolvedPackage
            _activeEngineLabel.value = resolvedLabel
            _availability.value = TtsAvailability.READY_PERSIAN
            pendingProbeQueue.clear()
            return
        }

        // Current engine did not report Persian support — probe next installed engine (SherpaTTS first!)
        if (explicitPreferredPackage.isBlank() && pendingProbeQueue.isNotEmpty()) {
            val nextPkg = pendingProbeQueue.removeFirst()
            bindEngine(nextPkg)
            return
        }

        // Some SherpaTTS / Piper builds don't declare fa locale in isLanguageAvailable until speak()
        val sherpaPkg = sherpaFallbackPackage
        if (explicitPreferredPackage.isBlank() && !usingFallbackBind && !sherpaPkg.isNullOrBlank()) {
            if (resolvedPackage == sherpaPkg) {
                attachProgressListener(engine)
                _activeEnginePackage.value = resolvedPackage
                _activeEngineLabel.value = resolvedLabel
                _availability.value = TtsAvailability.READY_PERSIAN
            } else {
                usingFallbackBind = true
                bindEngine(sherpaPkg)
            }
            return
        }

        // If user manually chose an engine, stay bound to it so they can still test/use it
        if (isSherpaOrExplicit) {
            attachProgressListener(engine)
            _activeEnginePackage.value = resolvedPackage
            _activeEngineLabel.value = resolvedLabel
            _availability.value = TtsAvailability.READY_PERSIAN
            return
        }

        attachProgressListener(engine)
        _activeEnginePackage.value = resolvedPackage
        _activeEngineLabel.value = resolvedLabel
        _availability.value = TtsAvailability.MISSING_PERSIAN_DATA
    }

    private fun onEngineBindFailed(failedPkg: String) {
        if (explicitPreferredPackage.isBlank() && pendingProbeQueue.isNotEmpty()) {
            val nextPkg = pendingProbeQueue.removeFirst()
            bindEngine(nextPkg)
            return
        }
        val sherpaPkg = sherpaFallbackPackage
        if (explicitPreferredPackage.isBlank() && !usingFallbackBind && !sherpaPkg.isNullOrBlank() && sherpaPkg != failedPkg) {
            usingFallbackBind = true
            bindEngine(sherpaPkg)
            return
        }
        _isSpeaking.value = false
        _availability.value = TtsAvailability.UNAVAILABLE
    }

    private fun attachProgressListener(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _isSpeaking.value = false
            }
        })
    }

    fun speak(
        text: String,
        speechRate: Float = 1.0f,
        persona: VoicePersona = VoicePersona.FEMALE_CALM,
        preferredVoiceName: String = "",
    ) {
        if (text.isBlank()) return
        val engine = tts ?: return

        val cleanForTts = text
            .replace(Regex("https?://\\S+"), "لینک")
            .replace("•", "،")
            .replace(Regex("[🎭📘🔎🎙⚠️✅❌]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleanForTts.isBlank()) return

        stop()

        if (preferredVoiceName.isNotBlank()) {
            val match: Voice? = runCatching {
                engine.voices?.firstOrNull { it.name == preferredVoiceName }
            }.getOrNull()
            if (match != null) {
                runCatching { engine.voice = match }
            }
        }

        engine.setSpeechRate(speechRate.coerceIn(0.6f, 1.5f))
        engine.setPitch(persona.pitch)
        engine.speak(cleanForTts, TextToSpeech.QUEUE_FLUSH, null, "sol_${System.currentTimeMillis()}")
    }

    fun openSystemTtsSettings() {
        val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appContext.startActivity(intent) }
    }

    fun recheckSystemVoices(preferredEnginePackage: String = explicitPreferredPackage) {
        connectToPhoneTts(preferredEnginePackage)
    }

    fun stop() {
        runCatching { tts?.stop() }
        _isSpeaking.value = false
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        _isSpeaking.value = false
    }

    companion object {
        private const val GOOGLE_TTS_PKG = "com.google.android.tts"

        private val PERSIAN_LOCALES = listOf(
            Locale.forLanguageTag("fa-IR"),
            Locale.forLanguageTag("fa"),
            Locale.forLanguageTag("fas-IR"),
            Locale.forLanguageTag("fas"),
            Locale.forLanguageTag("per"),
        )

        fun isSherpaEngine(packageName: String, label: String = ""): Boolean {
            val hay = "$packageName $label".lowercase(Locale.ROOT)
            return hay.contains("sherpa") || hay.contains("woheller69") || hay.contains("k2fsa")
        }

        fun isPersianLocale(locale: Locale?): Boolean {
            if (locale == null) return false
            val lang = locale.language.lowercase(Locale.ROOT)
            val tag = locale.toLanguageTag().lowercase(Locale.ROOT)
            return lang == "fa" || lang == "fas" || lang == "per" ||
                tag.startsWith("fa-") || tag == "fa" || tag.startsWith("fas")
        }

        internal fun snapshotEngines(engine: TextToSpeech): List<TtsEngineInfo> {
            val defaultPkg = runCatching { engine.defaultEngine.orEmpty() }.getOrDefault("")
            return runCatching {
                engine.engines.orEmpty().map { info ->
                    val label = info.label?.trim().orEmpty().ifBlank { info.name }
                    TtsEngineInfo(
                        packageName = info.name,
                        label = label,
                        isDefault = info.name == defaultPkg,
                        isSherpa = isSherpaEngine(info.name, label),
                    )
                }.sortedWith(
                    compareByDescending<TtsEngineInfo> { it.isSherpa }
                        .thenByDescending { it.isDefault }
                        .thenBy { it.label }
                )
            }.getOrDefault(emptyList())
        }

        internal fun configurePersianLanguageAndVoices(
            engine: TextToSpeech,
            updateVoices: (List<TtsVoiceInfo>) -> Unit = {},
        ): Boolean {
            var langSupported = false
            for (loc in PERSIAN_LOCALES) {
                val res = runCatching { engine.setLanguage(loc) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                if (res >= TextToSpeech.LANG_AVAILABLE) {
                    langSupported = true
                    break
                }
            }

            val allVoices = runCatching { engine.voices?.toList().orEmpty() }.getOrDefault(emptyList())
            val persianMatched = allVoices.filter { isPersianLocale(it.locale) }
            val mappedVoices = persianMatched
                .map { v ->
                    TtsVoiceInfo(
                        name = v.name,
                        localeTag = v.locale.toLanguageTag(),
                        isOffline = !v.isNetworkConnectionRequired,
                    )
                }
                .sortedByDescending { it.isOffline }
            updateVoices(mappedVoices)

            if (!langSupported && persianMatched.isNotEmpty()) {
                val chosen = persianMatched.firstOrNull { !it.isNetworkConnectionRequired } ?: persianMatched.first()
                runCatching { engine.voice = chosen }
                langSupported = true
            }
            return langSupported
        }
    }

    private fun configurePersianLanguageAndVoices(engine: TextToSpeech): Boolean {
        return Companion.configurePersianLanguageAndVoices(engine) { voices ->
            _persianVoices.value = voices
        }
    }
}
