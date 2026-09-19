package com.local.screentime.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 一次前台会话；跨午夜的会话在写入前已按本地日切分。 */
@Entity(
    tableName = "sessions",
    indices = [Index("startTs"), Index("day"), Index(value = ["day", "packageName"])],
)
data class UsageSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val startTs: Long,
    val endTs: Long,
    /** LocalDate.toEpochDay()，按会话起始时刻归属 */
    val day: Long,
)

/** 应用注册表：label/图标快照，冻结或卸载后仍能显示历史记录 */
@Entity(tableName = "app_info")
data class AppInfoEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    val firstSeenTs: Long,
)

/** 同步状态（水位线等） */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: Long,
)

/**
 * 每日聚合：每包每天的前台总时长（系统权威值）。
 * source: 0=事件流重建 1=系统聚合API(queryUsageStats) 2=root直读dumpsys
 */
@Entity(
    tableName = "usage_daily",
    primaryKeys = ["packageName", "day"],
    indices = [Index("day")],
)
data class DailyUsageEntity(
    val packageName: String,
    val day: Long,
    val totalMs: Long,
    val source: Int,
)

/** 每日拿起次数（KEYGUARD_HIDDEN 事件计数） */
@Entity(tableName = "day_stats")
data class DayStatsEntity(
    @PrimaryKey val day: Long,
    val pickups: Long,
)

/** 解锁后 15 秒内首个打开的应用计数 */
@Entity(tableName = "unlock_stats", primaryKeys = ["day", "packageName"])
data class UnlockStatsEntity(
    val day: Long,
    val packageName: String,
    val count: Long,
)

/** 周报 / 月报 / 年报 */
@Entity(tableName = "reports", primaryKeys = ["type", "periodKey"])
data class ReportEntity(
    val type: String,
    val periodKey: String,
    val generatedTs: Long,
    val text: String,
)

/** 每日每 UID 耗电（mAh，模型估算），由快照差分累计；跨充满周期自动重置 */
@Entity(
    tableName = "battery_daily",
    primaryKeys = ["uidKey", "day"],
    indices = [Index("day")],
)
data class BatteryDailyEntity(
    val uidKey: String,
    val day: Long,
    val packageName: String?,
    val totalMah: Double,
    val fgMah: Double,
    val bgMah: Double,
    val fgsMah: Double = 0.0,
    val screenMah: Double,
    val cpuMah: Double,
    val audioMah: Double,
    val videoMah: Double,
    val cameraMah: Double,
    val gnssMah: Double,
    val wifiMah: Double,
    val btMah: Double,
    val wakelockMah: Double,
    val sensorsMah: Double,
    val otherMah: Double,
)

/** 全局（整机）按组件耗电：屏幕/CPU/蓝牙/音频/GNSS… */
@Entity(
    tableName = "battery_global",
    primaryKeys = ["component", "day"],
    indices = [Index("day")],
)
data class BatteryGlobalEntity(
    val component: String,
    val day: Long,
    val mah: Double,
    val durationMs: Long,
)

/** 耗电累计快照（检测充满清零：新值<旧值即重置） */
@Entity(tableName = "battery_snapshot")
data class BatterySnapshotEntity(
    @PrimaryKey val uidKey: String,
    val cumMah: Double,
    val compsText: String,
    val updatedTs: Long,
)
