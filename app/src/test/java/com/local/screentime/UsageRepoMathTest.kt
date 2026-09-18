package com.local.screentime

import com.local.screentime.data.UsageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 耗电增量日归属（v0.16.6 引入的 dayWeights/deltaFrom）的数学性质测试。
 * 这两个函数是纯函数（v0.16.9 起移入 companion object 以便 JVM 单测）。
 */
class UsageRepoMathTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun epochDay(y: Int, mo: Int, d: Int): Long = LocalDate.of(y, mo, d).toEpochDay()

    @Test
    fun sameDayInterval_singleDayFullWeight() {
        val w = UsageRepository.dayWeights(ms(2026, 9, 18, 10, 0), ms(2026, 9, 18, 12, 0), zone)
        assertEquals(listOf<Long>(epochDay(2026, 9, 18)), w.map { it.first })
        assertEquals(1.0, w[0].second, 1e-12)
    }

    @Test
    fun midnightCrossing_proportionalSplit() {
        // 09-17 20:00 → 09-18 10:00：跨一夜，4h 归 09-17、10h 归 09-18
        val w = UsageRepository.dayWeights(ms(2026, 9, 17, 20, 0), ms(2026, 9, 18, 10, 0), zone)
        assertEquals(2, w.size)
        assertEquals(epochDay(2026, 9, 17), w[0].first)
        assertEquals(epochDay(2026, 9, 18), w[1].first)
        assertEquals(4.0 / 14.0, w[0].second, 1e-12)
        assertEquals(10.0 / 14.0, w[1].second, 1e-12)
        assertEquals(1.0, w.sumOf { it.second }, 1e-12)
    }

    @Test
    fun twoMidnights_threeSegments() {
        // 09-16 23:00 → 09-18 01:00：1h + 24h + 1h
        val w = UsageRepository.dayWeights(ms(2026, 9, 16, 23, 0), ms(2026, 9, 18, 1, 0), zone)
        assertEquals(3, w.size)
        assertEquals(epochDay(2026, 9, 16), w[0].first)
        assertEquals(epochDay(2026, 9, 17), w[1].first)
        assertEquals(epochDay(2026, 9, 18), w[2].first)
        assertEquals(1.0 / 26.0, w[0].second, 1e-12)
        assertEquals(24.0 / 26.0, w[1].second, 1e-12)
        assertEquals(1.0, w.sumOf { it.second }, 1e-12)
    }

    @Test
    fun invalidFrom_zero_allAttributedToDayOfTo() {
        val w = UsageRepository.dayWeights(0L, ms(2026, 9, 18, 23, 0), zone)
        assertEquals(listOf<Long>(epochDay(2026, 9, 18)), w.map { it.first })
        assertEquals(1.0, w[0].second, 1e-12)
    }

    @Test
    fun fromEqualsTo_singleEntry() {
        val t = ms(2026, 9, 18, 18, 5)
        val w = UsageRepository.dayWeights(t, t, zone)
        assertEquals(listOf<Long>(epochDay(2026, 9, 18)), w.map { it.first })
        assertEquals(1.0, w[0].second, 1e-12)
    }

    @Test
    fun deltaFrom_firstSync_usesWindowStart() {
        val windowStart = ms(2026, 9, 18, 18, 5)
        val now = ms(2026, 9, 18, 23, 45)
        assertEquals(windowStart, UsageRepository.deltaFrom(null, false, windowStart, now))
    }

    @Test
    fun deltaFrom_firstSync_noWindowStart_usesNow() {
        val now = ms(2026, 9, 18, 23, 45)
        assertEquals(now, UsageRepository.deltaFrom(null, false, null, now))
        assertEquals(now, UsageRepository.deltaFrom(null, false, 0L, now))
    }

    @Test
    fun deltaFrom_reset_usesLaterOfSnapshotAndWindowStart() {
        val lastTs = ms(2026, 9, 18, 12, 0)
        val windowStart = ms(2026, 9, 18, 18, 5)
        val now = ms(2026, 9, 18, 23, 45)
        assertEquals(windowStart, UsageRepository.deltaFrom(lastTs, true, windowStart, now))
        // 窗口起点早于快照（快照跨窗口存活）时，用快照时间
        val oldWindow = ms(2026, 9, 18, 6, 0)
        assertEquals(lastTs, UsageRepository.deltaFrom(lastTs, true, oldWindow, now))
    }

    @Test
    fun deltaFrom_normal_usesLastSnapshotTs() {
        val lastTs = ms(2026, 9, 18, 20, 58)
        val now = ms(2026, 9, 18, 23, 45)
        assertEquals(lastTs, UsageRepository.deltaFrom(lastTs, false, null, now))
    }

    @Test
    fun staleSnapshotThenChargeReset_dropsDelta_documentation() {
        // 语义钉死：v0.16.8 及之前 bilibili 丢失的机制 ——
        // fgs 致盲使快照长期停在 20:58 的 0.0831mAh，当晚真实累计 ~247mAh；
        // 夜里充电窗口重置后新值 ≈0.0005，reset 判定 (new < old-0.5) 不成立（旧快照本身就小），
        // 差分为负被 coerceAtLeast(0) 丢弃 → 整晚耗电无法入库。
        // 正则修复后快照每轮都会更新，此路径只影响 0.5mAh 以内的抖动，不再丢大额增量。
        val lastCum = 0.0831
        val afterReset = 0.0005
        val reset = afterReset < lastCum - 0.5
        val totalDelta = (afterReset - lastCum).coerceAtLeast(0.0)
        assertFalse(reset)
        assertEquals(0.0, totalDelta, 1e-12)
    }
}
