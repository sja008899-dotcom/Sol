package com.example.sol.speaker

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stores the device owner's speaker embedding encrypted at rest via Jetpack Security [EncryptedFile].
 * Never uploaded or logged; remains strictly on-device.
 */
class OwnerProfile(
    context: Context,
    private val storageFile: File = File(context.applicationContext.filesDir, "speaker/owner.bin"),
) {
    private val appContext = context.applicationContext

    fun exists(): Boolean = storageFile.exists() && storageFile.isFile && storageFile.length() > 0L

    fun save(embedding: FloatArray): Boolean {
        if (embedding.isEmpty()) return false
        return try {
            storageFile.parentFile?.mkdirs()
            if (storageFile.exists()) storageFile.delete()
            val masterKey = buildMasterKey()
            val encrypted = EncryptedFile.Builder(
                appContext,
                storageFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build()

            val byteBuf = ByteBuffer.allocate(embedding.size * 4).order(ByteOrder.BIG_ENDIAN)
            embedding.forEach { byteBuf.putFloat(it) }
            encrypted.openFileOutput().use { it.write(byteBuf.array()) }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed saving encrypted owner speaker profile", t)
            false
        }
    }

    fun load(): FloatArray? {
        if (!exists()) return null
        return try {
            val masterKey = buildMasterKey()
            val encrypted = EncryptedFile.Builder(
                appContext,
                storageFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build()

            val bytes = encrypted.openFileInput().use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size % 4 != 0) return null
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asFloatBuffer()
            FloatArray(fb.remaining()).also { fb.get(it) }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed loading encrypted owner speaker profile", t)
            null
        }
    }

    fun clear(): Boolean {
        return runCatching { !storageFile.exists() || storageFile.delete() }.getOrDefault(false)
    }

    private fun buildMasterKey(): MasterKey =
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    companion object {
        private const val TAG = "OwnerProfile"
    }
}
