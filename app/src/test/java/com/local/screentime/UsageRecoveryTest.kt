package com.local.screentime

import android.app.Application
import android.app.usage.UsageEvents
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.local.screentime.data.AppDatabase
import com.local.screentime.data.CollectionGapEntity
import com.local.screentime.data.DayStatsEntity
import com.local.screentime.data.RawEvent
import com.local.screentime.data.SESSION_SOURCE_ROOT
import com.local.screentime.data.SyncStateEntity
import com.local.screentime.data.UnlockStatsEntity
import com.local.screentime.data.UsageCoverageEntity
import com.local.screentime.data.UsageDao
import com.local.screentime.data.UsageRepository
import com.local.screentime.data.UsageSession
import com.local.screentime.data.VerifiedRange
import com.local.screentime.data.applyVerifiedUsage
import com.local.screentime.data.intersectRanges
import com.local.screentime.data.mergeRanges
import com.local.screentime.data.subtractRanges
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/** 真实 Room 事务验证：修复认证范围，同时保住窗口边界和历史缺口证据。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UsageRecoveryTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: UsageDao
    private lateinit var originalTimeZone: TimeZone
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 10, 18)
    private val day = date.toEpochDay()
    private val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun t(minute: Long) = dayStart + minute * 60_000L

    @Before
    fun setup() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.usageDao()
    }

    @After
    fun teardown() {
        db.close()
        TimeZone.setDefault(originalTimeZone)
    }

    private fun session(pkg: String, from: Long, to: Long) = UsageSession(
        packageName = pkg, startTs = t(from), endTs = t(to), day = day, source = SESSION_SOURCE_ROOT,
    )

    private fun event(minute: Long, type: Int, pkg: String = "android") = RawEvent(t(minute), type, pkg)

    private fun dailyEvents(includeMidnightBoundary: Boolean = true): List<RawEvent> = buildList {
        if (includeMidnightBoundary) add(event(0, UsageEvents.Event.SCREEN_NON_INTERACTIVE))
        add(event(480, UsageEvents.Event.KEYGUARD_HIDDEN))
        add(RawEvent(t(480) + 1_000, UsageEvents.Event.ACTIVITY_RESUMED, "com.actual"))
        add(event(510, UsageEvents.Event.ACTIVITY_PAUSED, "com.actual"))
    }

    private suspend fun recover(
        events: List<RawEvent>,
        ranges: List<VerifiedRange> = listOf(VerifiedRange(t(0), t(1440))),
        probe: (suspend () -> Unit)? = null,
    ) = applyVerifiedUsage(db, dao, events, ranges, t(1500), zone, "root-buckets", probe)

    private suspend fun sessionValues(): List<Triple<String, Long, Long>> =
        dao.sessionsOfDay(day).map { Triple(it.packageName, it.startTs, it.endTs) }

    @Test
    fun partialWindowPreservesBothBoundaryTailsAndWholeDayPickupCounts() = runBlocking {
        dao.insertSessions(listOf(
            session("com.left", 570, 630),
            session("com.obsolete", 630, 660),
            session("com.right", 690, 780),
            session("com.outside", 800, 810),
        ))
        val oldStats = DayStatsEntity(day, 99, SESSION_SOURCE_ROOT)
        val oldUnlock = UnlockStatsEntity(day, "com.history", 33, SESSION_SOURCE_ROOT)
        dao.upsertDayStats(listOf(oldStats))
        dao.upsertUnlockStats(listOf(oldUnlock))
        recover(
            listOf(
                event(600, UsageEvents.Event.ACTIVITY_RESUMED, "com.new"),
                event(610, UsageEvents.Event.ACTIVITY_PAUSED, "com.new"),
                event(660, UsageEvents.Event.KEYGUARD_HIDDEN),
                RawEvent(t(660) + 1_000, UsageEvents.Event.ACTIVITY_RESUMED, "com.next"),
                event(675, UsageEvents.Event.ACTIVITY_PAUSED, "com.next"),
            ), listOf(VerifiedRange(t(600), t(720))),
        )
        assertEquals(listOf(
            Triple("com.left", t(570), t(600)),
            Triple("com.new", t(600), t(610)),
            Triple("com.next", t(660) + 1_000, t(675)),
            Triple("com.right", t(720), t(780)),
            Triple("com.outside", t(800), t(810)),
        ), sessionValues())
        assertEquals(oldStats, dao.getDayStats(day))
        assertEquals(listOf(oldUnlock), dao.unlockStatsForDay(day))
        val coverage = dao.coverageOfDay(day)!!
        assertEquals(1, coverage.status)
        assertEquals(t(600), coverage.fromTs)
        assertEquals(t(720), coverage.toTs)
    }

    @Test
    fun certifiedFullDayCorrectsOvercountEvenWhenFirstUseIsAfterMidnight() = runBlocking {
        dao.insertSessions(listOf(session("com.actual", 490, 900), session("com.ghost", 1000, 1100)))
        dao.upsertDayStats(listOf(DayStatsEntity(day, 99, SESSION_SOURCE_ROOT)))
        dao.upsertUnlockStats(listOf(UnlockStatsEntity(day, "com.ghost", 99, SESSION_SOURCE_ROOT)))
        // 可信桶从午夜完整覆盖；首个使用事件在上午，不能据此误判为部分日。
        recover(dailyEvents(includeMidnightBoundary = false).reversed())
        assertEquals(listOf(Triple("com.actual", t(480) + 1_000, t(510))), sessionValues())
        assertEquals(1L, dao.getDayStats(day)!!.pickups)
        assertEquals(mapOf("com.actual" to 1L), dao.unlockStatsForDay(day).associate { it.packageName to it.count })
        val coverage = dao.coverageOfDay(day)!!
        assertEquals(2, coverage.status)
        assertEquals(t(0), coverage.fromTs)
        assertEquals(t(1440), coverage.toTs)
        assertEquals(1L, dao.getState(UsageRepository.KEY_DATA_REVISION))
    }

    @Test
    fun fullDayWithNoUnlocksRemovesObsoleteUnlockRowsAndWritesZero() = runBlocking {
        dao.upsertDayStats(listOf(DayStatsEntity(day, 99, SESSION_SOURCE_ROOT)))
        dao.upsertUnlockStats(listOf(UnlockStatsEntity(day, "com.ghost", 99, SESSION_SOURCE_ROOT)))
        recover(emptyList())
        assertEquals(0L, dao.getDayStats(day)!!.pickups)
        assertTrue(dao.unlockStatsForDay(day).isEmpty())
        assertEquals(2, dao.coverageOfDay(day)!!.status)
    }

    @Test
    fun repeatedImportDoesNotChangeTotalsRevisionOrSessionIdentity() = runBlocking {
        recover(dailyEvents())
        val firstSessions = dao.sessionsOfDay(day)
        val total = dao.dailyUsage(day).sumOf { it.totalMs }
        val revision = dao.getState(UsageRepository.KEY_DATA_REVISION)
        val pickups = dao.getDayStats(day)
        recover(dailyEvents())
        assertEquals(firstSessions, dao.sessionsOfDay(day))
        assertEquals(total, dao.dailyUsage(day).sumOf { it.totalMs })
        assertEquals(revision, dao.getState(UsageRepository.KEY_DATA_REVISION))
        assertEquals(pickups, dao.getDayStats(day))
        assertEquals(t(1440), dao.getState(UsageRepository.KEY_WATERMARK))
    }

    @Test
    fun failureBeforeCommitRollsBackSessionsStatsWatermarkCoverageGapsAndRevision() = runBlocking {
        dao.insertSessions(listOf(session("com.old", 100, 120)))
        val sessions = dao.sessionsOfDay(day)
        val stats = DayStatsEntity(day, 22, SESSION_SOURCE_ROOT)
        val unlocks = listOf(UnlockStatsEntity(day, "com.old", 22, SESSION_SOURCE_ROOT))
        val coverage = UsageCoverageEntity(day, t(0), t(240), 1, t(250), "previous")
        val usageGap = CollectionGapEntity("usage", t(0), t(1440), t(1450))
        val batteryGap = usageGap.copy(kind = "battery")
        dao.upsertDayStats(listOf(stats))
        dao.upsertUnlockStats(unlocks)
        dao.upsertUsageCoverage(listOf(coverage))
        dao.upsertCollectionGaps(listOf(usageGap, batteryGap))
        dao.putState(SyncStateEntity(UsageRepository.KEY_WATERMARK, t(240)))
        dao.putState(SyncStateEntity(UsageRepository.KEY_DATA_REVISION, 7))
        try {
            recover(dailyEvents(), probe = { throw IllegalStateException("injected before commit") })
            fail("injected exception must escape transaction")
        } catch (expected: IllegalStateException) {
            assertEquals("injected before commit", expected.message)
        }
        assertEquals(sessions, dao.sessionsOfDay(day))
        assertEquals(stats, dao.getDayStats(day))
        assertEquals(unlocks, dao.unlockStatsForDay(day))
        assertEquals(t(240), dao.getState(UsageRepository.KEY_WATERMARK))
        assertEquals(7L, dao.getState(UsageRepository.KEY_DATA_REVISION))
        assertEquals(coverage, dao.coverageOfDay(day))
        assertEquals(listOf(usageGap), dao.collectionGaps("usage"))
        assertEquals(listOf(batteryGap), dao.collectionGaps("battery"))
    }

    @Test
    fun certifiedRangeIsSubtractedFromUsageGapWhileBatteryGapRemains() = runBlocking {
        val gap = CollectionGapEntity("usage", t(500), t(800), t(900))
        val untouched = CollectionGapEntity("usage", t(900), t(950), t(1000))
        val battery = gap.copy(kind = "battery")
        dao.upsertCollectionGaps(listOf(gap, untouched, battery))
        // 首个会话边界晚于认证范围10分钟；源覆盖证据仍从认证起点扣除。
        recover(listOf(
            event(610, UsageEvents.Event.ACTIVITY_RESUMED, "com.new"),
            event(620, UsageEvents.Event.ACTIVITY_PAUSED, "com.new"),
        ), listOf(VerifiedRange(t(600), t(720))))
        assertEquals(listOf(
            gap.copy(toTs = t(600)), gap.copy(fromTs = t(720)), untouched,
        ), dao.collectionGaps("usage"))
        assertEquals(listOf(battery), dao.collectionGaps("battery"))
        assertFalse(dao.collectionGaps("usage").any { it.fromTs < t(720) && it.toTs > t(600) })
    }

    @Test
    fun rangeOperationsRespectHalfOpenEdgesAndMergeOverlaps() {
        assertEquals(listOf(VerifiedRange(10, 50), VerifiedRange(70, 80)), mergeRanges(listOf(
            VerifiedRange(30, 50), VerifiedRange(10, 20), VerifiedRange(20, 35), VerifiedRange(70, 80),
        )))
        assertEquals(listOf(VerifiedRange(15, 35), VerifiedRange(40, 50)), intersectRanges(
            listOf(VerifiedRange(10, 30), VerifiedRange(25, 50)),
            listOf(VerifiedRange(15, 35), VerifiedRange(40, 70)),
        ))
        assertTrue(intersectRanges(listOf(VerifiedRange(10, 20)), listOf(VerifiedRange(20, 30))).isEmpty())
        assertEquals(listOf(VerifiedRange(15, 20), VerifiedRange(40, 95)), subtractRanges(
            VerifiedRange(10, 100), listOf(
                VerifiedRange(0, 15), VerifiedRange(20, 30), VerifiedRange(25, 40), VerifiedRange(95, 120),
            ),
        ))
    }
}
