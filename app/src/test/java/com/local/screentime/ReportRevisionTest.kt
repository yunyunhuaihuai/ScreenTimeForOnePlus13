package com.local.screentime

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.local.screentime.data.AppDatabase
import com.local.screentime.data.ReportEntity
import com.local.screentime.data.ReportPeriods
import com.local.screentime.data.UsageRepository
import com.local.screentime.data.UsageSession
import com.local.screentime.data.SESSION_SOURCE_ROOT
import com.local.screentime.privileged.RootCommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * G（v0.18，P1-4）：正式报告与数据修订一致。
 * 环比按上一自然周期解析（不按本期天数平移）、过早「正式」报告降级、
 * 数据修订/格式版本触发静默重建、无观测日不输出成可信 0。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReportRevisionTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: UsageRepository
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = UsageRepository(context, RootCommandRunner(), db, useRootCollector = false)
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun dao() = db.usageDao()

    // ---------- 上一自然周期 ----------

    @Test
    fun previousPeriod_monthly_30vs31days_febLeap() {
        // 31 天月（7 月）的上一周期 = 6 月 30 天，不再按本期天数向前平移
        val july = ReportPeriods.lastCompletedShifted(LocalDate.of(2026, 8, 5), ReportPeriods.REPORT_MONTHLY, 0)
        assertEquals(LocalDate.of(2026, 7, 1), july.start)
        assertEquals(LocalDate.of(2026, 7, 31), july.end)
        val june = ReportPeriods.previousPeriod(july)
        assertEquals(LocalDate.of(2026, 6, 1), june.start)
        assertEquals("6 月自然月 30 天", LocalDate.of(2026, 6, 30), june.end)
        // 3 月月报（=2 月）环比对象 = 1 月的 31 天（非「向前平移 28 天」）
        val febPeriod = ReportPeriods.lastCompletedShifted(LocalDate.of(2026, 3, 3), ReportPeriods.REPORT_MONTHLY, 0)
        assertEquals("平年 2 月自然月 28 天", LocalDate.of(2026, 2, 28), febPeriod.end)
        assertEquals("2 月月报的环比区间 = 1 月自然月", LocalDate.of(2026, 1, 1), ReportPeriods.previousPeriod(febPeriod).start)
        assertEquals(LocalDate.of(2026, 1, 31), ReportPeriods.previousPeriod(febPeriod).end)
        // 闰年 2 月 29 天
        val febLeap = ReportPeriods.lastCompletedShifted(LocalDate.of(2028, 3, 3), ReportPeriods.REPORT_MONTHLY, 0)
        assertEquals(LocalDate.of(2028, 2, 29), febLeap.end)
    }

    @Test
    fun previousPeriod_weeklyAndYearly() {
        val week = ReportPeriods.lastCompletedShifted(LocalDate.of(2026, 9, 28), ReportPeriods.REPORT_WEEKLY, 0)
        val prevWeek = ReportPeriods.previousPeriod(week)
        assertEquals(week.start.minusDays(7), prevWeek.start)
        assertEquals(week.end.minusDays(7), prevWeek.end)
        val year = ReportPeriods.lastCompletedShifted(LocalDate.of(2026, 3, 1), ReportPeriods.REPORT_YEARLY, 0)
        assertEquals("Y:2025", year.key)
        assertEquals(LocalDate.of(2025, 1, 1), year.start)
        assertEquals(LocalDate.of(2025, 12, 31), year.end)
        // Y:2026 的上一自然年 = Y:2025
        val y2026 = ReportPeriods.periodFromKey(ReportPeriods.REPORT_YEARLY, "Y:2026")!!
        val prevOf2026 = ReportPeriods.previousPeriod(y2026)
        assertEquals(LocalDate.of(2025, 1, 1), prevOf2026.start)
        assertEquals(LocalDate.of(2025, 12, 31), prevOf2026.end)
    }

    @Test
    fun periodFromKey_reconstructsPrematureYearly() {
        // 审查实证：Y:2026 于 9/12 提前生成且 finalized=1 —— 必须能由 key 重建周期并识别
        val p = ReportPeriods.periodFromKey(ReportPeriods.REPORT_YEARLY, "Y:2026")!!
        assertEquals(LocalDate.of(2026, 1, 1), p.start)
        assertEquals(LocalDate.of(2026, 12, 31), p.end)
        val existing = ReportEntity(
            type = "YEARLY", periodKey = "Y:2026", generatedTs = ts(2026, 9, 12, 10, 0),
            text = "43h18m", finalized = true,
        )
        assertTrue("9/12 生成 = 早于周期结束，过早", ReportPeriods.premature(existing, p, zone))
        assertTrue(ReportPeriods.needsFinalizedGeneration(existing, p, zone))
    }

    // ---------- 入口级：降级 / 修订重建 / 覆盖 ----------

    @Test
    fun entry_prematureFinalizedReport_demotedToPreview_whileUnfinished() = runBlocking {
        // 复现真机存量形态：进行中年报 finalized=1
        dao().putReport(
            ReportEntity("YEARLY", "Y:2026", ts(2026, 9, 12, 10, 0), "旧文本", finalized = true)
        )
        repo.demotePrematureFinalizedReports(LocalDate.of(2026, 10, 3))
        val r = dao().getReport("YEARLY", "Y:2026")!!
        assertFalse("未结束周期的「正式」报告必须降级为预览", r.finalized)
        // 已结束周期的正式报告不受影响
        dao().putReport(
            ReportEntity("YEARLY", "Y:2025", ts(2027, 1, 2, 10, 0), "旧文本", finalized = true)
        )
        repo.demotePrematureFinalizedReports(LocalDate.of(2026, 10, 3))
        assertTrue(dao().getReport("YEARLY", "Y:2025")!!.finalized)
    }

    @Test
    fun entry_revisionBump_rebuildsStaleReport_silently_idempotent() = runBlocking {
        // 先以修订号 0 生成 2025 年报（周期已结束）
        repo.nowMs = { ts(2026, 1, 10, 9, 0) }
        repo.generateDueReports(false)
        val first = dao().getReport("YEARLY", "Y:2025")!!
        assertTrue(first.finalized)
        assertEquals(0L, first.dataRevision)
        val tsFirst = first.generatedTs
        // 数据修订（模拟 ROOT 纠错后 +1）：报告需要静默重建
        dao().putState(com.local.screentime.data.SyncStateEntity(UsageRepository.KEY_DATA_REVISION, 1))
        repo.nowMs = { ts(2026, 1, 10, 9, 30) }
        repo.generateDueReports(false)
        val second = dao().getReport("YEARLY", "Y:2025")!!
        assertTrue(second.generatedTs > tsFirst)
        assertEquals("重建后携带当前修订号", 1L, second.dataRevision)
        assertEquals("重建后携带当前格式版本", UsageRepository.REPORT_FORMAT_VERSION, second.formatVersion)
        // 幂等：同修订号下再次生成不重写
        repo.nowMs = { ts(2026, 1, 10, 9, 40) }
        repo.generateDueReports(false)
        assertEquals("幂等：不再重建", second.generatedTs, dao().getReport("YEARLY", "Y:2025")!!.generatedTs)
    }

    @Test
    fun entry_formatVersionUpgrade_rewritesOldWording() = runBlocking {
        // 存量月报（v1 格式 + 旧「总耗电」文案）→ 按新格式版本静默重建
        repo.nowMs = { ts(2026, 10, 5, 9, 0) }
        repo.generateDueReports(false)
        // 手工改回 v1 形态（模拟升级前旧报告）
        dao().putReport(
            ReportEntity("MONTHLY", "M:2026-9", ts(2026, 10, 1, 9, 0), "9月月报\n总耗电：123 mAh", finalized = true)
        )
        repo.generateDueReports(false)
        val rebuilt = dao().getReport("MONTHLY", "M:2026-9")!!
        assertTrue("旧文案被更新", !rebuilt.text.contains("总耗电"))
        assertEquals("格式版本刷新", UsageRepository.REPORT_FORMAT_VERSION, rebuilt.formatVersion)
    }

    @Test
    fun entry_reportShowsCoverageDays_andNoFalseNewWhenPrevEmpty() = runBlocking {
        // 上一完整周（相对 2026-10-05 周一 = 9/28~10/4）：只在 9/29 有观测
        val start = ts(2026, 9, 29, 10, 0)
        dao().insertSessions(
            listOf(
                UsageSession(
                    packageName = "com.a", startTs = start, endTs = start + 30 * 60_000L,
                    day = LocalDate.of(2026, 9, 29).toEpochDay(), source = SESSION_SOURCE_ROOT,
                )
            )
        )
        repo.nowMs = { ts(2026, 10, 5, 9, 0) }
        repo.generateDueReports(false)
        val weekly = dao().getReport("WEEKLY", "W:2026-09-28")!!
        assertTrue("报告标注覆盖天数", weekly.text.contains("数据覆盖：1/7 天"))
        assertTrue("未观测日不计为使用", weekly.text.contains("无观测记录"))
        // 对照上一周期（9/21~9/27）完全无观测：不输出「（新增）」
        assertTrue("缺对照覆盖时不得误报新增", !weekly.text.contains("（新增）"))
        assertTrue(weekly.text.contains("上一周期无数据"))
        assertTrue(weekly.text.contains("对比上一周期"))
    }
}
