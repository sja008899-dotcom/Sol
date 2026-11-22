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
            downloadModelSync(spec, onSavedPath)
        }
    }

    suspend fun downloadModelSync(
        spec: SlmModelSpec,
        onSavedPath: (String) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val destDir = modelStore.modelsDir.apply { mkdirs() }
        val finalFile = File(destDir, spec.fileName)
        val partFile = File(destDir, "${spec.fileName}.part")

        try {
            // Rule 7 fail-safe: refuse to download/activate without verified SHA-256 and exact byte count
            if (!spec.hasVerifiedIntegrityMetadata()) {
                partFile.delete()
                throw SolNetworkException.IntegrityMetadataMissingException()
            }

            // Disk guard: size * 1.25 + 1 GB
            val diskCheck = SolStorageGuard.checkFreeSpace(
                targetPath = destDir,
                payloadBytes = spec.expectedBytes,
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
                totalMb = (spec.expectedBytes / (1024L * 1024L)).coerceAtLeast(1L),
            )

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
                "مدل «${spec.titleFa}» پس از تأیید SHA-256 آمادهٔ اجراست.",
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
