package com.example.sol.memory

import com.example.sol.text.PersianTextNormalizer
import kotlinx.coroutines.flow.Flow

object PersianNormalizer {
    fun normalize(raw: String): String = PersianTextNormalizer.normalizeForMatching(raw)

    fun cleanText(raw: String): String = PersianTextNormalizer.cleanDisplayText(raw)
}

class MemoryRepository(private val dao: MemoryDao) {
    val allMemories: Flow<List<MemoryFactEntity>> = dao.observeAllMemories()

    suspend fun getAllOnce(): List<MemoryFactEntity> = dao.getAllMemoriesOnce()

    suspend fun remember(key: String, value: String, kind: String = "fact") {
        val existing = dao.findByKey(key)
        dao.upsertMemory(
            MemoryFactEntity(
                id = existing?.id ?: 0,
                kind = kind,
                keyText = key,
                valueText = value,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun updateMemory(item: MemoryFactEntity) {
        dao.upsertMemory(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun forgetByKey(key: String): Boolean = dao.deleteMemoryByKey(key) > 0

    suspend fun forgetById(id: Long): Boolean = dao.deleteMemoryById(id) > 0

    suspend fun clearAll() = dao.clearAllMemories()
}

class PersonRepository(private val dao: MemoryDao) {
    val allPersons: Flow<List<PersonEntity>> = dao.observeAllPersons()

    suspend fun getAllOnce(): List<PersonEntity> = dao.getAllPersonsOnce()

    suspend fun findPerson(queryName: String): PersonEntity? {
        val norm = PersianNormalizer.normalize(queryName)
        val exact = dao.findPersonByNormalizedName(norm)
        if (exact != null) return exact
        return dao.getAllPersonsOnce().firstOrNull {
            it.normalizedName.contains(norm) || norm.contains(it.normalizedName)
        }
    }

    suspend fun savePerson(
        name: String,
        phone: String = "",
        relation: String = "",
        callScriptNote: String = "",
        existingId: Long = 0,
    ) {
        val norm = PersianNormalizer.normalize(name)
        val existing = if (existingId != 0L) null else dao.findPersonByNormalizedName(norm)
        dao.upsertPerson(
            PersonEntity(
                id = if (existingId != 0L) existingId else (existing?.id ?: 0),
                name = name.trim(),
                normalizedName = norm,
                phone = phone.trim().ifBlank { existing?.phone ?: "" },
                relation = relation.trim().ifBlank { existing?.relation ?: "" },
                callScriptNote = callScriptNote.trim().ifBlank { existing?.callScriptNote ?: "" },
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun deletePerson(id: Long) {
        dao.deletePersonById(id)
    }
}

class LocationRepository(private val dao: MemoryDao) {
    val allLocations: Flow<List<LocationEntity>> = dao.observeAllLocations()

    suspend fun getAllOnce(): List<LocationEntity> = dao.getAllLocationsOnce()

    suspend fun findLocation(aliasOrName: String): LocationEntity? {
        val norm = PersianNormalizer.normalize(aliasOrName)
        val exact = dao.findLocationByNormalizedAlias(norm)
        if (exact != null) return exact
        return dao.getAllLocationsOnce().firstOrNull {
            it.normalizedAlias.contains(norm) ||
                norm.contains(it.normalizedAlias) ||
                PersianNormalizer.normalize(it.fullName).contains(norm)
        }
    }

    suspend fun saveLocation(
        alias: String,
        fullName: String,
        addressNote: String = "",
        existingId: Long = 0,
    ) {
        val norm = PersianNormalizer.normalize(alias)
        val existing = if (existingId != 0L) null else dao.findLocationByNormalizedAlias(norm)
        dao.upsertLocation(
            LocationEntity(
                id = if (existingId != 0L) existingId else (existing?.id ?: 0),
                alias = alias.trim(),
                normalizedAlias = norm,
                fullName = fullName.trim(),
                addressNote = addressNote.trim().ifBlank { existing?.addressNote ?: "" },
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun deleteLocation(id: Long) {
        dao.deleteLocationById(id)
    }
}

class HistoryRepository(private val dao: MemoryDao) {
    val history: Flow<List<HistoryEntity>> = dao.observeHistory()

    suspend fun record(
        userText: String,
        solText: String,
        intentAction: String = "GENERAL",
        status: String = "COMPLETED",
    ) {
        dao.insertHistory(
            HistoryEntity(
                userText = userText,
                solText = solText,
                intentAction = intentAction,
                status = status,
            )
        )
    }

    suspend fun recordIfAllowed(
        userText: String,
        solText: String,
        intentAction: String = "GENERAL",
        status: String = "COMPLETED",
        retentionMode: HistoryRetentionMode,
        privateModeEnabled: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (privateModeEnabled || retentionMode == HistoryRetentionMode.OFF) {
            return
        }
        dao.insertHistory(
            HistoryEntity(
                userText = userText,
                solText = solText,
                intentAction = intentAction,
                status = status,
                createdAt = nowMillis,
            )
        )
        enforceRetention(retentionMode, nowMillis)
    }

    suspend fun enforceRetention(
        retentionMode: HistoryRetentionMode,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int {
        return when (retentionMode) {
            HistoryRetentionMode.OFF -> {
                dao.clearHistory()
                0
            }
            HistoryRetentionMode.SEVEN_DAYS -> {
                val cutoff = nowMillis - SEVEN_DAYS_MILLIS
                dao.deleteHistoryOlderThan(cutoff)
            }
            HistoryRetentionMode.TWO_HUNDRED_ITEMS -> {
                dao.trimHistoryKeepingLatest(200)
            }
        }
    }

    suspend fun deleteEntry(id: Long) {
        dao.deleteHistoryById(id)
    }

    suspend fun clearAll() {
        dao.clearHistory()
    }

    suspend fun trimKeepingLatest(keepCount: Int = 200): Int {
        return dao.trimHistoryKeepingLatest(keepCount)
    }

    companion object {
        private const val SEVEN_DAYS_MILLIS = 7L * 24L * 60L * 60L * 1000L
    }
}

class KnowledgeRepository(private val dao: MemoryDao) {
    val books: Flow<List<BookKnowledgeEntity>> = dao.observeBooks()
    val roles: Flow<List<SolRoleEntity>> = dao.observeRoles()
    val backgroundLogs: Flow<List<BackgroundJobLogEntity>> = dao.observeBackgroundLogs()
    val bookChunkCount: Flow<Int> = dao.observeChunkCount()
    val scheduledAlarms: Flow<List<ScheduledAlarmEntity>> = dao.observeScheduledAlarms()

    suspend fun getAllBooksOnce(): List<BookKnowledgeEntity> = dao.getAllBooksOnce()

    suspend fun getAllRolesOnce(): List<SolRoleEntity> = dao.getAllRolesOnce()

    suspend fun getAllChunksOnce(): List<BookChunkEntity> = dao.getAllBookChunksOnce()

    suspend fun getChunksForBookOnce(bookId: Long): List<BookChunkEntity> = dao.getChunksForBookOnce(bookId)

    suspend fun replaceChunksForBook(bookId: Long, chunks: List<BookChunkEntity>) {
        dao.replaceChunksForBook(bookId, chunks)
    }

    suspend fun saveBook(book: BookKnowledgeEntity): Long = dao.upsertBook(book)

    suspend fun attachVoiceNoteToBook(bookId: Long, voiceNote: String) {
        val book = dao.getAllBooksOnce().firstOrNull { it.id == bookId } ?: return
        val combined = listOf(book.voiceNotes, voiceNote.trim())
            .filter { it.isNotBlank() }
            .joinToString("\n• ")
        dao.upsertBook(book.copy(voiceNotes = combined))
    }

    suspend fun deleteBook(id: Long) {
        dao.deleteBookCascade(id)
    }

    suspend fun saveRole(
        roleName: String,
        instructions: String,
        linkedBookTitle: String = "",
        isActive: Boolean = true,
        existingId: Long = 0,
    ): Long {
        val norm = PersianNormalizer.normalize(roleName)
        val existing = if (existingId != 0L) null else dao.findRoleByNormalizedName(norm)
        return dao.upsertRole(
            SolRoleEntity(
                id = if (existingId != 0L) existingId else (existing?.id ?: 0),
                roleName = roleName.trim(),
                normalizedRoleName = norm,
                instructions = instructions.trim(),
                linkedBookTitle = linkedBookTitle.trim().ifBlank { existing?.linkedBookTitle ?: "" },
                isActive = isActive,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun toggleRoleActive(role: SolRoleEntity, active: Boolean) {
        dao.upsertRole(role.copy(isActive = active, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteRole(id: Long) {
        dao.deleteRoleById(id)
    }

    suspend fun logBackgroundJob(
        jobKey: String,
        titleFa: String,
        status: String,
        detailFa: String,
    ) {
        dao.insertBackgroundLog(
            BackgroundJobLogEntity(
                jobKey = jobKey,
                titleFa = titleFa,
                status = status,
                detailFa = detailFa,
            )
        )
    }

    suspend fun clearBackgroundLogs() {
        dao.clearBackgroundLogs()
    }

    suspend fun insertAlarmWithUniqueRequestCode(alarm: ScheduledAlarmEntity): ScheduledAlarmEntity =
        dao.insertAlarmWithUniqueRequestCode(alarm)

    suspend fun saveScheduledAlarm(alarm: ScheduledAlarmEntity): Long = dao.upsertAlarm(alarm)

    suspend fun getAllAlarmsOnce(): List<ScheduledAlarmEntity> = dao.getAllAlarmsOnce()

    suspend fun getPendingAlarmsOnce(): List<ScheduledAlarmEntity> = dao.getPendingAlarmsOnce()

    suspend fun updateAlarmExactFlag(id: Long, isExact: Boolean) = dao.updateAlarmExactFlag(id, isExact)

    suspend fun markAlarmTriggered(requestCode: Int) = dao.markAlarmTriggered(requestCode)

    suspend fun markAlarmTriggeredById(id: Long) = dao.markAlarmTriggeredById(id)

    suspend fun deleteScheduledAlarm(id: Long) = dao.deleteAlarmById(id)

    suspend fun deleteAllUserData() = dao.deleteAllUserData()
}

class UserUtteranceRepository(private val dao: MemoryDao) {
    val allExamples: Flow<List<UserUtteranceExampleEntity>> = dao.observeUserUtteranceExamples()

    suspend fun getAllOnce(): List<UserUtteranceExampleEntity> = dao.getAllUserUtteranceExamplesOnce()

    suspend fun saveExample(
        intent: String,
        text: String,
        slotsJson: String = "{}",
    ): UserUtteranceExampleEntity {
        val cleanText = text.trim()
        val norm = PersianNormalizer.normalize(cleanText)
        val entity = UserUtteranceExampleEntity(
            intent = intent,
            text = cleanText,
            normalizedText = norm,
            slotsJson = slotsJson,
            createdAt = System.currentTimeMillis(),
        )
        val id = dao.insertUserUtteranceExample(entity)
        return entity.copy(id = id)
    }

    suspend fun deleteExample(id: Long): Boolean = dao.deleteUserUtteranceExampleById(id) > 0

    suspend fun clearAll() = dao.clearAllUserUtteranceExamples()
}

class NoteTodoRepository(private val dao: MemoryDao) {
    val allItems: Flow<List<NoteTodoEntity>> = dao.observeNotesAndTodos()

    suspend fun getAllOnce(): List<NoteTodoEntity> = dao.getAllNotesAndTodosOnce()

    suspend fun getByKindOnce(kind: String): List<NoteTodoEntity> = dao.getNotesAndTodosByKindOnce(kind)

    suspend fun addItem(kind: String, content: String): NoteTodoEntity {
        val clean = content.trim()
        val now = System.currentTimeMillis()
        val item = NoteTodoEntity(
            kind = kind,
            content = clean,
            isDone = false,
            createdAt = now,
            updatedAt = now,
        )
        val id = dao.upsertNoteTodo(item)
        return item.copy(id = id)
    }

    suspend fun markDone(id: Long, isDone: Boolean = true): Boolean =
        dao.setNoteTodoDone(id, isDone, System.currentTimeMillis()) > 0

    suspend fun deleteById(id: Long): Boolean = dao.deleteNoteTodoById(id) > 0

    suspend fun clearAll() = dao.clearAllNotesAndTodos()
}
