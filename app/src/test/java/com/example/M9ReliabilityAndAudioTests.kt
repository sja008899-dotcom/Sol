package com.example

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.example.sol.network.SolHttpClient
import com.example.sol.network.SolNetworkException
import com.example.sol.security.SolMemoryOptimizer
import com.example.sol.speaker.SpeakerEmbedder
import com.example.sol.speaker.SpeakerIdManager
import com.example.sol.tts.AudioConfig
import com.example.sol.tts.PersianPhonemizer
import com.example.sol.tts.PiperConfig
import com.example.sol.tts.PiperTtsEngine
import com.example.sol.tts.PiperTtsStatus
import com.example.sol.tts.TtsManager
import com.example.sol.voice.MicArbiter
import com.example.sol.voice.MicOwner
import com.example.sol.voice.SafeAudioReadTracker
import com.example.sol.voice.SafeReadAction
import com.example.sol.voice.SolAudioManager
import com.example.sol.voice.VoskVoiceEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class M9ReliabilityAndAudioTests {

    private class FakeHeadHttpURLConnection(
        url: URL,
        private val code: Int,
        private val body: ByteArray = ByteArray(0),
        private val headers: Map<String, String> = emptyMap(),
        private val declaredLen: Long = body.size.toLong(),
    ) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = code
        override fun getContentType(): String = "application/octet-stream"
        override fun getContentLengthLong(): Long = declaredLen
        override fun getHeaderField(name: String?): String? {
            if (name == null) return null
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        }

        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    }

    private fun publicDns(): (String) -> Array<InetAddress> = { host ->
        arrayOf(InetAddress.getByAddress(host, byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)))
    }

    @Test
    fun `preflightHeadMetadata extracts X-Linked-Etag X-Linked-Size and Location without following redirects`() {
        val fakeSha = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"
        var followRedirectsObserved = true
        var methodObserved = ""
        val client = SolHttpClient(
            dnsResolver = publicDns(),
            connectionOpener = { url ->
                object : HttpURLConnection(url) {
                    override fun setInstanceFollowRedirects(followRedirects: Boolean) {
                        followRedirectsObserved = followRedirects
                        super.setInstanceFollowRedirects(followRedirects)
                    }

                    override fun setRequestMethod(method: String?) {
                        methodObserved = method.orEmpty()
                        super.setRequestMethod(method)
                    }

                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy(): Boolean = false
                    override fun getResponseCode(): Int = 302
                    override fun getContentLengthLong(): Long = -1L
                    override fun getHeaderField(name: String?): String? = when {
                        name.equals("X-Linked-Etag", ignoreCase = true) -> "\"$fakeSha\""
                        name.equals("X-Linked-Size", ignoreCase = true) -> "522190848"
                        name.equals("Location", ignoreCase = true) -> "https://cas-bridge.xethub.hf.co/model.litertlm"
                        else -> null
                    }

                    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
                }
            },
        )

        val meta = client.preflightHeadMetadata(
            "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm"
        )
        assertFalse(followRedirectsObserved)
        assertEquals("HEAD", methodObserved)
        assertEquals(fakeSha, meta.sha256)
        assertEquals(522_190_848L, meta.contentLengthBytes)
        assertEquals("https://cas-bridge.xethub.hf.co/model.litertlm", meta.location)
    }

    @Test
    fun `vosk tier b rejects out of range size, missing graph folder, and tofu hash mismatch`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Out-of-range (< 20 MB) rejected when enforceSizeBounds = true
        val tinyZip = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("vosk-model-small-fa-0.5/am/final.mdl"))
                zos.write("MDL".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("vosk-model-small-fa-0.5/conf/mfcc.conf"))
                zos.write("cfg".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("vosk-model-small-fa-0.5/graph/HCLr.fst"))
                zos.write("fst".toByteArray())
                zos.closeEntry()
            }
            baos.toByteArray()
        }
        val tinyClient = SolHttpClient(
            dnsResolver = publicDns(),
            connectionOpener = { url -> FakeHeadHttpURLConnection(url, 200, tinyZip) },
            availableBytesProvider = { 4L * 1024L * 1024L * 1024L },
        )
        val boundedEngine = VoskVoiceEngine(
            context = context,
            httpClient = tinyClient,
            availableBytesProvider = { 4L * 1024L * 1024L * 1024L },
        )
        boundedEngine.deleteInstalledModel()
        val tooSmallRes = boundedEngine.downloadPersianSmallModelSync(
            allowHttpsWithoutPinnedHash = true,
            enforceSizeBounds = true,
        )
        assertTrue(tooSmallRes.isFailure)
        assertTrue(tooSmallRes.exceptionOrNull() is SolNetworkException.IntegrityMismatchException)

        // 2. ZIP missing graph/ rejected cleanly
        val noGraphZip = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("vosk-model-small-fa-0.5/am/final.mdl"))
                zos.write("MDL".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("vosk-model-small-fa-0.5/conf/mfcc.conf"))
                zos.write("cfg".toByteArray())
                zos.closeEntry()
            }
            baos.toByteArray()
        }
        val noGraphClient = SolHttpClient(
            dnsResolver = publicDns(),
            connectionOpener = { url -> FakeHeadHttpURLConnection(url, 200, noGraphZip) },
            availableBytesProvider = { 4L * 1024L * 1024L * 1024L },
        )
        val noGraphEngine = VoskVoiceEngine(
            context = context,
            httpClient = noGraphClient,
            availableBytesProvider = { 4L * 1024L * 1024L * 1024L },
        )
        val noGraphRes = noGraphEngine.downloadPersianSmallModelSync(
            allowHttpsWithoutPinnedHash = true,
            enforceSizeBounds = false,
        )
        assertTrue(noGraphRes.isFailure)
        assertTrue(noGraphRes.exceptionOrNull()?.message?.contains("graph") == true)

        // 3. TOFU pinned SHA-256 mismatch rejects tampered re-download
        val tofuEngine = VoskVoiceEngine(
            context = context,
            httpClient = tinyClient,
            availableBytesProvider = { 4L * 1024L * 1024L * 1024L },
            trustedSha256Provider = { "f".repeat(64) },
        )
        val tofuRes = tofuEngine.downloadPersianSmallModelSync(
            allowHttpsWithoutPinnedHash = true,
            enforceSizeBounds = false,
        )
        assertTrue(tofuRes.isFailure)
        assertTrue(tofuRes.exceptionOrNull() is SolNetworkException.IntegrityMismatchException)
        boundedEngine.deleteInstalledModel()
    }

    @Test
    fun `sol audio manager ignores transient focus loss and ducking while stopping only on permanent loss`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioManager = SolAudioManager(context)
        assertTrue(audioManager.requestListeningFocus {})
        assertFalse(audioManager.handleListeningFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertTrue(audioManager.hasListeningFocus)
        assertFalse(audioManager.handleListeningFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertTrue(audioManager.hasListeningFocus)
        assertTrue(audioManager.handleListeningFocusChange(AudioManager.AUDIOFOCUS_LOSS))
        assertFalse(audioManager.hasListeningFocus)
    }

    @Test
    fun `safe audio read tracker handles 1x rebuild, zero-read timeout, 2s zero silence, and system silenced`() {
        val buf = shortArrayOf(120, -240, 310, -150)

        // 1. One rebuild allowed on read < 0, second negative read fails
        val rebuildTracker = SafeAudioReadTracker(maxRebuildAttempts = 1)
        assertEquals(
            SafeReadAction.REBUILD_RECORDER,
            rebuildTracker.onReadResult(-3, buf, nowMs = 100L, isSilencedBySystem = false),
        )
        assertEquals(
            SafeReadAction.FAIL_READ_ERROR,
            rebuildTracker.onReadResult(-3, buf, nowMs = 200L, isSilencedBySystem = false),
        )

        // 2. read == 0 waits then times out after threshold
        val timeoutTracker = SafeAudioReadTracker(zeroReadTimeoutMs = 2_500L)
        assertEquals(
            SafeReadAction.ZERO_WAIT_CONTINUE,
            timeoutTracker.onReadResult(0, buf, nowMs = 1_000L, isSilencedBySystem = false),
        )
        assertEquals(
            SafeReadAction.FAIL_TIMEOUT,
            timeoutTracker.onReadResult(0, buf, nowMs = 3_500L, isSilencedBySystem = false),
        )

        // 3. Continuous all-zero digital samples fail after 2 seconds
        val silentBuf = ShortArray(64)
        val zeroAudioTracker = SafeAudioReadTracker(allZeroFailureThresholdMs = 2_000L)
        assertEquals(
            SafeReadAction.SAMPLES,
            zeroAudioTracker.onReadResult(64, silentBuf, nowMs = 1_000L, isSilencedBySystem = false),
        )
        assertEquals(
            SafeReadAction.FAIL_ALL_ZERO_AUDIO,
            zeroAudioTracker.onReadResult(64, silentBuf, nowMs = 3_050L, isSilencedBySystem = false),
        )

        // 4. API 29+ system silenced aborts immediately
        assertEquals(
            SafeReadAction.FAIL_SILENCED_BY_SYSTEM,
            zeroAudioTracker.onReadResult(4, buf, nowMs = 4_000L, isSilencedBySystem = true),
        )
    }

    @Test
    fun `mic arbiter pauses foreground wake before lease and resumes after release`(): Unit = runBlocking {
        val events = mutableListOf<String>()
        val arbiter = MicArbiter(
            pauseForegroundMic = { events.add("pause_fg") },
            resumeForegroundMic = { events.add("resume_fg") },
        )
        arbiter.withMicLease(MicOwner.OWNER_ENROLLMENT) {
            assertEquals(MicOwner.OWNER_ENROLLMENT, arbiter.activeOwner())
            events.add("enroll_capture")
        }
        assertEquals(null, arbiter.activeOwner())
        assertEquals(listOf("pause_fg", "enroll_capture", "resume_fg"), events)
    }

    @Test
    fun `mic arbiter suppresses intermediate foreground resume while another owner is waiting`(): Unit = runBlocking {
        val events = mutableListOf<String>()
        val arbiter = MicArbiter(
            pauseForegroundMic = { events.add("pause_fg") },
            resumeForegroundMic = { events.add("resume_fg") },
        )
        arbiter.acquire(MicOwner.VOICE_COMMAND)
        events.add("voice_start")

        val waiter = async {
            arbiter.withMicLease(MicOwner.OWNER_ENROLLMENT) {
                events.add("enroll_capture")
            }
        }
        delay(40L)
        events.add("voice_end")
        arbiter.release(MicOwner.VOICE_COMMAND)
        waiter.await()

        assertEquals(null, arbiter.activeOwner())
        // Foreground mic must only resume ONCE at the very end when waitersCount == 0
        assertEquals(1, events.count { it == "resume_fg" })
        assertEquals("resume_fg", events.last())
    }

    @Test
    fun `speaker embedder computes 80-bin cmvn fbank and detects quiet enrollment rms`() {
        val sampleRate = 16_000
        val sineWave = FloatArray(sampleRate) { i ->
            (0.25 * sin(2.0 * PI * 440.0 * i / sampleRate)).toFloat()
        }
        val fbank = SpeakerEmbedder.computeLogMelFbank80(sineWave)
        val expectedFrames = (sampleRate - SpeakerEmbedder.FRAME_SIZE) / SpeakerEmbedder.FRAME_SHIFT + 1
        assertEquals(expectedFrames * SpeakerEmbedder.NUM_MEL_BINS, fbank.size)
        assertTrue(fbank.all { it.isFinite() })

        // Verify per-bin CMVN produces zero mean for each of the 80 Mel bins
        for (melIdx in 0 until SpeakerEmbedder.NUM_MEL_BINS) {
            var sum = 0.0
            for (frameIdx in 0 until expectedFrames) {
                sum += fbank[frameIdx * SpeakerEmbedder.NUM_MEL_BINS + melIdx]
            }
            val mean = sum / expectedFrames
            assertTrue("Expected zero mean after CMVN for bin $melIdx, got $mean", abs(mean) < 1e-4)
        }

        // Quiet RMS rejected (< 0.005f), clear voice accepted
        val quietPcm = FloatArray(16_000) { 0.001f }
        assertTrue(SpeakerIdManager.computeMeanRms(quietPcm) < SpeakerIdManager.MIN_ENROLLMENT_MEAN_RMS)
        assertTrue(SpeakerIdManager.computeMeanRms(sineWave) >= SpeakerIdManager.MIN_ENROLLMENT_MEAN_RMS)
    }

    @Test
    fun `persian phonemizer preserves digits and punctuation and piper engine applies speechRate`() {
        val cfg = PiperConfig(
            sampleRate = 22_050,
            numSymbols = 64,
            numSpeakers = 1,
            phonemeType = "espeak",
            phonemeIdMap = mapOf(
                "^" to listOf(1),
                "_" to listOf(0),
                "$" to listOf(2),
                " " to listOf(3),
                "s" to listOf(10),
                "ɒ" to listOf(11),
                "l" to listOf(12),
                "m" to listOf(13),
                "1" to listOf(21),
                "2" to listOf(22),
                "?" to listOf(30),
                "," to listOf(31),
                "." to listOf(32),
            ),
            audio = AudioConfig(quality = "medium", sampleRate = 22_050),
            noiseScale = 0.667f,
            lengthScale = 1.0f,
            noiseW = 0.8f,
        )

        val report = PersianPhonemizer.toPhonemeIdsWithReport("سلام، ۱۲؟.", cfg)
        assertTrue(report.ids.isNotEmpty())
        assertEquals(1L, report.ids.first()) // BOS
        assertEquals(2L, report.ids.last()) // EOS
        assertTrue(report.ids.contains(21L)) // '۱' -> '1'
        assertTrue(report.ids.contains(22L)) // '۲' -> '2'
        assertTrue(report.ids.contains(30L)) // '؟' -> '?'
        assertTrue(report.ids.contains(31L)) // '،' -> ','
        assertTrue(report.ids.contains(32L)) // '.' -> '.'

        val engine = PiperTtsEngine(File("unused.onnx"), File("unused.json"))
        val fastScales = engine.buildScaleArray(cfg, speechRate = 1.25f)
        assertEquals(0.667f, fastScales[0], 0.001f)
        assertEquals(0.8f, fastScales[1], 0.001f) // 1.0 / 1.25
        assertEquals(0.8f, fastScales[2], 0.001f)
    }

    @Test
    fun `tts manager enters NeedsRiskConfirmation for custom piper files and memory trim invokes piper unload`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val piperDir = File(context.filesDir, "tts/piper").apply { mkdirs() }
        val onnxFile = File(piperDir, "fa_IR.onnx")
        val jsonFile = File(piperDir, "fa_IR.onnx.json")
        val audioManager = SolAudioManager(context)
        val ttsManager = TtsManager(context, audioManager)

        try {
            onnxFile.writeBytes("FAKE_ONNX_MODEL".toByteArray())
            jsonFile.writeText(
                """
                {
                  "audio": {"sample_rate": 22050, "quality": "medium"},
                  "num_symbols": 32,
                  "num_speakers": 1,
                  "phoneme_type": "espeak",
                  "phoneme_id_map": {"^": [1], "_": [0], "$": [2], "s": [10], " ": [3]}
                }
                """.trimIndent()
            )
            assertFalse(ttsManager.refreshInstalledPiper())
            val status = ttsManager.piperStatus.value
            assertTrue("Expected NeedsRiskConfirmation, got $status", status is PiperTtsStatus.NeedsRiskConfirmation)

            val optimizer = SolMemoryOptimizer(context)
            var piperUnloaded = false
            optimizer.manualTrimNow(
                evictRagCache = {},
                unloadVoskIfIdle = {},
                unloadPiperIfIdle = { piperUnloaded = true },
                releaseSlmCache = {},
            )
            assertTrue(piperUnloaded)
            assertNotNull(status)
        } finally {
            ttsManager.release()
            onnxFile.delete()
            jsonFile.delete()
        }
    }

    @Test
    fun `official piper and campp downloads reject tampered sha256 payloads even without matching headers`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeClient = SolHttpClient(
            dnsResolver = publicDns(),
            connectionOpener = { url -> FakeHeadHttpURLConnection(url, 200, "TAMPERED_PAYLOAD".toByteArray()) },
            availableBytesProvider = { 4L * 1024L * 1024L * 1024L },
        )

        val audioManager = SolAudioManager(context)
        val ttsManager = TtsManager(
            context = context,
            audioManager = audioManager,
            httpClient = fakeClient,
            availableBytesProvider = { 4L * 1024L * 1024L * 1024L },
        )
        ttsManager.disableAndDeletePiper()
        val piperRes = ttsManager.downloadOfficialPiperModelSync()
        assertTrue(piperRes.isFailure)
        assertTrue(piperRes.exceptionOrNull() is SolNetworkException.IntegrityMismatchException)
        ttsManager.release()

        val speakerManager = SpeakerIdManager(
            context = context,
            httpClient = fakeClient,
            availableBytesProvider = { 4L * 1024L * 1024L * 1024L },
        )
        speakerManager.deleteAllSpeakerData()
        val speakerRes = speakerManager.ensureAndDownloadSpeakerModel()
        assertTrue(speakerRes.isFailure)
        assertTrue(speakerRes.exceptionOrNull() is SolNetworkException.IntegrityMismatchException)

        // Legacy incompatible file (e.g., old int8 ConvInteger ONNX without matching FP32 SHA-256)
        // must be flagged incompatible so auto-upgrade / enrollment replaces it cleanly.
        speakerManager.modelFile.parentFile?.mkdirs()
        speakerManager.modelFile.writeBytes("OLD_INT8_CONVINTEGER_MODEL".toByteArray())
        speakerManager.refreshStatus()
        assertFalse(speakerManager.isModelCompatible())
        assertFalse(speakerManager.uiState.value.isModelInstalled)
        speakerManager.deleteAllSpeakerData()
    }
}
