package com.example.sol.memory

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memories",
    indices = [Index(value = ["keyText"], unique = true)]
)
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String, // "alias", "fact", "instruction"
    val keyText: String,
    val valueText: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "persons",
    indices = [Index(value = ["normalizedName"], unique = true)]
)
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val phone: String = "",
    val relation: String = "",
    val callScriptNote: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "locations",
    indices = [Index(value = ["normalizedAlias"], unique = true)]
)
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alias: String,
    val normalizedAlias: String,
    val fullName: String,
    val addressNote: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userText: String,
    val solText: String,
    val intentAction: String = "GENERAL",
    val status: String = "COMPLETED",
    val createdAt: Long = System.currentTimeMillis(),
)
