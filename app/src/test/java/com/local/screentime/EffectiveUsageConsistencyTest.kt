package com.local.screentime

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.local.screentime.data.AppDatabase
import com.local.screentime.data.DailyUsageEntity
import com.local.screentime.data.UsageDao
import com.local.screentime.data.UsageRepository
import com.local.screentime.data.UsageSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * P0-4 effective usage 唯一口径的一致性测试（Robolectric + 真实 Room）。
 *
 * 口径：对每个 (package, day) 取 max(会话聚合, usage_daily 回补值)；周期总量 =
 * 区间内各日 effective 值之和。UI（dailyRows）与周/月/年报（effectiveUsageBetween）
 * 必须给出完全一致的总量与 Top 排序——旧版报告单独读 sessions 表，历史回补值
 * （2026-09-04～09-12 旧 STOPPED bug 修正数据）进不了周报/月报。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EffectiveUsageConsistencyTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: UsageDao

    /** 2026-09-14（周一）.. 09-20（周日）作为样本周 */
    private val d1 = LocalDate.of(2026, 9, 14).toEpochDay()
    private val d2 = LocalDate.of(2026, 9, 15).toEpochDay()

    private val min = 60_000L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.usageDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    /** 在 day 当天给 pkg 造一段时长 minutes 分钟的会话（位于白天，不跨午夜） */
    private fun session(pkg: String, day: Long, minutes: Long, startMinuteOfDay: Long = 600) {
        val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai"))
            .toInstant().toEpochMilli()
        val start = dayStart + startMinuteOfDay * min
        runBlocking {
            dao.insertSessions(listOf(UsageSession(packageName = pkg, startTs = start, endTs = start + minutes * min, day = day)))
        }
    }

    private fun agg(pkg: String, day: Long, minutes: Long) {
        runBlocking {
            dao.upsertDaily(listOf(DailyUsageEntity(pkg, day, minutes * min, UsageRepository.SOURCE_ROOT)))
        }
    }

    @Test
    fun effectiveUsageDay_takesMaxOfSessionsAndBackfill() = runBlocking {
        // 会话 < 回补（旧 STOPPED bug 丢时长的历史日形态）
        session("com.a", d1, minutes = 10); agg("com.a", d1, minutes = 30)
        // 会话 > 回补（root 正常日形态）
        session("com.b", d1, minutes = 50); agg("com.b", d1, minutes = 20)
        // 仅回补（会话全丢）
        agg("com.c", d1, minutes = 5)
        // 仅会话（无回补）
        session("com.d", d1, minutes = 7)

        val rows = dao.effectiveUsageDay(d1).associate { it.packageName to it.totalMs }
        assertEquals(30 * min, rows["com.a"])
        assertEquals(50 * min, rows["com.b"])
        assertEquals(5 * min, rows["com.c"])
        assertEquals(7 * min, rows["com.d"])
        // 排序与 dailyRows（UI）一致：按 totalMs 降序
        val ordered = dao.effectiveUsageDay(d1).map { it.packageName }
        assertEquals(listOf("com.b", "com.a", "com.d", "com.c"), ordered)
    }

    @Test
    fun reportPeriodTotal_equalsSumOfUiDailyRows() = runBlocking {
        // 两天、两个应用、不同来源 mix：
        session("com.a", d1, 10); agg("com.a", d1, 30)   // d1 effective = 30
        session("com.a", d2, 3)                           // d2 effective = 3
        session("com.b", d1, 50); agg("com.b", d1, 20)   // d1 = 50
        agg("com.b", d2, 12); session("com.b", d2, 40)   // d2 = 40

        // UI 口径：逐日 dailyRows（effectiveUsageDay）求和
        val uiPerPkg = HashMap<String, Long>()
        for (day in d1..d2) {
            for (r in dao.effectiveUsageDay(day)) {
                uiPerPkg[r.packageName] = (uiPerPkg[r.packageName] ?: 0L) + r.totalMs
            }
        }
        // 报告口径：区间 effectiveUsageBetween 一次查询
        val reportPerPkg = dao.effectiveUsageBetween(d1, d2).associate { it.packageName to it.totalMs }

        assertEquals(uiPerPkg, reportPerPkg)
        assertEquals(33 * min, reportPerPkg["com.a"])  // 30 + 3：回补值与会话值逐日分别取 max 后求和
        assertEquals(90 * min, reportPerPkg["com.b"])  // 50 + 40
    }

    @Test
    fun reportTopApps_matchUiOrdering() = runBlocking {
        session("com.a", d1, 10); agg("com.a", d1, 30)
        session("com.b", d1, 50)
        session("com.c", d1, 45)

        val report = dao.effectiveUsageBetween(d1, d1).map { it.packageName }
        val ui = dao.effectiveUsageDay(d1).map { it.packageName }
        assertEquals(ui, report)
        assertEquals(listOf("com.b", "com.c", "com.a"), report.take(3))
    }

    @Test
    fun backfillOnlyDay_countsInReport() {
        runBlocking {
            // 关键回归场景：某天会话被旧 bug 全部丢弃、只剩 usage_daily 回补值——
            // 旧报告（仅读 sessions）会把这一天的时长整个丢掉，新口径必须计入
            agg("com.c", d1, 120)
            val reportPerPkg = dao.effectiveUsageBetween(d1, d1).associate { it.packageName to it.totalMs }
            assertEquals(120 * min, reportPerPkg["com.c"])
        }
    }
}
