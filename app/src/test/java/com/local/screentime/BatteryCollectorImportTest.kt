package com.local.screentime

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.local.screentime.data.AppDatabase
import com.local.screentime.data.BatteryCaptureOutcome
import com.local.screentime.data.BatterySource
import com.local.screentime.data.UsageDao
import com.local.screentime.data.UsageRepository
import com.local.screentime.privileged.CommandResult
import com.local.screentime.privileged.PrivilegedCommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/** Durable collector frames keep their observation zone, source membership and sequence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BatteryCollectorImportTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: UsageDao
    private lateinit var repo: UsageRepository
    private lateinit var originalTimeZone: TimeZone
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 10, 20).toEpochDay()
    private val windowStart = LocalDateTime.of(2026, 10, 20, 18, 5, 5).atZone(shanghai).toInstant().toEpochMilli()
    private fun observed(minute: Long) = windowStart + minute * 60_000L

    @Before
    fun setup() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(shanghai))
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.usageDao()
        // This suite imports supplied frames only. Any live source command is a test error.
        val runner = object : PrivilegedCommandRunner {
            override suspend fun isRootAvailable(): Boolean = false
            override suspend fun exec(cmd: String): CommandResult = error("Unexpected live command: $cmd")
        }
        repo = UsageRepository(context, runner, db, useRootCollector = false)
    }

    @After
    fun teardown() {
        db.close()
        TimeZone.setDefault(originalTimeZone)
    }

    private fun frame(total: Double, packages: List<String> = listOf("com.frame.original")): List<String> = listOf(
        "  Start clock time: 2026-10-20-18-05-05",
        "Estimated power use (mAh):",
        "  Capacity: 6000, Computed drain: $total, actual drain: $total - ${total + 1}",
        "  UID u0a42: $total ( screen: ${total / 2} cpu: ${total / 2} )",
        "# ST_COLLECTOR_UID_PACKAGES_V1",
    ) + packages.map { "package:$it uid:10042" }

    private suspend fun ingest(sequence: Long, minute: Long, lines: List<String>) = repo.syncBattery(
        attemptTs = observed(minute) - 100,
        commandStartTs = observed(minute) - 100,
        source = BatterySource.ROOT,
        capturedLines = lines,
        capturedEndTs = observed(minute),
        capturedZone = shanghai,
        collectorSequence = sequence,
    )

    private suspend fun snapshots() = dao.allBatterySnapshots().sortedBy { it.uidKey }

    private fun installCurrentOwner() {
        val pkg = PackageInfo().apply {
            packageName = "com.current.owner"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.current.owner"
                uid = 10042
            }
        }
        shadowOf(context.packageManager).installPackage(pkg)
        assertTrue(context.packageManager.getPackagesForUid(10042)!!.contains("com.current.owner"))
    }

    @Test
    fun shanghaiFrameImportedUnderUtcKeepsEpochAndDoesNotChargeSameCounterAgain() = runBlocking {
        assertTrue(ingest(1, 60, frame(10.0)) is BatteryCaptureOutcome.Success)
        val before = dao.batteryBetween(day, day)
        assertEquals(10.0, before, 1e-9)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals(ZoneId.of("UTC"), ZoneId.systemDefault())

        assertTrue(ingest(2, 75, frame(10.0)) is BatteryCaptureOutcome.Success)
        assertEquals("原时区窗口必须保持同一个epoch", windowStart, dao.getState(UsageRepository.KEY_BATTERY_WINDOW_START_TS))
        assertEquals(windowStart, dao.getBatterySnapshot("u0a42")!!.epochWindowStartTs)
        assertEquals("相同epoch累计值不可因为导入环境时区变化而重新入账", before, dao.batteryBetween(day, day), 1e-9)
        assertNull(dao.getState(UsageRepository.KEY_BATTERY_LOST_TAIL_FROM))
        assertTrue(dao.recentCaptureLogs(10).all { it.zoneId == "Asia/Shanghai" && it.epochWindowStartTs == windowStart })
        assertEquals(2L, dao.getState(UsageRepository.KEY_COLLECTOR_IMPORTED))
    }

    @Test
    fun replayOfAlreadyImportedSequenceLeavesLedgerSnapshotAndMembershipUnchanged() = runBlocking {
        val first = frame(10.0)
        assertTrue(ingest(1, 60, first) is BatteryCaptureOutcome.Success)
        assertTrue(ingest(2, 75, frame(15.0)) is BatteryCaptureOutcome.Success)
        val beforeTotal = dao.batteryBetween(day, day)
        val beforeSnapshots = snapshots()
        val beforeMembers = dao.uidPackagesFor("u0a42")
        val beforeLogs = dao.recentCaptureLogs(10)

        // A crash before ACK can redeliver an older frame after newer sequence 2 was committed.
        assertTrue(ingest(1, 60, first) is BatteryCaptureOutcome.Success)
        assertEquals(beforeTotal, dao.batteryBetween(day, day), 1e-9)
        assertEquals(beforeSnapshots, snapshots())
        assertEquals(beforeMembers, dao.uidPackagesFor("u0a42"))
        assertEquals(beforeLogs, dao.recentCaptureLogs(10))
        assertEquals(2L, dao.getState(UsageRepository.KEY_COLLECTOR_IMPORTED))
        assertEquals(observed(75), dao.getState(UsageRepository.KEY_LAST_BATTERY_SUCCESS_TS))
    }

    @Test
    fun oldObservationWithNewSequenceIsRejectedWithoutAdvancingImportOrSnapshot() = runBlocking {
        assertTrue(ingest(5, 90, frame(20.0)) is BatteryCaptureOutcome.Success)
        val before = snapshots()
        val beforeTotal = dao.batteryBetween(day, day)
        val beforeMembers = dao.uidPackagesFor("u0a42")
        val result = ingest(6, 75, frame(500.0))
        assertTrue(result is BatteryCaptureOutcome.Failed && result.stage == "clock_or_order")
        assertEquals(5L, dao.getState(UsageRepository.KEY_COLLECTOR_IMPORTED))
        assertEquals(before, snapshots())
        assertEquals(beforeTotal, dao.batteryBetween(day, day), 1e-9)
        assertEquals(beforeMembers, dao.uidPackagesFor("u0a42"))
        assertEquals(observed(90), dao.getState(UsageRepository.KEY_LAST_BATTERY_SUCCESS_TS))
    }

    @Test
    fun recordedUidMapFreezesOwnerAndArchiveInsteadOfCurrentPackageManager() = runBlocking {
        installCurrentOwner()
        assertTrue(ingest(1, 60, frame(10.0)) is BatteryCaptureOutcome.Success)
        val row = dao.getBatteryDaily("u0a42", day)!!
        assertEquals("com.frame.original", row.packageName)
        val member = dao.uidPackagesFor("u0a42").single()
        assertEquals("com.frame.original", member.packageName)
        assertEquals(observed(60), member.firstSeenTs)
        assertEquals(observed(60), member.lastSeenTs)
        assertEquals(10.0, repo.batteryForPackages(day, listOf("com.frame.original"))["com.frame.original"]!!.totalMah, 1e-9)
        assertNull(repo.batteryForPackages(day, listOf("com.current.owner"))["com.current.owner"])
    }

    @Test
    fun recordedSharedUidRemainsSharedEvenWhenOnlyOnePackageIsCurrentlyInstalled() = runBlocking {
        installCurrentOwner()
        assertTrue(ingest(1, 60, frame(10.0, listOf("com.old.first", "com.old.second"))) is BatteryCaptureOutcome.Success)
        assertNull(dao.getBatteryDaily("u0a42", day)!!.packageName)
        assertEquals(setOf("com.old.first", "com.old.second"), dao.uidPackagesFor("u0a42").map { it.packageName }.toSet())
        val attributed = repo.batteryForPackages(day, listOf("com.old.first", "com.old.second", "com.current.owner"))
        assertTrue("历史共享UID不可按当前唯一安装包猜归属", attributed.isEmpty())
    }
}
