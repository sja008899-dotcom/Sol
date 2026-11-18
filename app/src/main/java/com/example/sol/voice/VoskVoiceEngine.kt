package com.example.sol.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.example.sol.memory.PersianNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

sealed interface VoskModelStatus {
    data object NotInstalled : VoskModelStatus
    data class Downloading(val progressPercent: Int, val stageFa: String) : VoskModelStatus
    data class Installed(val modelSizeMb: Int, val isLoadedInRam: Boolean, val lastNoteFa: String) : VoskModelStatus
    data class Error(val messageFa: String) : VoskModelStatus
}

class VoiceVocabularyBias {
    @Volatile
    private var domainTerms: List<String> = emptyList()

    fun updateTerms(terms: List<String>) {
        domainTerms = terms
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .take(150)
    }

    fun currentTermsCount(): Int = domainTerms.size

    fun buildConfirmationGrammarJson(): String =
        "[\"تأیید\", \"تایید\", \"بله\", \"آره\", \"انجام بده\", \"بفرست\", \"لغو\", \"نه\", \"خیر\", \"کنسل\", \"متوقف\", \"[unk]\"]"

    fun buildWakeWordGrammarJson(): String =
        "[\"سول\", \"سلام سول\", \"sol\", \"های سول\", \"[unk]\"]"

    /**
     * Post-processes ASR transcript using known Contacts, Locations, Aliases & Books
     * so domain-specific Persian names (e.g. شرکت کارنو صنعت ماکیان) snap cleanly.
     */
    fun applyBiasCorrection(rawTranscript: String): String {
        var result = PersianNormalizer.cleanText(rawTranscript)
        if (result.isBlank() || domainTerms.isEmpty()) return result

        val normInput = PersianNormalizer.normalize(result)
        for (term in domainTerms) {
            val normTerm = PersianNormalizer.normalize(term)
            if (normTerm.length < 4) continue
            val collapsedTerm = normTerm.replace(" ", "")
            val collapsedInput = normInput.replace(" ", "")
            if (collapsedInput.contains(collapsedTerm) && !normInput.contains(normTerm)) {
                // Reconstruct matched spaced variant back to canonical user term
                val words = normTerm.split(' ')
                if (words.size >= 2) {
                    val loosePattern = Regex(words.joinToString("\\s*") { Regex.escape(it) })
                    result = loosePattern.replace(result, term)
                }
            }
        }
        return result
    }
}

class VoskVoiceEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    val vocabularyBias = VoiceVocabularyBias()

    private val modelRootDir: File
        get() = File(context.filesDir, MODEL_DIR_NAME)

    private val _modelStatus = MutableStateFlow<VoskModelStatus>(VoskModelStatus.NotInstalled)
    val modelStatus: StateFlow<VoskModelStatus> = _modelStatus.asStateFlow()

    @Volatile
    private var voskModel: Model? = null

    @Volatile
    private var activeListeningJob: Job? = null

    @Volatile
    private var autoUnloadJob: Job? = null

    private val isRecording = AtomicBoolean(false)

    init {
        runCatching { LibVosk.setLogLevel(LogLevel.WARNINGS) }
        refreshInstalledStatus()
    }

    fun isModelInstalled(): Boolean = findValidModelDir() != null

    fun isModelLoadedInRam(): Boolean = voskModel != null

    fun refreshInstalledStatus(note: String = "مدل آفلاین فارسی آماده اجراست") {
        val dir = findValidModelDir()
        if (dir != null) {
            val sizeMb = (dirSizeBytes(dir) / (1024L * 1024L)).toInt().coerceAtLeast(1)
            _modelStatus.value = VoskModelStatus.Installed(
                modelSizeMb = sizeMb,
                isLoadedInRam = voskModel != null,
                lastNoteFa = note,
            )
        } else if (_modelStatus.value !is VoskModelStatus.Downloading) {
            _modelStatus.value = VoskModelStatus.NotInstalled
        }
    }

    fun unloadModelFromRam(reason: String = "آزادسازی خودکار RAM هنگام بیکاری") {
        if (isRecording.get()) return
        autoUnloadJob?.cancel()
        runCatching { voskModel?.close() }
        voskModel = null
        refreshInstalledStatus(reason)
    }

    fun deleteInstalledModel() {
        stopListening()
        unloadModelFromRam("حذف مدل")
        runCatching { modelRootDir.deleteRecursively() }
        _modelStatus.value = VoskModelStatus.NotInstalled
    }

    fun downloadPersianSmallModel() {
        if (_modelStatus.value is VoskModelStatus.Downloading) return
        scope.launch {
            runCatching {
                _modelStatus.value = VoskModelStatus.Downloading(2, "در حال اتصال به مخزن رسمی Vosk Persian…")
                val zipFile = File(context.cacheDir, "vosk-fa-small.zip")
                downloadFileWithProgress(VOSK_FA_SMALL_URL, zipFile) { pct ->
                    _modelStatus.value = VoskModelStatus.Downloading(
                        progressPercent = pct.coerceIn(5, 88),
                        stageFa = "در حال دانلود مدل آفلاین فارسی ($pct٪)…",
                    )
                }
                _modelStatus.value = VoskModelStatus.Downloading(90, "در حال استخراج امن فایل‌های مدل…")
                zipFile.inputStream().use { extractZipSafely(it) }
                zipFile.delete()
                refreshInstalledStatus("مدل آفلاین فارسی Vosk با موفقیت نصب شد")
            }.onFailure { err ->
                _modelStatus.value = VoskModelStatus.Error(
                    "نصب مدل Vosk ناموفق بود: ${err.localizedMessage ?: "خطای شبکه یا فایل"}"
                )
            }
        }
    }

    fun importLocalModelZip(uri: Uri) {
        if (_modelStatus.value is VoskModelStatus.Downloading) return
        scope.launch {
            runCatching {
                _modelStatus.value = VoskModelStatus.Downloading(20, "در حال خواندن فایل ZIP مدل Vosk…")
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    extractZipSafely(stream)
                } ?: error("دسترسی به فایل انتخاب‌شده ممکن نشد.")
                require(isModelInstalled()) { "فایل ZIP انتخاب‌شده شامل پوشه معتبر مدل Vosk نیست." }
                refreshInstalledStatus("مدل آفلاین Vosk از حافظه گوشی نصب شد")
            }.onFailure { err ->
                _modelStatus.value = VoskModelStatus.Error(
                    err.localizedMessage ?: "وارد کردن فایل مدل ناموفق بود."
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startListening(
        grammarJson: String? = null,
        isWakeWordMode: Boolean = false,
        vadSilenceMs: Long = 1350L,
        onPartial: (String) -> Unit,
        onRmsChanged: (Float) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val modelDir = findValidModelDir()
        if (modelDir == null) {
            onError("مدل آفلاین Vosk نصب نشده است.")
            return
        }

        stopListening()
        autoUnloadJob?.cancel()
        isRecording.set(true)

        activeListeningJob = scope.launch {
            var recorder: AudioRecord? = null
            var recognizer: Recognizer? = null
            try {
                val model = voskModel ?: Model(modelDir.absolutePath).also {
                    voskModel = it
                    refreshInstalledStatus("مدل Vosk در RAM بارگذاری شد")
                }

                recognizer = if (!grammarJson.isNullOrBlank()) {
                    Recognizer(model, SAMPLE_RATE.toFloat(), grammarJson)
                } else {
                    Recognizer(model, SAMPLE_RATE.toFloat())
                }

                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(4096)

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2,
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("میکروفون دستگاه آماده نشد")
                }

                recorder.startRecording()
                val shortBuf = ShortArray(2048)
                var hasHeardSpeech = false
                var lastVoiceActivityAt = System.currentTimeMillis()
                var lastPartialSent = ""

                while (isRecording.get()) {
                    val read = recorder.read(shortBuf, 0, shortBuf.size)
                    if (read <= 0) continue

                    val rmsNorm = computeNormalizedRms(shortBuf, read)
                    mainHandler.post { onRmsChanged(rmsNorm) }

                    val now = System.currentTimeMillis()
                    if (rmsNorm > VAD_SPEECH_THRESHOLD) {
                        hasHeardSpeech = true
                        lastVoiceActivityAt = now
                    }

                    val isEndOfUtterance = recognizer.acceptWaveForm(shortBuf, read)
                    if (isEndOfUtterance) {
                        val txt = extractVoskText(recognizer.result, "text")
                        if (txt.isNotBlank()) {
                            val corrected = vocabularyBias.applyBiasCorrection(txt)
                            if (isWakeWordMode) {
                                val norm = PersianNormalizer.normalize(corrected)
                                if (norm.contains("سول") || norm.contains("sol")) {
                                    isRecording.set(false)
                                    mainHandler.post { onFinalResult(corrected) }
                                    break
                                }
                            } else {
                                isRecording.set(false)
                                mainHandler.post { onFinalResult(corrected) }
                                break
                            }
                        }
                    } else {
                        val partial = extractVoskText(recognizer.partialResult, "partial")
                        if (partial.isNotBlank() && partial != lastPartialSent) {
                            lastPartialSent = partial
                            hasHeardSpeech = true
                            lastVoiceActivityAt = now
                            if (isWakeWordMode) {
                                val norm = PersianNormalizer.normalize(partial)
                                if (norm.contains("سول") || norm.contains("sol")) {
                                    isRecording.set(false)
                                    mainHandler.post { onFinalResult(partial) }
                                    break
                                }
                            } else {
                                val correctedPartial = vocabularyBias.applyBiasCorrection(partial)
                                mainHandler.post { onPartial(correctedPartial) }
                            }
                        }
                    }

                    // Smart VAD pause auto-finalize when user finishes speaking
                    if (!isWakeWordMode && hasHeardSpeech && (now - lastVoiceActivityAt) >= vadSilenceMs) {
                        val finalTxt = extractVoskText(recognizer.finalResult, "text")
                            .ifBlank { lastPartialSent }
                        isRecording.set(false)
                        if (finalTxt.isNotBlank()) {
                            val corrected = vocabularyBias.applyBiasCorrection(finalTxt)
                            mainHandler.post { onFinalResult(corrected) }
                        } else {
                            mainHandler.post { onError("صدایی تشخیص داده نشد؛ لطفاً دوباره بگو") }
                        }
                        break
                    }
                }
            } catch (t: Throwable) {
                if (isRecording.get()) {
                    mainHandler.post {
                        onError("خطا در موتور آفلاین Vosk: ${t.localizedMessage ?: "نامشخص"}")
                    }
                }
            } finally {
                isRecording.set(false)
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                runCatching { recognizer?.close() }
                mainHandler.post { onRmsChanged(0f) }
                scheduleAutoUnloadAfterIdle()
            }
        }
    }

    fun stopListening() {
        isRecording.set(false)
        activeListeningJob?.cancel()
        activeListeningJob = null
        scheduleAutoUnloadAfterIdle()
    }

    private fun scheduleAutoUnloadAfterIdle() {
        autoUnloadJob?.cancel()
        autoUnloadJob = scope.launch {
            delay(IDLE_UNLOAD_DELAY_MS)
            if (!isRecording.get()) {
                unloadModelFromRam("آزادسازی خودکار مدل Vosk بعد از بیکاری (بهینه‌سازی ۴GB RAM)")
            }
        }
    }

    private fun extractVoskText(jsonStr: String?, key: String): String {
        if (jsonStr.isNullOrBlank()) return ""
        return runCatching {
            JSONObject(jsonStr).optString(key, "").trim()
        }.getOrDefault("")
    }

    private fun computeNormalizedRms(samples: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / count.coerceAtLeast(1))
        return (rms / 6500.0).toFloat().coerceIn(0f, 1f)
    }

    private fun findValidModelDir(): File? {
        val root = modelRootDir
        if (!root.exists()) return null
        if (hasVoskSignature(root)) return root
        return root.listFiles()?.firstOrNull { it.isDirectory && hasVoskSignature(it) }
    }

    private fun hasVoskSignature(dir: File): Boolean {
        val am = File(dir, "am")
        val conf = File(dir, "conf")
        return am.isDirectory && conf.isDirectory
    }

    private fun dirSizeBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { dirSizeBytes(it) } ?: 0L
    }

    private fun downloadFileWithProgress(urlString: String, target: File, onProgress: (Int) -> Unit) {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            require(code in 200..299) { "پاسخ سرور: HTTP $code" }
            val total = conn.contentLengthLong.coerceAtLeast(1L)
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(16_384)
                    var downloaded = 0L
                    var lastPct = -1
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun extractZipSafely(inputStream: InputStream) {
        val root = modelRootDir
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()
        val canonicalRoot = root.canonicalPath + File.separator

        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(8192)
            while (entry != null) {
                // Reject any executable binaries (Play DCL compliance)
                val lower = entry.name.lowercase()
                require(
                    !lower.endsWith(".so") &&
                        !lower.endsWith(".dex") &&
                        !lower.endsWith(".jar") &&
                        !lower.endsWith(".apk")
                ) { "فایل غیرمجاز در آرشیو مدل شناسایی شد." }

                val outFile = File(root, entry.name)
                val canonicalDest = outFile.canonicalPath
                require(canonicalDest.startsWith(canonicalRoot)) { "مسیر نامعتبر در فایل ZIP" }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    companion object {
        private const val MODEL_DIR_NAME = "vosk-model-fa"
        private const val SAMPLE_RATE = 16000
        private const val VAD_SPEECH_THRESHOLD = 0.08f
        private const val IDLE_UNLOAD_DELAY_MS = 90_000L
        const val VOSK_FA_SMALL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.4.zip"
    }
}
