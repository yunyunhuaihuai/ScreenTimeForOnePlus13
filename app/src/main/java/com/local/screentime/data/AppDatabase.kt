package com.local.screentime.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        UsageSession::class,
        AppInfoEntity::class,
        SyncStateEntity::class,
        DailyUsageEntity::class,
        BatteryDailyEntity::class,
        BatteryGlobalEntity::class,
        BatterySnapshotEntity::class,
        DayStatsEntity::class,
        UnlockStatsEntity::class,
        ReportEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun usageDao(): UsageDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "screentime.db",
                )
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
