package com.example.sol.tts

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class AudioConfig(
    val quality: String?,
    val sampleRate: Int?,
)

/**
 * Minimal parser and SHA-256 verifier for Piper's `.onnx.json` configuration.
 * Unknown, malformed, or hash-mismatched files fail safe without crashing.
 */
data class PiperConfig(
    val sampleRate: Int,
    val numSymbols: Int,
    val numSpeakers: Int,
    val phonemeType: String,
    val phonemeIdMap: Map<String, List<Int>>,
    val audio: AudioConfig,
    val noiseScale: Float = 0.667f,
    val lengthScale: Float = 1.0f,
    val noiseW: Float = 0.8f,
) {
    fun flatPhonemeIdMap(): Map<String, Int> =
        phonemeIdMap.mapNotNull { (k, ids) -> ids.firstOrNull()?.let { k to it } }.toMap()

    companion object {
        // User-confirmed SHA-256 checksums for Piper Persian model (.onnx) and config (.json)
        const val EXPECTED_ONNX_SHA256 = "e390c0e74ba71fd97c49ba662ee0c6e1724b462ba2d4561698af4f564840f126"
        const val EXPECTED_CONFIG_SHA256 = "d102276a05436613a05e1fbad8f125abcf8f56759aaf4fd36adf216653949997"

        fun parse(jsonFile: File): PiperConfig {
            require(jsonFile.exists() && jsonFile.isFile) {
                "فایل تنظیمات Piper یافت نشد."
            }
            return parseJsonString(jsonFile.readText(Charsets.UTF_8))
        }

        fun parseJsonString(rawJson: String): PiperConfig {
            val root = runCatching { JSONObject(rawJson) }.getOrElse {
                throw IllegalArgumentException("فایل JSON تنظیمات Piper معتبر نیست.")
            }
            val audioObj = root.optJSONObject("audio")
            val inferenceObj = root.optJSONObject("inference")
            val phonemeIdMapJson = root.optJSONObject("phoneme_id_map")
                ?: throw IllegalArgumentException("کلید phoneme_id_map در فایل تنظیمات Piper وجود ندارد.")

            val map = buildMap<String, List<Int>> {
                val keys = phonemeIdMapJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = phonemeIdMapJson.optJSONArray(key) ?: continue
                    val ids = buildList {
                        for (idx in 0 until arr.length()) {
                            add(arr.getInt(idx))
                        }
                    }
                    if (ids.isNotEmpty()) {
                        put(key, ids)
                    }
                }
            }
            require(map.isNotEmpty()) {
                "جدول phoneme_id_map در فایل تنظیمات Piper خالی است."
            }

            val audioSampleRate = audioObj?.optInt("sample_rate", 0)?.takeIf { it > 0 }
            val rootSampleRate = root.optInt("sample_rate", 0).takeIf { it > 0 }
            val resolvedSampleRate = audioSampleRate ?: rootSampleRate
                ?: throw IllegalArgumentException("نرخ نمونه‌برداری (sample_rate) در فایل تنظیمات Piper نامعتبر است.")

            val numSymbols = root.optInt("num_symbols", 0).takeIf { it > 0 }
                ?: throw IllegalArgumentException("تعداد نمادها (num_symbols) در فایل تنظیمات Piper نامعتبر است.")

            val numSpeakers = root.optInt("num_speakers", 1).coerceAtLeast(1)
            val phonemeType = root.optString("phoneme_type", "espeak").ifBlank { "espeak" }
            val quality = audioObj?.optString("quality")?.takeIf { it.isNotBlank() }

            val noiseScale = inferenceObj?.optDouble("noise_scale", 0.667)?.toFloat()?.takeIf { it > 0f } ?: 0.667f
            val lengthScale = inferenceObj?.optDouble("length_scale", 1.0)?.toFloat()?.takeIf { it > 0f } ?: 1.0f
            val noiseW = inferenceObj?.optDouble("noise_w", 0.8)?.toFloat()?.takeIf { it > 0f } ?: 0.8f

            return PiperConfig(
                sampleRate = resolvedSampleRate,
                numSymbols = numSymbols,
                numSpeakers = numSpeakers,
                phonemeType = phonemeType,
                phonemeIdMap = map,
                audio = AudioConfig(
                    quality = quality,
                    sampleRate = audioSampleRate,
                ),
                noiseScale = noiseScale,
                lengthScale = lengthScale,
                noiseW = noiseW,
            )
        }

        fun sha256(file: File): String {
            file.inputStream().use { input ->
                return sha256(input)
            }
        }

        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun verifyOnnxHash(actualSha256: String): Boolean =
            actualSha256.equals(EXPECTED_ONNX_SHA256, ignoreCase = true)

        fun verifyConfigHash(actualSha256: String): Boolean =
            actualSha256.equals(EXPECTED_CONFIG_SHA256, ignoreCase = true)
    }
}
