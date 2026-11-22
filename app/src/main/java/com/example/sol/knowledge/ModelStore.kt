package com.example.sol.knowledge

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.sol.network.SolStorageGuard
import java.io.File
import java.io.FileOutputStream

internal class ModelStore(
    private val context: Context,
    private val availableBytesProvider: (File) -> Long = SolStorageGuard::readAvailableBytes,
) {
    val modelsDir: File
        get() = File(context.filesDir, MODELS_DIR_NAME)

    fun findActiveModelFile(preferredPath: String = ""): File? {
        if (preferredPath.isNotBlank()) {
            val f = File(preferredPath)
            if (f.isFile && f.length() >= MIN_VALID_MODEL_BYTES) return f
        }
        val dir = modelsDir
        if (!dir.isDirectory) return null
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".litertlm", ignoreCase = true) && it.length() >= MIN_VALID_MODEL_BYTES }
            ?.maxByOrNull { it.lastModified() }
    }

    fun hasInstalledModel(preferredPath: String = ""): Boolean = findActiveModelFile(preferredPath) != null

    fun deleteAllModels(preferredPath: String) {
        findActiveModelFile(preferredPath)?.delete()
        modelsDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".litertlm", ignoreCase = true) || file.name.endsWith(".part")) {
                file.delete()
            }
        }
    }

    fun copyLocalUriToStore(
        uri: Uri,
        onProgress: (title: String, pct: Int, doneMb: Long, totalMb: Long) -> Unit,
    ): File {
        val displayName = resolveDisplayName(uri)
        require(displayName.endsWith(".litertlm", ignoreCase = true)) {
            "فقط فایل‌های مدل با پسوند .litertlm پشتیبانی می‌شوند (انتخاب‌شده: $displayName)."
        }
        val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val destDir = modelsDir.apply { mkdirs() }
        val finalFile = File(destDir, safeName)
        val partFile = File(destDir, "$safeName.part")

        val declaredSize = resolveFileSize(uri)
        if (declaredSize > 0L) {
            val diskCheck = SolStorageGuard.checkFreeSpace(destDir, declaredSize, availableBytesProvider)
            require(diskCheck.hasEnoughSpace) {
                "فضای آزاد ذخیره‌سازی کافی نیست (آزاد: ${diskCheck.availableMb}MB، موردنیاز با حاشیه امن: ${diskCheck.requiredMb}MB)."
            }
        }

        val totalMb = (declaredSize.coerceAtLeast(1L)) / (1024L * 1024L)
        onProgress("کپی فایل محلی $safeName", 10, 0L, totalMb)

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(partFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                var lastPct = -1
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    copied += read
                    if (declaredSize > 0L) {
                        val pct = ((copied * 100L) / declaredSize).toInt().coerceIn(5, 99)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress("کپی فایل محلی $safeName", pct, copied / (1024L * 1024L), totalMb)
                        }
                    }
                }
            }
        } ?: error("دسترسی به فایل انتخاب‌شده ممکن نشد.")

        if (partFile.length() < MIN_VALID_MODEL_BYTES) {
            partFile.delete()
            error("حجم فایل انتخاب‌شده بسیار کم است و نمی‌تواند یک مدل .litertlm معتبر باشد.")
        }

        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
        }
        return finalFile
    }

    fun readAvailableRamMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return -1L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024L * 1024L)
    }

    private fun resolveDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "model.litertlm"
    }

    private fun resolveFileSize(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx)
            }
        }
        return -1L
    }

    companion object {
        const val MODELS_DIR_NAME = "slm_models"
        const val MIN_VALID_MODEL_BYTES = 1L * 1024L * 1024L

        fun hasEnoughRamForModel(availableRamMb: Long, minRequiredRamMb: Long): Boolean {
            if (availableRamMb < 0L) return true
            return availableRamMb >= minRequiredRamMb
        }
    }
}
