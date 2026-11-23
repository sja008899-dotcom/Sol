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

class PiperTtsEngine(
    private val modelFile: File,
    private val configFile: File
) {
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var config: PiperConfig? = null
    private var audioTrack: AudioTrack? = null

    val isReady: Boolean get() = session != null && config != null
    val sampleRate: Int get() = config?.sampleRate ?: 22050

    fun initialize(): Boolean {
        return try {
            require(modelFile.exists()) { "Piper model missing" }
            require(configFile.exists()) { "Piper config missing" }
            config = PiperConfig.parse(configFile)
            env = OrtEnvironment.getEnvironment()
            session = env!!.createSession(modelFile.absolutePath)
            Log.i("PiperTtsEngine", "Piper engine ready, sampleRate=${config!!.sampleRate}")
            true
        } catch (t: Throwable) {
            Log.e("PiperTtsEngine", "Piper init failed", t)
            release()
            false
        }
    }

    fun synthesize(text: String): ShortArray? {
        val cfg = config ?: return null
        val sess = session ?: return null
        val envRef = env ?: return null
        if (text.isBlank()) return null

        return try {
            val ids = PersianPhonemizer.toPhonemeIds(text, cfg)
            if (ids.isEmpty()) return null

            val inputs = buildInputMap(sess, ids, envRef)
            val result = sess.run(inputs)
            val output = result.get(0) as OnnxTensor
            val floatBuffer = output.floatBuffer
            val pcm = ShortArray(floatBuffer.remaining())
            var idx = 0
            while (floatBuffer.hasRemaining()) {
                val sample = (floatBuffer.get() * Short.MAX_VALUE)
                    .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                pcm[idx++] = sample.toInt().toShort()
            }
            result.close()
            inputs.values.forEach { it.close() }
            pcm
        } catch (t: Throwable) {
            Log.e("PiperTtsEngine", "Piper synthesis failed", t)
            null
        }
    }

    private fun buildInputMap(sess: OrtSession, ids: LongArray, ortEnv: OrtEnvironment): Map<String, OnnxTensor> {
        val inputs = sess.inputNames
        val map = mutableMapOf<String, OnnxTensor>()

        val idsName = inputs.firstOrNull { it.contains("input", true) && !it.contains("length", true) } ?: inputs.first()
        map[idsName] = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))

        inputs.firstOrNull { it.contains("length", true) }?.let { name ->
            map[name] = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1))
        }
        inputs.firstOrNull { it.contains("scale", true) }?.let { name ->
            map[name] = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(floatArrayOf(0.667f, 1.0f, 0.8f)), longArrayOf(3))
        }
        inputs.firstOrNull { it.contains("sid", true) || it.contains("speaker", true) }?.let { name ->
            map[name] = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(longArrayOf(0L)), longArrayOf(1))
        }
        return map
    }

    fun play(pcm: ShortArray, sampleRate: Int): Boolean {
        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            audioTrack = track
            track.write(pcm, 0, pcm.size)
            track.play()
            true
        } catch (t: Throwable) {
            Log.e("PiperTtsEngine", "Piper playback failed", t)
            false
        }
    }

    fun stop() {
        try { audioTrack?.pause(); audioTrack?.flush(); audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null
    }

    fun release() {
        stop()
        try { session?.close() } catch (_: Throwable) {}
        session = null
        config = null
        env = null
    }
}
