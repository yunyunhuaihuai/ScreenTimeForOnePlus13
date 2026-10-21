package com.local.screentime

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.local.screentime.data.AppDatabase
import com.local.screentime.data.BatteryCaptureOutcome
import com.local.screentime.data.BatteryLedger
import com.local.screentime.data.CoverageGap
import com.local.screentime.data.UsageRepository
import com.local.screentime.privileged.CommandResult
import com.local.screentime.privileged.PrivilegedCommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * A（P0-1 复审）入口级回归：穿过真实 syncBattery 入口（shell→解析→诊断门控→
 * epoch 判定→账本事务）与真实 Room 文件库，验证「先识别 epoch、再应用适用的
 * 完整性判据」与「解析基线/epoch 标记/账本/快照在成功提交时协调推进」。
 *
 * 场景取自 2026-10-03 取证审查：旧 epoch 142 UID 的健康基线 vs 新 epoch 合法
 * 小样本（20 UID）——修复前该帧被 uid_count_drop 拒绝，epoch 与基线永远无法推进。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BatteryCaptureEntryTest {

    private lateinit var context: Context
    private lateinit var repo: UsageRepository
    private lateinit var runner: FakeRunner
    private lateinit var db: AppDatabase
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val window1 = ts(2026, 10, 1, 18, 5)
    private val window2 = ts(2026, 10, 2, 18, 5)

    /** 可编程 fake：按命令分发应答（batterystats / usagestats） */
    private class FakeRunner(
        var batteryLines: List<String> = emptyList(),
        var usageLines: List<String> = emptyList(),
    ) : PrivilegedCommandRunner {
        override suspend fun isRootAvailable(): Boolean = true
        override suspend fun exec(cmd: String): CommandResult = when {
            cmd.contains("batterystats") -> CommandResult(batteryLines, emptyList(), 0)
            cmd.contains("usagestats") -> CommandResult(usageLines, emptyList(), 0)
            else -> CommandResult(emptyList(), emptyList(), 0)
        }
    }

    /** 构造一帧 dump 文本：drain 概要 + 窗口起点 + n 个 UID */
    private fun dump(windowStart: Long, uidTotals: List<Double>): List<String> {
        val start = java.time.Instant.ofEpochMilli(windowStart).atZone(zone).toLocalDateTime()
        fun st(v: Double) = String.format(java.util.Locale.US, "%.4f", v)
        val sb = StringBuilder()
        sb.appendLine("  Start clock time: ${start.year}-${"%02d".format(start.monthValue)}-${"%02d".format(start.dayOfMonth)}-${"%02d".format(start.hour)}-${"%02d".format(start.minute)}-${"%02d".format(start.second)}")
        sb.appendLine("Estimated power use (mAh):")
        sb.appendLine("  Capacity: 5920, Computed drain: ${st(uidTotals.sum() + 100)}, actual drain: 150 - 160")
        uidTotals.forEachIndexed { i, v -> sb.appendLine("  UID u0a$i: ${st(v)} ( screen: ${st(v / 2)} cpu: ${st(v / 2)} )") }
        return sb.lines()
    }

    private fun capture(observedTs: Long): BatteryCaptureOutcome = runBlocking {
        repo.nowMs = { observedTs }   // 注入时钟：commandEnd/观测时刻可控
        repo.syncBattery(observedTs, observedTs - 100, com.local.screentime.data.BatterySource.ROOT)
    }

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        // in-memory Room（allowMainThreadQueries）：Robolectric 文件库的 SQLite shadow
        // 在多协程线程下会出现 Illegal connection pointer，入口级测试一律用内存库
        db = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // 旧 epoch 状态：142 UID 健康基线（真机 2026-10-02 形态）
        runner = FakeRunner(dump(window1, (0 until 142).map { 1.0 }))
        repo = UsageRepository(context, runner, db, useRootCollector = false)
        val dao = db.usageDao()
        dao.putState(com.local.screentime.data.SyncStateEntity(UsageRepository.KEY_PARSER_BASELINE_UIDS, 142))
        dao.putState(com.local.screentime.data.SyncStateEntity(UsageRepository.KEY_PARSER_HAD_WINDOW, 1))
        dao.putState(com.local.screentime.data.SyncStateEntity(UsageRepository.KEY_BATTERY_WINDOW_START_TS, window1))
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun dao() = db.usageDao()

    @Test
    fun old142_toNewEpoch20_toSame30_allAccepted() = runBlocking {
        // ① 旧 epoch（marker=window1）收到新窗口 window2 的 20 UID 合法帧：RESET → 放行
        runner.batteryLines = dump(window2, (0 until 20).map { 1.0 })
        val r1 = capture(ts(2026, 10, 2, 18, 6))
        assertTrue("新 epoch 小样本必须放行，实际 anomalies 拒绝: $r1", r1 is BatteryCaptureOutcome.Success)
        assertEquals(window2, dao().getState(UsageRepository.KEY_BATTERY_WINDOW_START_TS))
        assertEquals("基线随事务推进为新 epoch 的 20", 20L, dao().getState(UsageRepository.KEY_PARSER_BASELINE_UIDS))
        assertEquals("RESET 全量入账：20 UID 全部建快照", 20, dao().allBatterySnapshots().count { it.uidKey.startsWith("u0a") })

        // ② 同新 epoch 30 UID：基线 20，30 >= 20*0.5 → 放行
        runner.batteryLines = dump(window2, (0 until 30).map { 1.5 })
        val r2 = capture(ts(2026, 10, 2, 18, 21))
        assertTrue(r2 is BatteryCaptureOutcome.Success)
        assertEquals(30L, dao().getState(UsageRepository.KEY_PARSER_BASELINE_UIDS))
        assertEquals("epoch 标记不变", window2, dao().getState(UsageRepository.KEY_BATTERY_WINDOW_START_TS))
    }

    @Test
    fun sameEpochTruncation_rejected_baselineAndEpochUntouched_retrySucceeds() = runBlocking {
        // 先推进到新 epoch、基线 30
        runner.batteryLines = dump(window2, (0 until 30).map { 1.0 })
        assertTrue(capture(ts(2026, 10, 2, 18, 6)) is BatteryCaptureOutcome.Success)
        val day = LocalDate.of(2026, 10, 2).toEpochDay()
        val totalBefore = dao().batteryBetween(day, day)

        // ③ 同 epoch 被截断（30→10）：仍然检测，整帧拒绝
        runner.batteryLines = dump(window2, (0 until 10).map { 1.0 })
        val r = capture(ts(2026, 10, 2, 18, 21))
        assertTrue("同 epoch 截断必须拒绝", r is BatteryCaptureOutcome.Failed && r.stage == "parser")
        assertEquals("拒绝后基线不变", 30L, dao().getState(UsageRepository.KEY_PARSER_BASELINE_UIDS))
        assertEquals("拒绝后 epoch 标记不变", window2, dao().getState(UsageRepository.KEY_BATTERY_WINDOW_START_TS))
        assertEquals("拒绝后日账本不变", totalBefore, dao().batteryBetween(day, day), 1e-9)

        // ④ 重试一次（恢复 30 UID）：正确入账
        runner.batteryLines = dump(window2, (0 until 30).map { 1.5 })
        assertTrue(capture(ts(2026, 10, 2, 18, 36)) is BatteryCaptureOutcome.Success)
        assertTrue("重试后入账增量", dao().batteryBetween(day, day) > totalBefore)
    }

    @Test
    fun repeatedIdenticalFrame_zeroIncrement() = runBlocking {
        runner.batteryLines = dump(window2, (0 until 20).map { 2.0 })
        assertTrue(capture(ts(2026, 10, 2, 18, 6)) is BatteryCaptureOutcome.Success)
        val day = LocalDate.of(2026, 10, 2).toEpochDay()
        val after1 = dao().batteryBetween(day, day)

        runner.batteryLines = dump(window2, (0 until 20).map { 2.0 })
        assertTrue(capture(ts(2026, 10, 2, 18, 21)) is BatteryCaptureOutcome.Success)
        assertEquals("重复相同帧必须零增量", after1, dao().batteryBetween(day, day), 1e-9)
    }

    @Test
    fun newEpochUidAbsentThenReappears_notDiffedAgainstOldEpochBaseline() = runBlocking {
        // 旧 epoch：u0a0..u0a141 全部有快照（含新 epoch 首帧缺席的 u0a100）
        runner.batteryLines = dump(window1, (0 until 142).map { 5.0 })
        assertTrue(capture(ts(2026, 10, 1, 18, 6)) is BatteryCaptureOutcome.Success)
        assertNotNull(dao().getBatterySnapshot("u0a100"))

        // 新 epoch 首帧只有 u0a0..u0a19：u0a100 缺席，旧快照必须已被 RESET 清除
        runner.batteryLines = dump(window2, (0 until 20).map { 1.0 })
        assertTrue(capture(ts(2026, 10, 2, 18, 6)) is BatteryCaptureOutcome.Success)
        assertNull("RESET 必须清掉旧 epoch 快照（含本帧未出现的 UID）", dao().getBatterySnapshot("u0a100"))

        // 延迟重现：u0a100 新 epoch 值 1.0 —— 全额入账，不与旧基线 5.0 做差
        val day = LocalDate.of(2026, 10, 2).toEpochDay()
        val before = dao().batteryBetween(day, day)
        val lines = dump(window2, (0 until 20).map { 1.0 }).toMutableList()
        lines.add("  UID u0a100: 1.0000 ( screen: 0.5000 cpu: 0.5000 )")
        runner.batteryLines = lines
        assertTrue(capture(ts(2026, 10, 2, 18, 21)) is BatteryCaptureOutcome.Success)
        assertEquals("延迟重现 UID 全额入账", before + 1.0, dao().batteryBetween(day, day), 1e-6)
    }

    @Test
    fun gateRejection_doesNotClearPreviousDiagnostics() = runBlocking {
        runner.batteryLines = dump(window2, (0 until 20).map { 1.0 })
        assertTrue(capture(ts(2026, 10, 2, 18, 6)) is BatteryCaptureOutcome.Success)
        // 截断帧：诊断仍落盘（供回查），但基线不动
        runner.batteryLines = dump(window2, (0 until 5).map { 1.0 })
        capture(ts(2026, 10, 2, 18, 21))
        val diag = com.local.screentime.data.CaptureDiagnostics.fromJson(
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString(UsageRepository.PREF_LAST_BATTERY_DIAG, null)!!
        )!!
        assertTrue(diag.anomalies.any { it.startsWith("uid_count_drop") })
        assertEquals(5, diag.parsedUidCount)
        assertEquals("拒绝后基线保持此前健康帧的值", 20L, dao().getState(UsageRepository.KEY_PARSER_BASELINE_UIDS))
        assertEquals("拒绝后 epoch 标记不变", window2, dao().getState(UsageRepository.KEY_BATTERY_WINDOW_START_TS))
    }

    @Test
    fun captureLog_recordsSuccessAndRejection_withTimesAndVersions() = runBlocking {
        // v0.18（C）：每次尝试都进有界采集日志（成功与被拒帧都记），区分观测时刻与版本
        runner.batteryLines = dump(window2, (0 until 20).map { 1.0 })
        assertTrue(capture(ts(2026, 10, 2, 18, 6)) is BatteryCaptureOutcome.Success)
        runner.batteryLines = dump(window2, (0 until 5).map { 1.0 })
        capture(ts(2026, 10, 2, 18, 21))
        val logs = dao().recentCaptureLogs(10)
        assertEquals(2, logs.size)
        val success = logs.first { it.outcome == "SUCCESS" }
        assertEquals("观测时刻 = commandEnd", ts(2026, 10, 2, 18, 6), success.ts)
        assertEquals("attempt 与 command 时刻分开记录", ts(2026, 10, 2, 18, 6), success.attemptTs)
        assertEquals(window2, success.epochWindowStartTs)
        assertEquals("Asia/Shanghai", success.zoneId)
        assertEquals(20, success.uidCount)
        assertTrue(success.ledgerVersion > 0)
        assertTrue(success.parserVersion > 0)
        val rejected = logs.first { it.outcome.startsWith("FAILED") }
        assertTrue(rejected.detail.contains("uid_count_drop"))
        assertTrue("有界：上限 64", dao().recentCaptureLogs(100).size <= 64)
    }

    @Test
    fun epochReset_recordsLostTail() = runBlocking {
        // C：RESET 成功提交后记录「不可恢复尾段」= 上次成功采集 → 新窗口起点
        runner.batteryLines = dump(window1, (0 until 142).map { 1.0 })
        assertTrue(capture(ts(2026, 10, 1, 19, 0)) is BatteryCaptureOutcome.Success)
        // 新 epoch：充电发生在 10-02 18:05，上次成功采集 10-01 19:00 → [19:00, 18:05] 段永久丢失
        runner.batteryLines = dump(window2, (0 until 20).map { 1.0 })
        assertTrue(capture(ts(2026, 10, 2, 18, 6)) is BatteryCaptureOutcome.Success)
        assertEquals(ts(2026, 10, 1, 19, 0), dao().getState(UsageRepository.KEY_BATTERY_LOST_TAIL_FROM))
        assertEquals(window2, dao().getState(UsageRepository.KEY_BATTERY_LOST_TAIL_TO))
        val persistentGap = dao().collectionGaps("battery").single()
        assertEquals(ts(2026, 10, 1, 19, 0), persistentGap.fromTs)
        assertEquals(window2, persistentGap.toTs)
        // 同 epoch 后续成功不清掉尾段记录（它描述的是最近一次 RESET 的损失范围）
        runner.batteryLines = dump(window2, (0 until 20).map { 2.0 })
        assertTrue(capture(ts(2026, 10, 2, 18, 21)) is BatteryCaptureOutcome.Success)
        assertEquals(window2, dao().getState(UsageRepository.KEY_BATTERY_LOST_TAIL_TO))
        assertEquals("新成功不抹掉持久化历史缺口", listOf(persistentGap), dao().collectionGaps("battery"))
    }

    @Test
    fun dataHealthLine_variousStates() {
        // 时长、耗电、后台心跳各自计时，不能用最新的一项掩盖另一项中断。
        val now = ts(2026, 10, 2, 15, 0)
        val healthy = UsageRepository.DataHealthSummary(
            lastUsageSuccessTs = ts(2026, 10, 2, 9, 16), lastUsageFailTs = null,
            lastBatterySuccessTs = ts(2026, 10, 2, 14, 35), lastBatteryFailTs = null,
            maxCaptureGapMs = null, lastUsageSource = "ROOT", lastBatterySource = "ROOT",
            lostTailFromTs = null, lostTailToTs = null,
            collectorHeartbeatTs = now,
        )
        val line = UsageRepository.dataHealthLine(healthy, now, zone)
        assertTrue(line.contains("时长最后成功：10-02 09:16"))
        assertTrue(line.contains("耗电最后观测：10-02 14:35"))
        assertTrue(line.contains("后台采集心跳："))
        assertTrue("三条线均新鲜时不提示超时", !line.contains("已超时"))

        val staleBattery = UsageRepository.dataHealthLine(
            healthy.copy(lastUsageSuccessTs = now, lastBatterySuccessTs = now - 46 * 60_000L), now, zone,
        )
        assertTrue("最近时长成功不能盖住耗电超时", staleBattery.lineSequence().first { it.startsWith("耗电最后观测") }.contains("已超时"))
        assertTrue(staleBattery.lineSequence().first { it.startsWith("时长最后成功") }.contains("刚刚"))
        val later = now + 13 * 3600_000L
        val staleUsage = UsageRepository.dataHealthLine(
            healthy.copy(lastBatterySuccessTs = later, collectorHeartbeatTs = later), later, zone,
        )
        assertTrue("最近耗电成功不能盖住时长超时", staleUsage.lineSequence().first { it.startsWith("时长最后成功") }.contains("已超时"))
        assertTrue(!staleUsage.lineSequence().first { it.startsWith("耗电最后观测") }.contains("已超时"))

        val noData = healthy.copy(lastUsageSuccessTs = null, lastBatterySuccessTs = null)
        val noDataLine = UsageRepository.dataHealthLine(noData, now, zone)
        assertTrue(noDataLine.contains("时长最后成功：尚未记录"))
        assertTrue(noDataLine.contains("耗电最后观测：尚未记录"))

        val gappy = healthy.copy(maxCaptureGapMs = 4 * 3600_000L)
        assertTrue("实际观测最大间隔超 1h 才显示", UsageRepository.dataHealthLine(gappy, now, zone).contains("采样最大间隔"))
        val tiny = healthy.copy(maxCaptureGapMs = 15 * 60_000L)
        assertTrue("名义间隔不显示", !UsageRepository.dataHealthLine(tiny, now, zone).contains("采样最大间隔"))

        val historical = healthy.copy(
            usageGaps = listOf(CoverageGap(ts(2026, 10, 1, 9, 0), ts(2026, 10, 1, 18, 0))),
            batteryGaps = listOf(CoverageGap(ts(2026, 10, 1, 19, 0), ts(2026, 10, 2, 8, 0))),
            collectorError = "root采集器未启动",
        )
        val historicalLine = UsageRepository.dataHealthLine(historical, now, zone)
        assertTrue("最近观测不能抹掉历史时长缺口", historicalLine.contains("历史时长缺口：10-01 09:00 ～ 10-01 18:00"))
        assertTrue("最近观测不能抹掉历史耗电丢失", historicalLine.contains("历史耗电丢失：10-01 19:00 ～ 10-02 08:00"))
        assertTrue(historicalLine.contains("后台采集降级：root采集器未启动"))
    }
}
