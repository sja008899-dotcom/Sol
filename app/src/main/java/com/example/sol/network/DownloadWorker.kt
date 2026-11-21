package com.example.sol.network

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL).orEmpty().trim()
        val destinationPath = inputData.getString(KEY_DESTINATION_PATH).orEmpty().trim()
        val expectedBytes = inputData.getLong(KEY_EXPECTED_BYTES, -1L)
        val expectedSha256 = inputData.getString(KEY_EXPECTED_SHA256).orEmpty().trim()
        val rawHosts = inputData.getStringArray(KEY_ALLOWED_HOST_SUFFIXES)
        val allowedHosts = rawHosts?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet()
            ?: SolHttpClient.DEFAULT_MODEL_ALLOWED_HOSTS

        val outcome = executeVerifiedDownload(
            urlString = url,
            destinationPath = destinationPath,
            expectedBytes = expectedBytes,
            expectedSha256 = expectedSha256,
            allowedHostSuffixes = allowedHosts,
            httpClient = SolHttpClient(),
            isCancelled = { isStopped },
            onProgress = { pct, downloaded, total ->
                setProgressAsync(
                    workDataOf(
                        KEY_PROGRESS_PERCENT to pct,
                        KEY_DOWNLOADED_BYTES to downloaded,
                        KEY_EXPECTED_BYTES to total,
                    )
                )
            },
        )

        return if (outcome.success) {
            Result.success(
                workDataOf(
                    KEY_DESTINATION_PATH to outcome.destinationFile?.absolutePath.orEmpty(),
                    KEY_EXPECTED_BYTES to expectedBytes,
                    KEY_EXPECTED_SHA256 to expectedSha256,
                )
            )
        } else {
            Result.failure(workDataOf(KEY_ERROR_MESSAGE to outcome.errorMessageFa))
        }
    }

    data class DownloadOutcome(
        val success: Boolean,
        val destinationFile: File? = null,
        val errorMessageFa: String = "",
    )

    companion object {
        const val KEY_URL = "download_url"
        const val KEY_DESTINATION_PATH = "download_destination_path"
        const val KEY_EXPECTED_BYTES = "download_expected_bytes"
        const val KEY_EXPECTED_SHA256 = "download_expected_sha256"
        const val KEY_ALLOWED_HOST_SUFFIXES = "download_allowed_hosts"
        const val KEY_PROGRESS_PERCENT = "download_progress_percent"
        const val KEY_DOWNLOADED_BYTES = "download_bytes_downloaded"
        const val KEY_ERROR_MESSAGE = "download_error_message"

        internal fun executeVerifiedDownload(
            urlString: String,
            destinationPath: String,
            expectedBytes: Long,
            expectedSha256: String,
            allowedHostSuffixes: Set<String>? = SolHttpClient.DEFAULT_MODEL_ALLOWED_HOSTS,
            httpClient: SolHttpClient = SolHttpClient(),
            isCancelled: () -> Boolean = { false },
            onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _, _ -> },
        ): DownloadOutcome {
            if (urlString.isBlank() || destinationPath.isBlank()) {
                return DownloadOutcome(
                    success = false,
                    errorMessageFa = "نشانی یا مسیر ذخیرهٔ فایل مشخص نیست.",
                )
            }
            // Mandatory fail-safe: reject TODO(verify), malformed hash, or unknown size
            if (!SolHttpClient.isValidSha256Hex(expectedSha256) || expectedBytes <= 0L) {
                File("$destinationPath.part").delete()
                return DownloadOutcome(
                    success = false,
                    errorMessageFa = "اثر انگشت SHA-256 یا حجم فایل تأیید نشده است (TODO(verify))؛ دانلود ایمن متوقف شد.",
                )
            }

            val destFile = File(destinationPath)
            val partFile = File("$destinationPath.part")
            destFile.parentFile?.mkdirs()

            return try {
                val verifiedPart = httpClient.downloadVerifiedFile(
                    urlString = urlString,
                    partFile = partFile,
                    expectedBytes = expectedBytes,
                    expectedSha256 = expectedSha256,
                    allowedHostSuffixes = allowedHostSuffixes,
                    isCancelled = isCancelled,
                    onProgress = onProgress,
                )
                if (destFile.exists() && !destFile.delete()) {
                    throw java.io.IOException("جایگزینی فایل مقصد ممکن نشد.")
                }
                if (!verifiedPart.renameTo(destFile)) {
                    verifiedPart.copyTo(destFile, overwrite = true)
                    verifiedPart.delete()
                }
                DownloadOutcome(success = true, destinationFile = destFile)
            } catch (e: SolNetworkException) {
                DownloadOutcome(success = false, errorMessageFa = e.userMessageFa)
            } catch (t: Throwable) {
                partFile.delete()
                DownloadOutcome(
                    success = false,
                    errorMessageFa = t.localizedMessage ?: "خطا در دریافت فایل تأییدشده.",
                )
            }
        }

        fun buildWorkRequest(
            url: String,
            destinationPath: String,
            expectedBytes: Long,
            expectedSha256: String,
            allowedHostSuffixes: Set<String> = SolHttpClient.DEFAULT_MODEL_ALLOWED_HOSTS,
        ): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            return OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_URL to url,
                        KEY_DESTINATION_PATH to destinationPath,
                        KEY_EXPECTED_BYTES to expectedBytes,
                        KEY_EXPECTED_SHA256 to expectedSha256,
                        KEY_ALLOWED_HOST_SUFFIXES to allowedHostSuffixes.toTypedArray(),
                    )
                )
                .build()
        }

        fun enqueue(
            context: Context,
            url: String,
            destinationPath: String,
            expectedBytes: Long,
            expectedSha256: String,
            allowedHostSuffixes: Set<String> = SolHttpClient.DEFAULT_MODEL_ALLOWED_HOSTS,
            uniqueWorkName: String = "sol_verified_download_${url.hashCode()}",
        ): Operation {
            val req = buildWorkRequest(
                url = url,
                destinationPath = destinationPath,
                expectedBytes = expectedBytes,
                expectedSha256 = expectedSha256,
                allowedHostSuffixes = allowedHostSuffixes,
            )
            return WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName,
                ExistingWorkPolicy.KEEP,
                req,
            )
        }
    }
}
