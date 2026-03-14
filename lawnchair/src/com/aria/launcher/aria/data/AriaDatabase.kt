package com.aria.launcher.aria.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AppUsageEvent::class,
        AppPrediction::class,
        AppSkill::class,
        SkillResult::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AriaDatabase : RoomDatabase() {

    abstract fun appUsageEventDao(): AppUsageEventDao
    abstract fun appPredictionDao(): AppPredictionDao
    abstract fun skillDao(): SkillDao

    companion object {
        private const val DATABASE_NAME = "aria_db"

        @Volatile
        private var instance: AriaDatabase? = null

        fun getInstance(context: Context): AriaDatabase =
            instance ?: synchronized(this) {
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
