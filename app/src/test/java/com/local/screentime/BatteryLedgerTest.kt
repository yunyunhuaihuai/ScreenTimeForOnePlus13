package com.local.screentime

import com.local.screentime.data.BatteryDailyEntity
import com.local.screentime.data.BatteryGlobalEntity
import com.local.screentime.data.BatteryLedger
import com.local.screentime.data.BatteryParsed
import com.local.screentime.data.BatterySnapshotEntity
import com.local.screentime.data.UidPower
import com.local.screentime.data.UsageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier

/**
 * batterystats 差分纯计算层（BatteryLedger，v0.17.0 从 syncBattery 提取）的语义钉死测试。
 * 与旧 syncBattery 逐条对齐：重置比例判据、0.001 记录阈值、快照先行、other 组件归并、
 * 全局 `_dur` 时长差分；并覆盖 P0-1 要求的「两个 capture 同时基于同一旧快照启动」
 * 进程级序列化场景（不双计 / 不丢增量 / 快照不回退）。
 */
class BatteryLedgerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun epochDay(y: Int, mo: Int, d: Int): Long = LocalDate.of(y, mo, d).toEpochDay()

    private fun parsedOf(
        vararg uids: UidPower,
        computedDrain: Double = 0.0,
        windowStartTs: Long? = null,
    ) = BatteryParsed(uids.toList(), LinkedHashMap(), computedDrain, 0.0, windowStartTs)

    private fun uidOf(
        key: String, total: Double,
        comps: Map<String, Double> = emptyMap(),
        fg: Double = 0.0, bg: Double = 0.0, fgs: Double = 0.0,
    ) = UidPower(key, total, fg, bg, fgs, comps)

    private fun dailyRow(uidKey: String, day: Long, total: Double, fg: Double = 0.0) =
        BatteryDailyEntity(
            uidKey = uidKey, day = day, packageName = null, totalMah = total, fgMah = fg,
            bgMah = 0.0, fgsMah = 0.0, screenMah = 0.0, cpuMah = 0.0, audioMah = 0.0,
            videoMah = 0.0, cameraMah = 0.0, gnssMah = 0.0, wifiMah = 0.0, btMah = 0.0,
            wakelockMah = 0.0, sensorsMah = 0.0, otherMah = 0.0,
        )

    // ---------- 单次差分 ----------

    @Test
    fun firstSync_deltaFromWindowStart_singleDay() {
        val windowStart = ts(2026, 9, 18, 18, 5)
        val now = ts(2026, 9, 18, 23, 45)
        val out = BatteryLedger.compute(
            parsedOf(uidOf("u0a1", 10.0), windowStartTs = windowStart),
            BatteryLedger.Existing(emptyMap(), emptyMap(), emptyMap()),
            now, zone,
        )
        assertEquals(1, out.snapshots.size)
        assertEquals(10.0, out.snapshots[0].cumMah, 1e-9)
        assertEquals(1, out.dailyRows.size)
        assertEquals(epochDay(2026, 9, 18), out.dailyRows[0].day)
        assertEquals(10.0, out.dailyRows[0].totalMah, 1e-9)
    }

    @Test
    fun normalDelta_accumulatesOntoExistingDailyRow() {
        val lastTs = ts(2026, 9, 18, 12, 0)
        val now = ts(2026, 9, 18, 18, 0)
        val existing = BatteryLedger.Existing(
            snapshots = mapOf("u0a1" to BatterySnapshotEntity("u0a1", 100.0, "fg=50.0", lastTs)),
            daily = mapOf(
                BatteryLedger.dailyKey("u0a1", epochDay(2026, 9, 18)) to
                    dailyRow("u0a1", epochDay(2026, 9, 18), total = 60.0, fg = 30.0)
            ),
            global = emptyMap(),
        )
        val out = BatteryLedger.compute(
            parsedOf(uidOf("u0a1", 110.0, fg = 55.0)),
            existing, now, zone,
        )
        assertEquals(1, out.dailyRows.size)
        assertEquals(70.0, out.dailyRows[0].totalMah, 1e-9)  // 60 + (110-100)
        assertEquals(35.0, out.dailyRows[0].fgMah, 1e-9)     // 30 + (55-50)
        assertEquals(110.0, out.snapshots[0].cumMah, 1e-9)
    }

    @Test
    fun chargeReset_fullRecountUnderRatioRule() {
        val lastTs = ts(2026, 9, 17, 20, 0)
        val now = ts(2026, 9, 18, 8, 0)
        val existing = BatteryLedger.Existing(
            snapshots = mapOf("u0a1" to BatterySnapshotEntity("u0a1", 0.0831, "", lastTs)),
            daily = emptyMap(),
            global = emptyMap(),
        )
        // 0.005 < 0.0831×0.5 → 重置：totalDelta = 重置后的新值（需 > 0.001 记录阈值）；
        // 观测区间跨午夜 → 按 dayWeights 分摊到两天，两天之和 = 重置后的新值
        val out = BatteryLedger.compute(parsedOf(uidOf("u0a1", 0.005)), existing, now, zone)
        assertEquals(2, out.dailyRows.size)
        assertEquals(0.005, out.dailyRows.sumOf { it.totalMah }, 1e-9)
    }

    @Test
    fun rescaleDip_belowHalfThreshold_notReset() {
        // 真机实测的重算抖动（-1.8~-2.3%）不得误判为重置（v0.16.11 判据）
        val lastTs = ts(2026, 9, 18, 12, 0)
        val now = ts(2026, 9, 18, 13, 0)
        val existing = BatteryLedger.Existing(
            snapshots = mapOf("u0a333" to BatterySnapshotEntity("u0a333", 279.0, "", lastTs)),
            daily = emptyMap(),
            global = emptyMap(),
        )
        val out = BatteryLedger.compute(parsedOf(uidOf("u0a333", 274.0)), existing, now, zone)
        assertEquals(0, out.dailyRows.size)  // 负差分 clamp 为 0，不记录
        assertEquals(274.0, out.snapshots.single().cumMah, 1e-9)  // 快照仍推进
    }

    @Test
    fun tinyDelta_advancesSnapshot_butNoDailyRow() {
        // 语义要点：快照无论 delta 多小都要推进（否则低用量 UID 的差分永远追不上累计值），
        // daily 只在 delta > 0.001 时写
        val lastTs = ts(2026, 9, 18, 12, 0)
        val now = ts(2026, 9, 18, 12, 30)
        val existing = BatteryLedger.Existing(
            snapshots = mapOf("u0a1" to BatterySnapshotEntity("u0a1", 1.0, "", lastTs)),
            daily = emptyMap(), global = emptyMap(),
        )
        val out = BatteryLedger.compute(parsedOf(uidOf("u0a1", 1.0005)), existing, now, zone)
        assertEquals(1.0005, out.snapshots.single().cumMah, 1e-12)
        assertEquals(0, out.dailyRows.size)  // 0.0005 ≤ 0.001
    }

    @Test
    fun crossMidnight_deltaSplitByDayWeights() {
        val lastTs = ts(2026, 9, 17, 20, 0)
        val now = ts(2026, 9, 18, 10, 0)
        val out = BatteryLedger.compute(
            parsedOf(uidOf("u0a1", 114.0)),
            BatteryLedger.Existing(
                snapshots = mapOf("u0a1" to BatterySnapshotEntity("u0a1", 100.0, "", lastTs)),
                daily = emptyMap(), global = emptyMap(),
            ),
            now, zone,
        )
        assertEquals(2, out.dailyRows.size)
        val byDay = out.dailyRows.associateBy { it.day }
        assertEquals(14.0 * (4.0 / 14.0), byDay[epochDay(2026, 9, 17)]!!.totalMah, 1e-9)
        assertEquals(14.0 * (10.0 / 14.0), byDay[epochDay(2026, 9, 18)]!!.totalMah, 1e-9)
    }

    @Test
    fun unknownComponents_foldIntoOther() {
        val lastTs = ts(2026, 9, 18, 12, 0)
        val now = lastTs + 60_000
        val existing = BatteryLedger.Existing(
            snapshots = mapOf("u0a1" to BatterySnapshotEntity("u0a1", 0.0, "modem=1.0|gpu=2.0", lastTs)),
            daily = emptyMap(), global = emptyMap(),
        )
        val out = BatteryLedger.compute(
            parsedOf(uidOf("u0a1", 10.0, comps = mapOf("modem" to 3.0, "gpu" to 5.0))),
            existing, now, zone,
        )
        // modem/gpu 均非 KNOWN_COMPS：other = (3-1) + (5-2) = 5
        assertEquals(5.0, out.dailyRows.single().otherMah, 1e-9)
    }

    // ---------- 全局组件 ----------

    @Test
    fun globalDrain_mahAndDurationSnapshotsAdvanced() {
        val parsed = BatteryParsed(
            emptyList(),
            linkedMapOf("screen" to (10.0 to 3_600_000L)),
            computedDrain = 100.0, actualDrainLow = 0.0, windowStartTs = null,
        )
        val lastTs = ts(2026, 9, 18, 12, 0)
        val now = ts(2026, 9, 18, 13, 0)
        val day = epochDay(2026, 9, 18)
        val existing = BatteryLedger.Existing(
            snapshots = mapOf(
                BatteryLedger.GLOBAL_SNAPSHOT_KEY to
                    BatterySnapshotEntity(BatteryLedger.GLOBAL_SNAPSHOT_KEY, 90.0, "screen=8.0", lastTs),
                BatteryLedger.GLOBAL_DUR_SNAPSHOT_KEY to
                    BatterySnapshotEntity(BatteryLedger.GLOBAL_DUR_SNAPSHOT_KEY, 3_000_000.0, "screen=3000000.0", lastTs),
            ),
            daily = emptyMap(),
            global = mapOf(
                BatteryLedger.globalKey("screen", day) to BatteryGlobalEntity("screen", day, 50.0, 10_000_000L)
            ),
        )
        val out = BatteryLedger.compute(parsed, existing, now, zone)
        assertEquals(2, out.snapshots.size)
        assertEquals(52.0, out.globalRows.single().mah, 1e-9)             // 50 + (10-8)
        assertEquals(10_600_000L, out.globalRows.single().durationMs)     // 10_000_000 + (3_600_000-3_000_000)
    }

    // ---------- epoch 判定与 GLOBAL RESET（P0-1，真机抖音吞量案例钉死） ----------

    @Test
    fun epochState_rules() {
        val a = ts(2026, 10, 1, 18, 5)
        val b = ts(2026, 10, 2, 9, 0)
        assertEquals(BatteryLedger.EpochState.RESET, BatteryLedger.epochStateOf(a, b))
        assertEquals(BatteryLedger.EpochState.SAME, BatteryLedger.epochStateOf(a, a))
        assertEquals(BatteryLedger.EpochState.UNKNOWN, BatteryLedger.epochStateOf(null, a))
        assertEquals(BatteryLedger.EpochState.UNKNOWN, BatteryLedger.epochStateOf(a, null))
        assertEquals(BatteryLedger.EpochState.UNKNOWN, BatteryLedger.epochStateOf(null, null))
    }

    @Test
    fun epochChange_newEpoch148DeltaIsFullNotSwallowed() {
        // 真机案例：旧 epoch 快照 132.8（抖音 u0a333），新 epoch dump 148 mAh。
        // 旧比例判据：148 < 66.4 为 false → delta=15.2（吞掉 132.8）。
        // 新语义：窗口起点变化 = GLOBAL RESET → delta = 148（新 epoch 全量）。
        val oldWindow = ts(2026, 10, 1, 18, 5)
        val newWindow = ts(2026, 10, 2, 9, 0)
        val now = ts(2026, 10, 2, 10, 0)
        val existing = BatteryLedger.Existing(
            snapshots = mapOf("u0a333" to BatterySnapshotEntity("u0a333", 132.8, "", ts(2026, 10, 1, 23, 0))),
            daily = emptyMap(), global = emptyMap(),
        )
        val out = BatteryLedger.compute(
            parsedOf(uidOf("u0a333", 148.0), windowStartTs = newWindow),
            existing, now, zone, prevWindowStartTs = oldWindow,
        )
        assertTrue(out.epochChanged)
        assertEquals(148.0, out.dailyRows.single().totalMah, 1e-9)
        assertEquals(newWindow, out.newWindowStartTs)
    }

    @Test
    fun sameEpoch_rescaleDip_neverResets() {
        // 同 epoch 内 100→97 是模型回算：窗口起点未变时禁止任何数值型 reset
        val window = ts(2026, 10, 1, 18, 5)
        val now = ts(2026, 10, 1, 23, 0)
        val existing = BatteryLedger.Existing(
            snapshots = mapOf("u0a1" to BatterySnapshotEntity("u0a1", 100.0, "", ts(2026, 10, 1, 20, 0))),
            daily = emptyMap(), global = emptyMap(),
        )
        val out = BatteryLedger.compute(
            parsedOf(uidOf("u0a1", 97.0), windowStartTs = window),
            existing, now, zone, prevWindowStartTs = window,
        )
        assertFalse(out.epochChanged)
        assertEquals(0, out.dailyRows.size)  // 负差分 clamp 为 0，不记录
        assertEquals(97.0, out.snapshots.single().cumMah, 1e-9)
    }

    @Test
    fun epochChange_smallNewValue_fullDelta() {
        // 旧 epoch 100，新 epoch（真重置）只剩 5：delta=5（全量语义，与比例判据结果一致但依据不同）
        val oldWindow = ts(2026, 10, 1, 18, 5)
        val newWindow = ts(2026, 10, 2, 9, 0)
        val now = ts(2026, 10, 2, 9, 30)
        val existing = BatteryLedger.Existing(
            snapshots = mapOf("u0a1" to BatterySnapshotEntity("u0a1", 100.0, "", ts(2026, 10, 1, 23, 0))),
            daily = emptyMap(), global = emptyMap(),
        )
        val out = BatteryLedger.compute(
            parsedOf(uidOf("u0a1", 5.0), windowStartTs = newWindow),
            existing, now, zone, prevWindowStartTs = oldWindow,
        )
        assertTrue(out.epochChanged)
        assertEquals(5.0, out.dailyRows.single().totalMah, 1e-9)
    }

    @Test
    fun epochChange_uidMissingInFirstFrame_neverDiffsAgainstOldEpoch() {
        // 场景 4：epoch B 第一帧某 UID 缺失 → 旧快照必须被判定失效；
        // 该 UID 之后在 epoch B 以 80 mAh 出现时，按新 epoch 全量处理，绝不能与 epoch A 的 100 做差。
        val oldWindow = ts(2026, 10, 1, 18, 5)
        val newWindow = ts(2026, 10, 2, 9, 0)
        val now1 = ts(2026, 10, 2, 9, 30)
        val now2 = ts(2026, 10, 2, 15, 0)

        // 帧 1（epoch B 首帧）：u0a1 不在 dump 中（只有别的 UID）
        val frame1 = BatteryLedger.compute(
            parsedOf(uidOf("u0a2", 10.0), windowStartTs = newWindow),
            BatteryLedger.Existing(
                snapshots = mapOf("u0a1" to BatterySnapshotEntity("u0a1", 100.0, "", ts(2026, 10, 1, 23, 0))),
                daily = emptyMap(), global = emptyMap(),
            ),
            now1, zone, prevWindowStartTs = oldWindow,
        )
        assertTrue(frame1.epochChanged)
        // 帧 1 的事务语义：清空全部旧快照，只写本帧出现者的新快照
        val snapshotsAfterFrame1 = frame1.snapshots.associateBy { it.uidKey }

        // 帧 2：u0a1 以 80 mAh 重新出现——此时它不应有任何旧 epoch 基线
        val existing2 = BatteryLedger.Existing(
            snapshots = snapshotsAfterFrame1,
            daily = emptyMap(), global = emptyMap(),
        )
        val frame2 = BatteryLedger.compute(
            parsedOf(uidOf("u0a1", 80.0), windowStartTs = newWindow),
            existing2, now2, zone, prevWindowStartTs = newWindow,
        )
        assertFalse(frame2.epochChanged)  // 同 epoch
        assertEquals("80 必须按新 epoch 全量入账，而不是 80-100=0", 80.0, frame2.dailyRows.single().totalMah, 1e-9)
    }

    @Test
    fun windowStartMissing_fallsBackToRatioHeuristic() {
        // 场景 5：窗口起点缺失 → 退回比例 heuristic（真重置 0.0831→0.005 触发 full recount）
        val now = ts(2026, 10, 2, 9, 30)
        val existing = BatteryLedger.Existing(
            snapshots = mapOf("u0a1" to BatterySnapshotEntity("u0a1", 0.0831, "", ts(2026, 10, 1, 23, 0))),
            daily = emptyMap(), global = emptyMap(),
        )
        val out = BatteryLedger.compute(
            parsedOf(uidOf("u0a1", 0.005), windowStartTs = null),
            existing, now, zone, prevWindowStartTs = null,
        )
        assertFalse(out.epochChanged)
        assertNull(out.newWindowStartTs)  // 旧标记保留
        assertEquals(0.005, out.dailyRows.sumOf { it.totalMah }, 1e-9)
    }

    @Test
    fun epochChange_globalAndDurShareSameReset() {
        // 场景 6：UID/GLOBAL/GLOBAL_DUR 共用同一 epoch reset——整机 computedDrain 也按新 epoch 全量
        val oldWindow = ts(2026, 10, 1, 18, 5)
        val newWindow = ts(2026, 10, 2, 9, 0)
        val now = ts(2026, 10, 2, 10, 0)
        val day = epochDay(2026, 10, 2)
        val existing = BatteryLedger.Existing(
            snapshots = mapOf(
                BatteryLedger.GLOBAL_SNAPSHOT_KEY to
                    BatterySnapshotEntity(BatteryLedger.GLOBAL_SNAPSHOT_KEY, 1522.0, "screen=324.0", ts(2026, 10, 1, 23, 0)),
                BatteryLedger.GLOBAL_DUR_SNAPSHOT_KEY to
                    BatterySnapshotEntity(BatteryLedger.GLOBAL_DUR_SNAPSHOT_KEY, 7_000_000.0, "screen=7000000.0", ts(2026, 10, 1, 23, 0)),
            ),
            daily = emptyMap(),
            global = mapOf(
                BatteryLedger.globalKey("screen", day) to BatteryGlobalEntity("screen", day, 100.0, 1_000_000L)
            ),
        )
        val parsed = BatteryParsed(
            emptyList(),
            linkedMapOf("screen" to (10.0 to 60_000L)),
            computedDrain = 30.0, actualDrainLow = 0.0, windowStartTs = newWindow,
        )
        val out = BatteryLedger.compute(parsed, existing, now, zone, prevWindowStartTs = oldWindow)
        assertTrue(out.epochChanged)
        // epoch reset 只使快照基线失效：daily 行跨 epoch 累计（既有 100 + 新 epoch 全量增量 10）
        assertEquals(110.0, out.globalRows.single().mah, 1e-9)
        assertEquals(1_000_000L + 60_000L, out.globalRows.single().durationMs)
    }

    @Test
    fun candidateDays_coverAllDaysComputeTouches_multiDayWindow() {
        val lastTs = ts(2026, 9, 17, 18, 0)
        val now = ts(2026, 9, 19, 12, 0)
        val snapshots = mapOf("u0a1" to BatterySnapshotEntity("u0a1", 100.0, "", lastTs))
        val parsed = parsedOf(uidOf("u0a1", 124.0))
        val days = BatteryLedger.candidateDays(parsed, snapshots, now, zone)
        val out = BatteryLedger.compute(
            parsed,
            BatteryLedger.Existing(snapshots, emptyMap(), emptyMap()),
            now, zone,
        )
        assertEquals(days, out.dailyRows.map { it.day }.toSet())
        assertTrue("跨两个午夜的窗口应覆盖 ≥3 天", days.size >= 3)
    }

    // ---------- 并发序列化（P0-1） ----------

    /** 模拟 DB 语义的最小存储：upsert = REPLACE。 */
    private class FakeStore(initial: BatteryLedger.Existing) {
        val snapshots = ConcurrentHashMap<String, BatterySnapshotEntity>(initial.snapshots)
        val daily = ConcurrentHashMap<String, BatteryDailyEntity>(initial.daily)

        fun read() = BatteryLedger.Existing(snapshots.toMap(), daily.toMap(), emptyMap())

        fun write(out: BatteryLedger.Result) {
            out.snapshots.forEach { snapshots[it.uidKey] = it }
            out.dailyRows.forEach { daily[BatteryLedger.dailyKey(it.uidKey, it.day)] = it }
        }
    }

    /** 复刻仓库采集路径的锁结构：读状态 → 差分 → 写回，全程持锁。 */
    private suspend fun captureThrough(
        gate: kotlinx.coroutines.sync.Mutex,
        store: FakeStore,
        parsed: BatteryParsed,
        now: Long,
    ) {
        gate.withLock {
            val st = store.read()
            val out = BatteryLedger.compute(parsed, st, now, zone)
            store.write(out)
        }
    }

    @Test
    fun twoCapturesSameOldSnapshot_sharedGate_noDoubleCount() {
        // P0-1 核心场景：两个 capture 同时基于同一份旧快照(100)启动，观测同为 110。
        // 进程级共享锁保证第二个 capture 读到的是第一个提交后的新快照 → 增量只记一次。
        val lastTs = ts(2026, 9, 18, 12, 0)
        val now = ts(2026, 9, 18, 12, 1)
        val day = epochDay(2026, 9, 18)
        val store = FakeStore(
            BatteryLedger.Existing(
                mapOf("u0a1" to BatterySnapshotEntity("u0a1", 100.0, "", lastTs)),
                emptyMap(), emptyMap(),
            )
        )
        val parsed = parsedOf(uidOf("u0a1", 110.0))  // 真实增量 10
        runBlocking {
            val barrier = CyclicBarrier(2)
            listOf(parsed, parsed).map {
                async(Dispatchers.Default) {
                    barrier.await()  // 双方先对齐再抢锁：模拟同时基于旧快照启动
                    captureThrough(UsageRepository.SYNC_MUTEX, store, it, now)
                }
            }.awaitAll()
        }
        assertEquals("不双计（≠20）", 10.0, store.daily[BatteryLedger.dailyKey("u0a1", day)]!!.totalMah, 1e-9)
        assertEquals(110.0, store.snapshots["u0a1"]!!.cumMah, 1e-9)
    }

    @Test
    fun twoCapturesDifferentObservations_sharedGate_noLostDeltaNoStaleOverwrite() {
        val lastTs = ts(2026, 9, 18, 12, 0)
        val now = ts(2026, 9, 18, 12, 1)
        val day = epochDay(2026, 9, 18)
        val store = FakeStore(
            BatteryLedger.Existing(
                mapOf("u0a1" to BatterySnapshotEntity("u0a1", 100.0, "", lastTs)),
                emptyMap(), emptyMap(),
            )
        )
        // 两路观测值不同（110 与 115）：无论谁先执行，daily 必须是 15（两段增量各记一次），
        // 快照必须是两次真实观测之一（无 stale overwrite / cumMah 倒退）
        runBlocking {
            val barrier = CyclicBarrier(2)
            listOf(110.0, 115.0).map { total ->
                async(Dispatchers.Default) {
                    barrier.await()
                    captureThrough(UsageRepository.SYNC_MUTEX, store, parsedOf(uidOf("u0a1", total)), now)
                }
            }.awaitAll()
        }
        assertEquals("不丢增量（=15，不是 10 也不是 25）", 15.0, store.daily[BatteryLedger.dailyKey("u0a1", day)]!!.totalMah, 1e-9)
        assertTrue(
            "快照必须是两次观测之一",
            store.snapshots["u0a1"]!!.cumMah == 110.0 || store.snapshots["u0a1"]!!.cumMah == 115.0,
        )
    }

    @Test
    fun withoutSharedGate_staleCommitAfterFresh_overwritesSnapshotAndLosesDelta() {
        // 对照组（钉死 v0.16.7「实例锁已解决」结论的漏洞机制）：两个 capture 同时基于
        // 旧快照(100)启动，持新观测(110)者先提交，持旧观测(105)者随后提交——
        // 实例锁互不感知，无人拦截这次陈旧提交：
        //  - 快照 cumMah 110 → 105 倒退（污染下次差分基准，下轮 105→110 段被再记一遍）；
        //  - daily 被 B 的整行 REPLACE 覆盖，A 已记录的 105→110 段丢失。
        val lastTs = ts(2026, 9, 18, 12, 0)
        val now = ts(2026, 9, 18, 12, 1)
        val day = epochDay(2026, 9, 18)
        val store = FakeStore(
            BatteryLedger.Existing(
                mapOf("u0a1" to BatterySnapshotEntity("u0a1", 100.0, "", lastTs)),
                emptyMap(), emptyMap(),
            )
        )
        runBlocking {
            val staleView = store.read()  // B 先读走旧状态（两个 capture 同时基于旧快照启动）
            captureThrough(UsageRepository.SYNC_MUTEX, store, parsedOf(uidOf("u0a1", 110.0)), now)  // A 先提交
            // B 基于陈旧状态计算并提交（若无进程级互斥，这一步不会被推迟到 A 之后重新读库）
            store.write(BatteryLedger.compute(parsedOf(uidOf("u0a1", 105.0)), staleView, now, zone))
        }
        assertEquals("快照倒退（旧 bug 机制复现）", 105.0, store.snapshots["u0a1"]!!.cumMah, 1e-9)
        assertEquals("A 已提交的增量被 REPLACE 丢弃", 5.0, store.daily[BatteryLedger.dailyKey("u0a1", day)]!!.totalMah, 1e-9)
    }
}
