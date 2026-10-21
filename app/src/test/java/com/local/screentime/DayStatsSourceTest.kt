package com.local.screentime

import android.app.usage.UsageEvents
import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.local.screentime.data.AppDatabase
import com.local.screentime.data.DayStatsEntity
import com.local.screentime.data.RawEvent
import com.local.screentime.data.SESSION_SOURCE_API
import com.local.screentime.data.SESSION_SOURCE_LEGACY
import com.local.screentime.data.SESSION_SOURCE_ROOT
import com.local.screentime.data.UsageRepository
import com.local.screentime.data.DayEventStatsResult
import com.local.screentime.privileged.RootCommandRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking

/**
 * F（v0.18，P1-3）：拿起/首开统计的来源与覆盖语义。
 * ROOT 权威可整日原子替换；API 残缺流不得覆盖 ROOT 完整日；部分窗口不当整天覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DayStatsSourceTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: UsageRepository
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // 场景日：D1=昨天（窗口首日，残）、D2=今天
    private val d1 = LocalDate.of(2026, 10, 2).toEpochDay()
    private val d2 = LocalDate.of(2026, 10, 3).toEpochDay()
    private val observedAt = ts(2026, 10, 3, 15, 0)
    // 24h 窗口起点 = 昨天 15:00（在 D1 中间 → D1 部分覆盖）
    private val windowStart = ts(2026, 10, 2, 15, 0)

    private fun ev(day: Long, hour: Int, minute: Int, type: Int, pkg: String): RawEvent {
        val date = LocalDate.ofEpochDay(day)
        val t = date.atStartOfDay(zone).plusHours(hour.toLong()).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
        return RawEvent(t, type, pkg)
    }

    private val RESUMED = UsageEvents.Event.ACTIVITY_RESUMED
    private val KEYGUARD = UsageEvents.Event.KEYGUARD_HIDDEN

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = UsageRepository(context, RootCommandRunner(), db, useRootCollector = false)
    }

    private fun dao() = db.usageDao()

    private fun statsResult(pickups: Map<Long, Long>, unlockFirst: Map<Long, Map<String, Long>>) =
        DayEventStatsResult(pickups, unlockFirst)

    // ---------- 纯函数计划器 ----------

    @Test
    fun firstDayPartialWindow_skipped_todayWritten() {
        // 窗口 [昨天15:00, 今天15:00]：D1 不从零点覆盖 → 跳过；D2 是今天 → 写入
        val res = statsResult(mapOf(d1 to 5L, d2 to 10L), mapOf(d1 to mapOf("com.a" to 2L), d2 to mapOf("com.a" to 4L)))
        val plan = UsageRepository.planDayEventStatsWrites(
            res, windowStart, observedAt, zone, SESSION_SOURCE_ROOT, emptyMap(),
        )
        assertTrue(!plan.replaceDays.contains(d1))
        assertTrue(plan.replaceDays.contains(d2))
    }

    @Test
    fun onlyTodayWindow_firstDayEqualsToday_written() {
        // 验收「只有当天事件」：新装机当天窗口只有今天 → 即使是窗口首日也要写入
        val res = statsResult(mapOf(d2 to 3L), mapOf(d2 to mapOf("com.a" to 1L)))
        val plan = UsageRepository.planDayEventStatsWrites(
            res, ts(2026, 10, 3, 9, 0), observedAt, zone, SESSION_SOURCE_ROOT, emptyMap(),
        )
        assertTrue(plan.replaceDays.contains(d2))
    }

    @Test
    fun apiSource_neverOverwritesRootOrLegacyDay() {
        // 验收「ROOT完整日 -> API残缺」：D2 已有 ROOT 权威行，API 计算更小也不得覆盖
        val res = statsResult(mapOf(d2 to 2L), mapOf(d2 to mapOf("com.a" to 1L)))
        val plan = UsageRepository.planDayEventStatsWrites(
            res, windowStart, observedAt, zone, SESSION_SOURCE_API,
            mapOf(d2 to SESSION_SOURCE_ROOT),
        )
        assertTrue("API 不得覆盖 ROOT 日", plan.replaceDays.isEmpty())
        // LEGACY（v9 前历史行）同样按权威对待
        val plan2 = UsageRepository.planDayEventStatsWrites(
            res, windowStart, observedAt, zone, SESSION_SOURCE_API,
            mapOf(d2 to SESSION_SOURCE_LEGACY),
        )
        assertTrue("API 不得覆盖 LEGACY 日", plan2.replaceDays.isEmpty())
        // API 自己写的日子可以更新
        val plan3 = UsageRepository.planDayEventStatsWrites(
            res, windowStart, observedAt, zone, SESSION_SOURCE_API,
            mapOf(d2 to SESSION_SOURCE_API),
        )
        assertTrue(plan3.replaceDays.contains(d2))
        // 无任何行的日期 API 可以首次写入
        val plan4 = UsageRepository.planDayEventStatsWrites(
            res, windowStart, observedAt, zone, SESSION_SOURCE_API, emptyMap(),
        )
        assertTrue(plan4.replaceDays.contains(d2))
    }

    @Test
    fun rootSource_overwritesEverything_forCorrection() {
        // 验收「不以 max 锁死」：ROOT 可以纠正历史过计数（包括 API/LEGACY 写过的日）
        val res = statsResult(mapOf(d2 to 8L), emptyMap())
        val plan = UsageRepository.planDayEventStatsWrites(
            res, ts(2026, 10, 3, 0, 5), observedAt, zone, SESSION_SOURCE_ROOT,
            mapOf(d2 to SESSION_SOURCE_API),
        )
        assertTrue(plan.replaceDays.contains(d2))
    }

    // ---------- 入口级行为（真实 Room） ----------

    @Test
    fun entry_apiCanCreateUnobservedDay_withoutInventingLegacyAuthority() = runBlocking {
        val events = listOf(
            ev(d2, 9, 0, KEYGUARD, "x"),
            ev(d2, 9, 1, RESUMED, "com.new"),
        )
        repo.syncDayEventStats(events, zone, SESSION_SOURCE_API, observedAt)
        assertEquals(1L, dao().getDayStats(d2)!!.pickups)
        assertEquals(SESSION_SOURCE_API, dao().getDayStats(d2)!!.source)
    }

    @Test
    fun entry_rootReplaceClearsVanishedPackage_andCorrectsCount() = runBlocking {
        // D2 已有 ROOT 统计（含包 com.old 首开 3 次）
        dao().upsertDayStats(listOf(DayStatsEntity(d2, 4L, SESSION_SOURCE_ROOT)))
        dao().upsertUnlockStats(listOf(com.local.screentime.data.UnlockStatsEntity(d2, "com.old", 3L, SESSION_SOURCE_ROOT)))
        // 新的 ROOT 事件：D2 拿起 2 次、首开只有 com.new（com.old 已消失；RESUMED 在解锁后 5s 内）
        val events = listOf(
            ev(d2, 9, 0, KEYGUARD, "x"),
            ev(d2, 9, 0, RESUMED, "com.new"),
            ev(d2, 12, 0, KEYGUARD, "x"),
            ev(d2, 12, 0, RESUMED, "com.new"),
        )
        repo.syncDayEventStats(events, zone, SESSION_SOURCE_ROOT, observedAt)
        assertEquals("拿起整日替换纠错", 2L, dao().getDayStats(d2)!!.pickups)
        assertEquals("替换后行携带 ROOT 来源", SESSION_SOURCE_ROOT, dao().getDayStats(d2)!!.source)
        val unlock = dao().unlockStatsForDay(d2)
        assertTrue("已消失包的首开旧行被清除", unlock.none { it.packageName == "com.old" })
        assertTrue("新包首开行存在", unlock.any { it.packageName == "com.new" })
    }

    @Test
    fun entry_apiTrimDoesNotShrinkRootCount_rootRestoreCorrects() = runBlocking {
        // ① ROOT 完整日：拿起 10 次
        val rootEvents = (0 until 10).flatMap { i ->
            listOf(
                ev(d2, 8, i * 10, KEYGUARD, "x"),
                ev(d2, 8, i * 10 + 1, RESUMED, "com.new"),
            )
        }
        repo.syncDayEventStats(rootEvents, zone, SESSION_SOURCE_ROOT, observedAt)
        assertEquals(10L, dao().getDayStats(d2)!!.pickups)
        // ② API 残缺流（只看到 1 次拿起）：不得把 ROOT 完整日覆盖偏小
        val apiEvents = listOf(
            ev(d2, 8, 0, KEYGUARD, "x"),
            ev(d2, 8, 1, RESUMED, "com.new"),
        )
        repo.syncDayEventStats(apiEvents, zone, SESSION_SOURCE_API, observedAt)
        assertEquals("API 残缺不得覆盖 ROOT 计数", 10L, dao().getDayStats(d2)!!.pickups)
        // ③ ROOT 恢复：可修正（写成真实 6 次）
        val rootEvents2 = (0 until 6).flatMap { i ->
            listOf(
                ev(d2, 8, i * 10, KEYGUARD, "x"),
                ev(d2, 8, i * 10 + 1, RESUMED, "com.new"),
            )
        }
        repo.syncDayEventStats(rootEvents2, zone, SESSION_SOURCE_ROOT, observedAt)
        assertEquals("ROOT 恢复可纠错（不以 max 锁死历史过计数）", 6L, dao().getDayStats(d2)!!.pickups)
    }
}
