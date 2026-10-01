package com.local.screentime.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.local.screentime.data.UsageRepository.Companion.dayWeights
import com.local.screentime.data.UsageRepository.Companion.deltaFrom
import com.local.screentime.data.UsageRepository.Companion.encodeComps
import com.local.screentime.data.UsageRepository.Companion.decodeComps
import com.local.screentime.data.UsageRepository.Companion.uidOf
import java.time.ZoneId

/**
 * batterystats 快照差分的纯计算层（v0.17.0 从 UsageRepository.syncBattery 提取）。
 *
 * 为什么抽出来：syncBattery 旧实现把「读旧快照 → 算差分 → 写新快照 → 写 daily」散在
 * 仓库方法里、且**先推进快照再写 daily**（两次独立写入）。进程在中间被杀时，快照已
 * 指向新累计值、daily 还没落库，这一段差分永久丢失。提取成纯函数后：
 *  - compute() 的输入输出全部显式：给定同一份旧状态，结果可复现、可单测；
 *  - 写库统一收口到 applyBatteryLedgerTx 的单一 Room 事务（快照推进放在事务最后，
 *    与 daily/global 原子提交），事务外只做 shell 与解析。
 *
 * 语义与旧实现逐条对齐（重置判据 = new < old×0.5、0.001 mAh 记录阈值、
 * other = 未知组件之和、fg/bg/fgs 存于快照 comps、全局 `_dur` 时长差分快照），
 * 行为等价性由 BatteryLedgerTest 钉死。
 */
object BatteryLedger {

    /** 整机组件差分基线在 battery_snapshot 表里的主键（沿用历史值，勿改） */
    const val GLOBAL_SNAPSHOT_KEY = "__global__"

    /** 全局组件时长的差分基线主键（v0.16.14 引入） */
    const val GLOBAL_DUR_SNAPSHOT_KEY = "__global___dur"

    /** UID 行括号内已知组件；其余组件并入 otherMah */
    val KNOWN_COMPS = setOf(
        "screen", "cpu", "audio", "video", "camera", "gnss", "wifi", "bluetooth", "bt",
        "wakelock", "sensors", "mobile_radio", "ambient_display", "idle", "flashlight", "phone",
    )

    /**
     * batterystats 统计 epoch 判定（v0.17.1，P0-1）。
     *
     * 真机实证（OnePlus 13，2026-10-01）：旧的比例判据 `new < old×0.5` 会吞掉整个
     * 旧 epoch 尾段——抖音旧快照 132.8 mAh，新 epoch dump 148 mAh，148 < 66.4 不成立
     * → delta=15.2，旧 epoch 尾段约 132.8 mAh 被永久吞掉（且 snapshot 被推进到 148，
     * 无法回补；同日 screenMah 23.03 > totalMah 15.24 即此 bug 的衍生症状）。
     *
     * reset 是**整个统计窗口**的状态变化，权威判据是持久化的旧窗口起点 vs 本帧解析的
     * 新窗口起点（dump 的 Start clock time）——二者明确不同 = 进入新 epoch = GLOBAL RESET。
     * unplug 广播不可靠，禁止作为判据。窗口起点缺失时才退回旧比例 heuristic。
     */
    enum class EpochState {
        /** 确认进入新统计 epoch：所有旧快照（UID/GLOBAL/GLOBAL_DUR）一并失效 */
        RESET,
        /** 确认同一 epoch：禁止用比例判据（同 epoch 的 100→97 是模型回算，不是重置） */
        SAME,
        /** 窗口起点缺失/无法解析：只能退回旧的比例 heuristic（仅当旧快照存在时才有意义） */
        UNKNOWN,
    }

    fun epochStateOf(prevWindowStartTs: Long?, parsedWindowStartTs: Long?): EpochState = when {
        prevWindowStartTs != null && parsedWindowStartTs != null ->
            if (prevWindowStartTs != parsedWindowStartTs) EpochState.RESET else EpochState.SAME
        else -> EpochState.UNKNOWN
    }

    fun dailyKey(uidKey: String, day: Long): String = "$uidKey|$day"

    fun globalKey(component: String, day: Long): String = "$component|$day"

    /** 事务内批量读出的既有状态；daily/global 以 (uidKey|day) 为键 */
    data class Existing(
        val snapshots: Map<String, BatterySnapshotEntity>,
        val daily: Map<String, BatteryDailyEntity>,
        val global: Map<String, BatteryGlobalEntity>,
    )

    data class Result(
        val snapshots: List<BatterySnapshotEntity>,
        val dailyRows: List<BatteryDailyEntity>,
        val globalRows: List<BatteryGlobalEntity>,
        /** 本帧判定为 GLOBAL RESET：事务层必须删除全部旧 epoch 快照（含本帧未出现的 UID） */
        val epochChanged: Boolean = false,
        /** 需持久化的新窗口起点（epoch 标记）；null = 本帧窗口起点缺失，保留旧标记 */
        val newWindowStartTs: Long? = null,
    )

    /** 充电重置判据（v0.16.11 引入的比例规则）。
     *  v0.17.1 起降级为 **fallback**：仅当窗口起点缺失、epoch 无法判定时使用；
     *  epoch 可判定时以 [epochStateOf] 为权威（见其注释中的真机实证）。 */
    fun isReset(oldCumMah: Double?, newCumMah: Double): Boolean =
        oldCumMah != null && newCumMah < oldCumMah * 0.5

    /**
     * compute() 会触碰的全部本地日（epochDay）。供事务内**批量**读取 battery_daily /
     * battery_global，避免每个 UID 一次 round trip；与 compute() 使用同一组纯函数，
     * 保证覆盖 compute 实际触碰的每一天（否则既有行会被当作不存在而整体重写）。
     */
    fun candidateDays(
        parsed: BatteryParsed,
        snapshots: Map<String, BatterySnapshotEntity>,
        now: Long,
        zone: ZoneId,
        prevWindowStartTs: Long? = null,
    ): Set<Long> {
        val epoch = epochStateOf(prevWindowStartTs, parsed.windowStartTs)
        val globalReset = epoch == EpochState.RESET
        val days = LinkedHashSet<Long>()
        for (u in parsed.uids) {
            val effLast = if (globalReset) null else snapshots[u.uidKey]
            val reset = effLast != null && epoch == EpochState.UNKNOWN && isReset(effLast.cumMah, u.totalMah)
            days += dayWeights(deltaFrom(effLast?.updatedTs, reset, parsed.windowStartTs, now), now, zone).map { it.first }
        }
        if (parsed.computedDrain > 0) {
            val effLast = if (globalReset) null else snapshots[GLOBAL_SNAPSHOT_KEY]
            val reset = effLast != null && epoch == EpochState.UNKNOWN && isReset(effLast.cumMah, parsed.computedDrain)
            days += dayWeights(deltaFrom(effLast?.updatedTs, reset, parsed.windowStartTs, now), now, zone).map { it.first }
        }
        return days
    }

    /**
     * 差分计算（纯函数）。pkgOf 只负责把 UID 解析成归属包名（保持可注入/可测试），
     * 不做任何数据库或 shell 访问。
     *
     * epoch 语义（P0-1）：RESET 时**所有**旧快照（含本帧未出现的 UID，由事务层统一删除）
     * 视为不存在——差分按「首次同步」语义进行（totalDelta=全量、起点=新窗口起点）；
     * SAME 时禁止任何数值型 reset 判据；UNKNOWN（窗口起点缺失）才退回比例 heuristic。
     */
    fun compute(
        parsed: BatteryParsed,
        existing: Existing,
        now: Long,
        zone: ZoneId,
        pkgOf: (Int) -> String? = { null },
        prevWindowStartTs: Long? = null,
    ): Result {
        val epoch = epochStateOf(prevWindowStartTs, parsed.windowStartTs)
        val globalReset = epoch == EpochState.RESET
        val snapshots = ArrayList<BatterySnapshotEntity>()
        val dailyRows = ArrayList<BatteryDailyEntity>()

        for (u in parsed.uids) {
            val last = existing.snapshots[u.uidKey]
            // epoch RESET：旧 epoch 快照整体失效（哪怕本 UID 本帧恰好有旧快照）
            val effLast = if (globalReset) null else last
            // effLast 非 null 时 epoch 只能是 SAME/UNKNOWN（RESET 时 effLast 恒为 null）：
            // SAME 禁止数值型 reset（100→97 是模型回算）；UNKNOWN 才退回比例 heuristic
            val reset = when (epoch) {
                EpochState.RESET -> false
                EpochState.SAME -> false
                EpochState.UNKNOWN -> effLast != null && isReset(effLast.cumMah, u.totalMah)
            }
            val prevComps = decodeComps(effLast?.compsText ?: "")

            fun compDelta(key: String): Double {
                // fg/bg 在 dump 的 UID 行括号之外（“… fg: 25.1 bg: 1.04 (…)”），
                // 不在 comps（括号内组件表）里 —— 此前误从 u.comps 取值恒得 0（v0.16.7 修复）
                val nowV = when (key) {
                    "fg" -> u.fg
                    "bg" -> u.bg
                    "fgs" -> u.fgs
                    else -> u.comps[key] ?: 0.0
                }
                val prevV = if (effLast == null || reset) 0.0 else (prevComps[key] ?: 0.0)
                return (nowV - prevV).coerceAtLeast(0.0)
            }

            val totalDelta = if (effLast == null || reset) {
                u.totalMah
            } else {
                (u.totalMah - effLast.cumMah).coerceAtLeast(0.0)
            }
            snapshots.add(
                BatterySnapshotEntity(
                    uidKey = u.uidKey,
                    cumMah = u.totalMah,
                    compsText = encodeComps(u.comps + mapOf("fg" to u.fg, "bg" to u.bg, "fgs" to u.fgs)),
                    updatedTs = now,
                )
            )
            // 阈值从 0.01 收紧到 0.001：视频类应用一次亮屏几分钟的估算耗电可能只有几十 mAh，
            // 但被冰箱冻结后仅剩零星后台增量，0.01 会把整段使用期之间的微小增量整段丢弃（v0.16.7）。
            // 注意：快照无论 delta 多小都要推进，否则低用量 UID 的差分永远追不上累计值。
            if (totalDelta <= 0.001) continue
            val pkg = pkgOf(uidOf(u.uidKey))
            for ((day, w) in dayWeights(deltaFrom(effLast?.updatedTs, reset, parsed.windowStartTs, now), now, zone)) {
                val ex = existing.daily[dailyKey(u.uidKey, day)]
                dailyRows.add(
                    BatteryDailyEntity(
                        uidKey = u.uidKey,
                        day = day,
                        packageName = pkg,
                        totalMah = (ex?.totalMah ?: 0.0) + totalDelta * w,
                        fgMah = (ex?.fgMah ?: 0.0) + compDelta("fg") * w,
                        bgMah = (ex?.bgMah ?: 0.0) + compDelta("bg") * w,
                        fgsMah = (ex?.fgsMah ?: 0.0) + compDelta("fgs") * w,
                        screenMah = (ex?.screenMah ?: 0.0) + compDelta("screen") * w,
                        cpuMah = (ex?.cpuMah ?: 0.0) + compDelta("cpu") * w,
                        audioMah = (ex?.audioMah ?: 0.0) + compDelta("audio") * w,
                        videoMah = (ex?.videoMah ?: 0.0) + compDelta("video") * w,
                        cameraMah = (ex?.cameraMah ?: 0.0) + compDelta("camera") * w,
                        gnssMah = (ex?.gnssMah ?: 0.0) + compDelta("gnss") * w,
                        wifiMah = (ex?.wifiMah ?: 0.0) + compDelta("wifi") * w,
                        btMah = (ex?.btMah ?: 0.0) + compDelta("bluetooth") * w,
                        wakelockMah = (ex?.wakelockMah ?: 0.0) + compDelta("wakelock") * w,
                        sensorsMah = (ex?.sensorsMah ?: 0.0) + compDelta("sensors") * w,
                        otherMah = (ex?.otherMah ?: 0.0) + u.comps.keys
                            .filter { it !in KNOWN_COMPS }
                            .sumOf { compDelta(it) } * w,
                    )
                )
            }
        }

        val globalRows = ArrayList<BatteryGlobalEntity>()
        // 全局组件：与 UID 行同一 epoch 状态（P0-1：UID/GLOBAL/GLOBAL_DUR 必须共用同一 reset）
        if (parsed.computedDrain > 0) {
            val gLast = existing.snapshots[GLOBAL_SNAPSHOT_KEY]
            val effGLast = if (globalReset) null else gLast
            // UNKNOWN 时退回比例判据：整机重算/校准也会让 computedDrain 小幅回落（v0.16.11）
            val reset = when (epoch) {
                EpochState.RESET -> false
                EpochState.SAME -> false
                EpochState.UNKNOWN -> effGLast != null && isReset(effGLast.cumMah, parsed.computedDrain)
            }
            snapshots.add(
                BatterySnapshotEntity(GLOBAL_SNAPSHOT_KEY, parsed.computedDrain, encodeComps(parsed.globals.mapValues { it.value.first }), now)
            )
            val gLastDur = existing.snapshots[GLOBAL_DUR_SNAPSHOT_KEY]
            val effGDur = if (globalReset) null else gLastDur
            snapshots.add(
                BatterySnapshotEntity(
                    GLOBAL_DUR_SNAPSHOT_KEY,
                    parsed.globals.values.sumOf { it.second.toDouble() },
                    encodeComps(parsed.globals.mapValues { it.value.second.toDouble() }),
                    now,
                )
            )
            val prevComps = decodeComps(effGLast?.compsText ?: "")
            val prevDurComps = decodeComps(effGDur?.compsText ?: "")
            val weights = dayWeights(deltaFrom(effGLast?.updatedTs, reset, parsed.windowStartTs, now), now, zone)
            for ((comp, pair) in parsed.globals) {
                val nowV = pair.first
                val prevV = if (effGLast == null || reset) 0.0 else (prevComps[comp] ?: 0.0)
                val delta = (nowV - prevV).coerceAtLeast(0.0)
                val nowDur = pair.second
                val prevDur = if (effGDur == null || reset) 0L else (prevDurComps[comp]?.toLong() ?: 0L)
                val durDelta = (nowDur - prevDur).coerceAtLeast(0L)
                if (delta <= 0.01 && durDelta <= 0L) continue
                for ((day, w) in weights) {
                    val ex = existing.global[globalKey(comp, day)]
                    globalRows.add(
                        BatteryGlobalEntity(
                            component = comp,
                            day = day,
                            mah = (ex?.mah ?: 0.0) + delta * w,
                            durationMs = (ex?.durationMs ?: 0L) + (durDelta * w).toLong(),
                        )
                    )
                }
            }
        }
        return Result(
            snapshots = snapshots,
            dailyRows = dailyRows,
            globalRows = globalRows,
            epochChanged = globalReset,
            // epoch 标记与数据写入同事务持久化：RESET→新值；首帧（stored 缺失）→初始化；
            // 本帧窗口起点缺失→null（保留旧标记，等下一次可解析帧再比较）
            newWindowStartTs = parsed.windowStartTs,
        )
    }
}

/**
 * 原子提交一次 batterystats 差分（v0.17.0 引入；v0.17.1 加入 epoch 状态）。
 *
 * 单一 Room 事务内完成：批量读旧状态 → 纯差分计算 → 批量写 battery_daily /
 * battery_global → （epoch 变化时删除全部旧快照）→ 推进 battery_snapshot →
 * 推进 epoch 标记（sync_state.KEY_BATTERY_WINDOW_START_TS）。任一步抛异常
 * （含差分计算后注入的故障）整个事务回滚：快照不推进、daily/global 不推进、
 * **epoch 标记也不推进**（P0-4：下一次同步可基于完整旧状态重新计算）。
 *
 * epoch 变化时删除**全部**旧快照——不仅是本帧出现的 UID：新 epoch 第一帧缺席的
 * 应用之后重新出现时，绝不能拿旧 epoch 的基线做差（否则重演抖音吞量）。
 *
 * 调用前提：调用方已持有进程级同步锁（UsageRepository.SYNC_MUTEX）。
 * probeAfterCompute 仅供事务回滚测试注入故障，生产路径恒为 null。
 */
internal suspend fun applyBatteryLedgerTx(
    db: RoomDatabase,
    dao: UsageDao,
    parsed: BatteryParsed,
    now: Long,
    zone: ZoneId,
    pkgOf: (Int) -> String?,
    prevWindowStartTs: Long? = null,
    probeAfterCompute: (suspend () -> Unit)? = null,
): BatteryLedger.Result =
    db.withTransaction {
        val snapshots = dao.allBatterySnapshots().associateBy { it.uidKey }
        val days = BatteryLedger.candidateDays(parsed, snapshots, now, zone, prevWindowStartTs).toList()
        val existing = BatteryLedger.Existing(
            snapshots = snapshots,
            daily = if (days.isEmpty()) emptyMap()
            else dao.batteryDailyForDays(days).associate { BatteryLedger.dailyKey(it.uidKey, it.day) to it },
            global = if (days.isEmpty()) emptyMap()
            else dao.batteryGlobalForDays(days).associate { BatteryLedger.globalKey(it.component, it.day) to it },
        )
        val out = BatteryLedger.compute(parsed, existing, now, zone, pkgOf, prevWindowStartTs)
        probeAfterCompute?.invoke()
        // 写入顺序：先 daily/global、后快照/epoch 标记 —— 即便未来有人把这段挪出事务，
        // 快照与 epoch 推进仍是最后一步（先写 daily 丢的只是重算机会，先推快照丢的是真实数据）
        if (out.dailyRows.isNotEmpty()) dao.upsertBatteryDaily(out.dailyRows)
        if (out.globalRows.isNotEmpty()) dao.upsertBatteryGlobal(out.globalRows)
        if (out.epochChanged) {
            // GLOBAL RESET：旧 epoch 基线全部失效（含本帧未出现的 UID），再建新 epoch 快照
            dao.clearBatterySnapshots()
        }
        dao.upsertBatterySnapshots(out.snapshots)
        out.newWindowStartTs?.let { dao.putState(SyncStateEntity(UsageRepository.KEY_BATTERY_WINDOW_START_TS, it)) }
        out
    }
