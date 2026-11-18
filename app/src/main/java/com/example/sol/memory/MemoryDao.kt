package com.example.sol.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    // --- Memories (Aliases, Facts, Instructions) ---
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun observeAllMemories(): Flow<List<MemoryFactEntity>>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    suspend fun getAllMemoriesOnce(): List<MemoryFactEntity>

    @Query("SELECT * FROM memories WHERE keyText = :key LIMIT 1")
    suspend fun findByKey(key: String): MemoryFactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(item: MemoryFactEntity): Long

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Long): Int

    @Query("DELETE FROM memories WHERE keyText = :key")
    suspend fun deleteMemoryByKey(key: String): Int

    @Query("DELETE FROM memories")
    suspend fun clearAllMemories()

    // --- Persons ---
    @Query("SELECT * FROM persons ORDER BY updatedAt DESC")
    fun observeAllPersons(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM persons ORDER BY updatedAt DESC")
    suspend fun getAllPersonsOnce(): List<PersonEntity>

    @Query("SELECT * FROM persons WHERE normalizedName = :normalized LIMIT 1")
    suspend fun findPersonByNormalizedName(normalized: String): PersonEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPerson(person: PersonEntity): Long

    @Query("DELETE FROM persons WHERE id = :id")
    suspend fun deletePersonById(id: Long): Int

    // --- Saved Locations ---
    @Query("SELECT * FROM locations ORDER BY updatedAt DESC")
    fun observeAllLocations(): Flow<List<LocationEntity>>

    @Query("SELECT * FROM locations ORDER BY updatedAt DESC")
    suspend fun getAllLocationsOnce(): List<LocationEntity>

    @Query("SELECT * FROM locations WHERE normalizedAlias = :normalized LIMIT 1")
    suspend fun findLocationByNormalizedAlias(normalized: String): LocationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocation(location: LocationEntity): Long

    @Query("DELETE FROM locations WHERE id = :id")
    suspend fun deleteLocationById(id: Long): Int

    // --- History ---
    @Query("SELECT * FROM history ORDER BY createdAt DESC LIMIT 200")
    fun observeHistory(): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insertHistory(entry: HistoryEntity): Long

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteHistoryById(id: Long): Int

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY createdAt DESC LIMIT :keepCount)")
    suspend fun trimHistoryKeepingLatest(keepCount: Int): Int

    // --- Learned Books ---
    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    fun observeBooks(): Flow<List<BookKnowledgeEntity>>

    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    suspend fun getAllBooksOnce(): List<BookKnowledgeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBook(book: BookKnowledgeEntity): Long

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBookById(id: Long): Int

    // --- Learned Roles / Personas ---
    @Query("SELECT * FROM sol_roles ORDER BY updatedAt DESC")
    fun observeRoles(): Flow<List<SolRoleEntity>>

    @Query("SELECT * FROM sol_roles ORDER BY updatedAt DESC")
    suspend fun getAllRolesOnce(): List<SolRoleEntity>

    @Query("SELECT * FROM sol_roles WHERE normalizedRoleName = :normalized LIMIT 1")
    suspend fun findRoleByNormalizedName(normalized: String): SolRoleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRole(role: SolRoleEntity): Long

    @Query("DELETE FROM sol_roles WHERE id = :id")
    suspend fun deleteRoleById(id: Long): Int

    // --- Background Job Logs ---
    @Query("SELECT * FROM background_logs ORDER BY executedAt DESC LIMIT 100")
    fun observeBackgroundLogs(): Flow<List<BackgroundJobLogEntity>>

    @Insert
    suspend fun insertBackgroundLog(log: BackgroundJobLogEntity): Long

    @Query("DELETE FROM background_logs")
    suspend fun clearBackgroundLogs()

    // --- Book Chunks (BM25 Offline RAG) ---
    @Query("SELECT * FROM book_chunks ORDER BY bookId ASC, chunkIndex ASC")
    suspend fun getAllBookChunksOnce(): List<BookChunkEntity>

    @Query("SELECT * FROM book_chunks WHERE bookId = :bookId ORDER BY chunkIndex ASC")
    suspend fun getChunksForBookOnce(bookId: Long): List<BookChunkEntity>

    @Query("SELECT COUNT(*) FROM book_chunks")
    fun observeChunkCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookChunks(chunks: List<BookChunkEntity>)

    @Query("DELETE FROM book_chunks WHERE bookId = :bookId")
    suspend fun deleteChunksForBook(bookId: Long)

    // --- Exact Scheduled Jalali Alarms ---
    @Query("SELECT * FROM scheduled_alarms ORDER BY triggerAtMillis ASC")
    fun observeScheduledAlarms(): Flow<List<ScheduledAlarmEntity>>

    @Query("SELECT * FROM scheduled_alarms ORDER BY triggerAtMillis ASC")
    suspend fun getAllAlarmsOnce(): List<ScheduledAlarmEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlarm(alarm: ScheduledAlarmEntity): Long

    @Query("UPDATE scheduled_alarms SET isTriggered = 1 WHERE requestCode = :requestCode")
    suspend fun markAlarmTriggered(requestCode: Int)

    @Query("DELETE FROM scheduled_alarms WHERE id = :id")
    suspend fun deleteAlarmById(id: Long)
}
