package com.local.screentime

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.local.screentime.data.AppDatabase
import com.local.screentime.data.BatteryGlobalEntity
import com.local.screentime.data.BatteryLedger
import com.local.screentime.data.BatteryParsed
import com.local.screentime.data.BatterySnapshotEntity
import com.local.screentime.data.BatteryDailyEntity
import com.local.screentime.data.UsageDao
import com.local.screentime.data.UidPower
import com.local.screentime.data.applyBatteryLedgerTx
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * P0-2 原子事务测试（Robolectric + 真实 Room in-memory 库）：
 * 1. 差分计算后注入故障 → 快照与 daily/global 全部回滚，不出现半提交；
 * 2. 正常路径 → 快照推进与 daily/global 写入同时生效；
 * 3. 跨多天窗口的批量读取：既有行按增量累加，不被整行重写（candidateDays 覆盖性端到端验证）。
 *
 * application = Application::class：绕开真实 App.onCreate（其中 WorkManager 在
 * Robolectric 环境未初始化，与本测试无关）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BatteryLedgerTxTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: UsageDao
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun epochDay(y: Int, mo: Int, d: Int): Long = LocalDate.of(y, mo, d).toEpochDay()

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

    private fun parsedOf(uidTotal: Double, screenMah: Double = 0.0, computedDrain: Double = 0.0) =
        BatteryParsed(
            uids = listOf(UidPower("u0a1", uidTotal, 0.0, 0.0, 0.0, if (screenMah > 0) mapOf("screen" to screenMah) else emptyMap())),
            globals = if (screenMah > 0) linkedMapOf("screen" to (screenMah to 60_000L)) else LinkedHashMap(),
            computedDrain = computedDrain,
            actualDrainLow = 0.0,
            windowStartTs = null,
        )

    private fun seedDaily(uidKey: String, day: Long, total: Double) = BatteryDailyEntity(
        uidKey = uidKey, day = day, packageName = null, totalMah = total, fgMah = 0.0,
        bgMah = 0.0, fgsMah = 0.0, screenMah = 0.0, cpuMah = 0.0, audioMah = 0.0,
        videoMah = 0.0, cameraMah = 0.0, gnssMah = 0.0, wifiMah = 0.0, btMah = 0.0,
        wakelockMah = 0.0, sensorsMah = 0.0, otherMah = 0.0,
    )

    @Test
    fun failureAfterCompute_rollsBackSnapshotDailyAndGlobal() = runBlocking {
        val lastTs = ts(2026, 9, 18, 12, 0)
        val now = ts(2026, 9, 18, 13, 0)
        val day = epochDay(2026, 9, 18)
        dao.putBatterySnapshot(BatterySnapshotEntity("u0a1", 100.0, "", lastTs))
        dao.putBatterySnapshot(BatterySnapshotEntity(BatteryLedger.GLOBAL_SNAPSHOT_KEY, 90.0, "screen=8.0", lastTs))
        dao.upsertBatteryDaily(listOf(seedDaily("u0a1", day, 50.0)))
        dao.upsertBatteryGlobal(listOf(BatteryGlobalEntity("screen", day, 10.0, 1_000L)))

        class Injected : Exception("probe")
        var threw = false
        try {
            applyBatteryLedgerTx(
                db, dao,
                parsedOf(uidTotal = 110.0, screenMah = 9.0, computedDrain = 120.0),
                now, zone, pkgOf = { null },
                probeAfterCompute = { throw Injected() },
            )
        } catch (e: Injected) {
            threw = true
        }
        assertTrue("注入的故障应从事务中抛出", threw)
        // 全部回滚：快照未推进、daily/global 未写入（v0.16.x 旧实现会留下
        // 「快照已推进到 110/120、daily 仍是 50」的半提交，本轮差分永久丢失）
        assertEquals(100.0, dao.getBatterySnapshot("u0a1")!!.cumMah, 1e-9)
        assertEquals(90.0, dao.getBatterySnapshot(BatteryLedger.GLOBAL_SNAPSHOT_KEY)!!.cumMah, 1e-9)
        assertEquals(50.0, dao.getBatteryDaily("u0a1", day)!!.totalMah, 1e-9)
        assertEquals(10.0, dao.getBatteryGlobal("screen", day)!!.mah, 1e-9)
    }

    @Test
    fun successPath_snapshotDailyAndGlobalCommitTogether() = runBlocking {
        val lastTs = ts(2026, 9, 18, 12, 0)
        val now = ts(2026, 9, 18, 13, 0)
        val day = epochDay(2026, 9, 18)
        dao.putBatterySnapshot(BatterySnapshotEntity("u0a1", 100.0, "", lastTs))
        dao.putBatterySnapshot(BatterySnapshotEntity(BatteryLedger.GLOBAL_SNAPSHOT_KEY, 90.0, "screen=8.0", lastTs))
        dao.upsertBatteryDaily(listOf(seedDaily("u0a1", day, 50.0)))
        dao.upsertBatteryGlobal(listOf(BatteryGlobalEntity("screen", day, 10.0, 1_000L)))

        applyBatteryLedgerTx(
            db, dao,
            parsedOf(uidTotal = 110.0, screenMah = 9.0, computedDrain = 120.0),
            now, zone, pkgOf = { null },
        )
        assertEquals(110.0, dao.getBatterySnapshot("u0a1")!!.cumMah, 1e-9)
        assertEquals(120.0, dao.getBatterySnapshot(BatteryLedger.GLOBAL_SNAPSHOT_KEY)!!.cumMah, 1e-9)
        assertEquals(60.0, dao.getBatteryDaily("u0a1", day)!!.totalMah, 1e-9)
        assertEquals(11.0, dao.getBatteryGlobal("screen", day)!!.mah, 1e-9)
    }

    @Test
    fun epochChangeTx_realParsed_atomic() = runBlocking {
        val oldWindow = ts(2026, 9, 18, 12, 0)
        val newWindow = ts(2026, 9, 19, 9, 0)
        val now = ts(2026, 9, 19, 10, 0)
        dao.putBatterySnapshot(BatterySnapshotEntity("u0a1", 100.0, "", oldWindow))
        dao.putBatterySnapshot(BatterySnapshotEntity("u0a9", 500.0, "", oldWindow))  // 本帧缺席的 UID
        dao.putState(com.local.screentime.data.SyncStateEntity(
            com.local.screentime.data.UsageRepository.KEY_BATTERY_WINDOW_START_TS, oldWindow))

        val parsed = BatteryParsed(
            uids = listOf(UidPower("u0a1", 20.0, 0.0, 0.0, 0.0, emptyMap())),
            globals = LinkedHashMap(), computedDrain = 0.0, actualDrainLow = 0.0, windowStartTs = newWindow,
        )
        val out = applyBatteryLedgerTx(db, dao, parsed, now, zone, pkgOf = { null }, prevWindowStartTs = oldWindow)
        assertTrue(out.epochChanged)
        // 旧快照全部清除（含本帧缺席的 u0a9），只保留新 epoch 快照
        assertNull(dao.getBatterySnapshot("u0a9"))
        assertEquals(20.0, dao.getBatterySnapshot("u0a1")!!.cumMah, 1e-9)
        // epoch 标记在同一事务推进
        assertEquals(newWindow, dao.getState(com.local.screentime.data.UsageRepository.KEY_BATTERY_WINDOW_START_TS))
        // u0a1 按「首次同步」语义全量入账（而非 20-100 → 0）
        val day = epochDay(2026, 9, 19)
        assertEquals(20.0, dao.getBatteryDaily("u0a1", day)!!.totalMah, 1e-9)
    }

    @Test
    fun epochTxFailure_rollsBackEpochMarkerAndSnapshots() = runBlocking {
        // P0-4：epoch 推进与数据写入必须同事务 —— 注入故障后旧快照与旧 epoch 标记原样保留
        val oldWindow = ts(2026, 9, 18, 12, 0)
        val newWindow = ts(2026, 9, 19, 9, 0)
        val now = ts(2026, 9, 19, 10, 0)
        dao.putBatterySnapshot(BatterySnapshotEntity("u0a1", 100.0, "", oldWindow))
        dao.putState(com.local.screentime.data.SyncStateEntity(
            com.local.screentime.data.UsageRepository.KEY_BATTERY_WINDOW_START_TS, oldWindow))

        val parsed = BatteryParsed(
            uids = listOf(UidPower("u0a1", 20.0, 0.0, 0.0, 0.0, emptyMap())),
            globals = LinkedHashMap(), computedDrain = 0.0, actualDrainLow = 0.0, windowStartTs = newWindow,
        )
        class Injected : Exception("probe")
        var threw = false
        try {
            applyBatteryLedgerTx(db, dao, parsed, now, zone, pkgOf = { null }, prevWindowStartTs = oldWindow,
                probeAfterCompute = { throw Injected() })
        } catch (e: Injected) { threw = true }
        assertTrue(threw)
        assertEquals("epoch 标记不得推进", oldWindow, dao.getState(com.local.screentime.data.UsageRepository.KEY_BATTERY_WINDOW_START_TS))
        assertEquals("旧快照不得被删除", 100.0, dao.getBatterySnapshot("u0a1")!!.cumMah, 1e-9)
        assertNull(dao.getBatteryDaily("u0a1", epochDay(2026, 9, 19)))
    }

    @Test
    fun multiDayWindow_batchReadPreservesExistingRows_onEveryTouchedDay() {
        runBlocking {
            val lastTs = ts(2026, 9, 17, 18, 0)
            val now = ts(2026, 9, 19, 12, 0)
            val d17 = epochDay(2026, 9, 17)
            val d18 = epochDay(2026, 9, 18)
            val d19 = epochDay(2026, 9, 19)
            dao.putBatterySnapshot(BatterySnapshotEntity("u0a1", 100.0, "", lastTs))
            // 三个候选日都有既有行：若事务内批量读取漏掉任何一天，
            // 该日会被当作「无既有行」整行重写 → 既有累计蒸发
            dao.upsertBatteryDaily(
                listOf(seedDaily("u0a1", d17, 7.0), seedDaily("u0a1", d18, 8.0), seedDaily("u0a1", d19, 9.0))
            )

            applyBatteryLedgerTx(db, dao, parsedOf(uidTotal = 124.0), now, zone, pkgOf = { null })

            val rows = listOf(d17, d18, d19).associateWith { dao.getBatteryDaily("u0a1", it)!!.totalMah }
            // 24h 增量按重叠比例分摊到三天：每天 = 既有值 + 24×w，且三天的增量之和 = 24
            val increments = listOf(rows[d17]!! - 7.0, rows[d18]!! - 8.0, rows[d19]!! - 9.0)
            assertTrue("三天都必须拿到增量（不允许被整行重写为 0 既有）", increments.all { it > 0 })
            assertEquals(24.0, increments.sum(), 1e-9)
            assertEquals(124.0, dao.getBatterySnapshot("u0a1")!!.cumMah, 1e-9)
        }
    }
}
