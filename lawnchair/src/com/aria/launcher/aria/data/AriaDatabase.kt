package com.aria.launcher.aria.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
        SsidClassification::class,
        AriaRule::class,
        AppChain::class,
    ],
    version = 9,
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

    companion object {
        private const val DATABASE_NAME = "aria_db"

        @Volatile
        private var instance: AriaDatabase? = null

        fun getInstance(context: Context): AriaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AriaDatabase::class.java,
                DATABASE_NAME,
            )
                .fallbackToDestructiveMigration(true)
                .build()
                .also { instance = it }
        }
    }
}
