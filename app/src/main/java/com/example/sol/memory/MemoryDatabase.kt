package com.example.sol.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MemoryFactEntity::class,
        PersonEntity::class,
        LocationEntity::class,
        HistoryEntity::class,
        BookKnowledgeEntity::class,
        SolRoleEntity::class,
        BackgroundJobLogEntity::class,
        BookChunkEntity::class,
        ScheduledAlarmEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var INSTANCE: MemoryDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `book_chunks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `bookId` INTEGER NOT NULL,
                        `bookTitle` TEXT NOT NULL,
                        `chunkIndex` INTEGER NOT NULL,
                        `contentText` TEXT NOT NULL,
                        `normalizedTokens` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_chunks_bookId` ON `book_chunks` (`bookId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scheduled_alarms` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `requestCode` INTEGER NOT NULL,
                        `titleFa` TEXT NOT NULL,
                        `messageBody` TEXT NOT NULL,
                        `triggerAtMillis` INTEGER NOT NULL,
                        `jalaliLabel` TEXT NOT NULL,
                        `targetContact` TEXT NOT NULL,
                        `isExact` INTEGER NOT NULL,
                        `isTriggered` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): MemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    "sol_memory.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
