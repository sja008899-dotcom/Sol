package com.example.sol.speaker

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Extracts normalized speaker embeddings from 16 kHz mono PCM using a user-supplied ONNX model.
 * No speaker model is bundled with the APK; fails safe when absent or invalid.
 *
 * TODO(verify): verify exact tensor input/output names and sample rate against the user's chosen speaker ONNX checkpoint.
 */
class SpeakerEmbedder(
    private val modelFile: File,
) {
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    val isModelPresent: Boolean
        get() = modelFile.exists() && modelFile.isFile && modelFile.length() > 0L

    val isReady: Boolean
        get() = session != null

    fun initialize(): Boolean {
        if (!isModelPresent) return false
        return try {
            val ortEnv = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            val ortSession = ortEnv.createSession(modelFile.absolutePath, opts)
            env = ortEnv
            session = ortSession
            true
        } catch (t: Throwable) {
            Log.e(TAG, "SpeakerEmbedder init failed", t)
            release()
            false
        }
    }

    /**
     * @param pcm16k 16 kHz mono float samples normalized in [-1f, 1f].
     * @return L2-normalized embedding vector, or null if model unavailable or inference fails.
     */
    fun embed(pcm16k: FloatArray): FloatArray? {
        if (pcm16k.isEmpty()) return null
        if (!isReady && !initialize()) return null
        val ortEnv = env ?: return null
        val ortSession = session ?: return null

        var tensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        return try {
            // TODO(verify): confirm input tensor name and rank [1, N] for the selected speaker ONNX model.
            val inputName = ortSession.inputNames.firstOrNull() ?: "input"
            tensor = OnnxTensor.createTensor(
                ortEnv,
                FloatBuffer.wrap(pcm16k),
                longArrayOf(1L, pcm16k.size.toLong()),
            )
            result = ortSession.run(mapOf(inputName to tensor))
            val out = result.get(0) as? OnnxTensor ?: return null
            val fb = out.floatBuffer
            val vec = FloatArray(fb.remaining())
            fb.get(vec)
            l2Normalize(vec)
        } catch (t: Throwable) {
            Log.e(TAG, "Speaker embedding extraction failed", t)
            null
        } finally {
            runCatching { result?.close() }
            runCatching { tensor?.close() }
        }
    }

    fun release() {
        runCatching { session?.close() }
        session = null
        env = null
    }

    companion object {
        private const val TAG = "SpeakerEmbedder"

        fun l2Normalize(vec: FloatArray): FloatArray {
            if (vec.isEmpty()) return vec
            var sumSq = 0f
            for (v in vec) sumSq += v * v
            val norm = sqrt(sumSq).coerceAtLeast(1e-8f)
            return FloatArray(vec.size) { i -> vec[i] / norm }
        }
    }
}
