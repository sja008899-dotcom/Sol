package com.example

import android.content.ComponentCallbacks2
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.sol.knowledge.BookRagEngine
import com.example.sol.memory.BookKnowledgeEntity
import com.example.sol.memory.KnowledgeRepository
import com.example.sol.memory.LocationRepository
import com.example.sol.memory.MemoryDatabase
import com.example.sol.memory.MemoryRepository
import com.example.sol.memory.PersonRepository
import com.example.sol.memory.PreferencesRepository
import com.example.sol.security.EncryptedBackupManager
import com.example.sol.widget.SolWidgetStateMirror
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Sol", appName)
    }

    @Test
    fun `bm25 rag chunks and ranks relevant persian book content`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = KnowledgeRepository(db.memoryDao())
            val rag = BookRagEngine(repo)

            val bookId = repo.saveBook(
                BookKnowledgeEntity(
                    title = "مدیریت مرغداری کارنو",
                    author = "نویسنده نمونه",
                    summaryText = "راهنمای کنترل دما و تهویه سالن پرورش و مدیریت خوراک.",
                    voiceNotes = "حداقل دمای سالن در هفته اول ۳۲ درجه سانتیگراد است.",
                    sourceType = "PDF",
                )
            )
            val fullText = buildString {
                append("بخش اول درباره اصول عمومی حسابداری و بایگانی اسناد مالی است. ")
                append("بخش دوم درباره تهویه سالن، دمای سالن، رطوبت استاندارد و مدیریت پرورش است. ")
                append("بخش سوم به نگهداری ژنراتور برق اضطراری و سوخت دیزل می‌پردازد.")
            }
            val chunkCount = rag.indexBookContent(bookId, "مدیریت مرغداری کارنو", fullText)
            assertTrue(chunkCount >= 1)

            val ranked = rag.rankChunksBm25(
                query = "دمای سالن و تهویه چقدر باشد؟",
                chunks = repo.getAllChunksOnce(),
                preferredBookTitle = "مدیریت مرغداری کارنو",
                topK = 2,
            )
            assertTrue(ranked.isNotEmpty())
            assertTrue(ranked.first().chunk.contentText.contains("تهویه"))
            assertTrue(ranked.first().matchedTerms.isNotEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `encrypted backup roundtrip succeeds with valid password and rejects wrong password`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.memoryDao()
            val backupManager = EncryptedBackupManager(
                context = context,
                memoryRepository = MemoryRepository(dao),
                personRepository = PersonRepository(dao),
                locationRepository = LocationRepository(dao),
                knowledgeRepository = KnowledgeRepository(dao),
                preferencesRepository = PreferencesRepository(context),
            )
            val secretJson = """{"version":2,"note":"شرکت کارنو صنعت ماکیان"}"""
            val encrypted = backupManager.encryptPayload(secretJson, "sol-pass-1404")
            assertFalse(encrypted.contains("کارنو"))
            assertTrue(encrypted.contains("SOL_BACKUP_V2"))

            val decrypted = backupManager.decryptPayload(encrypted, "sol-pass-1404")
            assertEquals(secretJson, decrypted)

            try {
                backupManager.decryptPayload(encrypted, "wrong-password")
                fail("Expected decryption to fail with wrong password")
            } catch (_: IllegalArgumentException) {
                // Expected
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `widget state mirror and application trimMemory work safely`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SolWidgetStateMirror.saveAndRefresh(
            context = context,
            confirmedLocation = "شرکت کارنو صنعت ماکیان",
            activeRoleName = "مشاور پرورش",
        )
        assertEquals("شرکت کارنو صنعت ماکیان", SolWidgetStateMirror.readConfirmedLocation(context))
        assertEquals("مشاور پرورش", SolWidgetStateMirror.readActiveRole(context))

        val app = context.applicationContext as SolApplication
        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertTrue(app.container.memoryOptimizer.ramSnapshot.value.lastTrimActionFa.contains("RAM"))
    }

    @Test
    fun `built-in persian tts initializes cleanly without error and command brain handles colloquial prompts`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as SolApplication
        val c = app.container

        assertEquals(com.example.sol.voice.TtsAvailability.READY_BUILTIN_PERSIAN.labelFa.isNotBlank(), true)

        c.personRepository.savePerson(name = "رضا احمدی", phone = "09123456789", relation = "همکار")
        val personReply = c.commandBrain.process("شماره رضا احمدی چنده؟")
        assertTrue(personReply.replyText.contains("09123456789"))

        val genericWeb = c.commandBrain.process("یه چیزی از اینترنت دانلود کن")
        assertEquals(com.example.sol.brain.SolAction.WEB_BOOK_LEARN_ROLE, genericWeb.parsedCommand.action)
        assertTrue(genericWeb.pendingTask is com.example.sol.brain.PendingSolTask.InternetBookRoleFetch)
    }
}
