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
import com.example.sol.memory.PreferencesRepository
import com.example.sol.runtime.SolNetworkPolicy
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
        val allowTierB = inputData.getBoolean(KEY_ALLOW_TIER_B, false)
        val minBytes = inputData.getLong(KEY_MIN_ALLOWED_BYTES, -1L)
        val maxBytes = inputData.getLong(KEY_MAX_ALLOWED_BYTES, -1L)
        val tofuSha = inputData.getString(KEY_TOFU_SHA256)?.trim()?.takeIf { it.isNotBlank() }

        val sharedClient = (applicationContext as? com.example.SolApplication)
            ?.takeIf { it.isContainerInitialized() }
            ?.container
            ?.httpClient
        val effectiveClient = if (sharedClient != null) {
            sharedClient
        } else {
            val offlineStrict = runCatching {
                PreferencesRepository(applicationContext).getSnapshot().offlineStrictMode
            }.getOrDefault(false)
            SolHttpClient(
                networkPolicyProvider = {
                    if (offlineStrict) SolNetworkPolicy.OFFLINE_STRICT
                    else SolNetworkPolicy.USER_APPROVED_NETWORK
                },
            )
        }

        val outcome = executeVerifiedDownload(
            urlString = url,
            destinationPath = destinationPath,
            expectedBytes = expectedBytes,
            expectedSha256 = expectedSha256,
            allowTierB = allowTierB,
            minAllowedBytes = minBytes,
            maxAllowedBytes = maxBytes,
            tofuExpectedSha256 = tofuSha,
            allowedHostSuffixes = allowedHosts,
            httpClient = effectiveClient,
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
                    KEY_EXPECTED_BYTES to outcome.totalBytes,
                    KEY_EXPECTED_SHA256 to outcome.sha256Hex.ifBlank { expectedSha256 },
                    KEY_ACTUAL_SHA256 to outcome.sha256Hex,
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
        val sha256Hex: String = "",
        val totalBytes: Long = -1L,
    )

    companion object {
        const val KEY_URL = "download_url"
        const val KEY_DESTINATION_PATH = "download_destination_path"
        const val KEY_EXPECTED_BYTES = "download_expected_bytes"
        const val KEY_EXPECTED_SHA256 = "download_expected_sha256"
        const val KEY_ACTUAL_SHA256 = "download_actual_sha256"
        const val KEY_ALLOW_TIER_B = "download_allow_tier_b"
        const val KEY_MIN_ALLOWED_BYTES = "download_min_allowed_bytes"
        const val KEY_MAX_ALLOWED_BYTES = "download_max_allowed_bytes"
        const val KEY_TOFU_SHA256 = "download_tofu_sha256"
        const val KEY_ALLOWED_HOST_SUFFIXES = "download_allowed_hosts"
        const val KEY_PROGRESS_PERCENT = "download_progress_percent"
        const val KEY_DOWNLOADED_BYTES = "download_bytes_downloaded"
        const val KEY_ERROR_MESSAGE = "download_error_message"

        internal fun executeVerifiedDownload(
            urlString: String,
            destinationPath: String,
            expectedBytes: Long,
            expectedSha256: String,
            allowTierB: Boolean = false,
            minAllowedBytes: Long = -1L,
            maxAllowedBytes: Long = -1L,
            tofuExpectedSha256: String? = null,
            allowedHostSuffixes: Set<String>? = SolHttpClient.DEFAULT_MODEL_ALLOWED_HOSTS,
            httpClient: SolHttpClient = SolHttpClient(),
            isCancelled: () -> Boolean = { false },
            onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _, _ -> },
        ): DownloadOutcome {
            if (httpClient.isOfflineStrict()) {
                return DownloadOutcome(
                    success = false,
                    errorMessageFa = "حالت آفلاین سخت‌گیرانه (Offline Strict) فعال است؛ دانلود متوقف شد.",
                )
            }
            if (urlString.isBlank() || destinationPath.isBlank()) {
                return DownloadOutcome(
                    success = false,
                    errorMessageFa = "نشانی یا مسیر ذخیرهٔ فایل مشخص نیست.",
                )
            }
            val hasPinnedMetadata = SolHttpClient.isValidSha256Hex(expectedSha256) && expectedBytes > 0L
            // Mandatory fail-safe: reject TODO(verify) unless explicit Tier B confirmation was granted
            if (!hasPinnedMetadata && !allowTierB) {
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
                val (verifiedPart, actualSha, actualTotal) = if (hasPinnedMetadata) {
                    val file = httpClient.downloadVerifiedFile(
                        urlString = urlString,
                        partFile = partFile,
                        expectedBytes = expectedBytes,
                        expectedSha256 = expectedSha256,
                        allowedHostSuffixes = allowedHostSuffixes,
                        isCancelled = isCancelled,
                        onProgress = onProgress,
                    )
                    Triple(file, expectedSha256.lowercase(), expectedBytes)
                } else {
                    val headMeta = runCatching {
                        httpClient.preflightHeadMetadata(urlString, allowedHostSuffixes)
                    }.getOrNull()
                    val res = httpClient.downloadOfficialHttpsFileResult(
                        urlString = urlString,
                        partFile = partFile,
                        estimatedBytes = expectedBytes.coerceAtLeast(10L * 1024L * 1024L),
                        expectedSha256Override = headMeta?.sha256,
                        expectedBytesOverride = headMeta?.contentLengthBytes ?: -1L,
                        tofuExpectedSha256 = tofuExpectedSha256,
                        minAllowedBytes = minAllowedBytes,
                        maxAllowedBytes = maxAllowedBytes,
                        allowedHostSuffixes = allowedHostSuffixes,
                        redirectAllowedHostSuffixes = SolHttpClient.DEFAULT_CDN_REDIRECT_HOSTS,
                        isCancelled = isCancelled,
                        onProgress = onProgress,
                    )
                    Triple(res.file, res.sha256Hex, res.totalBytes)
                }
                if (destFile.exists() && !destFile.delete()) {
                    throw java.io.IOException("جایگزینی فایل مقصد ممکن نشد.")
                }
                if (!verifiedPart.renameTo(destFile)) {
                    verifiedPart.copyTo(destFile, overwrite = true)
                    verifiedPart.delete()
                }
                DownloadOutcome(
                    success = true,
                    destinationFile = destFile,
                    sha256Hex = actualSha,
                    totalBytes = actualTotal,
                )
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
            allowTierB: Boolean = false,
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
                        KEY_ALLOW_TIER_B to allowTierB,
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
            allowTierB: Boolean = false,
            uniqueWorkName: String = "sol_verified_download_${url.hashCode()}",
        ): Operation {
            val req = buildWorkRequest(
                url = url,
                destinationPath = destinationPath,
                expectedBytes = expectedBytes,
                expectedSha256 = expectedSha256,
                allowedHostSuffixes = allowedHostSuffixes,
                allowTierB = allowTierB,
            )
            return WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName,
                ExistingWorkPolicy.KEEP,
                req,
            )
        }
    }
}
