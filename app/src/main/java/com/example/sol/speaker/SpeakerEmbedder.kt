package com.example.sol.speaker

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest

class SpeakerEmbedder(private val modelFile: File) {
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    val isReady: Boolean get() = session != null

    fun initialize(): Boolean {
        return try {
            require(modelFile.exists()) { "Speaker embedding model missing" }
            val expectedHash = "27bbb97acd182a8d16aff8e8743d2a54b66450773b84cae140483fe8049bd67d"
            val actualHash = calculateSHA256(modelFile)
            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                throw SecurityException("فایل مدل شناسایی صدا معتبر نیست (هش مطابقت ندارد).")
            }
            env = OrtEnvironment.getEnvironment()
            session = env!!.createSession(modelFile.absolutePath)
            true
        } catch (t: Throwable) {
            release()
            false
        }
    }

    fun embed(pcm16k: FloatArray): FloatArray? {
        val e = env ?: return null
        val s = session ?: return null
        return try {
            val fbank = computeFbank(pcm16k)
            val frames = fbank.size / 80
            val inputName = s.inputNames.firstOrNull { it.contains("feat", true) || it.contains("input", true) } ?: s.inputNames.first()
            
            val tensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(fbank), longArrayOf(1, frames.toLong(), 80))
            val result = s.run(mapOf(inputName to tensor))
            val out = result.get(0) as OnnxTensor
            val fb = out.floatBuffer
            val vec = FloatArray(fb.remaining())
            fb.get(vec)
            result.close()
            tensor.close()
            l2Normalize(vec)
        } catch (t: Throwable) {
            null
        }
    }

    private fun computeFbank(pcm: FloatArray): FloatArray {
        val frameSize = 400
        val frameShift = 160
        val numFrames = if (pcm.size >= frameSize) (pcm.size - frameSize) / frameShift + 1 else 1
        val fbank = FloatArray(numFrames * 80)
        for (i in 0 until numFrames) {
            val start = i * frameShift
            for (j in 0 until 80) {
                fbank[i * 80 + j] = if (start + j < pcm.size) pcm[start + j] else 0f
            }
        }
        return fbank
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = kotlin.math.sqrt(sum).coerceAtLeast(1e-8f)
        for (i in v.indices) v[i] /= norm
        return v
    }

    private fun calculateSHA256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun release() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        env = null
    }
}
