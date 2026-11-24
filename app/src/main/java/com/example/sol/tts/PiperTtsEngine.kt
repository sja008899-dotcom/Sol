package com.example.sol.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

sealed interface PiperHashCheckResult {
    data class VerifiedOfficial(val onnxSha256: String, val configSha256: String) : PiperHashCheckResult
    data class VerifiedCustomTrusted(val onnxSha256: String, val configSha256: String) : PiperHashCheckResult
    data class MismatchNeedsConfirmation(val onnxSha256: String, val configSha256: String) : PiperHashCheckResult
    data class Error(val messageFa: String) : PiperHashCheckResult
}

class PiperTtsEngine(
    private val modelFile: File,
    private val configFile: File,
) {
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var config: PiperConfig? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    private val stopRequested = AtomicBoolean(false)

    @Volatile
    var lastErrorFa: String = ""
        private set

    val isReady: Boolean get() = session != null && config != null
    val sampleRate: Int get() = config?.sampleRate ?: 22_050

    fun inspectChecksums(
        trustedCustomHashes: Pair<String, String>? = null,
    ): PiperHashCheckResult {
        if (!modelFile.exists() || !modelFile.isFile || modelFile.length() <= 0L) {
            return PiperHashCheckResult.Error("فایل مدل Piper ONNX یافت نشد.")
        }
        if (!configFile.exists() || !configFile.isFile || configFile.length() <= 0L) {
            return PiperHashCheckResult.Error("فایل تنظیمات Piper JSON یافت نشد.")
        }
        return try {
            PiperConfig.parse(configFile)
            val onnxSha = PiperConfig.sha256(modelFile)
            val cfgSha = PiperConfig.sha256(configFile)
            val isOfficial = PiperConfig.verifyOnnxHash(onnxSha) && PiperConfig.verifyConfigHash(cfgSha)
            if (isOfficial) {
                PiperHashCheckResult.VerifiedOfficial(onnxSha, cfgSha)
            } else if (trustedCustomHashes != null &&
                trustedCustomHashes.first.equals(onnxSha, ignoreCase = true) &&
                trustedCustomHashes.second.equals(cfgSha, ignoreCase = true)
            ) {
                PiperHashCheckResult.VerifiedCustomTrusted(onnxSha, cfgSha)
            } else {
                PiperHashCheckResult.MismatchNeedsConfirmation(onnxSha, cfgSha)
            }
        } catch (t: Throwable) {
            PiperHashCheckResult.Error(t.localizedMessage ?: "فایل تنظیمات Piper JSON معتبر نیست.")
        }
    }

    fun initialize(
        trustedCustomHashes: Pair<String, String>? = null,
        allowUnverifiedRisk: Boolean = false,
    ): Boolean {
        lastErrorFa = ""
        return try {
            require(modelFile.exists()) { "فایل مدل Piper ONNX یافت نشد." }
            require(configFile.exists()) { "فایل تنظیمات Piper JSON یافت نشد." }

            val parsedCfg = PiperConfig.parse(configFile)
            if (!allowUnverifiedRisk) {
                when (val check = inspectChecksums(trustedCustomHashes)) {
                    is PiperHashCheckResult.VerifiedOfficial,
                    is PiperHashCheckResult.VerifiedCustomTrusted -> Unit
                    is PiperHashCheckResult.MismatchNeedsConfirmation -> {
                        lastErrorFa = "اثر انگشت فایل‌های Piper با نسخهٔ تأییدشده مطابقت ندارد."
                        return false
                    }
                    is PiperHashCheckResult.Error -> {
                        lastErrorFa = check.messageFa
                        return false
                    }
                }
            }

            val ortEnv = OrtEnvironment.getEnvironment()
            val ortSess = ortEnv.createSession(modelFile.absolutePath)
            config = parsedCfg
            env = ortEnv
            session = ortSess
            Log.i(TAG, "Piper engine ready, sampleRate=${parsedCfg.sampleRate}")
            true
        } catch (t: Throwable) {
            lastErrorFa = t.localizedMessage ?: "راه‌اندازی موتور Piper ناموفق بود."
            Log.e(TAG, "Piper init failed", t)
            release()
            false
        }
    }

    fun synthesize(text: String, speechRate: Float = 1.0f): ShortArray? {
        val cfg = config ?: return null
        val sess = session ?: return null
        val envRef = env ?: return null
        if (text.isBlank()) return null

        var inputs: Map<String, OnnxTensor> = emptyMap()
        var result: OrtSession.Result? = null
        return try {
            val report = PersianPhonemizer.toPhonemeIdsWithReport(text, cfg)
            if (report.ids.isEmpty()) {
                lastErrorFa = "تبدیل متن به آواهای Piper نتیجه‌ای نداشت."
                return null
            }

            inputs = buildInputMap(sess, report.ids, cfg, speechRate, envRef)
            result = sess.run(inputs)
            val output = result.get(0) as? OnnxTensor ?: return null
            val floatBuffer = output.floatBuffer
            val pcm = ShortArray(floatBuffer.remaining())
            var idx = 0
            while (floatBuffer.hasRemaining()) {
                val sample = (floatBuffer.get() * Short.MAX_VALUE)
                    .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                pcm[idx++] = sample.toInt().toShort()
            }
            pcm
        } catch (t: Throwable) {
            lastErrorFa = t.localizedMessage ?: "تولید صوت توسط Piper ناموفق بود."
            Log.e(TAG, "Piper synthesis failed", t)
            null
        } finally {
            runCatching { result?.close() }
            inputs.values.forEach { tensor -> runCatching { tensor.close() } }
        }
    }

    internal fun buildScaleArray(cfg: PiperConfig, speechRate: Float): FloatArray {
        val effectiveRate = speechRate.coerceIn(0.6f, 1.8f)
        val effectiveLengthScale = (cfg.lengthScale / effectiveRate).coerceIn(0.4f, 2.0f)
        return floatArrayOf(cfg.noiseScale, effectiveLengthScale, cfg.noiseW)
    }

    private fun buildInputMap(
        sess: OrtSession,
        ids: LongArray,
        cfg: PiperConfig,
        speechRate: Float,
        ortEnv: OrtEnvironment,
    ): Map<String, OnnxTensor> {
        val inputs = sess.inputNames
        val map = mutableMapOf<String, OnnxTensor>()

        val idsName = inputs.firstOrNull {
            it.contains("input", true) && !it.contains("length", true)
        } ?: inputs.first()
        map[idsName] = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))

        inputs.firstOrNull { it.contains("length", true) }?.let { name ->
            map[name] = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1))
        }
        inputs.firstOrNull { it.contains("scale", true) }?.let { name ->
            map[name] = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(buildScaleArray(cfg, speechRate)), longArrayOf(3))
        }
        if (cfg.numSpeakers > 1) {
            inputs.firstOrNull { it.contains("sid", true) || it.contains("speaker", true) }?.let { name ->
                map[name] = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(longArrayOf(0L)), longArrayOf(1))
            }
        }
        return map
    }

    fun play(pcm: ShortArray, sampleRate: Int): Boolean {
        if (pcm.isEmpty()) return false
        stopRequested.set(false)
        var track: AudioTrack? = null
        return try {
            val minBufBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(4096)

            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufBytes * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                lastErrorFa = "خطا در آماده‌سازی خروجی صدای AudioTrack"
                return false
            }

            audioTrack = track
            track.play()

            val chunkSamples = (minBufBytes / 2).coerceAtLeast(1024)
            var offset = 0
            while (offset < pcm.size && !stopRequested.get()) {
                val count = min(chunkSamples, pcm.size - offset)
                val written = track.write(pcm, offset, count, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    lastErrorFa = "خطا در پخش صوت Piper (کد $written)"
                    return false
                }
                offset += written
            }
            true
        } catch (t: Throwable) {
            lastErrorFa = t.localizedMessage ?: "پخش صدای Piper ناموفق بود."
            Log.e(TAG, "Piper playback failed", t)
            false
        } finally {
            audioTrack = null
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            runCatching { track?.stop() }
            runCatching { track?.release() }
        }
    }

    fun stop() {
        stopRequested.set(true)
        val t = audioTrack
        audioTrack = null
        runCatching { t?.pause() }
        runCatching { t?.flush() }
        runCatching { t?.stop() }
    }

    fun unloadSessionOnly() {
        stop()
        runCatching { session?.close() }
        session = null
        config = null
        env = null
    }

    fun release() {
        unloadSessionOnly()
    }

    companion object {
        private const val TAG = "PiperTtsEngine"
    }
}
