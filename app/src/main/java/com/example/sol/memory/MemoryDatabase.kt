package com.example.sol.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.BuildConfig

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
        UserUtteranceExampleEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var INSTANCE: MemoryDatabase? = null

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memories` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `keyText` TEXT NOT NULL,
                        `valueText` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memories_keyText` ON `memories` (`keyText`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `persons` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `normalizedName` TEXT NOT NULL,
                        `phone` TEXT NOT NULL,
                        `relation` TEXT NOT NULL,
                        `callScriptNote` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_persons_normalizedName` ON `persons` (`normalizedName`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `locations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `alias` TEXT NOT NULL,
                        `normalizedAlias` TEXT NOT NULL,
                        `fullName` TEXT NOT NULL,
                        `addressNote` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_locations_normalizedAlias` ON `locations` (`normalizedAlias`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `userText` TEXT NOT NULL,
                        `solText` TEXT NOT NULL,
                        `intentAction` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `books` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `author` TEXT NOT NULL,
                        `subjects` TEXT NOT NULL,
                        `summaryText` TEXT NOT NULL,
                        `voiceNotes` TEXT NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        `sourceUrl` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sol_roles` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `roleName` TEXT NOT NULL,
                        `normalizedRoleName` TEXT NOT NULL,
                        `instructions` TEXT NOT NULL,
                        `linkedBookTitle` TEXT NOT NULL,
                        `isActive` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sol_roles_normalizedRoleName` ON `sol_roles` (`normalizedRoleName`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `background_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `jobKey` TEXT NOT NULL,
                        `titleFa` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `detailFa` TEXT NOT NULL,
                        `executedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
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

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_createdAt` ON `history` (`createdAt`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scheduled_alarms_isTriggered_triggerAtMillis` ON `scheduled_alarms` (`isTriggered`, `triggerAtMillis`)"
                )
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_utterance_examples` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `intent` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `normalizedText` TEXT NOT NULL,
                        `slotsJson` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_utterance_examples_intent` ON `user_utterance_examples` (`intent`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_utterance_examples_normalizedText` ON `user_utterance_examples` (`normalizedText`)")
            }
        }

        internal val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        internal fun buildDatabase(
            context: Context,
            isDebugBuild: Boolean = BuildConfig.DEBUG,
        ): MemoryDatabase {
            val builder = Room.databaseBuilder(
                context.applicationContext,
                MemoryDatabase::class.java,
                "sol_memory.db"
            ).addMigrations(*ALL_MIGRATIONS)
            if (isDebugBuild) {
                // Only allowed in debug builds; release builds refuse destructive user-data wipes.
                builder.fallbackToDestructiveMigration(dropAllTables = true)
            }
            return builder.build()
        }

        fun getInstance(context: Context): MemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }
    }
}
