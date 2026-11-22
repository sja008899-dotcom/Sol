package com.example.sol.memory

import androidx.room.Entity
import androidx.room.Fts4
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

@Entity(
    tableName = "history",
    indices = [Index(value = ["createdAt"])]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userText: String,
    val solText: String,
    val intentAction: String = "GENERAL",
    val status: String = "COMPLETED",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "books")
data class BookKnowledgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "",
    val subjects: String = "",
    val summaryText: String,
    val voiceNotes: String = "",
    val sourceType: String = "INTERNET", // INTERNET_OPEN_LIBRARY, INTERNET_GUTENDEX, LOCAL_FILE
    val sourceUrl: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "sol_roles",
    indices = [Index(value = ["normalizedRoleName"], unique = true)]
)
data class SolRoleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roleName: String,
    val normalizedRoleName: String,
    val instructions: String,
    val linkedBookTitle: String = "",
    val isActive: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "background_logs")
data class BackgroundJobLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobKey: String,
    val titleFa: String,
    val status: String, // SCHEDULED, RUNNING, COMPLETED, FAILED
    val detailFa: String = "",
    val executedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "book_chunks",
    indices = [Index(value = ["bookId"])]
)
data class BookChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val bookTitle: String,
    val chunkIndex: Int,
    val contentText: String,
    val normalizedTokens: String,
)

@Fts4(contentEntity = BookChunkEntity::class)
@Entity(tableName = "book_chunks_fts")
data class BookChunkFtsEntity(
    val bookTitle: String,
    val contentText: String,
    val normalizedTokens: String,
)

@Entity(
    tableName = "scheduled_alarms",
    indices = [Index(value = ["isTriggered", "triggerAtMillis"])]
)
data class ScheduledAlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val requestCode: Int,
    val titleFa: String,
    val messageBody: String,
    val triggerAtMillis: Long,
    val jalaliLabel: String,
    val targetContact: String = "",
    val isExact: Boolean = true,
    val isTriggered: Boolean = false,
)

@Entity(
    tableName = "user_utterance_examples",
    indices = [
        Index(value = ["intent"]),
        Index(value = ["normalizedText"]),
    ]
)
data class UserUtteranceExampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val intent: String,
    val text: String,
    val normalizedText: String,
    val slotsJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "notes_todos",
    indices = [Index(value = ["kind", "isDone", "updatedAt"])]
)
data class NoteTodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String, // "NOTE", "TODO", "SHOPPING"
    val content: String,
    val isDone: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

