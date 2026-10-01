package com.local.screentime.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

data class DailyRow(val packageName: String, val day: Long, val totalMs: Long)

@Dao
interface UsageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<UsageSession>)

    /** 重算窗口前先删掉窗口内启动的旧会话（避免删掉在窗口外开始但在窗口内结束的跨界历史会话） */
    @Query("DELETE FROM sessions WHERE startTs >= :fromTs")
    suspend fun deleteSessionsStartingAfter(fromTs: Long)

    @Query(
        "SELECT packageName, day, SUM(endTs - startTs) AS totalMs FROM sessions " +
            "WHERE day = :day GROUP BY packageName ORDER BY totalMs DESC"
    )
    suspend fun dailyUsage(day: Long): List<DailyRow>

    /**
     * effective usage（P0-4 唯一口径）：会话聚合与 usage_daily 回补值逐包/逐日取 MAX。
     * 历史日（2026-09-04～09-12）的会话被旧版 STOPPED bug 丢 30~90%，由回补值兜底；
     * 首页 dailyRows 与周/月/年报共用本查询，保证总量与 Top 应用完全一致。
     */
    @Query(
        "SELECT packageName, day, MAX(ms) AS totalMs FROM (" +
            "SELECT packageName, day, SUM(endTs - startTs) AS ms FROM sessions WHERE day = :day GROUP BY packageName, day " +
            "UNION ALL " +
            "SELECT packageName, day, totalMs AS ms FROM usage_daily WHERE day = :day" +
            ") GROUP BY packageName, day ORDER BY totalMs DESC"
    )
    suspend fun effectiveUsageDay(day: Long): List<DailyRow>

    /** 周期版 effective usage：对区间内每天的逐包取 MAX 结果再求和（报告总量/Top 用） */
    @Query(
        "SELECT packageName, 0 AS day, SUM(eff) AS totalMs FROM (" +
            "SELECT packageName, day, MAX(ms) AS eff FROM (" +
            "SELECT packageName, day, SUM(endTs - startTs) AS ms FROM sessions WHERE day BETWEEN :from AND :to GROUP BY packageName, day " +
            "UNION ALL " +
            "SELECT packageName, day, totalMs AS ms FROM usage_daily WHERE day BETWEEN :from AND :to" +
            ") GROUP BY packageName, day" +
            ") GROUP BY packageName ORDER BY totalMs DESC"
    )
    suspend fun effectiveUsageBetween(from: Long, to: Long): List<DailyRow>

    @Query("SELECT * FROM sessions WHERE day = :day ORDER BY startTs")
    suspend fun sessionsOfDay(day: Long): List<UsageSession>

    @Query("SELECT DISTINCT packageName FROM sessions")
    suspend fun distinctPackages(): List<String>

    @Upsert
    suspend fun upsertApps(apps: List<AppInfoEntity>)

    @Query("SELECT * FROM app_info ORDER BY label")
    suspend fun allApps(): List<AppInfoEntity>

    @Query("SELECT * FROM app_info WHERE packageName = :packageName")
    suspend fun appInfo(packageName: String): AppInfoEntity?

    @Upsert
    suspend fun putState(state: SyncStateEntity)

    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun getState(key: String): Long?

    @Query("SELECT * FROM usage_daily WHERE day = :day ORDER BY totalMs DESC")
    suspend fun dailyAggRows(day: Long): List<DailyUsageEntity>

    @Query("DELETE FROM usage_daily")
    suspend fun clearUsageDaily()

    @Upsert
    suspend fun upsertDaily(rows: List<DailyUsageEntity>)

    @Query("SELECT * FROM battery_daily WHERE day = :day ORDER BY totalMah DESC")
    suspend fun batteryDailyRows(day: Long): List<BatteryDailyEntity>

    @Query("SELECT * FROM battery_daily WHERE uidKey = :uidKey AND day = :day")
    suspend fun getBatteryDaily(uidKey: String, day: Long): BatteryDailyEntity?

    @Upsert
    suspend fun upsertBatteryDaily(rows: List<BatteryDailyEntity>)

    @Query("UPDATE battery_daily SET packageName = NULL WHERE uidKey NOT LIKE 'u0a%'")
    suspend fun clearSystemUidPackageNames()

    @Query("SELECT * FROM battery_global WHERE day = :day ORDER BY mah DESC")
    suspend fun batteryGlobalRows(day: Long): List<BatteryGlobalEntity>

    @Query("SELECT * FROM battery_global WHERE component = :component AND day = :day")
    suspend fun getBatteryGlobal(component: String, day: Long): BatteryGlobalEntity?

    @Upsert
    suspend fun upsertBatteryGlobal(rows: List<BatteryGlobalEntity>)

    @Query("SELECT * FROM battery_snapshot WHERE uidKey = :uidKey")
    suspend fun getBatterySnapshot(uidKey: String): BatterySnapshotEntity?

    @Query("SELECT * FROM battery_snapshot")
    suspend fun allBatterySnapshots(): List<BatterySnapshotEntity>

    @Upsert
    suspend fun putBatterySnapshot(snapshot: BatterySnapshotEntity)

    @Upsert
    suspend fun upsertBatterySnapshots(rows: List<BatterySnapshotEntity>)

    /** 批量读取（差分事务内一次取齐待写日的既有行，替代逐 UID 查询） */
    @Query("SELECT * FROM battery_daily WHERE day IN (:days)")
    suspend fun batteryDailyForDays(days: List<Long>): List<BatteryDailyEntity>

    @Query("SELECT * FROM battery_global WHERE day IN (:days)")
    suspend fun batteryGlobalForDays(days: List<Long>): List<BatteryGlobalEntity>

    @Query("SELECT * FROM day_stats WHERE day = :day")
    suspend fun getDayStats(day: Long): DayStatsEntity?

    @Upsert
    suspend fun upsertDayStats(rows: List<DayStatsEntity>)

    @Query("SELECT * FROM unlock_stats WHERE day = :day ORDER BY count DESC LIMIT :limit")
    suspend fun unlockTop(day: Long, limit: Int): List<UnlockStatsEntity>

    @Query("DELETE FROM unlock_stats WHERE packageName LIKE '%launcher%'")
    suspend fun deleteUnlockLauncherRows()

    @Upsert
    suspend fun upsertUnlockStats(rows: List<UnlockStatsEntity>)

    @Query("SELECT * FROM reports ORDER BY generatedTs DESC LIMIT 10")
    suspend fun latestReports(): List<ReportEntity>

    @Query("SELECT * FROM reports WHERE type = :type AND periodKey = :periodKey")
    suspend fun getReport(type: String, periodKey: String): ReportEntity?

    @Upsert
    suspend fun putReport(report: ReportEntity)

    @Query("SELECT COALESCE(SUM(pickups), 0) FROM day_stats WHERE day BETWEEN :from AND :to")
    suspend fun pickupsBetween(from: Long, to: Long): Long

    @Query("SELECT COALESCE(SUM(totalMah), 0) FROM battery_daily WHERE day BETWEEN :from AND :to")
    suspend fun batteryBetween(from: Long, to: Long): Double
}
