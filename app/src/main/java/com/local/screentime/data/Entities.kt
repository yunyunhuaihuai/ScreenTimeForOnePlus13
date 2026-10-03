package com.local.screentime.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 会话数据来源（P0-2 source-aware 权威策略，v8 起落库）。
 *  ROOT 可修正对应窗口内的一切数据；API（ColorOS queryEvents 被深度裁剪）只能补
 *  ROOT 权威缺席的区间、永不删除 ROOT session；LEGACY = v7 及以前入库的历史会话
 *  （当时只有 root 重建路径），升级时按 ROOT 权威对待，禁止清库。 */
const val SESSION_SOURCE_LEGACY = 0
const val SESSION_SOURCE_ROOT = 1
const val SESSION_SOURCE_API = 2

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
    /** 数据来源（P0-2）；DB 列默认 0=LEGACY，历史会话升级后保持权威地位 */
    @ColumnInfo(defaultValue = "0") val source: Int = SESSION_SOURCE_LEGACY,
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

/** 每日拿起次数（KEYGUARD_HIDDEN 事件计数）。
 *  source（v9）：写入该行的事件流来源（0=LEGACY/1=ROOT/2=API）——API 残缺事件流
 *  不得覆盖 ROOT 建立的完整日计数（P1-3），按日判断权威来源。 */
@Entity(tableName = "day_stats")
data class DayStatsEntity(
    @PrimaryKey val day: Long,
    val pickups: Long,
    @ColumnInfo(defaultValue = "0") val source: Int = SESSION_SOURCE_LEGACY,
)

/** 解锁后 15 秒内首个打开的应用计数（source 语义同 day_stats） */
@Entity(tableName = "unlock_stats", primaryKeys = ["day", "packageName"])
data class UnlockStatsEntity(
    val day: Long,
    val packageName: String,
    val count: Long,
    @ColumnInfo(defaultValue = "0") val source: Int = SESSION_SOURCE_LEGACY,
)

/** 周报 / 月报 / 年报 */
@Entity(tableName = "reports", primaryKeys = ["type", "periodKey"])
data class ReportEntity(
    val type: String,
    val periodKey: String,
    val generatedTs: Long,
    val text: String,
    /** true=正式（周期已完整结束后生成）；false=手动生成的当前周期预览（数据不完整）。
     *  DB 列默认 1：v6 迁移把既有报告全部按正式历史保留。 */
    @ColumnInfo(defaultValue = "1") val finalized: Boolean = true,
    /** 生成时的数据修订版本（v9）：ROOT 修正/总量回补/统计纠错会使全局修订号递增，
     *  低于当前修订号的正式报告需要重建（静默，不重复通知） */
    @ColumnInfo(defaultValue = "0") val dataRevision: Long = 0,
    /** 报告文本格式版本（v9）：v1=旧「总耗电」文案时代；文案/口径修正后递增，
     *  旧缓存报告据此重建为修正后的措辞 */
    @ColumnInfo(defaultValue = "1") val formatVersion: Int = 1,
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
    /** 移动网络（mobile_radio 组件）耗电。v7 新增：此前该组件既不进独立列也不进
     *  otherMah（KNOWN_COMPS 包含却不映射），组件明细丢失。注意 UID totalMah
     *  是 UID 行开头的总值，本列缺失从未导致 totalMah 少记——修的是组件完整性。 */
    @ColumnInfo(defaultValue = "0.0") val mobileMah: Double = 0.0,
    /** 手电筒（v9）：KNOWN_COMPS 含 flashlight 但既无独立列也不进 other——UID 1000
     *  真机实测 flashlight=1.34 被静默丢弃。修组件完整性，非 total 少记。 */
    @ColumnInfo(defaultValue = "0.0") val flashlightMah: Double = 0.0,
    /** 息屏显示（ambient_display，v9） */
    @ColumnInfo(defaultValue = "0.0") val ambientDisplayMah: Double = 0.0,
    /** 系统空闲（idle，v9） */
    @ColumnInfo(defaultValue = "0.0") val idleMah: Double = 0.0,
    /** 通话（phone，v9） */
    @ColumnInfo(defaultValue = "0.0") val phoneMah: Double = 0.0,
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

/**
 * 耗电累计快照（差分基线）。v9 起区分两类状态：
 *  - 原始观测：cumMah / compsText —— 上一帧 dump 的原样累计值（UNKNOWN epoch 的
 *    比例 reset heuristic 仍依赖它）；
 *  - 已入账高水位：recordedCumMah / recordedCompsText —— 已经写入日账本的最高水位。
 *    同 epoch 的模型回算（100→97）不再推进高水位，恢复（97→100）就不会二次入账；
 *    微小增量（每次 +0.0005）因高水位推进而不会永久丢失（P0-2/P1-1，真机 46 条
 *    screen>total 历史行的教训）。
 *  epochWindowStartTs：写入本快照时的统计窗口起点（epoch 审计证据；0=未知/历史行）。
 */
@Entity(tableName = "battery_snapshot")
data class BatterySnapshotEntity(
    @PrimaryKey val uidKey: String,
    val cumMah: Double,
    val compsText: String,
    val updatedTs: Long,
    /** 已入账高水位（v9）。-1 = 未初始化（迁移历史行由 MIGRATION_8_9 立即回填） */
    @ColumnInfo(defaultValue = "-1.0") val recordedCumMah: Double = -1.0,
    /** 各组件的已入账高水位（含 fg/bg/fgs；编码同 compsText，v9） */
    @ColumnInfo(defaultValue = "") val recordedCompsText: String = "",
    /** 本快照所属统计 epoch 的窗口起点（v9；0 = 未知） */
    @ColumnInfo(defaultValue = "0") val epochWindowStartTs: Long = 0,
)

/**
 * UID → 包名成员关系的历史存档（v9，P1-5）：采集时把每个 UID 当时的包成员
 * （唯一/共享）连同有效期 [firstSeenTs, lastSeenTs] 持久化；读取历史耗电时按
 * 「该日有效」的成员解析归属，不再用当前安装状态重写历史（卸载保留历史、
 * UID 复用不错挂、共享不猜拆分）。无存档的历史行（本版本之前）保留现状并在
 * 诊断中标注不确定。
 */
@Entity(tableName = "uid_packages", primaryKeys = ["uidKey", "packageName"])
data class UidPackageEntity(
    val uidKey: String,
    val packageName: String,
    val firstSeenTs: Long,
    val lastSeenTs: Long,
)

/**
 * 有界采集日志（v9，P2-2）：每次 batterystats 采集尝试一行（成功/各类失败都记），
 * 上限保留最近 64 条。字段区分 attempt/commandStart/commandEnd/observed 四个时刻
 * （此前拿锁前的 now 被当成观测时刻，午夜可能错日），并记录时区与 epoch 证据、
 * parser/ledger 版本——结构化可审计的观测历史，替代「只存最后一帧诊断」。
 */
@Entity(tableName = "capture_log", indices = [Index("ts")])
data class CaptureLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 观测时刻：shell 返回、dump 内容对应的设备状态时刻（commandEnd） */
    val ts: Long,
    val attemptTs: Long,
    val commandStartTs: Long,
    val commandEndTs: Long,
    val zoneId: String,
    /** 通道：1=ROOT 2=DUMP */
    val source: Int,
    /** 采集时的解析器版本（CaptureDiagnostics.PARSER_VERSION） */
    val parserVersion: Int,
    /** 采集时的账本算法版本（BatteryLedger.LEDGER_VERSION） */
    val ledgerVersion: Int,
    /** SUCCESS / NO_DATA / PARSE_FAILED / SOURCE_UNAVAILABLE / FAILED:<stage> */
    val outcome: String,
    /** 本帧解析出的统计窗口起点（epoch 证据；null=本帧未解析出） */
    val epochWindowStartTs: Long?,
    val uidCount: Int,
    val globalCount: Int,
    val computedDrainMah: Double,
    val actualDrainLowMah: Double?,
    val actualDrainHighMah: Double?,
    val sumUidTotalMah: Double,
    /** 失败原因 / 异常明细（人类可读） */
    val detail: String,
)
