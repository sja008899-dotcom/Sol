package com.example.sol.network

import android.os.StatFs
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale

sealed class SolNetworkException(
    val userMessageFa: String,
    cause: Throwable? = null,
) : IOException(userMessageFa, cause) {
    class InsecureSchemeException(detail: String = "") :
        SolNetworkException("فقط نشانی‌های امن HTTPS مجاز هستند.${if (detail.isNotBlank()) " ($detail)" else ""}")

    class PrivateHostBlockedException :
        SolNetworkException("دسترسی به نشانی‌های محلی یا شبکهٔ داخلی برای امنیت مسدود شد.")

    class DisallowedHostException(host: String) :
        SolNetworkException("دامنهٔ «$host» در فهرست منابع مجاز دانلود Sol نیست.")

    class RedirectLimitException :
        SolNetworkException("تعداد تغییر مسیرهای سرور بیش از حد مجاز شد.")

    class ResponseTooLargeException(maxBytes: Long) :
        SolNetworkException("حجم پاسخ سرور از سقف مجاز (${maxBytes / (1024 * 1024)} مگابایت) بیشتر است.")

    class HttpStatusException(val statusCode: Int) :
        SolNetworkException("پاسخ نامعتبر سرور دریافت شد (کد HTTP $statusCode).")

    class IntegrityMetadataMissingException :
        SolNetworkException("اثر انگشت امنیتی (SHA-256) یا حجم دقیق فایل هنوز تأیید نشده است؛ برای ایمنی گوشی این فایل قابل استفاده نیست.")

    class IntegrityMismatchException :
        SolNetworkException("فایل دریافت‌شده با اثر انگشت SHA-256 یا حجم مورد انتظار هم‌خوانی ندارد و حذف شد.")

    class InsufficientDiskSpaceException(availableMb: Long, requiredMb: Long) :
        SolNetworkException("فضای آزاد ذخیره‌سازی کافی نیست (آزاد: ${availableMb}MB، حداقل موردنیاز با حاشیه امن: ${requiredMb}MB).")

    class CancelledException :
        SolNetworkException("عملیات دریافت توسط کاربر لغو شد.")

    class TransportException(cause: Throwable? = null) :
        SolNetworkException("ارتباط شبکه برقرار نشد یا با وقفه مواجه شد.", cause)
}

data class DiskCheckResult(
    val hasEnoughSpace: Boolean,
    val availableBytes: Long,
    val requiredBytes: Long,
) {
    val availableMb: Long get() = availableBytes / (1024L * 1024L)
    val requiredMb: Long get() = requiredBytes / (1024L * 1024L)
}

object SolStorageGuard {
    const val ONE_GIB_BYTES: Long = 1_073_741_824L
    const val SAFETY_MULTIPLIER: Double = 1.25

    fun requiredFreeBytes(payloadBytes: Long): Long {
        val safePayload = payloadBytes.coerceAtLeast(0L)
        return (safePayload * SAFETY_MULTIPLIER).toLong() + ONE_GIB_BYTES
    }

    fun checkFreeSpace(
        targetPath: File,
        payloadBytes: Long,
        availableBytesProvider: (File) -> Long = ::readAvailableBytes,
    ): DiskCheckResult {
        val required = requiredFreeBytes(payloadBytes)
        val avail = availableBytesProvider(targetPath)
        val ok = if (avail < 0L) false else avail >= required
        return DiskCheckResult(
            hasEnoughSpace = ok,
            availableBytes = avail.coerceAtLeast(0L),
            requiredBytes = required,
        )
    }

    fun readAvailableBytes(path: File): Long {
        val probe = if (path.exists()) path else (path.parentFile ?: path)
        return runCatching {
            StatFs(probe.absolutePath).availableBytes
        }.getOrDefault(-1L)
    }
}

class SolHttpClient(
    private val dnsResolver: (String) -> Array<InetAddress> = { host -> InetAddress.getAllByName(host) },
    private val connectionOpener: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection },
    private val availableBytesProvider: (File) -> Long = SolStorageGuard::readAvailableBytes,
) {

    fun fetchText(
        urlString: String,
        maxBytes: Long = DEFAULT_TEXT_MAX_BYTES,
        allowedHostSuffixes: Set<String>? = null,
    ): String {
        val (conn, _) = openSafeConnection(
            urlString = urlString,
            allowedHostSuffixes = allowedHostSuffixes,
            extraHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json,text/html,text/plain,*/*",
            ),
            connectTimeoutMs = TEXT_CONNECT_TIMEOUT_MS,
            readTimeoutMs = TEXT_READ_TIMEOUT_MS,
        )
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw SolNetworkException.HttpStatusException(code)
            }
            val declaredLen = conn.contentLengthLong
            if (declaredLen > maxBytes) {
                throw SolNetworkException.ResponseTooLargeException(maxBytes)
            }

            val charset = parseCharset(conn.contentType)
            val buffer = ByteArray(16 * 1024)
            val baos = ByteArrayOutputStream()
            var total = 0L
            conn.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > maxBytes) {
                        throw SolNetworkException.ResponseTooLargeException(maxBytes)
                    }
                    baos.write(buffer, 0, read)
                }
            }
            String(baos.toByteArray(), charset)
        } catch (e: SolNetworkException) {
            throw e
        } catch (t: Throwable) {
            throw SolNetworkException.TransportException(t)
        } finally {
            conn.disconnect()
        }
    }

    fun downloadVerifiedFile(
        urlString: String,
        partFile: File,
        expectedBytes: Long,
        expectedSha256: String,
        allowedHostSuffixes: Set<String> = DEFAULT_MODEL_ALLOWED_HOSTS,
        isCancelled: () -> Boolean = { false },
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _, _ -> },
    ): File {
        if (!isValidSha256Hex(expectedSha256) || expectedBytes <= 0L) {
            partFile.delete()
            throw SolNetworkException.IntegrityMetadataMissingException()
        }

        val checkDir = partFile.parentFile ?: partFile
        checkDir.mkdirs()

        // If a completed .part file already exists and matches exact length + SHA-256, reuse it cleanly
        var existingLength = if (partFile.isFile) partFile.length() else 0L
        if (existingLength == expectedBytes) {
            val existingHash = computeFileSha256(partFile)
            if (existingHash.equals(expectedSha256, ignoreCase = true)) {
                onProgress(100, expectedBytes, expectedBytes)
                return partFile
            } else {
                partFile.delete()
                existingLength = 0L
            }
        } else if (existingLength > expectedBytes) {
            partFile.delete()
            existingLength = 0L
        }

        val remainingToDownload = (expectedBytes - existingLength).coerceAtLeast(0L)
        val diskCheck = SolStorageGuard.checkFreeSpace(
            targetPath = checkDir,
            payloadBytes = remainingToDownload,
            availableBytesProvider = availableBytesProvider,
        )
        if (!diskCheck.hasEnoughSpace) {
            throw SolNetworkException.InsufficientDiskSpaceException(
                availableMb = diskCheck.availableMb,
                requiredMb = diskCheck.requiredMb,
            )
        }

        val headers = mutableMapOf("User-Agent" to USER_AGENT)
        if (existingLength > 0L) {
            headers["Range"] = "bytes=$existingLength-"
        }

        val (conn, _) = openSafeConnection(
            urlString = urlString,
            allowedHostSuffixes = allowedHostSuffixes,
            extraHeaders = headers,
            connectTimeoutMs = DOWNLOAD_CONNECT_TIMEOUT_MS,
            readTimeoutMs = DOWNLOAD_READ_TIMEOUT_MS,
        )

        var shouldDeletePartOnError = false
        return try {
            val code = conn.responseCode
            val contentType = conn.contentType.orEmpty().lowercase(Locale.ROOT)
            if (contentType.contains("text/html")) {
                shouldDeletePartOnError = true
                throw SolNetworkException.HttpStatusException(code)
            }

            if (code == 416) {
                // Range not satisfiable: delete corrupt partial and surface clear error
                shouldDeletePartOnError = true
                throw SolNetworkException.HttpStatusException(code)
            }
            if (code !in 200..299) {
                throw SolNetworkException.HttpStatusException(code)
            }

            val appendMode = (code == HttpURLConnection.HTTP_PARTIAL && existingLength > 0L)
            if (appendMode) {
                val contentRange = conn.getHeaderField("Content-Range")
                if (!contentRange.isNullOrBlank() && !contentRange.startsWith("bytes $existingLength-")) {
                    shouldDeletePartOnError = true
                    throw SolNetworkException.IntegrityMismatchException()
                }
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var totalDownloaded = 0L
            if (appendMode) {
                updateDigestFromFilePrefix(partFile, digest)
                totalDownloaded = existingLength
            } else {
                if (partFile.exists()) {
                    partFile.delete()
                }
            }

            val buf = ByteArray(32 * 1024)
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(partFile, appendMode).use { output ->
                    while (true) {
                        if (isCancelled()) {
                            shouldDeletePartOnError = true
                            throw SolNetworkException.CancelledException()
                        }
                        val read = input.read(buf)
                        if (read == -1) break
                        totalDownloaded += read
                        if (totalDownloaded > expectedBytes) {
                            shouldDeletePartOnError = true
                            throw SolNetworkException.IntegrityMismatchException()
                        }
                        output.write(buf, 0, read)
                        digest.update(buf, 0, read)

                        val pct = ((totalDownloaded * 100L) / expectedBytes).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct, totalDownloaded, expectedBytes)
                        }
                    }
                }
            }

            val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
            if (totalDownloaded != expectedBytes || !actualHex.equals(expectedSha256, ignoreCase = true)) {
                shouldDeletePartOnError = true
                throw SolNetworkException.IntegrityMismatchException()
            }

            partFile
        } catch (e: SolNetworkException) {
            if (shouldDeletePartOnError) partFile.delete()
            throw e
        } catch (t: Throwable) {
            if (shouldDeletePartOnError) partFile.delete()
            throw SolNetworkException.TransportException(t)
        } finally {
            conn.disconnect()
        }
    }

    internal fun validateUrlForRequest(
        rawUrl: String,
        allowedHostSuffixes: Set<String>? = null,
    ): URL {
        val url = runCatching { URL(rawUrl.trim()) }
            .getOrElse { throw SolNetworkException.InsecureSchemeException("نشانی نامعتبر") }

        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw SolNetworkException.InsecureSchemeException(url.protocol)
        }
        val host = url.host.orEmpty().trim()
        if (host.isBlank()) {
            throw SolNetworkException.PrivateHostBlockedException()
        }
        if (allowedHostSuffixes != null && !isHostAllowed(host, allowedHostSuffixes)) {
            throw SolNetworkException.DisallowedHostException(host)
        }

        val resolved = runCatching { dnsResolver(host) }
            .getOrElse { throw SolNetworkException.TransportException(it) }
        if (resolved.isEmpty() || resolved.any { !isPublicRoutableIp(it) }) {
            throw SolNetworkException.PrivateHostBlockedException()
        }
        return url
    }

    private fun openSafeConnection(
        urlString: String,
        allowedHostSuffixes: Set<String>?,
        extraHeaders: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Pair<HttpURLConnection, URL> {
        var currentUrl = validateUrlForRequest(urlString, allowedHostSuffixes)
        var hops = 0
        while (hops <= MAX_REDIRECTS) {
            val conn = try {
                connectionOpener(currentUrl).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                }
            } catch (t: Throwable) {
                throw SolNetworkException.TransportException(t)
            }
            val code = try {
                conn.responseCode
            } catch (t: Throwable) {
                conn.disconnect()
                throw SolNetworkException.TransportException(t)
            }
            if (code in REDIRECT_CODES) {
                val location = conn.getHeaderField("Location").orEmpty().trim()
                conn.disconnect()
                if (location.isBlank()) {
                    throw SolNetworkException.HttpStatusException(code)
                }
                val resolvedTarget = runCatching { URL(currentUrl, location).toString() }
                    .getOrElse { throw SolNetworkException.InsecureSchemeException("مسیر انتقال نامعتبر") }
                currentUrl = validateUrlForRequest(resolvedTarget, allowedHostSuffixes)
                hops++
                continue
            }
            return conn to currentUrl
        }
        throw SolNetworkException.RedirectLimitException()
    }

    private fun updateDigestFromFilePrefix(file: File, digest: MessageDigest) {
        val buf = ByteArray(32 * 1024)
        FileInputStream(file).use { fis ->
            while (true) {
                val read = fis.read(buf)
                if (read == -1) break
                digest.update(buf, 0, read)
            }
        }
    }

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        updateDigestFromFilePrefix(file, digest)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun parseCharset(contentType: String?): Charset {
        if (contentType.isNullOrBlank()) return Charsets.UTF_8
        val match = CHARSET_REGEX.find(contentType) ?: return Charsets.UTF_8
        val name = match.groupValues[1].trim().trim('"', '\'')
        return runCatching {
            if (Charset.isSupported(name)) Charset.forName(name) else Charsets.UTF_8
        }.getOrDefault(Charsets.UTF_8)
    }

    companion object {
        const val USER_AGENT = "SolOfflineLifeAssistant/2.0 (Android)"
        const val DEFAULT_TEXT_MAX_BYTES = 5L * 1024L * 1024L
        private const val MAX_REDIRECTS = 8
        private const val TEXT_CONNECT_TIMEOUT_MS = 15_000
        private const val TEXT_READ_TIMEOUT_MS = 20_000
        private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 20_000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 45_000
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val CHARSET_REGEX = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)
        private val SHA256_HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")

        val DEFAULT_MODEL_ALLOWED_HOSTS: Set<String> = setOf(
            "huggingface.co",
            "hf.co",
            "alphacephei.com",
        )

        fun isValidSha256Hex(value: String): Boolean {
            val trimmed = value.trim()
            if (trimmed.contains("TODO", ignoreCase = true)) return false
            return SHA256_HEX_REGEX.matches(trimmed)
        }

        fun isHostAllowed(host: String, allowedSuffixes: Set<String>): Boolean {
            val cleanHost = host.trim().lowercase(Locale.ROOT).trimEnd('.')
            return allowedSuffixes.any { suffix ->
                val cleanSuffix = suffix.trim().lowercase(Locale.ROOT).trimEnd('.')
                cleanHost == cleanSuffix || cleanHost.endsWith(".$cleanSuffix")
            }
        }

        fun isPublicRoutableIp(addr: InetAddress): Boolean {
            if (addr.isAnyLocalAddress ||
                addr.isLoopbackAddress ||
                addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress ||
                addr.isMulticastAddress
            ) {
                return false
            }
            val bytes = addr.address
            return when (addr) {
                is Inet4Address -> {
                    val b0 = bytes[0].toInt() and 0xFF
                    val b1 = bytes[1].toInt() and 0xFF
                    val isUnspecified = b0 == 0
                    val isLoopback = b0 == 127
                    val isPrivate10 = b0 == 10
                    val isCgnat = b0 == 100 && (b1 and 0xC0) == 64 // 100.64.0.0/10
                    val isLinkLocal = b0 == 169 && b1 == 254      // 169.254.0.0/16
                    val isPrivate172 = b0 == 172 && b1 in 16..31
                    val isPrivate192 = b0 == 192 && b1 == 168
                    val isMulticastOrReserved = b0 >= 224
                    !(isUnspecified || isLoopback || isPrivate10 || isCgnat ||
                        isLinkLocal || isPrivate172 || isPrivate192 || isMulticastOrReserved)
                }
                is Inet6Address -> {
                    val b0 = bytes[0].toInt() and 0xFF
                    val b1 = bytes[1].toInt() and 0xFF
                    val isUla = (b0 and 0xFE) == 0xFC            // fc00::/7 unique local
                    val isLinkLocal = b0 == 0xFE && (b1 and 0xC0) == 0x80
                    val isMulticast = b0 == 0xFF
                    val isAllZero = bytes.all { it == 0.toByte() }
                    !(isUla || isLinkLocal || isMulticast || isAllZero)
                }
                else -> false
            }
        }
    }
}
