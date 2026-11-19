package com.example.sol.security

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.sol.memory.BookChunkEntity
import com.example.sol.memory.BookKnowledgeEntity
import com.example.sol.memory.ConfirmationPolicy
import com.example.sol.memory.DeliveryPreference
import com.example.sol.memory.InvocationMode
import com.example.sol.memory.KnowledgeRepository
import com.example.sol.memory.LocationRepository
import com.example.sol.memory.MemoryRepository
import com.example.sol.memory.PersonRepository
import com.example.sol.memory.PreferencesRepository
import com.example.sol.memory.SolPreferences
import com.example.sol.memory.VoicePersona
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
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
)

class EncryptedBackupManager(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val personRepository: PersonRepository,
    private val locationRepository: LocationRepository,
    private val knowledgeRepository: KnowledgeRepository,
    private val preferencesRepository: PreferencesRepository,
) {
    suspend fun exportEncryptedBackup(uri: Uri, password: String): Result<BackupStats> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(password.length >= 4) { "رمز فایل پشتیبان باید حداقل ۴ کاراکتر باشد." }
                val (jsonPayload, stats) = buildPlainJsonPayload()
                val encryptedEnvelope = encryptPayload(jsonPayload, password)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(encryptedEnvelope.toByteArray(Charsets.UTF_8))
                } ?: error("دسترسی به مسیر ذخیره فایل ممکن نشد.")
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
            val envelopeText = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            } ?: error("خواندن فایل پشتیبان ممکن نشد.")

            val plainJson = decryptPayload(envelopeText, password)
            restoreFromPlainJson(plainJson, replaceExisting)
        }
    }

    /**
     * Exposed internally for unit testing encryption/decryption roundtrips cleanly.
     */
    internal fun encryptPayload(plainJson: String, password: String): String {
        val random = SecureRandom()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val key = deriveAesKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val cipherBytes = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))

        return JSONObject().apply {
            put("format", "SOL_BACKUP_V2")
            put("alg", "AES-256-GCM")
            put("kdf", "PBKDF2WithHmacSHA256")
            put("iterations", PBKDF2_ITERATIONS)
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
        val salt = Base64.decode(env.getString("saltB64"), Base64.NO_WRAP)
        val iv = Base64.decode(env.getString("ivB64"), Base64.NO_WRAP)
        val cipherBytes = Base64.decode(env.getString("cipherTextB64"), Base64.NO_WRAP)
        val iterations = env.optInt("iterations", PBKDF2_ITERATIONS)
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
        iterations: Int = PBKDF2_ITERATIONS,
    ): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val secret = factory.generateSecret(spec).encoded
        return SecretKeySpec(secret, "AES")
    }

    private suspend fun buildPlainJsonPayload(): Pair<String, BackupStats> {
        val memories = memoryRepository.getAllOnce()
        val persons = personRepository.getAllOnce()
        val locations = locationRepository.getAllOnce()
        val books = knowledgeRepository.getAllBooksOnce()
        val chunks = knowledgeRepository.getAllChunksOnce()
        val roles = knowledgeRepository.getAllRolesOnce()
        val prefs = preferencesRepository.getSnapshot()

        val chunksByBook = chunks.groupBy { it.bookId }

        val root = JSONObject().apply {
            put("version", 2)
            put("exportedAt", System.currentTimeMillis())
            put("preferences", JSONObject().apply {
                put("ownerName", prefs.ownerName)
                put("voicePersona", prefs.voicePersona.name)
                put("preferredTtsEnginePackage", prefs.preferredTtsEnginePackage)
                put("preferredTtsVoiceName", prefs.preferredTtsVoiceName)
                put("speechRate", prefs.speechRate.toDouble())
                put("autoSpeak", prefs.autoSpeak)
                put("invocationMode", prefs.invocationMode.name)
                put("deliveryPreference", prefs.deliveryPreference.name)
                put("confirmationPolicy", prefs.confirmationPolicy.name)
                put("darkTheme", prefs.darkTheme)
                put("confirmedCurrentLocation", prefs.confirmedCurrentLocation)
                put("confirmedCurrentLocationUpdatedAt", prefs.confirmedCurrentLocationUpdatedAt)
                put("voskOfflineEnabled", prefs.voskOfflineEnabled)
                put("wakeWordEnabled", prefs.wakeWordEnabled)
                put("slmSynthesisEnabled", prefs.slmSynthesisEnabled)
                put("slmPreferGpu", prefs.slmPreferGpu)
            })
            put("memories", JSONArray().apply {
                memories.forEach { m ->
                    put(JSONObject().apply {
                        put("kind", m.kind)
                        put("keyText", m.keyText)
                        put("valueText", m.valueText)
                    })
                }
            })
            put("persons", JSONArray().apply {
                persons.forEach { p ->
                    put(JSONObject().apply {
                        put("name", p.name)
                        put("phone", p.phone)
                        put("relation", p.relation)
                        put("callScriptNote", p.callScriptNote)
                    })
                }
            })
            put("locations", JSONArray().apply {
                locations.forEach { l ->
                    put(JSONObject().apply {
                        put("alias", l.alias)
                        put("fullName", l.fullName)
                        put("addressNote", l.addressNote)
                    })
                }
            })
            put("books", JSONArray().apply {
                books.forEach { b ->
                    put(JSONObject().apply {
                        put("title", b.title)
                        put("author", b.author)
                        put("subjects", b.subjects)
                        put("summaryText", b.summaryText)
                        put("voiceNotes", b.voiceNotes)
                        put("sourceType", b.sourceType)
                        put("sourceUrl", b.sourceUrl)
                        put("chunks", JSONArray().apply {
                            chunksByBook[b.id].orEmpty().forEach { c ->
                                put(JSONObject().apply {
                                    put("chunkIndex", c.chunkIndex)
                                    put("contentText", c.contentText)
                                    put("normalizedTokens", c.normalizedTokens)
                                })
                            }
                        })
                    })
                }
            })
            put("roles", JSONArray().apply {
                roles.forEach { r ->
                    put(JSONObject().apply {
                        put("roleName", r.roleName)
                        put("instructions", r.instructions)
                        put("linkedBookTitle", r.linkedBookTitle)
                        put("isActive", r.isActive)
                    })
                }
            })
        }

        val stats = BackupStats(
            memoriesCount = memories.size,
            personsCount = persons.size,
            locationsCount = locations.size,
            booksCount = books.size,
            chunksCount = chunks.size,
            rolesCount = roles.size,
        )
        return root.toString() to stats
    }

    private suspend fun restoreFromPlainJson(plainJson: String, replaceExisting: Boolean): BackupStats {
        val root = JSONObject(plainJson)

        if (replaceExisting) {
            memoryRepository.clearAll()
            personRepository.getAllOnce().forEach { personRepository.deletePerson(it.id) }
            locationRepository.getAllOnce().forEach { locationRepository.deleteLocation(it.id) }
            knowledgeRepository.getAllBooksOnce().forEach { knowledgeRepository.deleteBook(it.id) }
            knowledgeRepository.getAllRolesOnce().forEach { knowledgeRepository.deleteRole(it.id) }
        }

        val memArr = root.optJSONArray("memories") ?: JSONArray()
        for (i in 0 until memArr.length()) {
            val obj = memArr.getJSONObject(i)
            memoryRepository.remember(
                key = obj.getString("keyText"),
                value = obj.getString("valueText"),
                kind = obj.optString("kind", "fact"),
            )
        }

        val personArr = root.optJSONArray("persons") ?: JSONArray()
        for (i in 0 until personArr.length()) {
            val obj = personArr.getJSONObject(i)
            personRepository.savePerson(
                name = obj.getString("name"),
                phone = obj.optString("phone"),
                relation = obj.optString("relation"),
                callScriptNote = obj.optString("callScriptNote"),
            )
        }

        val locArr = root.optJSONArray("locations") ?: JSONArray()
        for (i in 0 until locArr.length()) {
            val obj = locArr.getJSONObject(i)
            locationRepository.saveLocation(
                alias = obj.getString("alias"),
                fullName = obj.getString("fullName"),
                addressNote = obj.optString("addressNote"),
            )
        }

        var restoredChunks = 0
        val existingTitles = knowledgeRepository.getAllBooksOnce().map { it.title }.toSet()
        val booksArr = root.optJSONArray("books") ?: JSONArray()
        for (i in 0 until booksArr.length()) {
            val obj = booksArr.getJSONObject(i)
            val title = obj.getString("title")
            if (!replaceExisting && title in existingTitles) continue

            val newBookId = knowledgeRepository.saveBook(
                BookKnowledgeEntity(
                    title = title,
                    author = obj.optString("author"),
                    subjects = obj.optString("subjects"),
                    summaryText = obj.optString("summaryText"),
                    voiceNotes = obj.optString("voiceNotes"),
                    sourceType = obj.optString("sourceType", "BACKUP"),
                    sourceUrl = obj.optString("sourceUrl"),
                )
            )

            val chunkArr = obj.optJSONArray("chunks") ?: JSONArray()
            val chunks = buildList {
                for (j in 0 until chunkArr.length()) {
                    val c = chunkArr.getJSONObject(j)
                    add(
                        BookChunkEntity(
                            bookId = newBookId,
                            bookTitle = title,
                            chunkIndex = c.optInt("chunkIndex", j),
                            contentText = c.getString("contentText"),
                            normalizedTokens = c.optString("normalizedTokens"),
                        )
                    )
                }
            }
            if (chunks.isNotEmpty()) {
                knowledgeRepository.replaceChunksForBook(newBookId, chunks)
                restoredChunks += chunks.size
            }
        }

        val rolesArr = root.optJSONArray("roles") ?: JSONArray()
        for (i in 0 until rolesArr.length()) {
            val obj = rolesArr.getJSONObject(i)
            knowledgeRepository.saveRole(
                roleName = obj.getString("roleName"),
                instructions = obj.optString("instructions"),
                linkedBookTitle = obj.optString("linkedBookTitle"),
                isActive = obj.optBoolean("isActive", true),
            )
        }

        root.optJSONObject("preferences")?.let { p ->
            val current = preferencesRepository.getSnapshot()
            preferencesRepository.restoreSnapshot(
                SolPreferences(
                    ownerName = p.optString("ownerName", current.ownerName),
                    voicePersona = runCatching {
                        VoicePersona.valueOf(p.optString("voicePersona"))
                    }.getOrDefault(current.voicePersona),
                    preferredTtsEnginePackage = p.optString("preferredTtsEnginePackage", current.preferredTtsEnginePackage),
                    preferredTtsVoiceName = p.optString("preferredTtsVoiceName", current.preferredTtsVoiceName),
                    speechRate = p.optDouble("speechRate", current.speechRate.toDouble()).toFloat(),
                    autoSpeak = p.optBoolean("autoSpeak", current.autoSpeak),
                    invocationMode = runCatching {
                        InvocationMode.valueOf(p.optString("invocationMode"))
                    }.getOrDefault(current.invocationMode),
                    deliveryPreference = runCatching {
                        DeliveryPreference.valueOf(p.optString("deliveryPreference"))
                    }.getOrDefault(current.deliveryPreference),
                    confirmationPolicy = runCatching {
                        ConfirmationPolicy.valueOf(p.optString("confirmationPolicy"))
                    }.getOrDefault(current.confirmationPolicy),
                    darkTheme = p.optBoolean("darkTheme", current.darkTheme),
                    confirmedCurrentLocation = p.optString("confirmedCurrentLocation", current.confirmedCurrentLocation),
                    confirmedCurrentLocationUpdatedAt = p.optLong(
                        "confirmedCurrentLocationUpdatedAt",
                        current.confirmedCurrentLocationUpdatedAt,
                    ),
                    voskOfflineEnabled = p.optBoolean("voskOfflineEnabled", current.voskOfflineEnabled),
                    wakeWordEnabled = p.optBoolean("wakeWordEnabled", current.wakeWordEnabled),
                    slmSynthesisEnabled = p.optBoolean("slmSynthesisEnabled", current.slmSynthesisEnabled),
                    slmPreferGpu = p.optBoolean("slmPreferGpu", current.slmPreferGpu),
                )
            )
        }

        return BackupStats(
            memoriesCount = memArr.length(),
            personsCount = personArr.length(),
            locationsCount = locArr.length(),
            booksCount = booksArr.length(),
            chunksCount = restoredChunks,
            rolesCount = rolesArr.length(),
        )
    }

    companion object {
        private const val PBKDF2_ITERATIONS = 100_000
    }
}
