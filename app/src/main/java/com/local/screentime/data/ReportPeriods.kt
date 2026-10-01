package com.local.screentime.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** 报告周期。finalized=true 为正式报告（周期已完整结束后生成）；false 为当前周期预览（数据不完整）。 */
data class ReportPeriod(
    val type: String,
    val key: String,
    val title: String,
    val start: LocalDate,
    val end: LocalDate,
    val finalized: Boolean,
)

/**
 * 报告周期解析器（v0.17.0 重设计，P0-3）。
 *
 * 旧实现的问题：`currentPeriods(today)` 返回「今天所在的进行中周期」，再以
 * `today ∈ [end, end+7]` 判断是否补生成——周期一切换（周一 / 次月 1 日 / 次年 1 月 1 日）
 * currentPeriods 就指向新周期，上一周期永远补不上；反而在周期最后一天会把尚未结束的
 * 周期当成正式报告提前生成。
 *
 * 新语义：
 *  - 正式报告一律针对「最近一个已完整结束的周期」：周报 = 上一个 Mon–Sun；
 *    月报 = 上一个自然月；年报 = 上一个自然年；
 *  - 当前进行中的周期只允许生成「预览」（finalized=false），周期结束后被同 key 的
 *    正式报告覆盖。
 *
 * periodKey 沿用旧格式（W:<周一日期> / M:<年>-<月> / Y:<年>），旧版本生成的报告
 * 既能被识别，迁移后也能按「生成时间早于周期结束 = 过早」规则升级为正式口径。
 */
object ReportPeriods {
    const val REPORT_WEEKLY = "WEEKLY"
    const val REPORT_MONTHLY = "MONTHLY"
    const val REPORT_YEARLY = "YEARLY"

    /** 每类报告自动补生成的最大回溯深度：ColorOS 杀后台激进，设备可能连续多周期
     *  一次同步都没跑成；回溯有界，避免对更早的空数据周期无限补生成。 */
    fun backfillLimit(type: String): Long = when (type) {
        REPORT_WEEKLY -> 8L
        REPORT_MONTHLY -> 6L
        else -> 2L
    }

    /**
     * 有界回溯补生成计划（P1-12）：逐周期检查——已有正式报告的**跳过并继续检查更早
     * 周期**（continue），缺失/过早的进入计划（generate）。
     *
     * 旧实现 `if (!needsFinalizedGeneration) break`：shift0 已存在就停止扫描——
     * 「shift0 已有、shift1 缺失、shift2 已有」时 shift1 永远不会补（例如 ColorOS
     * 连杀两周后台后第三周恢复，中间周的报告永久缺失）。
     *
     * 返回 (shift, period) 对；shift==0 的最新周期由调用方决定是否推送通知。
     */
    suspend fun backfillPlan(
        today: LocalDate,
        type: String,
        lookup: suspend (type: String, periodKey: String) -> ReportEntity?,
        zone: ZoneId,
    ): List<Pair<Long, ReportPeriod>> {
        val out = ArrayList<Pair<Long, ReportPeriod>>()
        for (shift in 0 until backfillLimit(type)) {
            val p = lastCompletedShifted(today, type, shift)
            val existing = lookup(p.type, p.key)
            if (!needsFinalizedGeneration(existing, p, zone)) continue
            out.add(shift to p)
        }
        return out
    }

    /**
     * 第 shift 个「已完整结束」的周期（shift=0 即最近一个）。
     * 周报用 previous(SUNDAY)（严格早于 today 的最近周日）：周一→昨天，周日→7 天前，
     * 因此该 Mon–Sun 周此时必然已完整结束，无需再按星期几分支。
     */
    fun lastCompletedShifted(today: LocalDate, type: String, shift: Long): ReportPeriod = when (type) {
        REPORT_WEEKLY -> {
            val end = today.with(TemporalAdjusters.previous(DayOfWeek.SUNDAY)).minusWeeks(shift)
            val start = end.minusDays(6)
            ReportPeriod(type, "W:$start", "$start ~ $end 周报", start, end, finalized = true)
        }
        REPORT_MONTHLY -> {
            val start = today.withDayOfMonth(1).minusMonths(1 + shift)
            val end = start.withDayOfMonth(start.lengthOfMonth())
            ReportPeriod(
                type, "M:${start.year}-${start.monthValue}",
                "${start.year}年${start.monthValue}月 月报", start, end, finalized = true,
            )
        }
        else -> {
            val year = (today.year - 1 - shift).toInt()
            ReportPeriod(
                type, "Y:$year", "$year 年报",
                LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), finalized = true,
            )
        }
    }

    /** 最近一个已完整结束的周期（周/月/年三类） */
    fun lastCompleted(today: LocalDate): List<ReportPeriod> = listOf(
        lastCompletedShifted(today, REPORT_WEEKLY, 0),
        lastCompletedShifted(today, REPORT_MONTHLY, 0),
        lastCompletedShifted(today, REPORT_YEARLY, 0),
    )

    /** 当前进行中的周期（仅手动预览用，finalized=false） */
    fun currentPreview(today: LocalDate): List<ReportPeriod> {
        val weeklyEnd = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val weeklyStart = weeklyEnd.minusDays(6)
        val monthStart = today.withDayOfMonth(1)
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        return listOf(
            ReportPeriod(
                REPORT_WEEKLY, "W:$weeklyStart", "$weeklyStart ~ $weeklyEnd 周报",
                weeklyStart, weeklyEnd, finalized = false,
            ),
            ReportPeriod(
                REPORT_MONTHLY, "M:${today.year}-${today.monthValue}",
                "${today.year}年${today.monthValue}月 月报", monthStart, monthEnd, finalized = false,
            ),
            ReportPeriod(
                REPORT_YEARLY, "Y:${today.year}", "${today.year} 年报",
                LocalDate.of(today.year, 1, 1), LocalDate.of(today.year, 12, 31), finalized = false,
            ),
        )
    }

    /** 周期结束时刻（本地次日 0 点）的毫秒时间戳 */
    fun periodEndInstantTs(p: ReportPeriod, zone: ZoneId): Long =
        p.end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * 正式报告是否需要（重新）生成：无记录，或既有记录生成于周期结束之前
     * （旧版本在周期最后一天提前生成的「正式」报告、或手动预览）。
     * 预览与正式报告共用同一 periodKey，周期结束后由正式报告覆盖。
     */
    fun needsFinalizedGeneration(existing: ReportEntity?, p: ReportPeriod, zone: ZoneId): Boolean =
        existing == null || existing.generatedTs < periodEndInstantTs(p, zone)
}
