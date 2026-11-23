package com.example.sol.knowledge

import android.net.Uri
import com.example.sol.network.SolHttpClient
import com.example.sol.network.SolNetworkException
import com.example.sol.network.SolStorageGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class ModelDownloader(
    private val modelStore: ModelStore,
    private val httpClient: SolHttpClient,
    private val availableBytesProvider: (File) -> Long,
    private val runtimeState: MutableStateFlow<SlmRuntimeState>,
    private val onUnloadBeforeDownload: (String) -> Unit,
    private val onRefreshStatus: (preferredPath: String, noteFa: String) -> Unit,
) {
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeDownloadJob: Job? = null

    private val downloadCancelled = AtomicBoolean(false)

    fun downloadModel(
        spec: SlmModelSpec,
        onSavedPath: (String) -> Unit,
    ) {
        if (activeDownloadJob?.isActive == true) return
        downloadCancelled.set(false)
        activeDownloadJob = downloadScope.launch {
            downloadModelSync(
                spec = spec,
                onSavedPath = onSavedPath,
                allowHttpsWithoutPinnedHash = true,
            )
        }
    }

    suspend fun downloadModelSync(
        spec: SlmModelSpec,
        onSavedPath: (String) -> Unit = {},
        allowHttpsWithoutPinnedHash: Boolean = false,
    ): Result<File> = withContext(Dispatchers.IO) {
        val destDir = modelStore.modelsDir.apply { mkdirs() }
        val finalFile = File(destDir, spec.fileName)
        val partFile = File(destDir, "${spec.fileName}.part")

        try {
            val hasPinnedMetadata = spec.hasVerifiedIntegrityMetadata()
            if (!hasPinnedMetadata && !allowHttpsWithoutPinnedHash) {
                partFile.delete()
                throw SolNetworkException.IntegrityMetadataMissingException()
            }

            val estimatedBytes = spec.estimatedDownloadBytes()
            val diskCheck = SolStorageGuard.checkFreeSpace(
                targetPath = destDir,
                payloadBytes = estimatedBytes,
                availableBytesProvider = availableBytesProvider,
            )
            if (!diskCheck.hasEnoughSpace) {
                throw SolNetworkException.InsufficientDiskSpaceException(
                    availableMb = diskCheck.availableMb,
                    requiredMb = diskCheck.requiredMb,
                )
            }

            onUnloadBeforeDownload("آزادسازی حافظه پیش از دانلود مدل")

            runtimeState.value = SlmRuntimeState.Downloading(
                modelTitleFa = spec.titleFa,
                progressPercent = 1,
                downloadedMb = 0L,
                totalMb = (estimatedBytes / (1024L * 1024L)).coerceAtLeast(1L),
            )

            if (hasPinnedMetadata) {
                httpClient.downloadVerifiedFile(
                    urlString = spec.downloadUrl,
                    partFile = partFile,
                    expectedBytes = spec.expectedBytes,
                    expectedSha256 = spec.sha256,
                    allowedHostSuffixes = setOf("huggingface.co", "hf.co"),
                    isCancelled = { downloadCancelled.get() },
                    onProgress = { pct, doneBytes, totalBytes ->
                        runtimeState.value = SlmRuntimeState.Downloading(
                            modelTitleFa = spec.titleFa,
                            progressPercent = pct,
                            downloadedMb = doneBytes / (1024L * 1024L),
                            totalMb = (totalBytes / (1024L * 1024L)).coerceAtLeast(1L),
                        )
                    },
                )
            } else {
                downloadOfficialSlmWithFallback(spec, partFile, estimatedBytes)
            }

            if (downloadCancelled.get()) {
                partFile.delete()
                onRefreshStatus("", "دانلود مدل لغو شد")
                return@withContext Result.failure(SolNetworkException.CancelledException())
            }

            if (partFile.length() < ModelStore.MIN_VALID_MODEL_BYTES) {
                partFile.delete()
                error("فایل دریافت‌شده معتبر یا کامل نیست (حجم کمتر از حد مجاز).")
            }

            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }

            onSavedPath(finalFile.absolutePath)
            onRefreshStatus(
                finalFile.absolutePath,
                "مدل «${spec.titleFa}» دانلود و آمادهٔ اجراست.",
            )
            Result.success(finalFile)
        } catch (t: Throwable) {
            if (t !is SolNetworkException.TransportException && t !is SolNetworkException.HttpStatusException) {
                if (t is SolNetworkException.IntegrityMismatchException ||
                    t is SolNetworkException.IntegrityMetadataMissingException ||
                    t is SolNetworkException.CancelledException
                ) {
                    partFile.delete()
                }
            }
            if (downloadCancelled.get() || t is CancellationException) {
                partFile.delete()
                onRefreshStatus("", "دانلود توسط کاربر متوقف شد")
            } else {
                val msg = if (t is SolNetworkException) {
                    t.userMessageFa
                } else {
                    "خطا در دانلود مدل: ${t.localizedMessage ?: "بررسی اتصال اینترنت و فضای حافظه"}"
                }
                runtimeState.value = SlmRuntimeState.Error(
                    messageFa = msg,
                    hasInstalledModel = modelStore.hasInstalledModel(),
                )
            }
            Result.failure(t)
        }
    }

    private fun downloadOfficialSlmWithFallback(
        spec: SlmModelSpec,
        partFile: File,
        estimatedBytes: Long,
    ) {
        val candidates = (listOf(spec.downloadUrl) + spec.fallbackDownloadUrls).distinct()
        var lastError: Throwable? = null
        for ((idx, candidateUrl) in candidates.withIndex()) {
            try {
                httpClient.downloadOfficialHttpsFile(
                    urlString = candidateUrl,
                    partFile = partFile,
                    estimatedBytes = estimatedBytes,
                    allowedHostSuffixes = setOf("huggingface.co", "hf.co"),
                    redirectAllowedHostSuffixes = SolHttpClient.DEFAULT_CDN_REDIRECT_HOSTS,
                    isCancelled = { downloadCancelled.get() },
                    onProgress = { pct, doneBytes, totalBytes ->
                        runtimeState.value = SlmRuntimeState.Downloading(
                            modelTitleFa = spec.titleFa,
                            progressPercent = pct,
                            downloadedMb = doneBytes / (1024L * 1024L),
                            totalMb = (totalBytes / (1024L * 1024L)).coerceAtLeast(1L),
                        )
                    },
                )
                return
            } catch (err: SolNetworkException.HttpStatusException) {
                lastError = err
                val canFallback = (err.statusCode == 404 || err.statusCode == 410) && idx < candidates.lastIndex
                if (canFallback) {
                    partFile.delete()
                    continue
                }
                throw err
            }
        }
        throw (lastError ?: SolNetworkException.TransportException())
    }

    fun cancelDownload() {
        downloadCancelled.set(true)
        activeDownloadJob?.cancel()
        activeDownloadJob = null
    }

    fun importLocalModel(
        uri: Uri,
        onResult: (Result<File>) -> Unit,
    ) {
        if (activeDownloadJob?.isActive == true) {
            onResult(Result.failure(IllegalStateException("در حال حاضر یک دانلود فعال است.")))
            return
        }
        downloadScope.launch {
            val res = runCatching {
                modelStore.copyLocalUriToStore(uri) { title, pct, doneMb, totalMb ->
                    runtimeState.value = SlmRuntimeState.Downloading(
                        modelTitleFa = title,
                        progressPercent = pct,
                        downloadedMb = doneMb,
                        totalMb = totalMb,
                    )
                }
            }
            res.fold(
                onSuccess = { file ->
                    onRefreshStatus(file.absolutePath, "مدل محلی با موفقیت وارد شد")
                    onResult(Result.success(file))
                },
                onFailure = { err ->
                    runtimeState.value = SlmRuntimeState.Error(
                        messageFa = err.localizedMessage ?: "وارد کردن فایل .litertlm ناموفق بود.",
                        hasInstalledModel = modelStore.hasInstalledModel(),
                    )
                    onResult(Result.failure(err))
                },
            )
        }
    }
}
