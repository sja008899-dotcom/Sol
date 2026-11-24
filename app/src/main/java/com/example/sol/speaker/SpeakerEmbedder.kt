package com.example.sol.speaker

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class SpeakerEmbedder(private val modelFile: File) {
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Volatile
    var lastErrorFa: String = ""
        private set

    val isModelPresent: Boolean
        get() = modelFile.exists() &&
            modelFile.isFile &&
            modelFile.length() > 0L

    val isReady: Boolean get() = session != null

    fun initialize(): Boolean {
        lastErrorFa = ""
        return try {
            require(modelFile.exists() && modelFile.isFile && modelFile.length() > 0L) {
                "فایل مدل شناسایی گوینده یافت نشد."
            }

            val actualHash = calculateSHA256(modelFile)
            if (!actualHash.equals(EXPECTED_CAMPP_SHA256, ignoreCase = true)) {
                lastErrorFa = "اثر انگشت فایل مدل شناسایی گوینده (CAM++) مطابقت ندارد."
                throw SecurityException(lastErrorFa)
            }

            val ortEnv = OrtEnvironment.getEnvironment()
            val ortSess = ortEnv.createSession(modelFile.absolutePath)
            env = ortEnv
            session = ortSess
            true
        } catch (t: Throwable) {
            if (lastErrorFa.isBlank()) {
                lastErrorFa = t.localizedMessage ?: "خطا در بارگذاری مدل شناسایی گوینده ONNX"
            }
            release()
            false
        }
    }

    fun embed(pcm16k: FloatArray): FloatArray? {
        if (pcm16k.isEmpty()) return null
        if (!isReady && isModelPresent) {
            if (!initialize()) return null
        }
        val ortEnvironment = env ?: return null
        val ortSession = session ?: return null

        var tensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        return try {
            val inputName = ortSession.inputNames.firstOrNull {
                it.contains("feat", true) ||
                    it.contains("fbank", true) ||
                    it.contains("wav", true) ||
                    it.contains("audio", true) ||
                    it.contains("input", true)
            } ?: ortSession.inputNames.first()

            val nodeInfo = ortSession.inputInfo[inputName]?.info as? TensorInfo
            val shapeRank = nodeInfo?.shape?.size ?: 3

            tensor = if (shapeRank == 2) {
                // Waveform input model: [1, num_samples]
                OnnxTensor.createTensor(
                    ortEnvironment,
                    FloatBuffer.wrap(pcm16k),
                    longArrayOf(1L, pcm16k.size.toLong()),
                )
            } else {
                // Standard CAM++ / Kaldi 80-bin Log-Mel Fbank input: [1, num_frames, 80]
                val fbank = computeLogMelFbank80(pcm16k)
                val frames = (fbank.size / NUM_MEL_BINS).coerceAtLeast(1)
                OnnxTensor.createTensor(
                    ortEnvironment,
                    FloatBuffer.wrap(fbank),
                    longArrayOf(1L, frames.toLong(), NUM_MEL_BINS.toLong()),
                )
            }

            result = ortSession.run(mapOf(inputName to tensor))
            val output = result.get(0) as? OnnxTensor ?: return null
            val floatBuffer = output.floatBuffer
            val vector = FloatArray(floatBuffer.remaining())
            floatBuffer.get(vector)
            if (vector.isEmpty()) null else l2Normalize(vector)
        } catch (t: Throwable) {
            lastErrorFa = t.localizedMessage ?: "خطا در استخراج بردار صدای گوینده"
            null
        } finally {
            runCatching { result?.close() }
            runCatching { tensor?.close() }
        }
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
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun release() {
        runCatching { session?.close() }
        session = null
        env = null
    }

    companion object {
        const val EXPECTED_CAMPP_SHA256 =
            "27bbb97acd182a8d16aff8e8743d2a54b66450773b84cae140483fe8049bd67d"

        const val SAMPLE_RATE = 16_000
        const val FRAME_SIZE = 400 // 25ms @ 16kHz
        const val FRAME_SHIFT = 160 // 10ms @ 16kHz
        const val FFT_SIZE = 512
        const val NUM_MEL_BINS = 80
        private const val LOW_FREQ_HZ = 20.0
        private const val HIGH_FREQ_HZ = 7600.0

        private val hammingWindow: FloatArray by lazy {
            FloatArray(FRAME_SIZE) { n ->
                (0.54 - 0.46 * cos((2.0 * PI * n) / (FRAME_SIZE - 1))).toFloat()
            }
        }

        private val melFilterBank: Array<FloatArray> by lazy {
            buildMelFilterBank(
                sampleRate = SAMPLE_RATE,
                fftSize = FFT_SIZE,
                numMels = NUM_MEL_BINS,
                lowFreqHz = LOW_FREQ_HZ,
                highFreqHz = HIGH_FREQ_HZ,
            )
        }

        /**
         * Computes standard Kaldi/CAM++ 80-dimensional Log-Mel Fbank features with per-utterance CMVN
         * (mean subtraction across frames per Mel bin) in row-major `[frames, 80]` layout.
         */
        fun computeLogMelFbank80(pcm: FloatArray): FloatArray {
            val paddedPcm = if (pcm.size < FRAME_SIZE) {
                FloatArray(FRAME_SIZE).also { pcm.copyInto(it) }
            } else {
                pcm
            }
            val numFrames = ((paddedPcm.size - FRAME_SIZE) / FRAME_SHIFT + 1).coerceAtLeast(1)
            val fbank = FloatArray(numFrames * NUM_MEL_BINS)
            val re = FloatArray(FFT_SIZE)
            val im = FloatArray(FFT_SIZE)
            val numBins = FFT_SIZE / 2 + 1
            val powerSpec = FloatArray(numBins)
            val filters = melFilterBank
            val win = hammingWindow

            val binSums = DoubleArray(NUM_MEL_BINS)
            for (frameIdx in 0 until numFrames) {
                val start = frameIdx * FRAME_SHIFT
                re.fill(0f)
                im.fill(0f)
                for (n in 0 until FRAME_SIZE) {
                    val sample = if (start + n < paddedPcm.size) paddedPcm[start + n] else 0f
                    re[n] = sample * win[n]
                }
                fftRadix2(re, im)

                for (k in 0 until numBins) {
                    val r = re[k]
                    val m = im[k]
                    powerSpec[k] = r * r + m * m
                }

                val rowOffset = frameIdx * NUM_MEL_BINS
                for (melIdx in 0 until NUM_MEL_BINS) {
                    val weights = filters[melIdx]
                    var energy = 0.0
                    for (k in 0 until numBins) {
                        val w = weights[k]
                        if (w != 0f) {
                            energy += powerSpec[k] * w
                        }
                    }
                    val logE = ln(energy.coerceAtLeast(1e-6)).toFloat()
                    fbank[rowOffset + melIdx] = logE
                    binSums[melIdx] += logE.toDouble()
                }
            }

            // CMVN (mean subtraction per frequency bin) required by CAM++ / WeSpeaker
            for (melIdx in 0 until NUM_MEL_BINS) {
                val mean = (binSums[melIdx] / numFrames).toFloat()
                for (frameIdx in 0 until numFrames) {
                    val idx = frameIdx * NUM_MEL_BINS + melIdx
                    fbank[idx] -= mean
                }
            }
            return fbank
        }

        private fun fftRadix2(re: FloatArray, im: FloatArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while ((j and bit) != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    val tmpR = re[i]
                    re[i] = re[j]
                    re[j] = tmpR
                    val tmpI = im[i]
                    im[i] = im[j]
                    im[j] = tmpI
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2.0 * PI / len
                val wLenR = cos(ang).toFloat()
                val wLenI = sin(ang).toFloat()
                var i = 0
                while (i < n) {
                    var wR = 1f
                    var wI = 0f
                    val half = len shr 1
                    for (k in 0 until half) {
                        val uR = re[i + k]
                        val uI = im[i + k]
                        val vIdx = i + k + half
                        val vR = re[vIdx] * wR - im[vIdx] * wI
                        val vI = re[vIdx] * wI + im[vIdx] * wR
                        re[i + k] = uR + vR
                        im[i + k] = uI + vI
                        re[vIdx] = uR - vR
                        im[vIdx] = uI - vI
                        val nextWR = wR * wLenR - wI * wLenI
                        wI = wR * wLenI + wI * wLenR
                        wR = nextWR
                    }
                    i += len
                }
                len = len shl 1
            }
        }

        private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
        private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        private fun buildMelFilterBank(
            sampleRate: Int,
            fftSize: Int,
            numMels: Int,
            lowFreqHz: Double,
            highFreqHz: Double,
        ): Array<FloatArray> {
            val numBins = fftSize / 2 + 1
            val lowMel = hzToMel(lowFreqHz)
            val highMel = hzToMel(highFreqHz)
            val melPoints = DoubleArray(numMels + 2) { idx ->
                lowMel + (highMel - lowMel) * idx / (numMels + 1)
            }
            val binPoints = IntArray(numMels + 2) { idx ->
                floor((fftSize + 1) * melToHz(melPoints[idx]) / sampleRate).toInt().coerceIn(0, numBins - 1)
            }

            return Array(numMels) { m ->
                val filter = FloatArray(numBins)
                val left = binPoints[m]
                val center = binPoints[m + 1]
                val right = binPoints[m + 2]
                if (center > left) {
                    for (k in left until center) {
                        filter[k] = ((k - left).toFloat() / (center - left)).coerceIn(0f, 1f)
                    }
                }
                if (right > center) {
                    for (k in center..right) {
                        filter[k] = ((right - k).toFloat() / (right - center)).coerceIn(0f, 1f)
                    }
                }
                filter
            }
        }

        fun l2Normalize(vec: FloatArray): FloatArray {
            if (vec.isEmpty()) return vec
            var sumSq = 0f
            for (v in vec) {
                sumSq += v * v
            }
            val norm = sqrt(sumSq).coerceAtLeast(1e-8f)
            return FloatArray(vec.size) { i -> vec[i] / norm }
        }
    }
}
