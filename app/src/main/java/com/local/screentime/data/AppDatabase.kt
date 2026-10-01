package com.local.screentime.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 6,
    // 导出 schema：今后每次升版本都有历史 schema 可依据，能写真正的增量迁移；
    // 配合下面的注释，destructive fallback 只保留给 4 之前的存量老库
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun usageDao(): UsageDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE battery_daily ADD COLUMN fgsMah REAL NOT NULL DEFAULT 0.0")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 报告 finalized 标记（P0-3）：既有报告按正式历史保留（列默认 1），
                // 此后手动预览以 false 写入、周期结束后由正式报告覆盖
                db.execSQL("ALTER TABLE reports ADD COLUMN finalized INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "screentime.db",
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                    // 历史 1→4 的 schema 未归档、无法补写迁移，存量老库仍走销毁式重建；
                    // 当前及以后版本（≥5）必须写增量迁移，禁止再往这个 fallback 里塞新版本。
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
