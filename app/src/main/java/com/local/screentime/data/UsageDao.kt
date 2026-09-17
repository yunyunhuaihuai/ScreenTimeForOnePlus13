package com.local.screentime.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

data class DailyRow(val packageName: String, val day: Long, val totalMs: Long)

data class DaySum(val day: Long, val totalMs: Long)

@Dao
interface UsageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<UsageSession>)

    /** 重算窗口前先删掉窗口内的旧会话（endTs 落在窗口内的都会被重建） */
    @Query("DELETE FROM sessions WHERE endTs >= :fromTs")
    suspend fun deleteSessionsEndedAfter(fromTs: Long)

    @Query(
        "SELECT packageName, day, SUM(endTs - startTs) AS totalMs FROM sessions " +
            "WHERE day = :day GROUP BY packageName ORDER BY totalMs DESC"
    )
    suspend fun dailyUsage(day: Long): List<DailyRow>

    @Query("SELECT COALESCE(SUM(endTs - startTs), 0) FROM sessions WHERE day = :day")
    suspend fun dayTotalMs(day: Long): Long

    @Query("SELECT * FROM sessions WHERE day = :day ORDER BY startTs")
    suspend fun sessionsOfDay(day: Long): List<UsageSession>

    @Query("SELECT MIN(startTs) FROM sessions")
    suspend fun earliestSessionTs(): Long?

    @Query("SELECT day, SUM(endTs - startTs) AS totalMs FROM sessions WHERE day >= :since GROUP BY day")
    suspend fun sessionsSumsSince(since: Long): List<DaySum>

    @Query("SELECT COALESCE(SUM(endTs - startTs), 0) FROM sessions WHERE day BETWEEN :from AND :to")
    suspend fun sessionsSumBetween(from: Long, to: Long): Long

    @Query(
        "SELECT packageName, 0 AS day, SUM(endTs - startTs) AS totalMs FROM sessions " +
            "WHERE day BETWEEN :from AND :to GROUP BY packageName ORDER BY totalMs DESC LIMIT :limit"
    )
    suspend fun topSessionsBetween(from: Long, to: Long, limit: Int): List<DailyRow>

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

    @Upsert
    suspend fun putBatterySnapshot(snapshot: BatterySnapshotEntity)

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
