package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.sol.knowledge.BookWebFetcher
import com.example.sol.knowledge.LocalSlmEngine
import com.example.sol.knowledge.SlmModelCatalog
import com.example.sol.memory.HistoryRetentionMode
import com.example.sol.memory.KnowledgeRepository
import com.example.sol.memory.LocationRepository
import com.example.sol.memory.MemoryDatabase
import com.example.sol.memory.MemoryRepository
import com.example.sol.memory.NoteTodoRepository
import com.example.sol.memory.PersonRepository
import com.example.sol.memory.PreferencesRepository
import com.example.sol.network.DownloadWorker
import com.example.sol.network.SolHttpClient
import com.example.sol.network.SolNetworkException
import com.example.sol.runtime.ModelAcquireResult
import com.example.sol.runtime.ModelResourceCoordinator
import com.example.sol.runtime.SolModelKind
import com.example.sol.runtime.SolModelResource
import com.example.sol.runtime.SolNetworkPolicy
import com.example.sol.runtime.SolNetworkPolicyCoordinator
import com.example.sol.security.EncryptedBackupManager
import com.example.sol.security.PermissionManager
import com.example.sol.security.PrivacyManager
import com.example.sol.speaker.OwnerProfile
import com.example.sol.speaker.SpeakerEmbedder
import com.example.sol.speaker.SpeakerIdManager
import com.example.sol.voice.ActiveAsrBackend
import com.example.sol.voice.VoiceEngine
import com.example.sol.voice.VoiceErrorAction
import com.example.sol.voice.VoiceListeningState
import com.example.sol.voice.VoskVoiceEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class M10HardeningAndPolicyTests {

    private class FakeDownloadConnection(
        url: URL,
        private val bytes: ByteArray,
    ) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = 200
        override fun getContentLengthLong(): Long = bytes.size.toLong()
        override fun getContentType(): String = "application/octet-stream"
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `slm and download worker fail closed when sha256 metadata is unpinned unless tierB explicitly allowed`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dnsCalls = AtomicInteger(0)
        val connCalls = AtomicInteger(0)
        val countingClient = SolHttpClient(
            dnsResolver = { host ->
                dnsCalls.incrementAndGet()
                arrayOf(InetAddress.getByAddress(host, byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)))
            },
            connectionOpener = { url ->
                connCalls.incrementAndGet()
                FakeDownloadConnection(url, ByteArray(1024))
            },
            availableBytesProvider = { 10L * 1024L * 1024L * 1024L },
        )

        val engine = LocalSlmEngine(
            context = context,
            httpClient = countingClient,
            availableBytesProvider = { 10L * 1024L * 1024L * 1024L },
        )
        val unpinnedSpec = SlmModelCatalog.models.first()
        assertFalse(unpinnedSpec.hasVerifiedIntegrityMetadata())

        val slmResult = engine.downloadModelSync(unpinnedSpec)
        assertTrue(slmResult.isFailure)
        assertTrue(slmResult.exceptionOrNull() is SolNetworkException.IntegrityMetadataMissingException)
        assertEquals(0, dnsCalls.get())
        assertEquals(0, connCalls.get())

        val workerDest = File(context.cacheDir, "worker_unpinned_test.bin")
        val workerOutcome = DownloadWorker.executeVerifiedDownload(
            urlString = unpinnedSpec.downloadUrl,
            destinationPath = workerDest.absolutePath,
            expectedBytes = unpinnedSpec.expectedBytes,
            expectedSha256 = unpinnedSpec.sha256,
            allowTierB = false,
            httpClient = countingClient,
        )
        assertFalse(workerOutcome.success)
        assertTrue(workerOutcome.errorMessageFa.contains("SHA-256"))
        assertEquals(0, dnsCalls.get())
        assertEquals(0, connCalls.get())
        workerDest.delete()
    }

    @Test
    fun `corrupted download payload deletes part file and throws IntegrityMismatchException`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val payload = "corrupted_payload_bytes".toByteArray()
        val wrongSha = "0".repeat(64)
        val partFile = File(context.cacheDir, "corrupt_dl_test.bin.part").apply { writeBytes("stale".toByteArray()) }

        val client = SolHttpClient(
            dnsResolver = { host ->
                arrayOf(InetAddress.getByAddress(host, byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)))
            },
            connectionOpener = { url -> FakeDownloadConnection(url, payload) },
            availableBytesProvider = { 10L * 1024L * 1024L * 1024L },
        )

        var caught: Throwable? = null
        try {
            client.downloadVerifiedFile(
                urlString = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/model.bin",
                partFile = partFile,
                expectedBytes = payload.size.toLong(),
                expectedSha256 = wrongSha,
                allowedHostSuffixes = setOf("huggingface.co"),
            )
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue(caught is SolNetworkException.IntegrityMismatchException)
        assertFalse("Corrupted .part file must be deleted", partFile.exists())
    }

    @Test
    fun `speaker embedder refuses unpinned model in embed and enforces custom sha pin`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testDir = File(context.filesDir, "m10_speaker_pin_test").apply { mkdirs() }
        val modelBytes = "NOT_AN_OFFICIAL_CAMPP_MODEL_BYTES".toByteArray()
        val modelFile = File(testDir, "custom_speaker.onnx").apply { writeBytes(modelBytes) }
        val actualSha = sha256Hex(modelBytes)

        try {
            // 1. Direct embed() without pin must fail closed (bypass removed)
            val unpinnedEmbedder = SpeakerEmbedder(modelFile)
            val embedded = unpinnedEmbedder.embed(FloatArray(1600) { 0.05f })
            assertEquals(null, embedded)
            assertTrue(
                "Expected checksum rejection error, got: ${unpinnedEmbedder.lastErrorFa}",
                unpinnedEmbedder.lastErrorFa.contains("قدیمی") || unpinnedEmbedder.lastErrorFa.contains("نامعتبر"),
            )

            // 2. Wrong custom pin must also fail checksum check
            val wrongPinEmbedder = SpeakerEmbedder(modelFile)
            assertFalse(wrongPinEmbedder.initialize(verifyOfficialHash = true, expectedCustomSha256 = "f".repeat(64)))
            assertTrue(wrongPinEmbedder.lastErrorFa.contains("نامعتبر"))

            // 3. Exact custom SHA pin clears checksum guard (ORT rejects dummy bytes later, NOT checksum guard)
            val matchingPinEmbedder = SpeakerEmbedder(modelFile)
            assertFalse(matchingPinEmbedder.initialize(verifyOfficialHash = true, expectedCustomSha256 = actualSha))
            assertFalse(
                "Matching custom SHA pin should clear checksum guard before ORT parsing: ${matchingPinEmbedder.lastErrorFa}",
                matchingPinEmbedder.lastErrorFa.contains("قدیمی یا نامعتبر"),
            )

            // 4. SpeakerIdManager flags custom unverified model in UI state
            val customPinFile = File(testDir, "${modelFile.name}.custom_sha256").apply { writeText(actualSha) }
            assertTrue(customPinFile.exists())
            val manager = SpeakerIdManager(
                context = context,
                modelFile = modelFile,
                ownerProfile = OwnerProfile(context, File(testDir, "owner.bin")),
            )
            assertTrue(manager.uiState.value.isCustomUnverifiedModel)
        } finally {
            testDir.deleteRecursively()
        }
    }

    @Test
    fun `offline strict blocks all network paths with zero sockets or dns and prevents SpeechRecognizer fallback`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val coordinator = SolNetworkPolicyCoordinator(initialPolicy = SolNetworkPolicy.OFFLINE_STRICT)
        val dnsCalls = AtomicInteger(0)
        val connCalls = AtomicInteger(0)

        val strictClient = SolHttpClient(
            dnsResolver = { host ->
                dnsCalls.incrementAndGet()
                arrayOf(InetAddress.getByAddress(host, byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)))
            },
            connectionOpener = { url ->
                connCalls.incrementAndGet()
                FakeDownloadConnection(url, ByteArray(16))
            },
            availableBytesProvider = { 10L * 1024L * 1024L * 1024L },
            networkPolicyCoordinator = coordinator,
        )

        val partFile = File(context.cacheDir, "strict_blocked.part")

        val fetchErr = runCatching { strictClient.fetchText("https://duckduckgo.com/?q=test") }.exceptionOrNull()
        assertTrue(fetchErr is SolNetworkException.OfflineStrictBlockedException)

        val headErr = runCatching {
            strictClient.preflightHeadMetadata("https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm")
        }.exceptionOrNull()
        assertTrue(headErr is SolNetworkException.OfflineStrictBlockedException)

        val dlErr = runCatching {
            strictClient.downloadVerifiedFile(
                urlString = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
                partFile = partFile,
                expectedBytes = 16L,
                expectedSha256 = "a".repeat(64),
                allowedHostSuffixes = setOf("huggingface.co"),
            )
        }.exceptionOrNull()
        assertTrue(dlErr is SolNetworkException.OfflineStrictBlockedException)

        val httpsErr = runCatching {
            strictClient.downloadOfficialHttpsFileResult(
                urlString = "https://huggingface.co/foo/bar.onnx",
                partFile = partFile,
                estimatedBytes = 1024L,
                allowedHostSuffixes = setOf("huggingface.co"),
            )
        }.exceptionOrNull()
        assertTrue(httpsErr is SolNetworkException.OfflineStrictBlockedException)

        val workerRes = DownloadWorker.executeVerifiedDownload(
            urlString = "https://huggingface.co/foo/bar.onnx",
            destinationPath = File(context.cacheDir, "strict_worker.bin").absolutePath,
            expectedBytes = 16L,
            expectedSha256 = "a".repeat(64),
            httpClient = strictClient,
        )
        assertFalse(workerRes.success)
        assertTrue(workerRes.errorMessageFa.contains("Offline Strict"))

        val bookFetcher = BookWebFetcher(context, httpClient = strictClient)
        val bookRes = bookFetcher.fetchBookOrWebpageFromInternet("https://fa.wikipedia.org/wiki/Sol")
        assertTrue(bookRes.isFailure)
        assertTrue(bookRes.exceptionOrNull() is SolNetworkException.OfflineStrictBlockedException)

        assertEquals("DNS must never be queried in OFFLINE_STRICT", 0, dnsCalls.get())
        assertEquals("Sockets must never be opened in OFFLINE_STRICT", 0, connCalls.get())
        assertTrue(coordinator.lastNetworkEventFa.value.contains("مسدود"))

        // VoiceEngine must refuse Android SpeechRecognizer fallback when Offline Strict is enabled
        val voskDir = File(context.filesDir, "vosk-model-fa").apply { deleteRecursively() }
        try {
            val vosk = VoskVoiceEngine(context, httpClient = strictClient)
            assertFalse(vosk.isModelInstalled())
            val voiceEngine = VoiceEngine(
                context = context,
                voskEngine = vosk,
                isOfflineStrict = { true },
            )
            assertEquals(ActiveAsrBackend.VOSK_OFFLINE, voiceEngine.resolvePreferredBackend(preferVosk = false))
            assertFalse(voiceEngine.isAvailable())
            voiceEngine.startListening(preferVosk = false) {}
            val state = voiceEngine.state.value
            assertTrue("Expected Error state in strict offline without Vosk, got $state", state is VoiceListeningState.Error)
            assertEquals(VoiceErrorAction.OPEN_VOSK_SETTINGS, (state as VoiceListeningState.Error).action)
        } finally {
            voskDir.deleteRecursively()
            partFile.delete()
        }
    }

    @Test
    fun `model resource coordinator evicts idle peers skips busy engines checks low ram and is idempotent`(): Unit = runBlocking {
        var availRamMb = 1_500L
        var voskLoaded = true
        var voskUnloads = 0
        var piperLoaded = true
        var piperBusy = false
        var piperUnloads = 0
        var speakerLoaded = true
        var speakerUnloads = 0
        var slmLoaded = false

        val coordinator = ModelResourceCoordinator(availableRamMbProvider = { availRamMb })

        coordinator.register(object : SolModelResource {
            override val kind = SolModelKind.VOSK_ASR
            override val minRequiredRamMb = 250L
            override fun isLoaded(): Boolean = voskLoaded
            override fun isBusy(): Boolean = false
            override suspend fun load(): Result<Unit> {
                voskLoaded = true
                return Result.success(Unit)
            }
            override suspend fun unload() {
                voskLoaded = false
                voskUnloads++
            }
        })
        coordinator.register(object : SolModelResource {
            override val kind = SolModelKind.PIPER_TTS
            override val minRequiredRamMb = 220L
            override fun isLoaded(): Boolean = piperLoaded
            override fun isBusy(): Boolean = piperBusy
            override suspend fun load(): Result<Unit> {
                piperLoaded = true
                return Result.success(Unit)
            }
            override suspend fun unload() {
                piperLoaded = false
                piperUnloads++
            }
        })
        coordinator.register(object : SolModelResource {
            override val kind = SolModelKind.SPEAKER_ID
            override val minRequiredRamMb = 180L
            override fun isLoaded(): Boolean = speakerLoaded
            override fun isBusy(): Boolean = false
            override suspend fun load(): Result<Unit> {
                speakerLoaded = true
                return Result.success(Unit)
            }
            override suspend fun unload() {
                speakerLoaded = false
                speakerUnloads++
            }
        })
        coordinator.register(object : SolModelResource {
            override val kind = SolModelKind.LOCAL_SLM
            override val minRequiredRamMb = 1_100L
            override fun isLoaded(): Boolean = slmLoaded
            override fun isBusy(): Boolean = false
            override suspend fun load(): Result<Unit> {
                slmLoaded = true
                return Result.success(Unit)
            }
            override suspend fun unload() {
                slmLoaded = false
            }
        })

        // 1. Busy Piper must NOT be interrupted while Vosk & Speaker are evicted; low RAM returns Busy
        piperBusy = true
        availRamMb = 500L
        val busyRes = coordinator.prepareBeforeSlmLoad(1_000L)
        assertTrue("Expected Busy result when Piper is speaking & RAM is low, got $busyRes", busyRes is ModelAcquireResult.Busy)
        assertEquals(SolModelKind.PIPER_TTS, (busyRes as ModelAcquireResult.Busy).activeModel)
        assertTrue(piperLoaded)
        assertEquals(0, piperUnloads)
        assertFalse(voskLoaded)
        assertFalse(speakerLoaded)
        assertEquals(1, voskUnloads)
        assertEquals(1, speakerUnloads)

        // 2. Repeated unload() calls are strictly idempotent
        assertFalse("Repeated unload on already unloaded Vosk must return false", coordinator.unload(SolModelKind.VOSK_ASR))
        assertEquals(1, voskUnloads)

        // 3. Once Piper is idle, unload succeeds once then becomes no-op; InsufficientMemory fires when RAM too low
        piperBusy = false
        assertTrue(coordinator.unload(SolModelKind.PIPER_TTS))
        assertFalse(coordinator.unload(SolModelKind.PIPER_TTS))
        assertEquals(1, piperUnloads)

        availRamMb = 400L
        val lowRamRes = coordinator.prepareBeforeSlmLoad(1_000L)
        assertTrue(lowRamRes is ModelAcquireResult.InsufficientMemory)

        // 4. With enough RAM, acquireForUse loads SLM
        availRamMb = 1_400L
        val acquireSlm = coordinator.acquireForUse(SolModelKind.LOCAL_SLM, requiredRamMbOverride = 900L)
        assertTrue(acquireSlm is ModelAcquireResult.Acquired)
        assertTrue(slmLoaded)
    }

    @Test
    fun `privacy audit preserves indices 0 and 1 and reflects offline strict and custom N5 status`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val privacyManager = PrivacyManager(PermissionManager(context))

        val items = privacyManager.getSecurityAudit(
            alwaysConfirmCommunication = true,
            activeAsrBackend = ActiveAsrBackend.VOSK_OFFLINE,
            voskInstalled = true,
            piperTtsReady = true,
            historyRetentionMode = HistoryRetentionMode.TWO_HUNDRED_ITEMS,
            privateModeEnabled = false,
            offlineStrictMode = true,
            lastNetworkEventFa = "مسدود در حالت آفلاین سخت‌گیرانه: دریافت متن وب",
            speakerIdCustomUnverified = true,
        )

        assertEquals(7, items.size)
        assertTrue(items[0].titleFa.contains("تشخیص گفتار"))
        assertTrue(items[0].isCompliant)
        assertTrue(items[1].titleFa.contains("Piper ONNX"))
        assertTrue(items[1].isCompliant)
        assertTrue(items[2].titleFa.contains("حالت آفلاین سخت‌گیرانه"))
        assertTrue(items[2].detailFa.contains("مسدود در حالت آفلاین سخت‌گیرانه"))
        assertTrue(items[4].detailFa.contains("تأییدنشده ناشر"))
        assertFalse("Unverified custom N5 should flag compliance notice on item 4", items[4].isCompliant)
    }

    @Test
    fun `encrypted backup V3 preserves offlineStrictMode across export and import`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefsRepo = PreferencesRepository(context)
        val dao = db.memoryDao()

        try {
            prefsRepo.resetAllPreferences()
            prefsRepo.setOfflineStrictMode(true)
            assertTrue(prefsRepo.getSnapshot().offlineStrictMode)

            val backupManager = EncryptedBackupManager(
                context = context,
                database = db,
                memoryRepository = MemoryRepository(dao),
                personRepository = PersonRepository(dao),
                locationRepository = LocationRepository(dao),
                knowledgeRepository = KnowledgeRepository(dao),
                preferencesRepository = prefsRepo,
                noteTodoRepository = NoteTodoRepository(dao),
                availableBytesProvider = { 5L * 1024L * 1024L * 1024L },
            )

            val backupFile = File(context.cacheDir, "m10_offline_strict_roundtrip.solbackup").apply { delete() }
            backupFile.outputStream().use { os ->
                backupManager.exportStreamingV3(os, "SolStrictTest#2026")
            }

            // Flip back to false locally, then restore from V3 backup
            prefsRepo.setOfflineStrictMode(false)
            assertFalse(prefsRepo.getSnapshot().offlineStrictMode)

            backupFile.inputStream().use { ins ->
                backupManager.importFromStream(ins, "SolStrictTest#2026", replaceExisting = true)
            }
            assertTrue(
                "V3 restore must preserve offlineStrictMode = true",
                prefsRepo.getSnapshot().offlineStrictMode,
            )
            backupFile.delete()
        } finally {
            prefsRepo.resetAllPreferences()
            db.close()
        }
    }
}
