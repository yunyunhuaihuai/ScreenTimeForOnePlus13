package com.local.screentime

import com.local.screentime.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class UsageSourceWindowTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun ts(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test fun truncatedOrStaleDumpCannotAuthorizeReplacement() {
        val header = listOf("user=0", "  Last 24 hour events (timeRange=\"2026/10/20 12:37 – 2026/10/21 12:37\" )")
        val now = ts("2026-10-21T12:37:55")
        assertTrue(dumpCoverage(header, now, zone).isEmpty())
        assertTrue(dumpCoverage(header + "  In-memory daily stats", now + 180_000, zone).isEmpty())
        val verified = dumpCoverage(header + "  In-memory daily stats", now, zone)[0]!!.single()
        assertEquals(ts("2026-10-20T12:38:00"), verified.from)
        assertEquals(now, verified.to)
    }

    @Test fun failedSecondaryUserNeverBecomesAbsent() {
        val source = combineUsageSources(HistoryReadResult(emptyList(),
            listOf(HistoryCoverage(0, "x", 100, 500, true, 500)),
            listOf(HistoryReadFailure(999, null, "CE_UNAVAILABLE")), setOf(0)),
            emptyList(), emptyMap())
        assertTrue(source.ranges.isEmpty())
        assertTrue(source.failures.single().contains("999"))
    }

    @Test fun persistedMillisecondsWinWithoutDiscardingSecondaryTail() {
        val history = HistoryReadResult(listOf(RawEvent(150, 1, "app", 0),
            RawEvent(160, 1, "clone", 999)), listOf(
            HistoryCoverage(0, "0", 100, 200, false, 200),
            HistoryCoverage(999, "999", 100, 180, false, 180)), emptyList(), setOf(0, 999))
        val source = combineUsageSources(history, listOf(RawEvent(149, 1, "app", 0),
            RawEvent(159, 1, "clone", 999), RawEvent(190, 2, "clone", 999),
            RawEvent(210, 2, "app", 0)), mapOf(0 to listOf(VerifiedRange(120, 300)),
            999 to listOf(VerifiedRange(120, 300))))
        assertEquals(listOf(150L, 160L, 190L, 210L), source.events.map { it.ts })
        assertEquals(listOf(VerifiedRange(100, 300)), source.ranges)
    }

    @Test fun missingHistoricalBucketRemainsGapEvenWhenLatestReadSucceeded() {
        val history = HistoryReadResult(emptyList(), listOf(
            HistoryCoverage(0, "a", 100, 200, true, 200),
            HistoryCoverage(0, "c", 300, 400, true, 400)), emptyList(), setOf(0))
        val source = combineUsageSources(history, emptyList(), mapOf(0 to listOf(VerifiedRange(350, 500))))
        assertEquals(listOf(VerifiedRange(100, 200), VerifiedRange(300, 500)), source.ranges)
        assertEquals(listOf(VerifiedRange(200, 300)), subtractRanges(VerifiedRange(100, 500), source.ranges))
    }
}
