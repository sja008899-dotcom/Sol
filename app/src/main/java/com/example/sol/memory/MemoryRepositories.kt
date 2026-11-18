package com.example.sol.memory

import kotlinx.coroutines.flow.Flow

object PersianNormalizer {
    fun normalize(raw: String): String {
        return raw.trim()
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .replace('ة', 'ه')
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('ؤ', 'و')
            .replace('\u200C', ' ')
            .replace(Regex("[۰-۹]")) { (it.value[0] - '۰').toString() }
            .replace(Regex("[٠-٩]")) { (it.value[0] - '٠').toString() }
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }

    fun cleanText(raw: String): String {
        return raw.trim()
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .replace(Regex("[۰-۹]")) { (it.value[0] - '۰').toString() }
            .replace(Regex("[٠-٩]")) { (it.value[0] - '٠').toString() }
            .replace(Regex("\\s+"), " ")
    }
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

    suspend fun deleteEntry(id: Long) {
        dao.deleteHistoryById(id)
    }

    suspend fun clearAll() {
        dao.clearHistory()
    }
}
