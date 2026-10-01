package com.local.screentime

import com.local.screentime.data.ReportEntity
import com.local.screentime.data.ReportPeriods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * P0-3 周期解析器边界测试。
 *
 * 旧实现（currentPeriods + `today ∈ [end, end+7]`）在周期切换日（周一/次月 1 日/
 * 次年 1 月 1 日）后永远补不上上一周期，且在周期最后一天提前生成未完结的"正式"报告。
 * 本测试钉死新语义：正式报告只针对「最近已完整结束的周期」。
 *
 * 2026-09-27 是周日（2026-09 原始边界样本）。
 */
class ReportPeriodsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    // ---------- 周报边界 ----------

    @Test
    fun sunday_lastCompletedIsPreviousMonToSun() {
        // 周日（含 00:01 与 23:59）：当前周尚未结束，正式周报必须是上一周 Mon-Sun
        val sunday = LocalDate.of(2026, 9, 27)
        val p = ReportPeriods.lastCompletedShifted(sunday, ReportPeriods.REPORT_WEEKLY, 0)
        assertEquals(LocalDate.of(2026, 9, 14), p.start)
        assertEquals(LocalDate.of(2026, 9, 20), p.end)
        assertTrue(p.finalized)
        // 进行中的当前周只允许作为预览
        val preview = ReportPeriods.currentPreview(sunday).first { it.type == ReportPeriods.REPORT_WEEKLY }
        assertEquals(LocalDate.of(2026, 9, 21), preview.start)
        assertEquals(LocalDate.of(2026, 9, 27), preview.end)
        assertFalse(preview.finalized)
    }

    @Test
    fun monday_lastCompletedIsTheWeekThatEndedYesterday() {
        // 周一：上一周（Mon-Sun）已完整结束，正是补生成的目标周期（旧实现此日恒错过）
        val monday = LocalDate.of(2026, 9, 28)
        val p = ReportPeriods.lastCompletedShifted(monday, ReportPeriods.REPORT_WEEKLY, 0)
        assertEquals(LocalDate.of(2026, 9, 21), p.start)
        assertEquals(LocalDate.of(2026, 9, 27), p.end)
    }

    @Test
    fun midWeek_lastCompletedIsPreviousWeek() {
        val saturday = LocalDate.of(2026, 9, 26)
        val p = ReportPeriods.lastCompletedShifted(saturday, ReportPeriods.REPORT_WEEKLY, 0)
        assertEquals(LocalDate.of(2026, 9, 14), p.start)
        assertEquals(LocalDate.of(2026, 9, 20), p.end)
    }

    @Test
    fun weeklyShift_walksBackFullWeeks() {
        val monday = LocalDate.of(2026, 9, 28)
        val p1 = ReportPeriods.lastCompletedShifted(monday, ReportPeriods.REPORT_WEEKLY, 1)
        assertEquals(LocalDate.of(2026, 9, 14), p1.start)
        assertEquals(LocalDate.of(2026, 9, 20), p1.end)
    }

    // ---------- 月报边界 ----------

    @Test
    fun lastDayOfMonth_lastCompletedIsPreviousMonth() {
        // 月末最后一天：本月尚未结束，正式月报必须是上一个自然月
        val p = ReportPeriods.lastCompletedShifted(LocalDate.of(2026, 9, 30), ReportPeriods.REPORT_MONTHLY, 0)
        assertEquals(LocalDate.of(2026, 8, 1), p.start)
        assertEquals(LocalDate.of(2026, 8, 31), p.end)
        assertEquals("M:2026-8", p.key)  // 沿用旧 key 格式（无补零）
    }

    @Test
    fun firstDayOfNextMonth_lastCompletedIsJustEndedMonth() {
        // 次月 1 日：上一自然月完整结束（旧实现此日恒错过）
        val p = ReportPeriods.lastCompletedShifted(LocalDate.of(2026, 10, 1), ReportPeriods.REPORT_MONTHLY, 0)
        assertEquals(LocalDate.of(2026, 9, 1), p.start)
        assertEquals(LocalDate.of(2026, 9, 30), p.end)
        assertEquals("M:2026-9", p.key)
    }

    @Test
    fun monthLengths_handled() {
        // 平月/闰月/31 天月：2024 闰年 2 月 29 天
        val p = ReportPeriods.lastCompletedShifted(LocalDate.of(2024, 3, 15), ReportPeriods.REPORT_MONTHLY, 0)
        assertEquals(LocalDate.of(2024, 2, 29), p.end)
    }

    // ---------- 年报边界 ----------

    @Test
    fun dec31_lastCompletedIsPreviousYear() {
        // 12 月 31 日：本年尚未结束，正式年报必须是上一自然年
        val p = ReportPeriods.lastCompletedShifted(LocalDate.of(2026, 12, 31), ReportPeriods.REPORT_YEARLY, 0)
        assertEquals(LocalDate.of(2025, 1, 1), p.start)
        assertEquals(LocalDate.of(2025, 12, 31), p.end)
        assertEquals("Y:2025", p.key)
    }

    @Test
    fun jan1_lastCompletedIsJustEndedYear() {
        // 1 月 1 日：上一自然年完整结束（旧实现此日恒错过）
        val p = ReportPeriods.lastCompletedShifted(LocalDate.of(2027, 1, 1), ReportPeriods.REPORT_YEARLY, 0)
        assertEquals(LocalDate.of(2026, 1, 1), p.start)
        assertEquals(LocalDate.of(2026, 12, 31), p.end)
        assertEquals("Y:2026", p.key)
    }

    // ---------- 过早/预览报告的升级规则 ----------

    @Test
    fun needsFinalizedGeneration_missingRow() {
        val p = ReportPeriods.lastCompletedShifted(LocalDate.of(2026, 9, 28), ReportPeriods.REPORT_WEEKLY, 0)
        assertTrue(ReportPeriods.needsFinalizedGeneration(null, p, zone))
    }

    @Test
    fun needsFinalizedGeneration_prematureReport_isRegenerated() {
        // 周期最后一天（周日晚）生成的旧"正式"报告：早于周期结束时刻 → 需要重新生成
        val sunday = LocalDate.of(2026, 9, 27)
        val p = ReportPeriods.lastCompletedShifted(LocalDate.of(2026, 9, 28), ReportPeriods.REPORT_WEEKLY, 0)
        val prematureTs = sunday.plusDays(0).atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
        val existing = ReportEntity(p.type, p.key, prematureTs, "旧报告", finalized = true)
        assertTrue(ReportPeriods.needsFinalizedGeneration(existing, p, zone))
    }

    @Test
    fun needsFinalizedGeneration_afterPeriodEnd_final() {
        // 周期结束后生成的正式报告：不重生成
        val p = ReportPeriods.lastCompletedShifted(LocalDate.of(2026, 9, 28), ReportPeriods.REPORT_WEEKLY, 0)
        val afterEnd = LocalDate.of(2026, 9, 28).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val existing = ReportEntity(p.type, p.key, afterEnd, "正式报告", finalized = true)
        assertFalse(ReportPeriods.needsFinalizedGeneration(existing, p, zone))
    }

    @Test
    fun previewRow_isUpgradedToFinalAtPeriodEnd() {
        // 周期内手动预览（finalized=false）：周期结束后按同 key 升级为正式报告
        val previewPeriod = ReportPeriods.currentPreview(LocalDate.of(2026, 9, 25))
            .first { it.type == ReportPeriods.REPORT_WEEKLY }
        assertEquals("W:2026-09-21", previewPeriod.key)
        val previewTs = LocalDateTime.of(2026, 9, 25, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val existing = ReportEntity(previewPeriod.type, previewPeriod.key, previewTs, "预览", finalized = false)
        // 周一再看：同 key 的正式周期
        val finalPeriod = ReportPeriods.lastCompletedShifted(LocalDate.of(2026, 9, 28), ReportPeriods.REPORT_WEEKLY, 0)
        assertEquals(previewPeriod.key, finalPeriod.key)
        assertTrue(ReportPeriods.needsFinalizedGeneration(existing, finalPeriod, zone))
    }

    @Test
    fun backfillLimits_bounded() {
        assertEquals(8L, ReportPeriods.backfillLimit(ReportPeriods.REPORT_WEEKLY))
        assertEquals(6L, ReportPeriods.backfillLimit(ReportPeriods.REPORT_MONTHLY))
        assertEquals(2L, ReportPeriods.backfillLimit(ReportPeriods.REPORT_YEARLY))
    }

    // ---------- 回归保护：weekStart 不得漂移 ----------

    @Test
    fun weeklyPeriods_alwaysStartOnMonday() {
        // 任意一天，正式周报的 start 必须是周一、end 必须是周日
        var d = LocalDate.of(2026, 1, 1)
        while (d < LocalDate.of(2026, 3, 1)) {
            val p = ReportPeriods.lastCompletedShifted(d, ReportPeriods.REPORT_WEEKLY, 0)
            assertEquals("start 必须是周一: $d", java.time.DayOfWeek.MONDAY, p.start.dayOfWeek)
            assertEquals("end 必须是周日: $d", java.time.DayOfWeek.SUNDAY, p.end.dayOfWeek)
            assertTrue("end 必须严格早于 today: $d", p.end.isBefore(d))
            d = d.plusDays(1)
        }
    }
}
