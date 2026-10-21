package com.local.screentime.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 使用时长、耗电和后台心跳使用各自的超时线，互不代替。 */
data class CollectionThresholds(
    val usageStaleMs: Long = 12 * 60 * 60_000L,
    val batteryStaleMs: Long = 45 * 60_000L,
    val heartbeatStaleMs: Long = 45 * 60_000L,
) {
    init {
        require(usageStaleMs > 0 && batteryStaleMs > 0 && heartbeatStaleMs > 0)
    }
}

/** 已确认的历史覆盖缺口；近期观测成功不能证明这段数据已恢复。 */
data class CoverageGap(val fromTs: Long, val toTs: Long) {
    init {
        require(fromTs > 0 && toTs > fromTs)
    }

    companion object {
        fun fromBounds(fromTs: Long?, toTs: Long?): CoverageGap? =
            if (fromTs != null && toTs != null && fromTs > 0 && toTs > fromTs) {
                CoverageGap(fromTs, toTs)
            } else null
    }
}

data class ObservationStatus(
    val lastSuccessTs: Long?,
    val ageMs: Long?,
    val stale: Boolean,
    val clockSkew: Boolean = false,
)

/**
 * 纯函数健康视图：新鲜度与历史覆盖独立表达，不把「最近成功」显示为「历史完整」。
 * 历史缺口由调用者读取持久化证据传入，本模型不会按成功时间过滤它们。
 */
data class CollectionStatus(
    val usage: ObservationStatus,
    val battery: ObservationStatus,
    val collectorHeartbeat: ObservationStatus,
    val usageGaps: List<CoverageGap>,
    val batteryLostTails: List<CoverageGap>,
    val collectorDegradedReason: String?,
) {
    val hasCurrentWarning: Boolean
        get() = usage.stale || battery.stale || collectorHeartbeat.stale ||
            collectorDegradedReason != null

    val hasHistoricalGaps: Boolean
        get() = usageGaps.isNotEmpty() || batteryLostTails.isNotEmpty()

    fun formatSummary(zoneId: ZoneId = ZoneId.systemDefault()): String = buildList {
        add(formatObservation("时长最后成功", usage, zoneId))
        add(formatObservation("耗电最后观测", battery, zoneId))
        add(formatObservation("后台采集心跳", collectorHeartbeat, zoneId))
        collectorDegradedReason?.let { add("后台采集降级：$it") }
        usageGaps.forEach { add("历史时长缺口：${formatGap(it, zoneId)}") }
        batteryLostTails.forEach { add("历史耗电丢失：${formatGap(it, zoneId)}") }
    }.joinToString("\n")

    companion object {
        fun evaluate(
            nowTs: Long,
            usageLastSuccessTs: Long?,
            batteryLastSuccessTs: Long?,
            collectorHeartbeatTs: Long?,
            usageGaps: List<CoverageGap> = emptyList(),
            batteryLostTails: List<CoverageGap> = emptyList(),
            collectorDegradedReason: String? = null,
            thresholds: CollectionThresholds = CollectionThresholds(),
        ): CollectionStatus = CollectionStatus(
            usage = observation(nowTs, usageLastSuccessTs, thresholds.usageStaleMs),
            battery = observation(nowTs, batteryLastSuccessTs, thresholds.batteryStaleMs),
            collectorHeartbeat = observation(nowTs, collectorHeartbeatTs, thresholds.heartbeatStaleMs),
            usageGaps = usageGaps.toList(),
            batteryLostTails = batteryLostTails.toList(),
            collectorDegradedReason = collectorDegradedReason?.trim()?.takeIf { it.isNotEmpty() },
        )

        private fun observation(nowTs: Long, timestamp: Long?, staleMs: Long): ObservationStatus {
            val valid = timestamp?.takeIf { it > 0 }
                ?: return ObservationStatus(null, null, stale = true)
            val clockSkew = valid > nowTs
            val age = if (clockSkew) 0L else nowTs - valid
            return ObservationStatus(valid, age, stale = clockSkew || age >= staleMs, clockSkew)
        }

        private val timeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm")

        private fun formatTime(timestamp: Long, zoneId: ZoneId): String =
            timeFormat.format(Instant.ofEpochMilli(timestamp).atZone(zoneId))

        private fun formatGap(gap: CoverageGap, zoneId: ZoneId): String =
            "${formatTime(gap.fromTs, zoneId)} ～ ${formatTime(gap.toTs, zoneId)}"

        private fun formatObservation(label: String, status: ObservationStatus, zoneId: ZoneId): String {
            val timestamp = status.lastSuccessTs ?: return "$label：尚未记录"
            val detail = when {
                status.clockSkew -> "时间异常，请检查系统时间"
                status.stale -> "${formatAge(status.ageMs ?: 0)}，已超时"
                else -> formatAge(status.ageMs ?: 0)
            }
            return "$label：${formatTime(timestamp, zoneId)}（$detail）"
        }

        private fun formatAge(ageMs: Long): String = when {
            ageMs < 60_000L -> "刚刚"
            ageMs < 60 * 60_000L -> "${ageMs / 60_000L}分钟前"
            ageMs < 24 * 60 * 60_000L -> "${ageMs / (60 * 60_000L)}小时前"
            else -> "${ageMs / (24 * 60 * 60_000L)}天前"
        }
    }
}
