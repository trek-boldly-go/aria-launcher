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
                .build()
                .also { instance = it }
        }
    }
}
