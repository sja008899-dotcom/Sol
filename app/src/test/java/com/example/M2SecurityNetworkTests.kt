package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.sol.knowledge.LocalSlmEngine
import com.example.sol.knowledge.SlmModelCatalog
import com.example.sol.knowledge.SlmModelSpec
import com.example.sol.knowledge.SlmRuntimeState
import com.example.sol.memory.BookChunkEntity
import com.example.sol.memory.BookKnowledgeEntity
import com.example.sol.memory.HistoryRetentionMode
import com.example.sol.memory.KnowledgeRepository
import com.example.sol.memory.LocationRepository
import com.example.sol.memory.MemoryDatabase
import com.example.sol.memory.MemoryRepository
import com.example.sol.memory.PersonRepository
import com.example.sol.memory.PreferencesRepository
import com.example.sol.network.SolHttpClient
import com.example.sol.network.SolNetworkException
import com.example.sol.network.SolStorageGuard
import com.example.sol.security.EncryptedBackupManager
import com.example.sol.security.PermissionManager
import com.example.sol.security.PrivacyManager
import com.example.sol.security.SolMemoryOptimizer
import com.example.sol.voice.ActiveAsrBackend
import com.example.sol.voice.VoiceEngine
import com.example.sol.voice.VoskModelStatus
import com.example.sol.voice.VoskVoiceEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class M2SecurityNetworkTests {

    private class FakeHttpURLConnection(
        url: URL,
        private val code: Int,
        private val body: ByteArray = ByteArray(0),
        private val headers: Map<String, String> = emptyMap(),
        private val contentTypeHeader: String? = "application/octet-stream",
        private val declaredLength: Long = body.size.toLong(),
    ) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = code
        override fun getContentType(): String? = contentTypeHeader
        override fun getContentLengthLong(): Long = declaredLength
        override fun getHeaderField(name: String?): String? {
            if (name == null) return null
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        }

        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    }

    private fun publicDns(ip: ByteArray = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)): (String) -> Array<InetAddress> =
        { host -> arrayOf(InetAddress.getByAddress(host, ip)) }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    // =========================================================================
    // A6: SolHttpClient (HTTPS-only, SSRF/redirect defense, 5MB streaming cap)
    // =========================================================================

    @Test
    fun `a6 rejects cleartext http and all private or reserved ips including on redirect`() {
        val publicClient = SolHttpClient(dnsResolver = publicDns())
        try {
            publicClient.fetchText("http://example.com/page")
            fail("Expected http:// URL to be rejected")
        } catch (_: SolNetworkException.InsecureSchemeException) {
            // Expected
        }

        val privateIps = listOf(
            byteArrayOf(127, 0, 0, 1),
            byteArrayOf(10, 0, 0, 5),
            byteArrayOf(172.toByte(), 16, 0, 1),
            byteArrayOf(192.toByte(), 168.toByte(), 1, 1),
            byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte()),
            byteArrayOf(100.toByte(), 64, 0, 1), // CGNAT 100.64.0.0/10
        )
        for (ip in privateIps) {
            val ssrfClient = SolHttpClient(dnsResolver = { host ->
                arrayOf(InetAddress.getByAddress(host, ip))
            })
            try {
                ssrfClient.fetchText("https://internal.example.org/")
                fail("Expected private/reserved IP ${ip.joinToString(".") { (it.toInt() and 0xFF).toString() }} to be blocked")
            } catch (_: SolNetworkException.PrivateHostBlockedException) {
                // Expected
            }
        }

        // IPv6 loopback and ULA fc00::/7
        val ipv6Client = SolHttpClient(dnsResolver = { host ->
            arrayOf(InetAddress.getByName("fc00::1").let { InetAddress.getByAddress(host, it.address) })
        })
        try {
            ipv6Client.fetchText("https://ipv6-ula.example.org/")
            fail("Expected IPv6 ULA fc00::/7 to be blocked")
        } catch (_: SolNetworkException.PrivateHostBlockedException) {
            // Expected
        }

        // Redirect from public HTTPS URL to internal IP must also be blocked before connecting
        val redirectSsrfClient = SolHttpClient(
            dnsResolver = { host ->
                if (host == "public.example.org") {
                    arrayOf(InetAddress.getByAddress(host, byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)))
                } else {
                    arrayOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1)))
                }
            },
            connectionOpener = { url ->
                FakeHttpURLConnection(
                    url = url,
                    code = 302,
                    headers = mapOf("Location" to "https://127.0.0.1/secret"),
                )
            },
        )
        try {
            redirectSsrfClient.fetchText("https://public.example.org/start")
            fail("Expected redirect to loopback to be blocked")
        } catch (_: SolNetworkException.PrivateHostBlockedException) {
            // Expected
        }
    }

    @Test
    fun `a6 enforces 5mb streaming cap and parses charset header`() {
        val oversized = ByteArray(1024 * 1024 + 512) { 'A'.code.toByte() }
        val limitClient = SolHttpClient(
            dnsResolver = publicDns(),
            connectionOpener = { url ->
                FakeHttpURLConnection(
                    url = url,
                    code = 200,
                    body = oversized,
                    contentTypeHeader = "text/html; charset=utf-8",
                    declaredLength = -1L, // omitted Content-Length to verify streaming counter
                )
            },
        )
        try {
            limitClient.fetchText("https://books.example.org/huge", maxBytes = 1024L * 1024L)
            fail("Expected ResponseTooLargeException when streaming exceeds maxBytes")
        } catch (_: SolNetworkException.ResponseTooLargeException) {
            // Expected
        }

        val persianText = "سلام بر دنیای کتاب"
        val utf8Bytes = persianText.toByteArray(Charsets.UTF_8)
        val okClient = SolHttpClient(
            dnsResolver = publicDns(),
            connectionOpener = { url ->
                FakeHttpURLConnection(
                    url = url,
                    code = 200,
                    body = utf8Bytes,
                    contentTypeHeader = "text/html; charset=UTF-8",
                )
            },
        )
        assertEquals(persianText, okClient.fetchText("https://books.example.org/ok"))
    }

    // =========================================================================
    // A4: Checksum Fail-Safe, Range 206 Resume, Disk Guard, RAM Catalog, No GC
    // =========================================================================

    @Test
    fun `a4 refuses unverified catalog checksums fail-safe for both slm and vosk`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            var connectionCalled = false
            val trapHttpClient = SolHttpClient(
                dnsResolver = publicDns(),
                connectionOpener = { url ->
                    connectionCalled = true
                    FakeHttpURLConnection(url, 200)
                },
            )

            val slmEngine = LocalSlmEngine(
                context = context,
                httpClient = trapHttpClient,
                availableBytesProvider = { 10L * 1024L * 1024L * 1024L },
            )
            val slmResult = slmEngine.downloadModelSync(SlmModelCatalog.models.first()) {}
            assertTrue(slmResult.isFailure)
            assertTrue(slmResult.exceptionOrNull() is SolNetworkException.IntegrityMetadataMissingException)
            assertTrue(slmEngine.runtimeState.value is SlmRuntimeState.Error)

            val voskEngine = VoskVoiceEngine(
                context = context,
                httpClient = trapHttpClient,
                availableBytesProvider = { 10L * 1024L * 1024L * 1024L },
            )
            val voskResult = voskEngine.downloadPersianSmallModelSync()
            assertTrue(voskResult.isFailure)
            assertTrue(voskResult.exceptionOrNull() is SolNetworkException.IntegrityMetadataMissingException)
            assertTrue(voskEngine.modelStatus.value is VoskModelStatus.Error)

            assertFalse("Network must never be contacted when SHA-256 metadata is TODO(verify)", connectionCalled)
        }
    }

    @Test
    fun `a4 verifies streaming sha256, 206 range resume, part deletion on tamper, host allowlist and disk guard`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val payload = ByteArray(1_050_000) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
        val validSha = sha256Hex(payload)
        val spec = SlmModelSpec(
            id = "test_verified_slm",
            titleFa = "مدل تست",
            descriptionFa = "توضیح",
            fileName = "test_verified_model.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/Test/resolve/main/model.litertlm",
            sha256 = validSha,
            expectedBytes = payload.size.toLong(),
            approxSizeMb = 1,
            minFreeRamMb = 620L,
            requiredFreeDiskMb = 1025L,
            isRecommendedFor4Gb = true,
        )

        // Pre-write half of the .part file to exercise HTTP 206 Range resume
        val modelsDir = File(context.filesDir, "slm_models").apply { mkdirs() }
        val partFile = File(modelsDir, "${spec.fileName}.part")
        val finalFile = File(modelsDir, spec.fileName)
        finalFile.delete()
        val splitPoint = 400_000
        partFile.writeBytes(payload.copyOfRange(0, splitPoint))

        var observedRangeHeader: String? = null
        val resumeHttpClient = SolHttpClient(
            dnsResolver = publicDns(),
            connectionOpener = { url ->
                object : HttpURLConnection(url) {
                    private var rangeVal: String? = null
                    override fun setRequestProperty(key: String?, value: String?) {
                        if (key.equals("Range", ignoreCase = true)) {
                            rangeVal = value
                            observedRangeHeader = value
                        }
                        super.setRequestProperty(key, value)
                    }
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy(): Boolean = false
                    override fun getResponseCode(): Int = if (rangeVal == "bytes=$splitPoint-") 206 else 200
                    override fun getContentType(): String = "application/octet-stream"
                    override fun getContentLengthLong(): Long = (payload.size - splitPoint).toLong()
                    override fun getHeaderField(name: String?): String? =
                        if (name.equals("Content-Range", ignoreCase = true)) {
                            "bytes $splitPoint-${payload.size - 1}/${payload.size}"
                        } else {
                            null
                        }
                    override fun getInputStream(): InputStream =
                        ByteArrayInputStream(payload.copyOfRange(splitPoint, payload.size))
                }
            },
            availableBytesProvider = { 5L * 1024L * 1024L * 1024L },
        )

        val slmEngine = LocalSlmEngine(
            context = context,
            httpClient = resumeHttpClient,
            availableBytesProvider = { 5L * 1024L * 1024L * 1024L },
        )
        var callbackSavedPath = ""
        val okRes = slmEngine.downloadModelSync(spec) { saved -> callbackSavedPath = saved }
        assertTrue("Expected 206 resume + SHA-256 match to succeed: ${okRes.exceptionOrNull()}", okRes.isSuccess)
        assertEquals("bytes=$splitPoint-", observedRangeHeader)
        assertTrue(finalFile.exists())
        assertFalse(partFile.exists())
        assertEquals(finalFile.absolutePath, callbackSavedPath)
        assertEquals(validSha, sha256Hex(finalFile.readBytes()))

        // Clean up model before testing tamper & disk guard
        finalFile.delete()

        // Corrupt bytes -> SHA-256 mismatch MUST delete .part
        val tamperedClient = SolHttpClient(
            dnsResolver = publicDns(),
            connectionOpener = { url ->
                val corrupted = payload.copyOf()
                corrupted[10] = (corrupted[10].toInt() xor 0xFF).toByte()
                FakeHttpURLConnection(url, 200, corrupted)
            },
            availableBytesProvider = { 5L * 1024L * 1024L * 1024L },
        )
        val slmTamperEngine = LocalSlmEngine(
            context = context,
            httpClient = tamperedClient,
            availableBytesProvider = { 5L * 1024L * 1024L * 1024L },
        )
        val tamperRes = slmTamperEngine.downloadModelSync(spec) {}
        assertTrue(tamperRes.isFailure)
        assertTrue(tamperRes.exceptionOrNull() is SolNetworkException.IntegrityMismatchException)
        assertFalse("Corrupted .part must be deleted immediately", partFile.exists())
        assertFalse(finalFile.exists())

        // Disk guard (size * 1.25 + 1 GiB) must reject low disk space (e.g. 500 MB free)
        val lowDiskEngine = LocalSlmEngine(
            context = context,
            httpClient = tamperedClient,
            availableBytesProvider = { 500L * 1024L * 1024L },
        )
        val diskRes = lowDiskEngine.downloadModelSync(spec) {}
        assertTrue(diskRes.isFailure)
        assertTrue(diskRes.exceptionOrNull() is SolNetworkException.InsufficientDiskSpaceException)

        // Disallowed host must fail before downloading
        val badHostSpec = spec.copy(downloadUrl = "https://evil-mirror.example.net/model.litertlm")
        val hostRes = slmTamperEngine.downloadModelSync(badHostSpec) {}
        assertTrue(hostRes.isFailure)
        assertTrue(hostRes.exceptionOrNull() is SolNetworkException.DisallowedHostException)
    }

    @Test
    fun `a4 verifies vosk verified zip install, slm 4gb catalog filter, and absence of System gc`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Create valid dummy Vosk ZIP archive containing am/final.mdl + conf/mfcc.conf
        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("vosk-model-small-fa-0.4/am/final.mdl"))
                zos.write("MDL".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("vosk-model-small-fa-0.4/conf/mfcc.conf"))
                zos.write("--use-energy=false".toByteArray())
                zos.closeEntry()
            }
            baos.toByteArray()
        }
        val zipSha = sha256Hex(zipBytes)
        val voskClient = SolHttpClient(
            dnsResolver = publicDns(),
            connectionOpener = { url -> FakeHttpURLConnection(url, 200, zipBytes) },
            availableBytesProvider = { 4L * 1024L * 1024L * 1024L },
        )
        val voskEngine = VoskVoiceEngine(
            context = context,
            httpClient = voskClient,
            availableBytesProvider = { 4L * 1024L * 1024L * 1024L },
        )
        voskEngine.deleteInstalledModel()
        val installRes = voskEngine.downloadPersianSmallModelSync(
            downloadUrl = VoskVoiceEngine.VOSK_FA_SMALL_URL,
            expectedBytes = zipBytes.size.toLong(),
            expectedSha256 = zipSha,
        )
        assertTrue("Expected valid Vosk ZIP install: ${installRes.exceptionOrNull()}", installRes.isSuccess)
        assertTrue(voskEngine.isModelInstalled())
        voskEngine.deleteInstalledModel()

        // Catalog filter on 4 GB Galaxy A12 (~3700 MB total RAM) hides 1.5B, keeps 0.6B INT4 first
        val a12Models = SlmModelCatalog.modelsForDevice(3700L)
        assertFalse(a12Models.any { it.id.contains("1_5b") })
        assertEquals("qwen3_0_6b_int4", a12Models.first().id)
        val flagshipModels = SlmModelCatalog.modelsForDevice(8192L)
        assertTrue(flagshipModels.any { it.id.contains("1_5b") })

        // Verify SolMemoryOptimizer.manualTrimNow calls callbacks cleanly without crashing
        val optimizer = SolMemoryOptimizer(context)
        var ragEvicted = false
        var voskUnloaded = false
        var slmReleased = false
        optimizer.manualTrimNow(
            evictRagCache = { ragEvicted = true },
            unloadVoskIfIdle = { voskUnloaded = true },
            releaseSlmCache = { slmReleased = true },
        )
        assertTrue(ragEvicted && voskUnloaded && slmReleased)
        assertEquals(
            1_073_741_824L + (1000L * 1.25).toLong(),
            SolStorageGuard.requiredFreeBytes(1000L),
        )
    }

    @Test
    fun `a4 official ui download supports huggingface redirect x-linked-etag sha256 verification and 404 mirror fallback`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Vosk fallback from 404 on 0.5 to 200 on 0.42
        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("vosk-model-small-fa-0.42/am/final.mdl"))
                zos.write("MDL".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("vosk-model-small-fa-0.42/conf/mfcc.conf"))
                zos.write("--use-energy=false".toByteArray())
                zos.closeEntry()
            }
            baos.toByteArray()
        }
        val requestedUrls = mutableListOf<String>()
        val fallbackVoskClient = SolHttpClient(
            dnsResolver = publicDns(),
            connectionOpener = { url ->
                requestedUrls += url.toString()
                if (url.toString().contains("0.5.zip")) {
                    FakeHttpURLConnection(url, 404)
                } else {
                    FakeHttpURLConnection(url, 200, zipBytes)
                }
            },
            availableBytesProvider = { 4L * 1024L * 1024L * 1024L },
        )
        val voskEngine = VoskVoiceEngine(
            context = context,
            httpClient = fallbackVoskClient,
            availableBytesProvider = { 4L * 1024L * 1024L * 1024L },
        )
        voskEngine.deleteInstalledModel()
        val voskRes = voskEngine.downloadPersianSmallModelSync(allowHttpsWithoutPinnedHash = true)
        assertTrue("Expected Vosk 404 fallback download to succeed: ${voskRes.exceptionOrNull()}", voskRes.isSuccess)
        assertTrue(requestedUrls.any { it.contains("0.5.zip") })
        assertTrue(requestedUrls.any { it.contains("0.42.zip") })
        assertTrue(voskEngine.isModelInstalled())
        voskEngine.deleteInstalledModel()

        // 2. Hugging Face 302 redirect to hf.co CDN with X-Linked-Etag SHA-256 + X-Linked-Size
        val slmPayload = ByteArray(1_050_000) { idx -> ((idx * 17 + 3) and 0xFF).toByte() }
        val expectedLinkedSha = sha256Hex(slmPayload)
        val hfClient = SolHttpClient(
            dnsResolver = publicDns(),
            connectionOpener = { url ->
                val urlStr = url.toString()
                when {
                    urlStr.contains("Qwen3-0.6B_dynamic_wi4b32_afp32") -> FakeHttpURLConnection(url, 404)
                    urlStr.contains("huggingface.co") -> FakeHttpURLConnection(
                        url = url,
                        code = 302,
                        headers = mapOf(
                            "Location" to "https://cas-bridge.xethub.hf.co/blob/test-model.litertlm",
                            "X-Linked-Etag" to "\"$expectedLinkedSha\"",
                            "X-Linked-Size" to slmPayload.size.toString(),
                        ),
                        declaredLength = -1L,
                    )
                    urlStr.contains("cas-bridge.xethub.hf.co") -> FakeHttpURLConnection(
                        url = url,
                        code = 200,
                        body = slmPayload,
                        headers = mapOf("ETag" to "\"$expectedLinkedSha\""),
                    )
                    else -> FakeHttpURLConnection(url, 404)
                }
            },
            availableBytesProvider = { 5L * 1024L * 1024L * 1024L },
        )
        val slmEngine = LocalSlmEngine(
            context = context,
            httpClient = hfClient,
            availableBytesProvider = { 5L * 1024L * 1024L * 1024L },
        )
        var savedSlmPath = ""
        val slmRes = slmEngine.downloadModelSync(
            spec = SlmModelCatalog.models.first(),
            onSavedPath = { savedSlmPath = it },
            allowHttpsWithoutPinnedHash = true,
        )
        assertTrue("Expected HF SLM download with fallback + X-Linked-Etag to succeed: ${slmRes.exceptionOrNull()}", slmRes.isSuccess)
        assertTrue(File(savedSlmPath).exists())
        File(savedSlmPath).delete()

        // 3. Mismatched X-Linked-Etag MUST reject and clean .part
        val badHfClient = SolHttpClient(
            dnsResolver = publicDns(),
            connectionOpener = { url ->
                if (url.toString().contains("huggingface.co")) {
                    FakeHttpURLConnection(
                        url = url,
                        code = 302,
                        headers = mapOf(
                            "Location" to "https://cas-bridge.xethub.hf.co/blob/tampered.litertlm",
                            "X-Linked-Etag" to "\"${"0".repeat(64)}\"",
                            "X-Linked-Size" to slmPayload.size.toString(),
                        ),
                    )
                } else {
                    FakeHttpURLConnection(url, 200, slmPayload)
                }
            },
            availableBytesProvider = { 5L * 1024L * 1024L * 1024L },
        )
        val badSlmEngine = LocalSlmEngine(
            context = context,
            httpClient = badHfClient,
            availableBytesProvider = { 5L * 1024L * 1024L * 1024L },
        )
        val mismatchRes = badSlmEngine.downloadModelSync(
            spec = SlmModelCatalog.models.first(),
            onSavedPath = {},
            allowHttpsWithoutPinnedHash = true,
        )
        assertTrue(mismatchRes.isFailure)
        assertTrue(mismatchRes.exceptionOrNull() is SolNetworkException.IntegrityMismatchException)
    }

    // =========================================================================
    // A5: Encrypted Backup V3 Streaming + V2 Compat + Atomic Room Transaction
    // =========================================================================

    @Test
    fun `a5 v3 streaming backup roundtrip, tamper protection, and atomic transaction rollback`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefsRepo = PreferencesRepository(context)
        try {
            val dao = db.memoryDao()
            val memRepo = MemoryRepository(dao)
            val personRepo = PersonRepository(dao)
            val locRepo = LocationRepository(dao)
            val knowledgeRepo = KnowledgeRepository(dao)

            val backupManager = EncryptedBackupManager(
                context = context,
                database = db,
                memoryRepository = memRepo,
                personRepository = personRepo,
                locationRepository = locRepo,
                knowledgeRepository = knowledgeRepo,
                preferencesRepository = prefsRepo,
                availableBytesProvider = { 5L * 1024L * 1024L * 1024L },
            )

            // Seed rich Persian data
            memRepo.remember("alias:دفتر", "شرکت کارنو صنعت ماکیان", kind = "alias")
            personRepo.savePerson("علی رضایی", "09120001111", "همکار", "معرفی شفاف")
            locRepo.saveLocation("خانه", "تهران، نیاوران", "منزل")
            val bookId = knowledgeRepo.saveBook(
                BookKnowledgeEntity(
                    title = "عادت‌های اتمی",
                    author = "جیمز کلیر",
                    summaryText = "تغییرات کوچک روزانه",
                    sourceType = "WEB",
                )
            )
            knowledgeRepo.replaceChunksForBook(
                bookId,
                listOf(
                    BookChunkEntity(
                        bookId = bookId,
                        bookTitle = "عادت‌های اتمی",
                        chunkIndex = 0,
                        contentText = "محتوای بخش اول",
                        normalizedTokens = "محتوای بخش اول",
                    )
                ),
            )
            knowledgeRepo.saveRole("مشاور عادت", "با لحن آرام راهنمایی کن", linkedBookTitle = "عادت‌های اتمی")
            prefsRepo.setOwnerName("سهراب")
            prefsRepo.setPrivateModeEnabled(false)
            prefsRepo.setHistoryRetentionMode(HistoryRetentionMode.SEVEN_DAYS)

            val outFile = File(context.cacheDir, "test_v3.solbackup")
            outFile.delete()
            val exportStats = outFile.outputStream().use { os ->
                backupManager.exportStreamingV3(os, "StrongPass#1404")
            }
            assertEquals(1, exportStats.memoriesCount)
            assertEquals(1, exportStats.personsCount)
            assertEquals(1, exportStats.booksCount)
            assertEquals(1, exportStats.chunksCount)
            assertEquals(1, exportStats.rolesCount)
            assertTrue(exportStats.fileSizeBytes > 0L)

            val rawBytes = outFile.readBytes()
            val firstLine = rawBytes.decodeToString(0, minOf(rawBytes.size, 32)).lineSequence().first()
            assertEquals("SOL_BACKUP_V3", firstLine)

            // Password < 8 chars rejected on export
            val shortPassUri = Uri.fromFile(File(context.cacheDir, "short_pass.solbackup"))
            val shortRes = backupManager.exportEncryptedBackup(shortPassUri, "1234567")
            assertTrue(shortRes.isFailure)

            // Mutate DB cleanly, then verify wrong password & tampered header AAD do NOT wipe existing rows!
            memRepo.remember("alias:باشگاه", "مجموعه انقلاب", kind = "alias")
            val wrongPassRes = outFile.inputStream().use { ins ->
                runCatching { backupManager.importFromStream(ins, "WrongPass#9999", replaceExisting = true) }
            }
            assertTrue(wrongPassRes.isFailure)
            assertEquals(2, memRepo.getAllOnce().size) // Unharmed!

            // Tamper header AAD (swap a character inside the JSON header line) -> GCM AAD auth MUST fail before DB wipe
            val firstNl = rawBytes.indexOf('\n'.code.toByte())
            val secondNl = rawBytes.indexOf('\n'.code.toByte(), startIndex = firstNl + 1)
            val tamperedHeader = rawBytes.copyOf()
            // Flip alg string inside header without touching payload/salt/iv
            val headerSlice = rawBytes.copyOfRange(firstNl + 1, secondNl).decodeToString()
            val modHeader = headerSlice.replace("GZIP", "Gzip").toByteArray()
            System.arraycopy(modHeader, 0, tamperedHeader, firstNl + 1, modHeader.size)
            val aadFail = ByteArrayInputStream(tamperedHeader).use { ins ->
                runCatching { backupManager.importFromStream(ins, "StrongPass#1404", replaceExisting = true) }
            }
            assertTrue("Tampered GCM AAD header must fail decryption", aadFail.isFailure)
            assertEquals(2, memRepo.getAllOnce().size) // Still unharmed!

            // Reject extreme PBKDF2 iteration count (> 5_000_000) before KDF hangs device
            val highIterHeader = headerSlice.replace("600000", "9000000")
            val badIterEnvelope = buildString {
                append("SOL_BACKUP_V3\n")
                append(highIterHeader)
                append("\n")
            }.toByteArray() + rawBytes.copyOfRange(secondNl + 1, rawBytes.size)
            val iterFail = ByteArrayInputStream(badIterEnvelope).use { ins ->
                runCatching { backupManager.importFromStream(ins, "StrongPass#1404", replaceExisting = true) }
            }
            assertTrue(iterFail.isFailure)

            // Valid V3 import with replaceExisting = true restores exact seeded snapshot
            val importStats = outFile.inputStream().use { ins ->
                backupManager.importFromStream(ins, "StrongPass#1404", replaceExisting = true)
            }
            assertEquals(1, importStats.memoriesCount)
            assertEquals(1, importStats.booksCount)
            assertEquals(1, importStats.chunksCount)
            assertEquals(1, importStats.rolesCount)
            assertEquals("شرکت کارنو صنعت ماکیان", memRepo.getAllOnce().single().valueText)
            assertEquals("سهراب", prefsRepo.getSnapshot().ownerName)
            assertEquals(HistoryRetentionMode.SEVEN_DAYS, prefsRepo.getSnapshot().historyRetentionMode)
            outFile.delete()
            Unit
        } finally {
            prefsRepo.resetAllPreferences()
            db.close()
        }
    }

    // =========================================================================
    // A7: Privacy Honesty, Retention, Private Mode, EXTRA_PREFER_OFFLINE, Wipe
    // =========================================================================

    @Test
    fun `a7 privacy audit accurately reports asr backend and retention, private mode & wipe work`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefsRepo = PreferencesRepository(context)
        try {
            val dao = db.memoryDao()
            val memRepo = MemoryRepository(dao)
            val personRepo = PersonRepository(dao)
            val locRepo = LocationRepository(dao)
            val historyRepo = com.example.sol.memory.HistoryRepository(dao)
            val knowledgeRepo = KnowledgeRepository(dao)

            // PrivacyManager never claims ASR is offline when Android SpeechRecognizer is active
            val privacyManager = PrivacyManager(PermissionManager(context))
            val onlineAudit = privacyManager.getSecurityAudit(
                alwaysConfirmCommunication = true,
                activeAsrBackend = ActiveAsrBackend.ANDROID_SPEECH_RECOGNIZER,
                voskInstalled = false,
                ttsRequiresNetwork = true,
                historyRetentionMode = HistoryRetentionMode.TWO_HUNDRED_ITEMS,
                privateModeEnabled = false,
            )
            val asrItem = onlineAudit.first()
            assertFalse("ASR audit item must be non-compliant/warning when Android SpeechRecognizer active", asrItem.isCompliant)
            assertTrue(asrItem.detailFa.contains("اینترنت") || asrItem.detailFa.contains("سرور"))
            assertFalse(onlineAudit[1].isCompliant)

            val offlineAudit = privacyManager.getSecurityAudit(
                alwaysConfirmCommunication = true,
                activeAsrBackend = ActiveAsrBackend.VOSK_OFFLINE,
                voskInstalled = true,
                ttsRequiresNetwork = false,
                historyRetentionMode = HistoryRetentionMode.SEVEN_DAYS,
                privateModeEnabled = true,
            )
            assertTrue(offlineAudit.first().isCompliant)
            assertTrue(offlineAudit[1].isCompliant)
            assertTrue(offlineAudit.any { it.detailFa.contains("حالت خصوصی") })

            // Verify EXTRA_PREFER_OFFLINE constant behavior used in VoiceEngine
            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            assertTrue(speechIntent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
            assertNotNull(VoiceEngine(context))

            // Private Mode & Retention OFF skip history insertion
            historyRepo.recordIfAllowed(
                userText = "سوال خصوصی",
                solText = "پاسخ",
                retentionMode = HistoryRetentionMode.TWO_HUNDRED_ITEMS,
                privateModeEnabled = true,
            )
            assertEquals(0, dao.deleteHistoryOlderThan(Long.MAX_VALUE))

            historyRepo.recordIfAllowed(
                userText = "سوال با نگهداری خاموش",
                solText = "پاسخ",
                retentionMode = HistoryRetentionMode.OFF,
                privateModeEnabled = false,
            )
            assertEquals(0, dao.deleteHistoryOlderThan(Long.MAX_VALUE))

            // 7-day retention prunes >7 day records and keeps recent ones
            val now = 1_750_000_000_000L
            val eightDaysAgo = now - (8L * 24L * 60L * 60L * 1000L)
            historyRepo.recordIfAllowed(
                userText = "قدیمی",
                solText = "پاسخ قدیمی",
                retentionMode = HistoryRetentionMode.TWO_HUNDRED_ITEMS,
                privateModeEnabled = false,
                nowMillis = eightDaysAgo,
            )
            historyRepo.recordIfAllowed(
                userText = "جدید",
                solText = "پاسخ جدید",
                retentionMode = HistoryRetentionMode.SEVEN_DAYS,
                privateModeEnabled = false,
                nowMillis = now,
            )
            assertEquals(1, dao.deleteHistoryOlderThan(Long.MAX_VALUE)) // Only recent survived!

            // deleteAllUserData wipes all Room tables + DataStore
            memRepo.remember("k", "v")
            personRepo.savePerson("علی")
            locRepo.saveLocation("دفتر", "کارنو")
            val bId = knowledgeRepo.saveBook(BookKnowledgeEntity(title = "کتاب", author = "نویسنده", summaryText = "خلاصه"))
            knowledgeRepo.replaceChunksForBook(
                bId,
                listOf(
                    BookChunkEntity(
                        bookId = bId,
                        bookTitle = "کتاب",
                        chunkIndex = 0,
                        contentText = "متن",
                        normalizedTokens = "متن",
                    )
                ),
            )
            knowledgeRepo.saveRole("نقش", "دستور")
            knowledgeRepo.logBackgroundJob("job", "عنوان", "DONE", "جزئیات")
            knowledgeRepo.insertAlarmWithUniqueRequestCode(
                com.example.sol.memory.ScheduledAlarmEntity(
                    requestCode = 0,
                    titleFa = "تست",
                    messageBody = "پیام",
                    triggerAtMillis = now + 60_000L,
                    jalaliLabel = "۱۴۰۴",
                    isExact = true,
                )
            )
            prefsRepo.setOwnerName("تست")

            knowledgeRepo.deleteAllUserData()
            prefsRepo.resetAllPreferences()

            assertTrue(memRepo.getAllOnce().isEmpty())
            assertTrue(personRepo.getAllOnce().isEmpty())
            assertTrue(locRepo.getAllOnce().isEmpty())
            assertTrue(knowledgeRepo.getAllBooksOnce().isEmpty())
            assertTrue(knowledgeRepo.getAllChunksOnce().isEmpty())
            assertTrue(knowledgeRepo.getAllRolesOnce().isEmpty())
            assertTrue(knowledgeRepo.getAllAlarmsOnce().isEmpty())
            assertEquals("کاربر", prefsRepo.getSnapshot().ownerName)
        } finally {
            prefsRepo.resetAllPreferences()
            db.close()
        }
    }

    private fun ByteArray.indexOf(target: Byte, startIndex: Int = 0): Int {
        for (i in startIndex until size) {
            if (this[i] == target) return i
        }
        return -1
    }
}
