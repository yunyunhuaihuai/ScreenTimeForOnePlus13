package com.local.screentime

import com.local.screentime.data.CollectionStatus
import com.local.screentime.data.CollectionThresholds
import com.local.screentime.data.CoverageGap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CollectionStatusTest {
    private val now = Instant.parse("2026-10-21T04:35:00Z").toEpochMilli()
    private val hour = 60 * 60_000L

    @Test
    fun freshUsageDoesNotHideStaleBatteryOrHeartbeat() {
        val status = CollectionStatus.evaluate(now, now - hour, now - hour, now - hour)
        assertFalse(status.usage.stale)
        assertTrue(status.battery.stale)
        assertTrue(status.collectorHeartbeat.stale)
        assertTrue(status.hasCurrentWarning)
    }

    @Test
    fun freshObservationsKeepEveryHistoricalGapAndDegradation() {
        val usage = listOf(CoverageGap(now - 4 * 24 * hour, now - 3 * 24 * hour))
        val battery = listOf(CoverageGap(now - 5 * 24 * hour, now - 2 * 24 * hour))
        val status = CollectionStatus.evaluate(
            now, now, now, now, usage, battery, "root采集未启动",
        )
        assertFalse(status.usage.stale)
        assertEquals(usage, status.usageGaps)
        assertEquals(battery, status.batteryLostTails)
        assertTrue(status.hasHistoricalGaps)
        assertTrue(status.hasCurrentWarning)
        val text = status.formatSummary(ZoneId.of("Asia/Shanghai"))
        assertTrue(text.contains("时长最后成功：10-21 12:35"))
        assertTrue(text.contains("耗电最后观测：10-21 12:35"))
        assertTrue(text.contains("历史时长缺口："))
        assertTrue(text.contains("历史耗电丢失："))
        assertTrue(text.contains("后台采集降级：root采集未启动"))
    }

    @Test
    fun thresholdsAreIndependentAndConfigurable() {
        val status = CollectionStatus.evaluate(
            now, now - hour, now - hour, now - hour,
            thresholds = CollectionThresholds(usageStaleMs = hour, batteryStaleMs = 2 * hour, heartbeatStaleMs = 3 * hour),
        )
        assertTrue(status.usage.stale)
        assertFalse(status.battery.stale)
        assertFalse(status.collectorHeartbeat.stale)
    }

    @Test
    fun missingTimestampAndClockRollbackCannotReportFresh() {
        val status = CollectionStatus.evaluate(now, 0, now + hour, null)
        assertTrue(status.usage.stale)
        assertTrue(status.battery.stale)
        assertTrue(status.battery.clockSkew)
        val text = status.formatSummary(ZoneId.of("Asia/Shanghai"))
        assertTrue(text.contains("时长最后成功：尚未记录"))
        assertTrue(text.contains("时间异常"))
    }
}
