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

/**
 * Self-contained offline Piper TTS engine backed by onnxruntime-android.
 * Validates SHA-256 of both `.onnx` and `.onnx.json` before creating an ORT session.
 */
class PiperTtsEngine(
    private val modelFile: File,
    private val configFile: File,
    private val verifyChecksums: Boolean = true,
) {
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var config: PiperConfig? = null
    private var audioTrack: AudioTrack? = null
    private val stopRequested = AtomicBoolean(false)

    var lastErrorFa: String = ""
        private set

    val isReady: Boolean
        get() = session != null && config != null

    val sampleRate: Int
        get() = config?.sampleRate ?: DEFAULT_SAMPLE_RATE

    fun initialize(): Boolean {
        lastErrorFa = ""
        return try {
            if (!modelFile.exists() || !modelFile.isFile) {
                lastErrorFa = "فایل مدل .onnx پیدا نشد."
                return false
            }
            if (!configFile.exists() || !configFile.isFile) {
                lastErrorFa = "فایل تنظیمات .json پیدا نشد."
                return false
            }

            if (verifyChecksums) {
                val onnxHash = PiperConfig.sha256(modelFile)
                if (!PiperConfig.verifyOnnxHash(onnxHash)) {
                    lastErrorFa = "فایل مدل .onnx نامعتبر است و با اثر انگشت SHA-256 تأییدشده مطابقت ندارد."
                    return false
                }
                val cfgHash = PiperConfig.sha256(configFile)
                if (!PiperConfig.verifyConfigHash(cfgHash)) {
                    lastErrorFa = "فایل تنظیمات .json نامعتبر است و با اثر انگشت SHA-256 تأییدشده مطابقت ندارد."
                    return false
                }
            }

            val parsedConfig = PiperConfig.parse(configFile)
            val ortEnv = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            val ortSession = ortEnv.createSession(modelFile.absolutePath, opts)
            config = parsedConfig
            env = ortEnv
            session = ortSession
            Log.i(TAG, "Piper engine initialized: sampleRate=${parsedConfig.sampleRate}, inputs=${ortSession.inputNames}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Piper initialization failed", t)
            lastErrorFa = t.localizedMessage?.takeIf { it.isNotBlank() }
                ?.let { "بارگذاری موتور Piper ناموفق بود: $it" }
                ?: "بارگذاری موتور Piper ناموفق بود."
            release()
            false
        }
    }

    /**
     * Synthesizes Persian text to 16-bit mono PCM. Must run on a background thread.
     */
    fun synthesize(text: String, speechRate: Float = 1.0f): ShortArray? {
        val cfg = config ?: return null
        val sess = session ?: return null
        val ortEnv = env ?: return null
        if (text.isBlank()) return null

        val ids = PersianPhonemizer.toPhonemeIds(text, cfg)
        if (ids.isEmpty()) return null

        var inputTensor: OnnxTensor? = null
        var lengthsTensor: OnnxTensor? = null
        var scalesTensor: OnnxTensor? = null
        var sidTensor: OnnxTensor? = null
        var result: OrtSession.Result? = null

        return try {
            val inputNames = sess.inputNames
            // TODO(verify): verify tensor names against the exact Piper checkpoint if customized
            val inputName = if ("input" in inputNames) "input" else inputNames.firstOrNull() ?: "input"
            val lengthsName = if ("input_lengths" in inputNames) "input_lengths" else "input_lengths"
            val scalesName = if ("scales" in inputNames) "scales" else "scales"
            val sidName = when {
                "sid" in inputNames -> "sid"
                "speaker_id" in inputNames -> "speaker_id"
                else -> null
            }

            inputTensor = OnnxTensor.createTensor(
                ortEnv,
                LongBuffer.wrap(ids),
                longArrayOf(1L, ids.size.toLong()),
            )
            lengthsTensor = OnnxTensor.createTensor(
                ortEnv,
                LongBuffer.wrap(longArrayOf(ids.size.toLong())),
                longArrayOf(1L),
            )
            val safeLengthScale = (cfg.lengthScale / speechRate.coerceIn(0.6f, 1.5f)).coerceIn(0.5f, 2.0f)
            scalesTensor = OnnxTensor.createTensor(
                ortEnv,
                FloatBuffer.wrap(floatArrayOf(cfg.noiseScale, safeLengthScale, cfg.noiseW)),
                longArrayOf(3L),
            )

            val inputs = linkedMapOf<String, OnnxTensor>(
                inputName to inputTensor,
                lengthsName to lengthsTensor,
                scalesName to scalesTensor,
            )
            if (sidName != null) {
                sidTensor = OnnxTensor.createTensor(
                    ortEnv,
                    LongBuffer.wrap(longArrayOf(0L)),
                    longArrayOf(1L),
                )
                inputs[sidName] = sidTensor
            }

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
            Log.e(TAG, "Piper synthesis failed", t)
            lastErrorFa = "تولید صدا در موتور Piper با خطا مواجه شد."
            null
        } finally {
            runCatching { result?.close() }
            runCatching { inputTensor?.close() }
            runCatching { lengthsTensor?.close() }
            runCatching { scalesTensor?.close() }
            runCatching { sidTensor?.close() }
        }
    }

    /**
     * Plays synthesized PCM synchronously until completion or [stop] is called.
     */
    fun play(pcm: ShortArray, sampleRate: Int): Boolean {
        if (pcm.isEmpty()) return false
        stopRequested.set(false)
        return try {
            val byteSize = pcm.size * 2
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(4096)

            val track = AudioTrack.Builder()
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
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { track.release() }
                return false
            }

            audioTrack = track
            track.play()

            var offset = 0
            val chunkSize = 2048
            while (offset < pcm.size && !stopRequested.get()) {
                val count = (pcm.size - offset).coerceAtMost(chunkSize)
                val written = track.write(pcm, offset, count)
                if (written <= 0) break
                offset += written
            }
            if (!stopRequested.get()) {
                // Allow hardware buffer to drain cleanly
                val durationMs = ((pcm.size.toLong() * 1000L) / sampleRate.coerceAtLeast(8000)).coerceAtMost(15_000L)
                val drainWait = durationMs.coerceAtMost(350L)
                if (drainWait > 0) Thread.sleep(drainWait)
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Piper playback failed", t)
            false
        } finally {
            stopAudioTrackOnly()
        }
    }

    fun stop() {
        stopRequested.set(true)
        stopAudioTrackOnly()
    }

    private fun stopAudioTrackOnly() {
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    fun release() {
        stop()
        runCatching { session?.close() }
        session = null
        config = null
        env = null
    }

    companion object {
        private const val TAG = "PiperTtsEngine"
        private const val DEFAULT_SAMPLE_RATE = 22050
    }
}
