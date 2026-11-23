package com.example.sol.speaker

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.sqrt

class SpeakerEmbedder(private val modelFile: File) {
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    val isModelPresent: Boolean
        get() = modelFile.exists() &&
            modelFile.isFile &&
            modelFile.length() > 0L

    val isReady: Boolean get() = session != null

    // The current feature extractor is not a real log-mel Fbank implementation.
    // Keep speaker verification disabled until the exact model preprocessing contract is implemented.
    val isPreprocessingSupported: Boolean get() = false

    @Synchronized
    fun initialize(): Boolean {
        if (isReady) return true
        release()
        return try {
            require(modelFile.exists()) {
                "Speaker embedding model missing"
            }

            val expectedHash =
                "27bbb97acd182a8d16aff8e8743d2a54b66450773b84cae140483fe8049bd67d"

            val actualHash = calculateSHA256(modelFile)

            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                throw SecurityException(
                    "فایل مدل شناسایی صدا معتبر نیست (هش مطابقت ندارد)."
                )
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
        val ortEnvironment = env ?: return null
        val ortSession = session ?: return null

        return try {
            if (!isPreprocessingSupported) {
                throw IllegalStateException(
                    "Speaker model preprocessing is not validated; refusing to generate an unreliable embedding."
                )
            }
            val fbank = computeFbank(pcm16k)
            val frames = fbank.size / 80

            val inputName =
                ortSession.inputNames.firstOrNull {
                    it.contains("feat", true) ||
                        it.contains("input", true)
                } ?: ortSession.inputNames.first()

            val tensor = OnnxTensor.createTensor(
                ortEnvironment,
                FloatBuffer.wrap(fbank),
                longArrayOf(1, frames.toLong(), 80)
            )

            val result = ortSession.run(
                mapOf(inputName to tensor)
            )

            val output = result.get(0) as OnnxTensor
            val floatBuffer = output.floatBuffer
            val vector = FloatArray(floatBuffer.remaining())
            floatBuffer.get(vector)

            result.close()
            tensor.close()

            l2Normalize(vector)
        } catch (t: Throwable) {
            null
        }
    }

    private fun computeFbank(pcm: FloatArray): FloatArray {
        val frameSize = 400
        val frameShift = 160

        val numFrames =
            if (pcm.size >= frameSize) {
                (pcm.size - frameSize) / frameShift + 1
            } else {
                1
            }

        val fbank = FloatArray(numFrames * 80)

        for (i in 0 until numFrames) {
            val start = i * frameShift

            for (j in 0 until 80) {
                fbank[i * 80 + j] =
                    if (start + j < pcm.size) pcm[start + j] else 0f
            }
        }

        return fbank
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0f

        for (value in vector) {
            sum += value * value
        }

        val norm = sqrt(sum).coerceAtLeast(1e-8f)

        for (i in vector.indices) {
            vector[i] /= norm
        }

        return vector
    }

    private fun calculateSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")

        file.inputStream().use { input ->
            val buffer = ByteArray(8192)

            while (true) {
                val read = input.read(buffer)

                if (read <= 0) break

                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") {
            "%02x".format(it)
        }
    }

    fun release() {
        try {
            session?.close()
        } catch (_: Throwable) {
        }

        session = null
        env = null
    }

    companion object {
        fun l2Normalize(vec: FloatArray): FloatArray {
            if (vec.isEmpty()) return vec

            var sumSq = 0f

            for (v in vec) {
                sumSq += v * v
            }

            val norm = sqrt(sumSq).coerceAtLeast(1e-8f)

            return FloatArray(vec.size) { i ->
                vec[i] / norm
            }
        }
    }
}
