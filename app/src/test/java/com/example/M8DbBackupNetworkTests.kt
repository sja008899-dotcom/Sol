package com.example

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.example.sol.knowledge.BookRagEngine
import com.example.sol.memory.BookChunkEntity
import com.example.sol.memory.BookKnowledgeEntity
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
import com.example.sol.security.EncryptedBackupManager
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class M8DbBackupNetworkTests {

    @Test
    fun `room v6 to v7 migration rebuilds FTS4 index for existing chunks and release build disallows destructive wipe`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_v6_to_v7.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `keyText` TEXT NOT NULL, `valueText` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memories_keyText` ON `memories` (`keyText`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `persons` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `normalizedName` TEXT NOT NULL, `phone` TEXT NOT NULL, `relation` TEXT NOT NULL, `callScriptNote` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_persons_normalizedName` ON `persons` (`normalizedName`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `locations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `alias` TEXT NOT NULL, `normalizedAlias` TEXT NOT NULL, `fullName` TEXT NOT NULL, `addressNote` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_locations_normalizedAlias` ON `locations` (`normalizedAlias`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userText` TEXT NOT NULL, `solText` TEXT NOT NULL, `intentAction` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_createdAt` ON `history` (`createdAt`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `author` TEXT NOT NULL, `subjects` TEXT NOT NULL, `summaryText` TEXT NOT NULL, `voiceNotes` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `sol_roles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `roleName` TEXT NOT NULL, `normalizedRoleName` TEXT NOT NULL, `instructions` TEXT NOT NULL, `linkedBookTitle` TEXT NOT NULL, `isActive` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sol_roles_normalizedRoleName` ON `sol_roles` (`normalizedRoleName`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `background_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `jobKey` TEXT NOT NULL, `titleFa` TEXT NOT NULL, `status` TEXT NOT NULL, `detailFa` TEXT NOT NULL, `executedAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `book_chunks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `bookTitle` TEXT NOT NULL, `chunkIndex` INTEGER NOT NULL, `contentText` TEXT NOT NULL, `normalizedTokens` TEXT NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_chunks_bookId` ON `book_chunks` (`bookId`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `scheduled_alarms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `requestCode` INTEGER NOT NULL, `titleFa` TEXT NOT NULL, `messageBody` TEXT NOT NULL, `triggerAtMillis` INTEGER NOT NULL, `jalaliLabel` TEXT NOT NULL, `targetContact` TEXT NOT NULL, `isExact` INTEGER NOT NULL, `isTriggered` INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_alarms_isTriggered_triggerAtMillis` ON `scheduled_alarms` (`isTriggered`, `triggerAtMillis`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `user_utterance_examples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `intent` TEXT NOT NULL, `text` TEXT NOT NULL, `normalizedText` TEXT NOT NULL, `slotsJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_utterance_examples_intent` ON `user_utterance_examples` (`intent`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_utterance_examples_normalizedText` ON `user_utterance_examples` (`normalizedText`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `notes_todos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `content` TEXT NOT NULL, `isDone` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_todos_kind_isDone_updatedAt` ON `notes_todos` (`kind`, `isDone`, `updatedAt`)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        helper.writableDatabase.use { v6db ->
            val bookId = v6db.insert(
                "books",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("title", "عادت‌های اتمی")
                    put("author", "جیمز کلیر")
                    put("subjects", "تغییر عادت")
                    put("summaryText", "بهبود یک درصدی روزانه")
                    put("voiceNotes", "")
                    put("sourceType", "TEXT")
                    put("sourceUrl", "")
                    put("createdAt", 1000L)
                },
            )
            v6db.insert(
                "book_chunks",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("bookId", bookId)
                    put("bookTitle", "عادت‌های اتمی")
                    put("chunkIndex", 0)
                    put("contentText", "قدرت تغییرات خرد و انباشته در ساختن عادت‌های پایدار")
                    put("normalizedTokens", "قدرت تغییرات خرد انباشته ساختن عادت پایدار")
                },
            )
        }
        helper.close()

        // Open using real ALL_MIGRATIONS (includes MIGRATION_6_7 creating book_chunks_fts & rebuilding)
        val migrated = Room.databaseBuilder(context, MemoryDatabase::class.java, dbName)
            .addMigrations(*MemoryDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = migrated.memoryDao()
            val ftsHits = dao.searchBookChunksFts("انباشته*", limit = 10)
            assertEquals("Expected v6->v7 FTS4 rebuild to index pre-existing chunks", 1, ftsHits.size)
            assertTrue(ftsHits.first().contentText.contains("انباشته"))

            val rag = BookRagEngine(KnowledgeRepository(dao))
            val result = rag.consultOffline("تغییرات انباشته چطور کار می‌کند؟")
            assertTrue(result.topChunks.isNotEmpty())
            assertTrue(result.answerText.contains("FTS4"))
        } finally {
            migrated.close()
            dbFile.delete()
        }

        // Verify MemoryDatabase.buildDatabase(..., isDebugBuild = false) builds cleanly without calling fallbackToDestructiveMigration
        val releaseDb = MemoryDatabase.buildDatabase(context, isDebugBuild = false)
        releaseDb.close()
    }

    @Test
    fun `multi-MB V3 encrypted backup roundtrip, tampered ciphertext, truncated stream, and atomic rollback work`() = runBlocking<Unit> {
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
            val noteTodoRepo = NoteTodoRepository(dao)

            val backupManager = EncryptedBackupManager(
                context = context,
                database = db,
                memoryRepository = memRepo,
                personRepository = personRepo,
                locationRepository = locRepo,
                knowledgeRepository = knowledgeRepo,
                preferencesRepository = prefsRepo,
                noteTodoRepository = noteTodoRepo,
                availableBytesProvider = { 5L * 1024L * 1024L * 1024L },
            )

            // Seed > 2.5 MB of varied content (high-entropy pseudo-hex tags + Persian prose so even gzip output stays multi-hundred KB / payload > 2.5 MB)
            memRepo.remember("alias:باشگاه", "مجموعه ورزشی انقلاب", kind = "alias")
            val bookId = knowledgeRepo.saveBook(
                BookKnowledgeEntity(
                    title = "کتاب حجیم آزمایشی",
                    author = "نویسنده",
                    summaryText = "خلاصه کتاب بزرگ",
                    sourceType = "TEXT",
                )
            )
            val rng = java.util.Random(42L)
            val chunks = (0 until 550).map { idx ->
                val entropySuffix = (0 until 240).joinToString("") {
                    Integer.toHexString(rng.nextInt(16))
                }
                val text = "بخش شماره $idx: ذخیره ایمن دانش آفلاین در Sol بدون سرور ابری. $entropySuffix"
                BookChunkEntity(
                    bookId = bookId,
                    bookTitle = "کتاب حجیم آزمایشی",
                    chunkIndex = idx,
                    contentText = text,
                    normalizedTokens = "بخش ذخیره ایمن دانش آفلاین",
                )
            }
            // Add high-entropy varied text to guarantee > 2.5 MB UTF-8 chunk data
            val extraBigChunks = (550 until 1_100).map { idx ->
                val bigHex = ByteArray(2_400).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }
                BookChunkEntity(
                    bookId = bookId,
                    bookTitle = "کتاب حجیم آزمایشی",
                    chunkIndex = idx,
                    contentText = "فصل $idx $bigHex",
                    normalizedTokens = "فصل $idx",
                )
            }
            val allChunks = chunks + extraBigChunks
            val totalUtf8Bytes = allChunks.sumOf { it.contentText.toByteArray(Charsets.UTF_8).size }
            assertTrue("Expected >2.5 MB plaintext JSON chunk content, got $totalUtf8Bytes bytes", totalUtf8Bytes > 2_500_000)
            knowledgeRepo.replaceChunksForBook(bookId, allChunks)
            noteTodoRepo.addItem("SHOPPING", "نان سنگک و پنیر")

            val backupFile = File(context.cacheDir, "large_v3_test.solbackup").apply { delete() }
            val exportStats = backupFile.outputStream().use { os ->
                backupManager.exportStreamingV3(os, "MultiMbSecret#1404")
            }
            assertEquals(1_100, exportStats.chunksCount)
            assertTrue("Expected large encrypted backup file (>1 MB), got ${exportStats.fileSizeBytes}", exportStats.fileSizeBytes > 1_000_000L)

            val rawBackup = backupFile.readBytes()

            // Mutate DB after export so we can verify atomic rollback leaves NEW rows completely untouched on failures!
            memRepo.remember("alias:بقالی", "فروشگاه محل", kind = "alias")
            val sentinelCountBeforeFailure = memRepo.getAllOnce().size
            assertEquals(2, sentinelCountBeforeFailure)

            // 1. Wrong password must fail safely without deleting sentinel memory
            val wrongPass = ByteArrayInputStream(rawBackup).use { ins ->
                runCatching { backupManager.importFromStream(ins, "WrongPass#0000", replaceExisting = true) }
            }
            assertTrue(wrongPass.isFailure)
            assertEquals(sentinelCountBeforeFailure, memRepo.getAllOnce().size)

            // 2. Tampered ciphertext byte near middle of stream must fail GCM auth without deleting sentinel
            val tampered = rawBackup.copyOf()
            val midIndex = tampered.size / 2
            tampered[midIndex] = (tampered[midIndex].toInt() xor 0x5A).toByte()
            val tamperedRes = ByteArrayInputStream(tampered).use { ins ->
                runCatching { backupManager.importFromStream(ins, "MultiMbSecret#1404", replaceExisting = true) }
            }
            assertTrue("Tampered ciphertext must fail AEAD authentication", tamperedRes.isFailure)
            assertEquals(sentinelCountBeforeFailure, memRepo.getAllOnce().size)

            // 3. Truncated file (60% of bytes) must fail safely and leave sentinel untouched
            val truncated = rawBackup.copyOfRange(0, (rawBackup.size * 0.6).toInt())
            val truncatedRes = ByteArrayInputStream(truncated).use { ins ->
                runCatching { backupManager.importFromStream(ins, "MultiMbSecret#1404", replaceExisting = true) }
            }
            assertTrue("Truncated backup must fail safely", truncatedRes.isFailure)
            assertEquals(sentinelCountBeforeFailure, memRepo.getAllOnce().size)

            // 4. Valid restore replaces DB atomically back to 1 memory + 1,100 chunks + 1 note
            val restoreStats = ByteArrayInputStream(rawBackup).use { ins ->
                backupManager.importFromStream(ins, "MultiMbSecret#1404", replaceExisting = true)
            }
            assertEquals(1, restoreStats.memoriesCount)
            assertEquals(1_100, restoreStats.chunksCount)
            assertEquals(1_100, dao.getChunkCountOnce())
            assertEquals("مجموعه ورزشی انقلاب", memRepo.getAllOnce().single().valueText)
            assertEquals(1, noteTodoRepo.getAllOnce().size)

            backupFile.delete()
        } finally {
            prefsRepo.resetAllPreferences()
            db.close()
        }
    }

    @Test
    fun `download logic against real local HTTP server handles 206 Range resume, wrong hash, http redirect, HTML MIME, disallowed host, and TODO verify`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val payload = ByteArray(48_000) { idx -> ((idx * 17 + 3) and 0xFF).toByte() }
        val expectedSha = sha256Hex(payload)
        val capturedRangeHeader = AtomicReference<String?>(null)

        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/model.bin") { exchange ->
            val rangeHdr = exchange.requestHeaders.getFirst("Range")
            capturedRangeHeader.set(rangeHdr)
            if (rangeHdr != null && rangeHdr.startsWith("bytes=")) {
                val start = rangeHdr.removePrefix("bytes=").substringBefore("-").toInt()
                val slice = payload.copyOfRange(start, payload.size)
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                exchange.sendResponseHeaders(206, slice.size.toLong())
                exchange.responseBody.use { it.write(slice) }
            } else {
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
        }
        server.createContext("/wrong-hash.bin") { exchange ->
            val corrupted = payload.copyOf().also { it[42] = (it[42].toInt() xor 0xFF).toByte() }
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, corrupted.size.toLong())
            exchange.responseBody.use { it.write(corrupted) }
        }
        server.createContext("/redirect-http") { exchange ->
            exchange.responseHeaders.add("Location", "http://huggingface.co/insecure-model.bin")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/html-instead-of-bin") { exchange ->
            val html = "<html><body>Sign in required</body></html>".toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, html.size.toLong())
            exchange.responseBody.use { it.write(html) }
        }
        server.start()

        try {
            val port = server.address.port
            // Point allowed host huggingface.co to our real local HttpServer TCP port while keeping public-IP validation intact
            val client = SolHttpClient(
                dnsResolver = { host ->
                    arrayOf(InetAddress.getByAddress(host, byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)))
                },
                connectionOpener = { httpsUrl ->
                    val localUrl = URL("http", "127.0.0.1", port, httpsUrl.file)
                    localUrl.openConnection() as HttpURLConnection
                },
                availableBytesProvider = { 5L * 1024L * 1024L * 1024L },
            )

            // 1. 206 Range Resume over real socket
            val partFile = File(context.cacheDir, "server_resume_test.part").apply {
                writeBytes(payload.copyOfRange(0, 12_000))
            }
            val verifiedPart = client.downloadVerifiedFile(
                urlString = "https://huggingface.co/model.bin",
                partFile = partFile,
                expectedBytes = payload.size.toLong(),
                expectedSha256 = expectedSha,
            )
            assertEquals("bytes=12000-", capturedRangeHeader.get())
            assertEquals(expectedSha, sha256Hex(verifiedPart.readBytes()))
            partFile.delete()

            // 2. Wrong hash deletes part & throws IntegrityMismatchException
            try {
                client.downloadVerifiedFile(
                    urlString = "https://huggingface.co/wrong-hash.bin",
                    partFile = partFile,
                    expectedBytes = payload.size.toLong(),
                    expectedSha256 = expectedSha,
                )
                fail("Expected IntegrityMismatchException for corrupted payload")
            } catch (_: SolNetworkException.IntegrityMismatchException) {
                assertFalse("Corrupted part file must be deleted", partFile.exists())
            }

            // 3. Redirect to http:// is rejected cleanly
            try {
                client.downloadVerifiedFile(
                    urlString = "https://huggingface.co/redirect-http",
                    partFile = partFile,
                    expectedBytes = payload.size.toLong(),
                    expectedSha256 = expectedSha,
                )
                fail("Expected InsecureSchemeException on redirect to http://")
            } catch (_: SolNetworkException.InsecureSchemeException) {
                // Expected
            }

            // 4. text/html response instead of binary model fails cleanly
            try {
                client.downloadVerifiedFile(
                    urlString = "https://huggingface.co/html-instead-of-bin",
                    partFile = partFile,
                    expectedBytes = payload.size.toLong(),
                    expectedSha256 = expectedSha,
                )
                fail("Expected HttpStatusException on text/html response")
            } catch (_: SolNetworkException.HttpStatusException) {
                assertFalse(partFile.exists())
            }

            // 5. Host outside allowlist fails before connecting
            try {
                client.downloadVerifiedFile(
                    urlString = "https://untrusted.example.org/model.bin",
                    partFile = partFile,
                    expectedBytes = payload.size.toLong(),
                    expectedSha256 = expectedSha,
                )
                fail("Expected DisallowedHostException")
            } catch (_: SolNetworkException.DisallowedHostException) {
                // Expected
            }

            // 6. DownloadWorker rejects TODO(verify) checksums fail-safe
            val todoWorker = TestListenableWorkerBuilder<DownloadWorker>(context)
                .setInputData(
                    workDataOf(
                        DownloadWorker.KEY_URL to "https://huggingface.co/model.bin",
                        DownloadWorker.KEY_DESTINATION_PATH to File(context.cacheDir, "todo_model.bin").absolutePath,
                        DownloadWorker.KEY_EXPECTED_BYTES to 1024L,
                        DownloadWorker.KEY_EXPECTED_SHA256 to "TODO(verify)",
                    )
                )
                .build()
            val workerRes = todoWorker.doWork()
            assertTrue(workerRes is ListenableWorker.Result.Failure)
            assertTrue(workerRes.outputData.getString(DownloadWorker.KEY_ERROR_MESSAGE).orEmpty().contains("TODO(verify)"))
        } finally {
            server.stop(0)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
