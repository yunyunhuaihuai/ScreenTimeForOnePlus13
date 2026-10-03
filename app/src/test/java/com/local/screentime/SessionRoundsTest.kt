package com.local.screentime

import com.local.screentime.data.UsageSession
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * D（v0.18）：使用轮次与真实使用时长分离。
 * 复现场景（2026-10-03 审查 §C）：2026-09-15 QQ sessions 合计 65分19秒，UI 合并区间
 * 跨度合计 95分35秒——空档 30分16秒 被当作使用显示。修复：activeDuration = 会话区间
 * 并集；span 单独标注。
 */
class SessionRoundsTest {

    private val day = LocalDate.of(2026, 9, 15).toEpochDay()
    private val t0 = 1758000000000L  // 任意毫秒起点，只看相对值

    private fun sess(startOffsetMs: Long, endOffsetMs: Long) = UsageSession(
        packageName = "com.tencent.mobileqq",
        startTs = t0 + startOffsetMs,
        endTs = t0 + endOffsetMs,
        day = day,
    )

    @Test
    fun gapBetweenSessions_oneRound_activeVsSpan() {
        // 验收样例：10:00-10:01 + 10:02-10:03 → 1 轮、使用 2 分钟、跨度 3 分钟
        val rounds = mergeSessionRounds(
            listOf(sess(0, 60_000), sess(120_000, 180_000)),
            gapMs = 90_000,
        )
        assertEquals(1, rounds.size)
        assertEquals(120_000L, rounds[0].activeMs)   // 空档 60s 不计入使用
        assertEquals(180_000L, rounds[0].end - rounds[0].start)  // 跨度 3 分钟
    }

    @Test
    fun qqSample_activeIsSessionSum_notSpan() {
        // 复现场景（2026-09-15 QQ，脱敏重构）：会话合计 65分19秒；旧实现按合并区间跨度
        // 显示 95分35秒——空档被当作使用。新语义：单轮内 active = 会话合计，span 单独标注
        val sessions = listOf(
            sess(0, 20 * 60_000),                      // 20 分
            sess(21 * 60_000, 36 * 60_000),            // 15 分（间隔 60s ≤ 90s → 同轮）
            sess(37 * 60_000, 37 * 60_000 + 30 * 60_000 + 19_000),  // 30分19秒（间隔 60s → 同轮）
        )
        val rounds = mergeSessionRounds(sessions)
        assertEquals(1, rounds.size)
        assertEquals("active = 会话合计 65分19秒", 65 * 60_000L + 19_000L, rounds[0].activeMs)
        assertEquals("span = 轮次跨度（含空档 2 分钟）", 67 * 60_000L + 19_000L, rounds[0].end - rounds[0].start)
        assertEquals("span − active = 空档", 120_000L, (rounds[0].end - rounds[0].start) - rounds[0].activeMs)
    }

    @Test
    fun overlappingSessions_activeUsesUnion_notSum() {
        // 防御 ROOT+API fallback 罕见重叠：并集不双计
        val rounds = mergeSessionRounds(
            listOf(sess(0, 120_000), sess(60_000, 180_000)),
        )
        assertEquals(1, rounds.size)
        assertEquals("并集 3 分钟，而非求和 5 分钟", 180_000L, rounds[0].activeMs)
    }

    @Test
    fun tinyGap_merges_largeGap_splits() {
        val s = listOf(
            sess(0, 60_000),
            sess(80_000, 120_000),      // 间隔 20s ≤ 90s → 同轮
            sess(10 * 60_000, 11 * 60_000),  // 间隔远超 90s → 新轮
        )
        val rounds = mergeSessionRounds(s)
        assertEquals(2, rounds.size)
        // 轮内 [0,60s] 与 [80s,120s] 不相邻：active = 并集 100s（60s+40s），不是跨度 120s
        assertEquals(100_000L, rounds[0].activeMs)
        assertEquals(120_000L, rounds[0].end - rounds[0].start)
        assertEquals(60_000L, rounds[1].activeMs)
    }

    @Test
    fun emptySessions_emptyRounds() {
        assertEquals(0, mergeSessionRounds(emptyList()).size)
    }
}
