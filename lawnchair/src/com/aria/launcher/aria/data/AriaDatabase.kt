package com.aria.launcher.aria.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aria.launcher.aria.engine.rules.AriaRule
import com.aria.launcher.aria.engine.rules.AriaRuleDao
import com.aria.launcher.aria.engine.rules.RuleTypeConverters

@Database(
    entities = [
        AppUsageEvent::class,
        AppPrediction::class,
        AppSkill::class,
        SkillResult::class,
        UserMemory::class,
        UserMemoryFts::class,
        SsidClassification::class,
        AriaRule::class,
        AppChain::class,
        DomainPermission::class,
    ],
    version = 10,
    exportSchema = true,
)
@TypeConverters(VenueCategoryConverter::class, VisitContextConverter::class, RuleTypeConverters::class)
abstract class AriaDatabase : RoomDatabase() {

    abstract fun appUsageEventDao(): AppUsageEventDao
    abstract fun appPredictionDao(): AppPredictionDao
    abstract fun skillDao(): SkillDao
    abstract fun userMemoryDao(): UserMemoryDao
    abstract fun ssidClassificationDao(): SsidClassificationDao
    abstract fun ariaRuleDao(): AriaRuleDao
    abstract fun appChainDao(): AppChainDao
    abstract fun domainPermissionDao(): DomainPermissionDao

    companion object {
        private const val DATABASE_NAME = "aria_db"

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `domain_permissions` (
                        `domain` TEXT NOT NULL,
                        `allowedMethods` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`domain`)
                    )""",
                )
                // v10 also introduced the UserMemoryFts (@Fts4) entity. Room validates the
                // migrated schema against the compiled entities on first open, so the FTS
                // virtual table must be created here or every v9 user crash-loops on upgrade.
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `user_memory_fts` USING FTS4(`fact` TEXT NOT NULL)",
                )
                // Backfill the index from existing memories (rowid = user_memory.id, matching
                // MemoryRepository.insertFts) so a v9 user's prior memories remain searchable.
                db.execSQL(
                    "INSERT INTO `user_memory_fts`(`rowid`, `fact`) SELECT `id`, `fact` FROM `user_memory`",
                )
            }
        }

        @Volatile
        private var instance: AriaDatabase? = null

        fun getInstance(context: Context): AriaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AriaDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(MIGRATION_9_10)
                // Versions 1–8 predate the removal of destructive fallback and never had
                // real migrations (destructive migration covered every pre-v9 dev bump).
                // Recreate the DB only when upgrading from those dev-era versions; v9+
                // real-user data is preserved by the explicit migrations above.
                .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5, 6, 7, 8)
                .build()
                .also { instance = it }
        }
    }
}
