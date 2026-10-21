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
        UidPackageEntity::class,
        CaptureLogEntity::class,
        UsageCoverageEntity::class,
        CollectionGapEntity::class,
    ],
    version = 10,
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

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 会话来源标记（P0-2）：历史会话（v7 前只有 root 重建路径入库）按 LEGACY=权威处理；
                // API fallback 只能补 ROOT 缺席区间、永不删除权威历史
                db.execSQL("ALTER TABLE sessions ADD COLUMN source INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // —— B（P0-2/P1-1）快照分离「原始观测」与「已入账高水位」：同 epoch 模型回算
                //    （100→97→100）不再二次入账；微量增量不再因阈值+快照推进而永久丢失。
                //    历史行先令高水位 = 原始观测，保证升级后第一帧的差分与旧语义连续。
                db.execSQL("ALTER TABLE battery_snapshot ADD COLUMN recordedCumMah REAL NOT NULL DEFAULT -1.0")
                db.execSQL("ALTER TABLE battery_snapshot ADD COLUMN recordedCompsText TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE battery_snapshot ADD COLUMN epochWindowStartTs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE battery_snapshot SET recordedCumMah = cumMah, recordedCompsText = compsText")
                // —— I（P2-1）组件明细完整：KNOWN_COMPS 已含却无处落库的四个组件
                //    （真机 UID1000 flashlight=1.34 曾被静默丢弃；修组件完整性，非 total 少记）
                db.execSQL("ALTER TABLE battery_daily ADD COLUMN flashlightMah REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE battery_daily ADD COLUMN ambientDisplayMah REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE battery_daily ADD COLUMN idleMah REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE battery_daily ADD COLUMN phoneMah REAL NOT NULL DEFAULT 0.0")
                // —— F（P1-3）事件统计来源：API 残缺流不得覆盖 ROOT 完整日计数
                db.execSQL("ALTER TABLE day_stats ADD COLUMN source INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE unlock_stats ADD COLUMN source INTEGER NOT NULL DEFAULT 0")
                // —— G（P1-4）报告数据修订版本 + 文本格式版本
                db.execSQL("ALTER TABLE reports ADD COLUMN dataRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE reports ADD COLUMN formatVersion INTEGER NOT NULL DEFAULT 1")
                // —— H（P1-5）UID 成员历史存档：采集时持久化归属，历史数据不随当前安装状态漂移
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `uid_packages` (" +
                        "`uidKey` TEXT NOT NULL, `packageName` TEXT NOT NULL, " +
                        "`firstSeenTs` INTEGER NOT NULL, `lastSeenTs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`uidKey`, `packageName`))"
                )
                // —— C（P2-2）有界采集日志：区分 attempt/command/observed 时刻与时区/epoch 证据
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `capture_log` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`ts` INTEGER NOT NULL, `attemptTs` INTEGER NOT NULL, " +
                        "`commandStartTs` INTEGER NOT NULL, `commandEndTs` INTEGER NOT NULL, " +
                        "`zoneId` TEXT NOT NULL, `source` INTEGER NOT NULL, " +
                        "`parserVersion` INTEGER NOT NULL, `ledgerVersion` INTEGER NOT NULL, " +
                        "`outcome` TEXT NOT NULL, " +
                        "`epochWindowStartTs` INTEGER, `uidCount` INTEGER NOT NULL, `globalCount` INTEGER NOT NULL, " +
                        "`computedDrainMah` REAL NOT NULL, `actualDrainLowMah` REAL, `actualDrainHighMah` REAL, " +
                        "`sumUidTotalMah` REAL NOT NULL, `detail` TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_capture_log_ts` ON `capture_log` (`ts`)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `usage_coverage` (`day` INTEGER NOT NULL, `fromTs` INTEGER NOT NULL, `toTs` INTEGER NOT NULL, `status` INTEGER NOT NULL, `updatedTs` INTEGER NOT NULL, `source` TEXT NOT NULL, PRIMARY KEY(`day`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `collection_gaps` (`kind` TEXT NOT NULL, `fromTs` INTEGER NOT NULL, `toTs` INTEGER NOT NULL, `detectedTs` INTEGER NOT NULL, PRIMARY KEY(`kind`, `fromTs`, `toTs`))")
                // 保留升级前已确认的耗电丢失尾段，不能由升级后的成功采样清掉。
                db.execSQL("INSERT OR IGNORE INTO collection_gaps SELECT 'battery', a.value, b.value, b.value FROM sync_state a, sync_state b WHERE a.`key`='battery_lost_tail_from_ts' AND b.`key`='battery_lost_tail_to_ts' AND b.value>a.value")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "screentime.db",
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
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
