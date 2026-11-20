package com.example.sol.security

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.room.withTransaction
import com.example.sol.memory.BookChunkEntity
import com.example.sol.memory.BookKnowledgeEntity
import com.example.sol.memory.ConfirmationPolicy
import com.example.sol.memory.DeliveryPreference
import com.example.sol.memory.HistoryRetentionMode
import com.example.sol.memory.InvocationMode
import com.example.sol.memory.KnowledgeRepository
import com.example.sol.memory.LocationRepository
import com.example.sol.memory.MemoryDatabase
import com.example.sol.memory.MemoryRepository
import com.example.sol.memory.NoteTodoRepository
import com.example.sol.memory.PersonRepository
import com.example.sol.memory.PreferencesRepository
import com.example.sol.memory.SolPreferences
import com.example.sol.memory.VoicePersona
import com.example.sol.network.SolNetworkException
import com.example.sol.network.SolStorageGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class BackupStats(
    val memoriesCount: Int,
    val personsCount: Int,
    val locationsCount: Int,
    val booksCount: Int,
    val chunksCount: Int,
    val rolesCount: Int,
    val fileSizeBytes: Long = 0L,
    val notesTodosCount: Int = 0,
)

private data class StagedMemory(val kind: String, val keyText: String, val valueText: String)
private data class StagedNoteTodo(val kind: String, val content: String, val isDone: Boolean)
private data class StagedPerson(val name: String, val phone: String, val relation: String, val callScriptNote: String)
private data class StagedLocation(val alias: String, val fullName: String, val addressNote: String)
private data class StagedBookChunk(val chunkIndex: Int, val contentText: String, val normalizedTokens: String)
private data class StagedBook(
    val title: String,
    val author: String,
    val subjects: String,
    val summaryText: String,
    val voiceNotes: String,
    val sourceType: String,
    val sourceUrl: String,
    val chunks: List<StagedBookChunk>,
)
private data class StagedRole(
    val roleName: String,
    val instructions: String,
    val linkedBookTitle: String,
    val isActive: Boolean,
)
private data class StagedPreferences(
    val ownerName: String?,
    val voicePersona: String?,
    val preferredTtsEnginePackage: String?,
    val preferredTtsVoiceName: String?,
    val speechRate: Double?,
    val autoSpeak: Boolean?,
    val invocationMode: String?,
    val deliveryPreference: String?,
    val confirmationPolicy: String?,
    val darkTheme: Boolean?,
    val confirmedCurrentLocation: String?,
    val confirmedCurrentLocationUpdatedAt: Long?,
    val voskOfflineEnabled: Boolean?,
    val wakeWordEnabled: Boolean?,
    val slmSynthesisEnabled: Boolean?,
    val slmPreferGpu: Boolean?,
    val historyRetentionMode: String?,
    val privateModeEnabled: Boolean?,
)

private data class StagedBackupPayload(
    val memories: List<StagedMemory>,
    val persons: List<StagedPerson>,
    val locations: List<StagedLocation>,
    val books: List<StagedBook>,
    val roles: List<StagedRole>,
    val notesTodos: List<StagedNoteTodo>,
    val preferences: StagedPreferences?,
)

class EncryptedBackupManager(
    private val context: Context,
    private val database: MemoryDatabase,
    private val memoryRepository: MemoryRepository,
    private val personRepository: PersonRepository,
    private val locationRepository: LocationRepository,
    private val knowledgeRepository: KnowledgeRepository,
    private val preferencesRepository: PreferencesRepository,
    private val availableBytesProvider: (File) -> Long = SolStorageGuard::readAvailableBytes,
    private val noteTodoRepository: NoteTodoRepository? = null,
) {
    suspend fun exportEncryptedBackup(uri: Uri, password: String): Result<BackupStats> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(password.length >= MIN_EXPORT_PASSWORD_LENGTH) {
                    "رمز فایل پشتیبان باید حداقل ۸ کاراکتر باشد."
                }
                val estimatedPayloadBytes = estimateBackupPayloadBytes()
                val diskCheck = SolStorageGuard.checkFreeSpace(
                    targetPath = context.filesDir,
                    payloadBytes = estimatedPayloadBytes,
                    availableBytesProvider = availableBytesProvider,
                )
                if (!diskCheck.hasEnoughSpace) {
                    throw SolNetworkException.InsufficientDiskSpaceException(
                        availableMb = diskCheck.availableMb,
                        requiredMb = diskCheck.requiredMb,
                    )
                }

                val outStream = context.contentResolver.openOutputStream(uri)
                    ?: error("دسترسی به مسیر ذخیره فایل ممکن نشد.")
                val stats = outStream.use { rawOut ->
                    exportStreamingV3(rawOut, password)
                }
                preferencesRepository.recordBackupTimestamp()
                stats
            }
        }

    suspend fun importEncryptedBackup(
        uri: Uri,
        password: String,
        replaceExisting: Boolean = false,
    ): Result<BackupStats> = withContext(Dispatchers.IO) {
        runCatching {
            require(password.isNotBlank()) { "رمز فایل پشتیبان را وارد کنید." }
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: error("خواندن فایل پشتیبان ممکن نشد.")
            inputStream.use { stream ->
                importFromStream(stream, password, replaceExisting)
            }
        }
    }

    internal suspend fun exportStreamingV3(output: OutputStream, password: String): BackupStats {
        require(password.length >= MIN_EXPORT_PASSWORD_LENGTH) {
            "رمز فایل پشتیبان باید حداقل ۸ کاراکتر باشد."
        }
        val memories = memoryRepository.getAllOnce()
        val persons = personRepository.getAllOnce()
        val locations = locationRepository.getAllOnce()
        val books = knowledgeRepository.getAllBooksOnce()
        val roles = knowledgeRepository.getAllRolesOnce()
        val notesTodos = noteTodoRepository?.getAllOnce().orEmpty()
        val prefs = preferencesRepository.getSnapshot()

        val random = SecureRandom()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }

        val headerJson = JSONObject().apply {
            put("format", FORMAT_V3)
            put("alg", "AES-256-GCM")
            put("kdf", KDF_ALGORITHM)
            put("iterations", PBKDF2_ITERATIONS_V3)
            put("compression", "GZIP")
            put("saltB64", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("ivB64", Base64.encodeToString(iv, Base64.NO_WRAP))
        }.toString()
        val headerBytes = headerJson.toByteArray(Charsets.UTF_8)

        val key = deriveAesKey(password, salt, PBKDF2_ITERATIONS_V3)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(headerBytes)

        val countingOut = CountingOutputStream(output)
        countingOut.write("$MAGIC_LINE_V3\n".toByteArray(Charsets.UTF_8))
        countingOut.write(headerBytes)
        countingOut.write('\n'.code)
        countingOut.flush()

        var totalChunks = 0
        CipherOutputStream(NonClosingOutputStream(countingOut), cipher).use { cipherOut ->
            GZIPOutputStream(cipherOut).use { gzipOut ->
                OutputStreamWriter(gzipOut, Charsets.UTF_8).use { writer ->
                    JsonWriter(writer).use { jw ->
                        jw.beginObject()
                        jw.name("version").value(3)
                        jw.name("exportedAt").value(System.currentTimeMillis())

                        jw.name("preferences").beginObject()
                        jw.name("ownerName").value(prefs.ownerName)
                        jw.name("voicePersona").value(prefs.voicePersona.name)
                        jw.name("preferredTtsEnginePackage").value(prefs.preferredTtsEnginePackage)
                        jw.name("preferredTtsVoiceName").value(prefs.preferredTtsVoiceName)
                        jw.name("speechRate").value(prefs.speechRate.toDouble())
                        jw.name("autoSpeak").value(prefs.autoSpeak)
                        jw.name("invocationMode").value(prefs.invocationMode.name)
                        jw.name("deliveryPreference").value(prefs.deliveryPreference.name)
                        jw.name("confirmationPolicy").value(prefs.confirmationPolicy.name)
                        jw.name("darkTheme").value(prefs.darkTheme)
                        jw.name("confirmedCurrentLocation").value(prefs.confirmedCurrentLocation)
                        jw.name("confirmedCurrentLocationUpdatedAt").value(prefs.confirmedCurrentLocationUpdatedAt)
                        jw.name("voskOfflineEnabled").value(prefs.voskOfflineEnabled)
                        jw.name("wakeWordEnabled").value(prefs.wakeWordEnabled)
                        jw.name("slmSynthesisEnabled").value(prefs.slmSynthesisEnabled)
                        jw.name("slmPreferGpu").value(prefs.slmPreferGpu)
                        jw.name("historyRetentionMode").value(prefs.historyRetentionMode.name)
                        jw.name("privateModeEnabled").value(prefs.privateModeEnabled)
                        jw.endObject()

                        jw.name("memories").beginArray()
                        for (m in memories) {
                            jw.beginObject()
                            jw.name("kind").value(m.kind)
                            jw.name("keyText").value(m.keyText)
                            jw.name("valueText").value(m.valueText)
                            jw.endObject()
                        }
                        jw.endArray()

                        jw.name("persons").beginArray()
                        for (p in persons) {
                            jw.beginObject()
                            jw.name("name").value(p.name)
                            jw.name("phone").value(p.phone)
                            jw.name("relation").value(p.relation)
                            jw.name("callScriptNote").value(p.callScriptNote)
                            jw.endObject()
                        }
                        jw.endArray()

                        jw.name("locations").beginArray()
                        for (l in locations) {
                            jw.beginObject()
                            jw.name("alias").value(l.alias)
                            jw.name("fullName").value(l.fullName)
                            jw.name("addressNote").value(l.addressNote)
                            jw.endObject()
                        }
                        jw.endArray()

                        jw.name("books").beginArray()
                        for (b in books) {
                            val bookChunks = knowledgeRepository.getChunksForBookOnce(b.id)
                            totalChunks += bookChunks.size
                            jw.beginObject()
                            jw.name("title").value(b.title)
                            jw.name("author").value(b.author)
                            jw.name("subjects").value(b.subjects)
                            jw.name("summaryText").value(b.summaryText)
                            jw.name("voiceNotes").value(b.voiceNotes)
                            jw.name("sourceType").value(b.sourceType)
                            jw.name("sourceUrl").value(b.sourceUrl)
                            jw.name("chunks").beginArray()
                            for (c in bookChunks) {
                                jw.beginObject()
                                jw.name("chunkIndex").value(c.chunkIndex.toLong())
                                jw.name("contentText").value(c.contentText)
                                jw.name("normalizedTokens").value(c.normalizedTokens)
                                jw.endObject()
                            }
                            jw.endArray()
                            jw.endObject()
                        }
                        jw.endArray()

                        jw.name("roles").beginArray()
                        for (r in roles) {
                            jw.beginObject()
                            jw.name("roleName").value(r.roleName)
                            jw.name("instructions").value(r.instructions)
                            jw.name("linkedBookTitle").value(r.linkedBookTitle)
                            jw.name("isActive").value(r.isActive)
                            jw.endObject()
                        }
                        jw.endArray()

                        jw.name("notesTodos").beginArray()
                        for (nt in notesTodos) {
                            jw.beginObject()
                            jw.name("kind").value(nt.kind)
                            jw.name("content").value(nt.content)
                            jw.name("isDone").value(nt.isDone)
                            jw.endObject()
                        }
                        jw.endArray()

                        jw.endObject()
                    }
                }
            }
        }
        countingOut.flush()

        return BackupStats(
            memoriesCount = memories.size,
            personsCount = persons.size,
            locationsCount = locations.size,
            booksCount = books.size,
            chunksCount = totalChunks,
            rolesCount = roles.size,
            fileSizeBytes = countingOut.bytesWritten,
            notesTodosCount = notesTodos.size,
        )
    }

    internal suspend fun importFromStream(
        rawStream: InputStream,
        password: String,
        replaceExisting: Boolean,
    ): BackupStats {
        val countingIn = CountingInputStream(rawStream)
        val firstChunk = ByteArrayOutputStream()
        // Read first line to detect SOL_BACKUP_V3 magic header vs legacy JSON V2 envelope
        val firstLine = readLineUtf8(countingIn, MAX_MAGIC_LINE_BYTES, firstChunk)
        val staged = if (firstLine.trim() == MAGIC_LINE_V3) {
            parseAndAuthenticateV3(countingIn, password)
        } else {
            // Reconstruct legacy V2 envelope (capped safely at MAX_LEGACY_V2_BYTES)
            val rest = readAllBounded(countingIn, MAX_LEGACY_V2_BYTES - firstChunk.size())
            val combined = ByteArray(firstChunk.size() + rest.size).also { out ->
                System.arraycopy(firstChunk.toByteArray(), 0, out, 0, firstChunk.size())
                System.arraycopy(rest, 0, out, firstChunk.size(), rest.size)
            }
            val envelopeJson = String(combined, Charsets.UTF_8)
            val plainJson = decryptPayload(envelopeJson, password)
            parseStagedFromJsonReader(JsonReader(plainJson.reader()))
        }

        // Only apply DB changes inside a single Room transaction AFTER full validation & GCM tag verification!
        val stats = applyValidatedBackupInTransaction(staged, replaceExisting, countingIn.bytesRead)
        preferencesRepository.recordBackupTimestamp()
        return stats
    }

    private fun parseAndAuthenticateV3(stream: InputStream, password: String): StagedBackupPayload {
        val headerLine = readLineUtf8(stream, MAX_HEADER_LINE_BYTES)
        require(headerLine.isNotBlank()) { "سربرگ فایل پشتیبان V3 ناقص است." }
        val headerBytes = headerLine.toByteArray(Charsets.UTF_8)
        val header = JSONObject(headerLine)

        require(header.optString("format") == FORMAT_V3) { "فرمت فایل پشتیبان نامعتبر است." }
        require(header.optString("alg") == "AES-256-GCM") { "الگوریتم رمزنگاری پشتیبانی نمی‌شود." }
        require(header.optString("kdf") == KDF_ALGORITHM) { "الگوریتم مشتق‌سازی کلید نامعتبر است." }
        val iterations = header.optInt("iterations", -1)
        require(iterations in 1..MAX_KDF_ITERATIONS) {
            "تعداد تکرارهای کلید در فایل پشتیبان غیرمجاز است ($iterations)."
        }

        val salt = Base64.decode(header.getString("saltB64"), Base64.NO_WRAP)
        val iv = Base64.decode(header.getString("ivB64"), Base64.NO_WRAP)
        require(salt.size >= 8 && iv.size == 12) { "پارامترهای رمزنگاری فایل نامعتبر هستند." }

        val key = deriveAesKey(password, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(headerBytes)

        val gcmStream = GcmDecryptInputStream(stream, cipher)
        val staged = try {
            GZIPInputStream(gcmStream).use { gzip ->
                InputStreamReader(gzip, Charsets.UTF_8).use { reader ->
                    JsonReader(reader).use { jr ->
                        val parsed = parseStagedFromJsonReader(jr)
                        // Drain remaining GZIP trailer bytes and verify GCM tag explicitly before returning
                        val drainBuf = ByteArray(1024)
                        while (gzip.read(drainBuf) != -1) {
                            // draining trailer
                        }
                        gcmStream.verifyAuthenticatedEof()
                        parsed
                    }
                }
            }
        } catch (e: SolInvalidBackupAuthException) {
            throw IllegalArgumentException("رمز اشتباه است یا فایل پشتیبان آسیب دیده است.", e)
        } catch (t: Throwable) {
            if (isAuthFailure(t)) {
                throw IllegalArgumentException("رمز اشتباه است یا فایل پشتیبان آسیب دیده است.", t)
            }
            throw IllegalArgumentException("ساختار فایل پشتیبان معتبر نیست: ${t.localizedMessage ?: "نامشخص"}", t)
        }
        return staged
    }

    /**
     * Kept for legacy SOL_BACKUP_V2 compatibility & roundtrip testing.
     */
    internal fun encryptPayload(plainJson: String, password: String): String {
        val random = SecureRandom()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val key = deriveAesKey(password, salt, PBKDF2_ITERATIONS_V2)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val cipherBytes = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))

        return JSONObject().apply {
            put("format", "SOL_BACKUP_V2")
            put("alg", "AES-256-GCM")
            put("kdf", KDF_ALGORITHM)
            put("iterations", PBKDF2_ITERATIONS_V2)
            put("saltB64", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("ivB64", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("cipherTextB64", Base64.encodeToString(cipherBytes, Base64.NO_WRAP))
        }.toString(2)
    }

    internal fun decryptPayload(envelopeJson: String, password: String): String {
        val env = JSONObject(envelopeJson)
        require(env.optString("format").startsWith("SOL_BACKUP")) {
            "فرمت این فایل متعلق به پشتیبان Sol نیست."
        }
        val iterations = env.optInt("iterations", PBKDF2_ITERATIONS_V2)
        require(iterations in 1..MAX_KDF_ITERATIONS) {
            "تعداد تکرارهای KDF در فایل پشتیبان بیش از حد مجاز است ($iterations)."
        }
        val salt = Base64.decode(env.getString("saltB64"), Base64.NO_WRAP)
        val iv = Base64.decode(env.getString("ivB64"), Base64.NO_WRAP)
        val cipherBytes = Base64.decode(env.getString("cipherTextB64"), Base64.NO_WRAP)
        val key = deriveAesKey(password, salt, iterations)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val plainBytes = runCatching { cipher.doFinal(cipherBytes) }
            .getOrElse { throw IllegalArgumentException("رمز اشتباه است یا فایل پشتیبان آسیب دیده است.") }
        return String(plainBytes, Charsets.UTF_8)
    }

    private fun deriveAesKey(
        password: String,
        salt: ByteArray,
        iterations: Int,
    ): SecretKeySpec {
        require(iterations in 1..MAX_KDF_ITERATIONS) { "تعداد تکرار KDF غیرمجاز است." }
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, 256)
        return try {
            val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            val encoded = factory.generateSecret(spec).encoded
            try {
                SecretKeySpec(encoded, "AES")
            } finally {
                encoded.fill(0)
            }
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    private fun parseStagedFromJsonReader(jr: JsonReader): StagedBackupPayload {
        val memories = mutableListOf<StagedMemory>()
        val persons = mutableListOf<StagedPerson>()
        val locations = mutableListOf<StagedLocation>()
        val books = mutableListOf<StagedBook>()
        val roles = mutableListOf<StagedRole>()
        val notesTodos = mutableListOf<StagedNoteTodo>()
        var preferences: StagedPreferences? = null

        jr.beginObject()
        while (jr.hasNext()) {
            when (jr.nextName()) {
                "memories" -> readMemories(jr, memories)
                "persons" -> readPersons(jr, persons)
                "locations" -> readLocations(jr, locations)
                "books" -> readBooks(jr, books)
                "roles" -> readRoles(jr, roles)
                "notesTodos" -> readNotesTodos(jr, notesTodos)
                "preferences" -> preferences = readPreferences(jr)
                else -> jr.skipValue()
            }
        }
        jr.endObject()

        return StagedBackupPayload(
            memories = memories,
            persons = persons,
            locations = locations,
            books = books,
            roles = roles,
            notesTodos = notesTodos,
            preferences = preferences,
        )
    }

    private suspend fun applyValidatedBackupInTransaction(
        staged: StagedBackupPayload,
        replaceExisting: Boolean,
        fileSizeBytes: Long,
    ): BackupStats {
        var restoredChunks = 0
        var importedBooks = 0

        database.withTransaction {
            val dao = database.memoryDao()
            if (replaceExisting) {
                dao.clearAllMemories()
                dao.clearAllPersons()
                dao.clearAllLocations()
                dao.clearAllBookChunks()
                dao.clearAllBooks()
                dao.clearAllRoles()
                dao.clearAllNotesAndTodos()
            }

            for (m in staged.memories) {
                memoryRepository.remember(
                    key = m.keyText,
                    value = m.valueText,
                    kind = m.kind,
                )
            }

            for (p in staged.persons) {
                personRepository.savePerson(
                    name = p.name,
                    phone = p.phone,
                    relation = p.relation,
                    callScriptNote = p.callScriptNote,
                )
            }

            for (l in staged.locations) {
                locationRepository.saveLocation(
                    alias = l.alias,
                    fullName = l.fullName,
                    addressNote = l.addressNote,
                )
            }

            val existingTitles = knowledgeRepository.getAllBooksOnce().map { it.title }.toHashSet()
            for (b in staged.books) {
                if (!replaceExisting && b.title in existingTitles) continue
                existingTitles.add(b.title)
                importedBooks++

                val newBookId = knowledgeRepository.saveBook(
                    BookKnowledgeEntity(
                        title = b.title,
                        author = b.author,
                        subjects = b.subjects,
                        summaryText = b.summaryText,
                        voiceNotes = b.voiceNotes,
                        sourceType = b.sourceType,
                        sourceUrl = b.sourceUrl,
                    )
                )
                val chunks = b.chunks.mapIndexed { idx, c ->
                    BookChunkEntity(
                        bookId = newBookId,
                        bookTitle = b.title,
                        chunkIndex = if (c.chunkIndex >= 0) c.chunkIndex else idx,
                        contentText = c.contentText,
                        normalizedTokens = c.normalizedTokens,
                    )
                }
                if (chunks.isNotEmpty()) {
                    knowledgeRepository.replaceChunksForBook(newBookId, chunks)
                    restoredChunks += chunks.size
                }
            }

            for (r in staged.roles) {
                knowledgeRepository.saveRole(
                    roleName = r.roleName,
                    instructions = r.instructions,
                    linkedBookTitle = r.linkedBookTitle,
                    isActive = r.isActive,
                )
            }

            if (noteTodoRepository != null) {
                for (nt in staged.notesTodos) {
                    val saved = noteTodoRepository.addItem(kind = nt.kind, content = nt.content)
                    if (nt.isDone) {
                        noteTodoRepository.markDone(saved.id, true)
                    }
                }
            }
        }

        // Apply preferences only after Room transaction succeeds cleanly
        staged.preferences?.let { p ->
            val current = preferencesRepository.getSnapshot()
            preferencesRepository.restoreSnapshot(
                SolPreferences(
                    ownerName = p.ownerName?.takeIf { it.isNotBlank() } ?: current.ownerName,
                    voicePersona = p.voicePersona?.let { runCatching { VoicePersona.valueOf(it) }.getOrNull() }
                        ?: current.voicePersona,
                    preferredTtsEnginePackage = p.preferredTtsEnginePackage ?: current.preferredTtsEnginePackage,
                    preferredTtsVoiceName = p.preferredTtsVoiceName ?: current.preferredTtsVoiceName,
                    speechRate = p.speechRate?.toFloat()?.coerceIn(0.6f, 1.5f) ?: current.speechRate,
                    autoSpeak = p.autoSpeak ?: current.autoSpeak,
                    invocationMode = p.invocationMode?.let { runCatching { InvocationMode.valueOf(it) }.getOrNull() }
                        ?: current.invocationMode,
                    deliveryPreference = p.deliveryPreference?.let { runCatching { DeliveryPreference.valueOf(it) }.getOrNull() }
                        ?: current.deliveryPreference,
                    confirmationPolicy = p.confirmationPolicy?.let { runCatching { ConfirmationPolicy.valueOf(it) }.getOrNull() }
                        ?: current.confirmationPolicy,
                    darkTheme = p.darkTheme ?: current.darkTheme,
                    confirmedCurrentLocation = p.confirmedCurrentLocation ?: current.confirmedCurrentLocation,
                    confirmedCurrentLocationUpdatedAt = p.confirmedCurrentLocationUpdatedAt
                        ?: current.confirmedCurrentLocationUpdatedAt,
                    voskOfflineEnabled = p.voskOfflineEnabled ?: current.voskOfflineEnabled,
                    wakeWordEnabled = p.wakeWordEnabled ?: current.wakeWordEnabled,
                    slmSynthesisEnabled = p.slmSynthesisEnabled ?: current.slmSynthesisEnabled,
                    slmPreferGpu = p.slmPreferGpu ?: current.slmPreferGpu,
                    historyRetentionMode = p.historyRetentionMode?.let {
                        runCatching { HistoryRetentionMode.valueOf(it) }.getOrNull()
                    } ?: current.historyRetentionMode,
                    privateModeEnabled = p.privateModeEnabled ?: current.privateModeEnabled,
                )
            )
        }

        return BackupStats(
            memoriesCount = staged.memories.size,
            personsCount = staged.persons.size,
            locationsCount = staged.locations.size,
            booksCount = importedBooks,
            chunksCount = restoredChunks,
            rolesCount = staged.roles.size,
            fileSizeBytes = fileSizeBytes,
            notesTodosCount = staged.notesTodos.size,
        )
    }

    private fun readMemories(jr: JsonReader, out: MutableList<StagedMemory>) {
        jr.beginArray()
        while (jr.hasNext()) {
            var kind = "fact"
            var key = ""
            var value = ""
            jr.beginObject()
            while (jr.hasNext()) {
                when (jr.nextName()) {
                    "kind" -> kind = nextStringOrEmpty(jr)
                    "keyText" -> key = nextStringOrEmpty(jr)
                    "valueText" -> value = nextStringOrEmpty(jr)
                    else -> jr.skipValue()
                }
            }
            jr.endObject()
            require(key.isNotBlank()) { "کلید حافظه در پشتیبان نامعتبر است." }
            out.add(StagedMemory(kind.ifBlank { "fact" }, key, value))
        }
        jr.endArray()
    }

    private fun readPersons(jr: JsonReader, out: MutableList<StagedPerson>) {
        jr.beginArray()
        while (jr.hasNext()) {
            var name = ""
            var phone = ""
            var relation = ""
            var callNote = ""
            jr.beginObject()
            while (jr.hasNext()) {
                when (jr.nextName()) {
                    "name" -> name = nextStringOrEmpty(jr)
                    "phone" -> phone = nextStringOrEmpty(jr)
                    "relation" -> relation = nextStringOrEmpty(jr)
                    "callScriptNote" -> callNote = nextStringOrEmpty(jr)
                    else -> jr.skipValue()
                }
            }
            jr.endObject()
            require(name.isNotBlank()) { "نام مخاطب در پشتیبان خالی است." }
            out.add(StagedPerson(name, phone, relation, callNote))
        }
        jr.endArray()
    }

    private fun readLocations(jr: JsonReader, out: MutableList<StagedLocation>) {
        jr.beginArray()
        while (jr.hasNext()) {
            var alias = ""
            var fullName = ""
            var addressNote = ""
            jr.beginObject()
            while (jr.hasNext()) {
                when (jr.nextName()) {
                    "alias" -> alias = nextStringOrEmpty(jr)
                    "fullName" -> fullName = nextStringOrEmpty(jr)
                    "addressNote" -> addressNote = nextStringOrEmpty(jr)
                    else -> jr.skipValue()
                }
            }
            jr.endObject()
            require(alias.isNotBlank() && fullName.isNotBlank()) { "رکورد مکان در پشتیبان ناقص است." }
            out.add(StagedLocation(alias, fullName, addressNote))
        }
        jr.endArray()
    }

    private fun readBooks(jr: JsonReader, out: MutableList<StagedBook>) {
        jr.beginArray()
        while (jr.hasNext()) {
            var title = ""
            var author = ""
            var subjects = ""
            var summaryText = ""
            var voiceNotes = ""
            var sourceType = "BACKUP"
            var sourceUrl = ""
            val chunks = mutableListOf<StagedBookChunk>()
            jr.beginObject()
            while (jr.hasNext()) {
                when (jr.nextName()) {
                    "title" -> title = nextStringOrEmpty(jr)
                    "author" -> author = nextStringOrEmpty(jr)
                    "subjects" -> subjects = nextStringOrEmpty(jr)
                    "summaryText" -> summaryText = nextStringOrEmpty(jr)
                    "voiceNotes" -> voiceNotes = nextStringOrEmpty(jr)
                    "sourceType" -> sourceType = nextStringOrEmpty(jr).ifBlank { "BACKUP" }
                    "sourceUrl" -> sourceUrl = nextStringOrEmpty(jr)
                    "chunks" -> readBookChunks(jr, chunks)
                    else -> jr.skipValue()
                }
            }
            jr.endObject()
            require(title.isNotBlank()) { "عنوان کتاب در پشتیبان خالی است." }
            out.add(StagedBook(title, author, subjects, summaryText, voiceNotes, sourceType, sourceUrl, chunks))
        }
        jr.endArray()
    }

    private fun readBookChunks(jr: JsonReader, out: MutableList<StagedBookChunk>) {
        jr.beginArray()
        while (jr.hasNext()) {
            var idx = -1
            var contentText = ""
            var normalizedTokens = ""
            jr.beginObject()
            while (jr.hasNext()) {
                when (jr.nextName()) {
                    "chunkIndex" -> idx = nextIntOrDefault(jr, -1)
                    "contentText" -> contentText = nextStringOrEmpty(jr)
                    "normalizedTokens" -> normalizedTokens = nextStringOrEmpty(jr)
                    else -> jr.skipValue()
                }
            }
            jr.endObject()
            require(contentText.isNotBlank()) { "متن بخش کتاب در پشتیبان خالی است." }
            out.add(StagedBookChunk(idx, contentText, normalizedTokens))
        }
        jr.endArray()
    }

    private fun readRoles(jr: JsonReader, out: MutableList<StagedRole>) {
        jr.beginArray()
        while (jr.hasNext()) {
            var roleName = ""
            var instructions = ""
            var linkedBookTitle = ""
            var isActive = true
            jr.beginObject()
            while (jr.hasNext()) {
                when (jr.nextName()) {
                    "roleName" -> roleName = nextStringOrEmpty(jr)
                    "instructions" -> instructions = nextStringOrEmpty(jr)
                    "linkedBookTitle" -> linkedBookTitle = nextStringOrEmpty(jr)
                    "isActive" -> isActive = nextBooleanOrDefault(jr, true)
                    else -> jr.skipValue()
                }
            }
            jr.endObject()
            require(roleName.isNotBlank()) { "نام نقش در پشتیبان خالی است." }
            out.add(StagedRole(roleName, instructions, linkedBookTitle, isActive))
        }
        jr.endArray()
    }

    private fun readNotesTodos(jr: JsonReader, out: MutableList<StagedNoteTodo>) {
        jr.beginArray()
        while (jr.hasNext()) {
            var kind = "NOTE"
            var content = ""
            var isDone = false
            jr.beginObject()
            while (jr.hasNext()) {
                when (jr.nextName()) {
                    "kind" -> kind = nextStringOrEmpty(jr).ifBlank { "NOTE" }
                    "content" -> content = nextStringOrEmpty(jr)
                    "isDone" -> isDone = nextBooleanOrDefault(jr, false)
                    else -> jr.skipValue()
                }
            }
            jr.endObject()
            if (content.isNotBlank()) {
                out.add(StagedNoteTodo(kind, content, isDone))
            }
        }
        jr.endArray()
    }

    private fun readPreferences(jr: JsonReader): StagedPreferences? {
        if (jr.peek() == JsonToken.NULL) {
            jr.nextNull()
            return null
        }
        var ownerName: String? = null
        var voicePersona: String? = null
        var ttsPkg: String? = null
        var ttsVoice: String? = null
        var speechRate: Double? = null
        var autoSpeak: Boolean? = null
        var invocationMode: String? = null
        var deliveryPreference: String? = null
        var confirmationPolicy: String? = null
        var darkTheme: Boolean? = null
        var confirmedCurrentLocation: String? = null
        var confirmedLocationUpdatedAt: Long? = null
        var voskOfflineEnabled: Boolean? = null
        var wakeWordEnabled: Boolean? = null
        var slmSynthesisEnabled: Boolean? = null
        var slmPreferGpu: Boolean? = null
        var historyRetentionMode: String? = null
        var privateModeEnabled: Boolean? = null

        jr.beginObject()
        while (jr.hasNext()) {
            when (jr.nextName()) {
                "ownerName" -> ownerName = nextStringOrNull(jr)
                "voicePersona" -> voicePersona = nextStringOrNull(jr)
                "preferredTtsEnginePackage" -> ttsPkg = nextStringOrNull(jr)
                "preferredTtsVoiceName" -> ttsVoice = nextStringOrNull(jr)
                "speechRate" -> speechRate = nextDoubleOrNull(jr)
                "autoSpeak" -> autoSpeak = nextBooleanOrNull(jr)
                "invocationMode" -> invocationMode = nextStringOrNull(jr)
                "deliveryPreference" -> deliveryPreference = nextStringOrNull(jr)
                "confirmationPolicy" -> confirmationPolicy = nextStringOrNull(jr)
                "darkTheme" -> darkTheme = nextBooleanOrNull(jr)
                "confirmedCurrentLocation" -> confirmedCurrentLocation = nextStringOrNull(jr)
                "confirmedCurrentLocationUpdatedAt" -> confirmedLocationUpdatedAt = nextLongOrNull(jr)
                "voskOfflineEnabled" -> voskOfflineEnabled = nextBooleanOrNull(jr)
                "wakeWordEnabled" -> wakeWordEnabled = nextBooleanOrNull(jr)
                "slmSynthesisEnabled" -> slmSynthesisEnabled = nextBooleanOrNull(jr)
                "slmPreferGpu" -> slmPreferGpu = nextBooleanOrNull(jr)
                "historyRetentionMode" -> historyRetentionMode = nextStringOrNull(jr)
                "privateModeEnabled" -> privateModeEnabled = nextBooleanOrNull(jr)
                else -> jr.skipValue()
            }
        }
        jr.endObject()

        return StagedPreferences(
            ownerName = ownerName,
            voicePersona = voicePersona,
            preferredTtsEnginePackage = ttsPkg,
            preferredTtsVoiceName = ttsVoice,
            speechRate = speechRate,
            autoSpeak = autoSpeak,
            invocationMode = invocationMode,
            deliveryPreference = deliveryPreference,
            confirmationPolicy = confirmationPolicy,
            darkTheme = darkTheme,
            confirmedCurrentLocation = confirmedCurrentLocation,
            confirmedCurrentLocationUpdatedAt = confirmedLocationUpdatedAt,
            voskOfflineEnabled = voskOfflineEnabled,
            wakeWordEnabled = wakeWordEnabled,
            slmSynthesisEnabled = slmSynthesisEnabled,
            slmPreferGpu = slmPreferGpu,
            historyRetentionMode = historyRetentionMode,
            privateModeEnabled = privateModeEnabled,
        )
    }

    private fun nextStringOrEmpty(jr: JsonReader): String =
        if (jr.peek() == JsonToken.NULL) {
            jr.nextNull()
            ""
        } else {
            jr.nextString()
        }

    private fun nextStringOrNull(jr: JsonReader): String? =
        if (jr.peek() == JsonToken.NULL) {
            jr.nextNull()
            null
        } else {
            jr.nextString()
        }

    private fun nextIntOrDefault(jr: JsonReader, default: Int): Int =
        if (jr.peek() == JsonToken.NULL) {
            jr.nextNull()
            default
        } else {
            jr.nextInt()
        }

    private fun nextLongOrNull(jr: JsonReader): Long? =
        if (jr.peek() == JsonToken.NULL) {
            jr.nextNull()
            null
        } else {
            jr.nextLong()
        }

    private fun nextDoubleOrNull(jr: JsonReader): Double? =
        if (jr.peek() == JsonToken.NULL) {
            jr.nextNull()
            null
        } else {
            jr.nextDouble()
        }

    private fun nextBooleanOrDefault(jr: JsonReader, default: Boolean): Boolean =
        if (jr.peek() == JsonToken.NULL) {
            jr.nextNull()
            default
        } else {
            jr.nextBoolean()
        }

    private fun nextBooleanOrNull(jr: JsonReader): Boolean? =
        if (jr.peek() == JsonToken.NULL) {
            jr.nextNull()
            null
        } else {
            jr.nextBoolean()
        }

    private suspend fun estimateBackupPayloadBytes(): Long {
        val dbFile = context.getDatabasePath("sol_memory.db")
        val dbSize = if (dbFile.isFile) dbFile.length() else 0L
        val chunksCount = knowledgeRepository.getAllChunksOnce().size.toLong()
        return maxOf(dbSize, chunksCount * 700L, 64L * 1024L)
    }

    private fun readLineUtf8(
        stream: InputStream,
        maxBytes: Int,
        mirror: ByteArrayOutputStream? = null,
    ): String {
        val baos = ByteArrayOutputStream()
        while (baos.size() < maxBytes) {
            val b = stream.read()
            if (b == -1) break
            mirror?.write(b)
            if (b == '\n'.code) break
            if (b != '\r'.code) baos.write(b)
        }
        return String(baos.toByteArray(), Charsets.UTF_8)
    }

    private fun readAllBounded(stream: InputStream, maxBytes: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (baos.size() <= maxBytes) {
            val n = stream.read(buf)
            if (n == -1) return baos.toByteArray()
            if (baos.size() + n > maxBytes) {
                throw IllegalArgumentException("حجم فایل پشتیبان قدیمی V2 بیش از حد مجاز است.")
            }
            baos.write(buf, 0, n)
        }
        return baos.toByteArray()
    }

    private fun isAuthFailure(t: Throwable?): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 10) {
            val name = cur.javaClass.name
            if (name.contains("AEADBadTagException") ||
                name.contains("BadPaddingException") ||
                cur is SolInvalidBackupAuthException
            ) {
                return true
            }
            cur = cur.cause
            depth++
        }
        return false
    }

    private class SolInvalidBackupAuthException(cause: Throwable? = null) :
        Exception("GCM authentication tag mismatch", cause)

    private class CountingOutputStream(target: OutputStream) : FilterOutputStream(target) {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            bytesWritten += len
        }
    }

    private class CountingInputStream(source: InputStream) : FilterInputStream(source) {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b != -1) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) bytesRead += n
            return n
        }
    }

    private class NonClosingOutputStream(target: OutputStream) : FilterOutputStream(target) {
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
        }

        override fun close() {
            out.flush()
        }
    }

    /**
     * Explicit streaming AES-GCM decryptor that never swallows AEADBadTagException across Android / JVM providers.
     */
    private class GcmDecryptInputStream(
        private val upstream: InputStream,
        private val cipher: Cipher,
    ) : InputStream() {
        private val inBuf = ByteArray(16 * 1024)
        private var outBuf: ByteArray = ByteArray(0)
        private var outPos: Int = 0
        private var eofVerified: Boolean = false

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n == -1) -1 else (single[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (outPos >= outBuf.size) {
                if (eofVerified) return -1
                refillBuffer()
            }
            val avail = outBuf.size - outPos
            val copy = minOf(len, avail)
            System.arraycopy(outBuf, outPos, b, off, copy)
            outPos += copy
            return copy
        }

        private fun refillBuffer() {
            while (true) {
                val read = upstream.read(inBuf)
                if (read == -1) {
                    verifyAuthenticatedEof()
                    return
                }
                val dec = cipher.update(inBuf, 0, read)
                if (dec != null && dec.isNotEmpty()) {
                    outBuf = dec
                    outPos = 0
                    return
                }
            }
        }

        fun verifyAuthenticatedEof() {
            if (eofVerified) return
            val tail = try {
                cipher.doFinal()
            } catch (t: Throwable) {
                throw SolInvalidBackupAuthException(t)
            }
            eofVerified = true
            outBuf = tail ?: ByteArray(0)
            outPos = 0
        }
    }

    companion object {
        const val FORMAT_V3 = "SOL_BACKUP_V3"
        private const val MAGIC_LINE_V3 = "SOL_BACKUP_V3"
        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val PBKDF2_ITERATIONS_V3 = 600_000
        private const val PBKDF2_ITERATIONS_V2 = 100_000
        const val MAX_KDF_ITERATIONS = 5_000_000
        const val MIN_EXPORT_PASSWORD_LENGTH = 8
        private const val MAX_MAGIC_LINE_BYTES = 256
        private const val MAX_HEADER_LINE_BYTES = 16 * 1024
        private const val MAX_LEGACY_V2_BYTES = 64 * 1024 * 1024
    }
}
