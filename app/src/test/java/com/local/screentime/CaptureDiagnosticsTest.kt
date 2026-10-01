package com.local.screentime

import com.local.screentime.data.BatteryParsed
import com.local.screentime.data.CaptureDiagnostics
import com.local.screentime.data.UidPower
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

/**
 * P1-2 parser 完整性门控 + P1-4 能量口径比率。
 * 异常判据基于本设备健康基线（不设固定 UID 阈值），真机正常帧 parsedUidCount≈142。
 * JSON 序列化依赖 org.json → Robolectric 运行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CaptureDiagnosticsTest {

    private fun parsed(
        uidCount: Int = 142,
        computedDrain: Double = 1522.0,
        windowStartTs: Long? = 1000L,
    ) = BatteryParsed(
        uids = (0 until uidCount).map { UidPower("u0a$it", 1.0, 0.0, 0.0, 0.0, emptyMap()) },
        globals = linkedMapOf("screen" to (324.0 to 1L)),
        computedDrain = computedDrain,
        actualDrainLow = 1954.0,
        actualDrainHigh = 2072.0,
        windowStartTs = windowStartTs,
        batteryCapacityMah = 5920.0,
        rawLineCount = 10690,
    )

    @Test
    fun healthyFrame_noAnomalies() {
        val d = CaptureDiagnostics.fromParsed(parsed(), baselineUidCount = 141, hadWindowBaseline = true)
        assertTrue(d.anomalies.isEmpty())
        assertEquals(142, d.parsedUidCount)
        assertEquals(142.0, d.sumUidTotalMah, 1e-9)
        assertEquals(5920.0, d.batteryCapacityMah!!, 1e-9)
    }

    @Test
    fun computedDrainMissing_isAnomaly() {
        val d = CaptureDiagnostics.fromParsed(parsed(computedDrain = 0.0), baselineUidCount = 142, hadWindowBaseline = true)
        assertTrue(d.anomalies.contains("computed_drain_missing"))
    }

    @Test
    fun windowStartMissingWithBaseline_isAnomaly() {
        // 本设备此前可解析窗口起点，本帧突然缺失 → 异常（fgs 失配史的教训：结构变化要显式暴露）
        val d = CaptureDiagnostics.fromParsed(parsed(windowStartTs = null), baselineUidCount = 142, hadWindowBaseline = true)
        assertTrue(d.anomalies.contains("window_start_missing"))
    }

    @Test
    fun windowStartMissingWithoutBaseline_notAnomaly() {
        // 尚无健康基线（首帧）：不判异常
        val d = CaptureDiagnostics.fromParsed(parsed(windowStartTs = null), baselineUidCount = null, hadWindowBaseline = false)
        assertFalse(d.anomalies.contains("window_start_missing"))
    }

    @Test
    fun catastrophicUidCountDrop_isAnomaly() {
        // 相对健康基线（142）灾难性下降（<50%）→ 异常；不设绝对阈值
        val d = CaptureDiagnostics.fromParsed(parsed(uidCount = 60), baselineUidCount = 142, hadWindowBaseline = true)
        assertTrue(d.anomalies.any { it.startsWith("uid_count_drop") })
        // 同等降幅但无基线 → 不判（首帧/新设备）
        val d2 = CaptureDiagnostics.fromParsed(parsed(uidCount = 60), baselineUidCount = null, hadWindowBaseline = true)
        assertFalse(d2.anomalies.any { it.startsWith("uid_count_drop") })
        // 正常波动（142→120）→ 不判
        val d3 = CaptureDiagnostics.fromParsed(parsed(uidCount = 120), baselineUidCount = 142, hadWindowBaseline = true)
        assertFalse(d3.anomalies.any { it.startsWith("uid_count_drop") })
    }

    @Test
    fun powerSectionUnparsed_isAnomaly() {
        val empty = BatteryParsed(
            uids = emptyList(), globals = linkedMapOf(), computedDrain = 0.0,
            actualDrainLow = 0.0, windowStartTs = null, rawLineCount = 5000,
        )
        val d = CaptureDiagnostics.detectAnomalies(empty, baselineUidCount = null, hadWindowBaseline = false)
        assertTrue(d.contains("power_section_unparsed"))
        assertTrue(d.contains("computed_drain_missing"))
    }

    @Test
    fun energyRatios_computedFromRealDeviceShape() {
        // P1-4 口径：UID 归因比率 = UID sum / computed；模型覆盖率 = computed / actual 区间。
        // 真机 2026-10-01：computed 1522、actual 1954-2072（差值是区间 432~550，不写死）
        val d = CaptureDiagnostics.fromParsed(parsed(), baselineUidCount = 142, hadWindowBaseline = true)
        assertEquals(142.0 / 1522.0, d.uidAttributionRatio!!, 1e-9)
        assertEquals(1522.0 / 1954.0, d.modelCoverageLow!!, 1e-9)
        assertEquals(1522.0 / 2072.0, d.modelCoverageHigh!!, 1e-9)
        // actual 与 computed 的缺口区间表述
        val gapLow = d.actualDrainLowMah!! - d.computedDrainMah
        val gapHigh = d.actualDrainHighMah!! - d.computedDrainMah
        assertTrue(gapLow < gapHigh)
    }

    @Test
    fun jsonRoundTrip_preservesFields() {
        val d = CaptureDiagnostics.fromParsed(parsed(), baselineUidCount = 142, hadWindowBaseline = true)
        val back = CaptureDiagnostics.fromJson(d.toJson().toString())!!
        assertEquals(d.parsedUidCount, back.parsedUidCount)
        assertEquals(d.computedDrainMah, back.computedDrainMah, 1e-9)
        assertEquals(d.windowStartTs, back.windowStartTs)
        assertEquals(d.anomalies, back.anomalies)
        assertEquals(d.sumUidTotalMah, back.sumUidTotalMah, 1e-9)
    }

    @Test
    fun jsonGarbage_returnsNull() {
        assertNull(CaptureDiagnostics.fromJson("not json"))
    }
}
