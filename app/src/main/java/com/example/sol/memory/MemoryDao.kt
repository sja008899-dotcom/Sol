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
}
