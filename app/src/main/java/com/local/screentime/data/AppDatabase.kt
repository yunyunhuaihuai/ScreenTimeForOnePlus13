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
    version = 7,
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

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 移动网络组件耗电列（P1-6）：修复组件明细丢失（非 total 少记）
                db.execSQL("ALTER TABLE battery_daily ADD COLUMN mobileMah REAL NOT NULL DEFAULT 0.0")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "screentime.db",
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    // v0.17.0（P0-5）：移除全局 fallbackToDestructiveMigration——
                    // 对长期历史记录应用，缺迁移就该显式失败，而不是静默清库。
                    // 仅 v1-3 例外：它们是早期开发版本、schema 未归档（schemas/ 只有 4.json 起，
                    // 4.json 为按 MIGRATION_4_5 逆推重建），无法补写迁移；这些老库定向销毁重建。
                    // v4 及以后任何升级必须走显式 Migration（4→5、5→6 均有迁移测试钉死）。
                    .fallbackToDestructiveMigrationFrom(1, 2, 3)
                    .build().also { instance = it }
            }
    }
}
