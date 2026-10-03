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
 * 语义（v0.18 重写为高水位记账）：epoch 判定 = 持久化窗口起点（fallback 比例判据
 * new < old×0.5）、增量相对「已入账高水位」计算（同 epoch 回落不二次入账、微量
 * 增量不因阈值+快照推进而丢失）、组件按 COMPONENT_COLUMNS 全映射（未知字段唯一
 * 兜底 other）、全局 `_dur` 时长差分快照。行为由 BatteryLedgerTest 钉死。
 */
object BatteryLedger {

    /** 整机组件差分基线在 battery_snapshot 表里的主键（沿用历史值，勿改） */
    const val GLOBAL_SNAPSHOT_KEY = "__global__"

    /** 全局组件时长的差分基线主键（v0.16.14 引入） */
    const val GLOBAL_DUR_SNAPSHOT_KEY = "__global___dur"

    /**
     * 组件字段 → 存储目的地的完整映射（v0.18，P2-1）。
     * 每个已解析组件字段**有且仅有一个**存储目的地（列）；两个键映射到同一列
     * （bluetooth/bt 是同一组件的 ROM 别名）视为一个目的地。未列出的字段进 otherMah
     * ——唯一的、可审计的下兜底。fg/bg/fgs 是 UID 行括号外的前台/后台/前台服务标签，
     * 各有专列（v0.16.7 曾因误从括号内取值恒得 0）。历史教训：KNOWN_COMPS 含
     * mobile_radio/flashlight 等却既不落列也不进 other，组件明细被静默丢弃
     * （真机 UID1000 flashlight=1.34）。
     */
    val COMPONENT_COLUMNS: Map<String, String> = mapOf(
        "screen" to "screen",
        "cpu" to "cpu",
        "audio" to "audio",
        "video" to "video",
        "camera" to "camera",
        "gnss" to "gnss",
        "wifi" to "wifi",
        "mobile_radio" to "mobile",
        "bluetooth" to "bt",
        "bt" to "bt",
        "wakelock" to "wakelock",
        "sensors" to "sensors",
        "flashlight" to "flashlight",
        "ambient_display" to "ambientDisplay",
        "idle" to "idle",
        "phone" to "phone",
        // 括号外标签字段
        "fg" to "fg",
        "bg" to "bg",
        "fgs" to "fgs",
    )

    /** UID 行括号内已知组件（= 有明确存储目的地的字段）；其余组件并入 otherMah */
    val KNOWN_COMPS = COMPONENT_COLUMNS.keys

    /** 账本算法版本：记账语义变化时递增，随采集日志落盘 */
    const val LEDGER_VERSION = 2

    /** 快照里记录的已入账高水位（-1 = 未初始化的历史行/旧语义数据） */
    private fun recordedCumOf(snap: BatterySnapshotEntity?): Double =
        snap?.recordedCumMah?.takeIf { it >= 0 } ?: snap?.cumMah ?: 0.0

    private fun recordedCompsOf(snap: BatterySnapshotEntity?): Map<String, Double> =
        if (snap != null && snap.recordedCumMah >= 0) decodeComps(snap.recordedCompsText)
        else decodeComps(snap?.compsText ?: "")

    /** 快照所属 epoch 的窗口起点（优先本帧解析值；本帧缺失则沿用快照既有标记） */
    private fun epochTsOf(
        parsedWindowStartTs: Long?,
        effLast: BatterySnapshotEntity?,
        prevWindowStartTs: Long?,
    ): Long = parsedWindowStartTs
        ?: effLast?.epochWindowStartTs?.takeIf { it > 0 }
        ?: prevWindowStartTs
        ?: 0L


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

    /**
     * 账本一致性不变量（P1-7，真机曾出现 screenMah 23.03 > totalMah 15.24 的故障行——
     * 该现象是 P0-1 吞量 bug 的衍生症状，根因修复后新数据不应再出现；本检查作为
     * 诊断门卫持续盯着账本）。
     *
     * 语义：**任意单一**真实功率组件不得显著大于 UID total（total 是 UID 行开头的
     * 总值；组件间可能有 smear/other/rounding 语义，不要求组件之和严格等于 total，
     * 所以不用「求和相等」这种脆弱判据）。违反 = "LEDGER_INCONSISTENT"，仅诊断标记，
     * 不阻断提交（阻断会让历史脏日卡死整条采集链）。
     */
    fun ledgerInconsistencies(
        rows: List<BatteryDailyEntity>,
        toleranceMah: Double = LEDGER_COMPONENT_TOLERANCE_MAH,
    ): List<String> {
        val out = ArrayList<String>()
        for (r in rows) {
            val worstComp = maxOf(
                r.screenMah, r.cpuMah, r.audioMah, r.videoMah, r.cameraMah, r.gnssMah,
                r.wifiMah, r.mobileMah, r.btMah, r.wakelockMah, r.sensorsMah,
            )
            if (worstComp > r.totalMah + toleranceMah) {
                out.add(
                    "LEDGER_INCONSISTENT ${r.uidKey}|day=${r.day}: maxComponent=$worstComp > total=${r.totalMah}"
                )
            }
        }
        return out
    }

    const val LEDGER_COMPONENT_TOLERANCE_MAH = 0.05

    /**
     * 合并跨帧的 LEDGER_INCONSISTENT 样本（真机回归发现的记录缺陷：诊断只存最后一帧，
     * 后续干净帧会把此前检出抹掉——真机 QQ u0a351 cpu 4.6 > total 4.0 的检出就被
     * 下一帧无违规覆写）。去重保序、上限 cap 行；全空返回 null（调用方据此清 key）。
     */
    fun mergeLedgerInconsistencies(previous: String?, fresh: List<String>, cap: Int = 50): String? {
        val merged = LinkedHashSet<String>()
        previous?.lineSequence()?.filter { it.isNotBlank() }?.forEach { merged.add(it) }
        fresh.filter { it.isNotBlank() }.forEach { merged.add(it) }
        if (merged.isEmpty()) return null
        return merged.take(cap).joinToString("\n")
    }

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
     *
     * 记账语义（v0.18，P0-2/P1-1 重写）：**原始观测与已入账高水位分离**。
     * 快照同时保存两类状态：cumMah/compsText = 上一帧原始观测（UNKNOWN 比例判据仍依赖
     * 它）；recordedCumMah/recordedCompsText = 已经写入日账本的最高水位。每帧增量一律
     * 相对高水位计算：max(0, 本帧观测 − 高水位)。
     *  - 同 epoch 模型回算（100→97）不再推进高水位 → 恢复到 100 时增量为 0，
     *    **不会二次入账**（旧实现 max(new-old,0) + 写回低快照会再记 3）；
     *  - 记录阈值取消（旧 0.001/0.01 会随快照推进把微量增量永久吞掉——100 次
     *    +0.0005 丢失 0.05 mAh）：存储层任何有限正增量都有去向，阈值只留给展示层；
     *  - total 无变化但组件/时长有变化时照常入账（组件是独立高水位，不跟随 total）。
     *
     * 选择与局限（记录在案）：高水位记账对「模型永久下调且不再恢复」的极端情形会
     * 高估（上限 = 回调幅度，实测 ~2% smear）；不做带符号回冲是因为负增量无法在
     * dayWeights 的时间归属下无歧义地落到某一天，且会改写已提交的历史行。账本
     * 不变量（ledgerInconsistencies）持续监督，历史 46 条 screen>total 行保留
     * 诊断、不擅自用组件反推 total。
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
            // 高水位基线：reset/首次观测按 0 处理（全量入账）；否则取已入账高水位
            val recCumPrev = if (effLast == null || reset) 0.0 else recordedCumOf(effLast)
            val recCompsPrev = if (effLast == null || reset) emptyMap() else recordedCompsOf(effLast)

            // 本帧原始观测（fg/bg/fgs 在括号外，与括号内组件表合并保存）
            val rawNow = HashMap<String, Double>(u.comps.size + 3)
            rawNow.putAll(u.comps)
            rawNow["fg"] = u.fg
            rawNow["bg"] = u.bg
            rawNow["fgs"] = u.fgs

            // 增量 = max(0, 原始观测 − 已入账高水位)，逐键独立
            val totalDelta = (u.totalMah - recCumPrev).coerceAtLeast(0.0)
            val compDeltas = HashMap<String, Double>()
            for ((k, v) in rawNow) {
                val d = (v - (recCompsPrev[k] ?: 0.0)).coerceAtLeast(0.0)
                if (d > 0.0) compDeltas[k] = d
            }

            // 新快照：原始观测 + 高水位（max 合并，永不回退）+ epoch 标记
            val recNew = HashMap<String, Double>(recCompsPrev)
            for ((k, v) in rawNow) recNew[k] = maxOf(recNew[k] ?: 0.0, v)
            snapshots.add(
                BatterySnapshotEntity(
                    uidKey = u.uidKey,
                    cumMah = u.totalMah,
                    compsText = encodeComps(rawNow),
                    updatedTs = now,
                    recordedCumMah = maxOf(recCumPrev, u.totalMah),
                    recordedCompsText = encodeComps(recNew),
                    epochWindowStartTs = epochTsOf(parsed.windowStartTs, effLast, prevWindowStartTs),
                )
            )

            // 存储层守恒：任何有限正增量都落库（无阈值）。total 与组件是独立高水位，
            // total 无变化时组件/fg-bg-fgs 的变化照常处理
            if (totalDelta <= 0.0 && compDeltas.isEmpty()) continue
            // 按存储目的地聚合组件增量（bluetooth/bt 同列求和；未知字段唯一兜底 other）
            val colDeltas = HashMap<String, Double>()
            for ((k, d) in compDeltas) {
                val col = COMPONENT_COLUMNS[k] ?: "other"
                colDeltas[col] = (colDeltas[col] ?: 0.0) + d
            }
            val pkg = pkgOf(uidOf(u.uidKey))
            for ((day, w) in dayWeights(deltaFrom(effLast?.updatedTs, reset, parsed.windowStartTs, now), now, zone)) {
                val ex = existing.daily[dailyKey(u.uidKey, day)]
                fun acc(col: String): Double = (colDeltas[col] ?: 0.0) * w
                dailyRows.add(
                    BatteryDailyEntity(
                        uidKey = u.uidKey,
                        day = day,
                        packageName = pkg,
                        totalMah = (ex?.totalMah ?: 0.0) + totalDelta * w,
                        fgMah = (ex?.fgMah ?: 0.0) + acc("fg"),
                        bgMah = (ex?.bgMah ?: 0.0) + acc("bg"),
                        fgsMah = (ex?.fgsMah ?: 0.0) + acc("fgs"),
                        screenMah = (ex?.screenMah ?: 0.0) + acc("screen"),
                        cpuMah = (ex?.cpuMah ?: 0.0) + acc("cpu"),
                        audioMah = (ex?.audioMah ?: 0.0) + acc("audio"),
                        videoMah = (ex?.videoMah ?: 0.0) + acc("video"),
                        cameraMah = (ex?.cameraMah ?: 0.0) + acc("camera"),
                        gnssMah = (ex?.gnssMah ?: 0.0) + acc("gnss"),
                        wifiMah = (ex?.wifiMah ?: 0.0) + acc("wifi"),
                        btMah = (ex?.btMah ?: 0.0) + acc("bt"),
                        wakelockMah = (ex?.wakelockMah ?: 0.0) + acc("wakelock"),
                        sensorsMah = (ex?.sensorsMah ?: 0.0) + acc("sensors"),
                        mobileMah = (ex?.mobileMah ?: 0.0) + acc("mobile"),
                        flashlightMah = (ex?.flashlightMah ?: 0.0) + acc("flashlight"),
                        ambientDisplayMah = (ex?.ambientDisplayMah ?: 0.0) + acc("ambientDisplay"),
                        idleMah = (ex?.idleMah ?: 0.0) + acc("idle"),
                        phoneMah = (ex?.phoneMah ?: 0.0) + acc("phone"),
                        otherMah = (ex?.otherMah ?: 0.0) + acc("other"),
                    )
                )
            }
        }

        val globalRows = ArrayList<BatteryGlobalEntity>()
        // 全局组件：与 UID 行同一 epoch 状态（P0-1：UID/GLOBAL/GLOBAL_DUR 必须共用同一 reset）；
        // 记账与 UID 行同一高水位语义（功率与时长各自独立的高水位基线）
        if (parsed.computedDrain > 0) {
            val gLast = existing.snapshots[GLOBAL_SNAPSHOT_KEY]
            val effGLast = if (globalReset) null else gLast
            // UNKNOWN 时退回比例判据：整机重算/校准也会让 computedDrain 小幅回落（v0.16.11）
            val reset = when (epoch) {
                EpochState.RESET -> false
                EpochState.SAME -> false
                EpochState.UNKNOWN -> effGLast != null && isReset(effGLast.cumMah, parsed.computedDrain)
            }
            val gRecCumPrev = if (effGLast == null || reset) 0.0 else recordedCumOf(effGLast)
            val gRecCompsPrev = if (effGLast == null || reset) emptyMap() else recordedCompsOf(effGLast)
            val gRawComps = parsed.globals.mapValues { it.value.first }
            val gRecNew = HashMap<String, Double>(gRecCompsPrev)
            for ((k, v) in gRawComps) gRecNew[k] = maxOf(gRecNew[k] ?: 0.0, v)
            snapshots.add(
                BatterySnapshotEntity(
                    GLOBAL_SNAPSHOT_KEY, parsed.computedDrain, encodeComps(gRawComps), now,
                    recordedCumMah = maxOf(gRecCumPrev, parsed.computedDrain),
                    recordedCompsText = encodeComps(gRecNew),
                    epochWindowStartTs = epochTsOf(parsed.windowStartTs, effGLast, prevWindowStartTs),
                )
            )
            val gLastDur = existing.snapshots[GLOBAL_DUR_SNAPSHOT_KEY]
            val effGDur = if (globalReset) null else gLastDur
            val gDurRecCompsPrev = if (effGDur == null || reset) emptyMap() else recordedCompsOf(effGDur)
            val gRawDurs = parsed.globals.mapValues { it.value.second.toDouble() }
            val gDurRecNew = HashMap<String, Double>(gDurRecCompsPrev)
            for ((k, v) in gRawDurs) gDurRecNew[k] = maxOf(gDurRecNew[k] ?: 0.0, v)
            snapshots.add(
                BatterySnapshotEntity(
                    GLOBAL_DUR_SNAPSHOT_KEY,
                    parsed.globals.values.sumOf { it.second.toDouble() },
                    encodeComps(gRawDurs),
                    now,
                    recordedCumMah = maxOf(recordedCumOf(effGDur), parsed.globals.values.sumOf { it.second.toDouble() }),
                    recordedCompsText = encodeComps(gDurRecNew),
                    epochWindowStartTs = epochTsOf(parsed.windowStartTs, effGDur, prevWindowStartTs),
                )
            )
            val weights = dayWeights(deltaFrom(effGLast?.updatedTs, reset, parsed.windowStartTs, now), now, zone)
            for ((comp, pair) in parsed.globals) {
                val mahDelta = (pair.first - (gRecCompsPrev[comp] ?: 0.0)).coerceAtLeast(0.0)
                val durDelta = (pair.second - (gDurRecCompsPrev[comp] ?: 0.0).toLong()).coerceAtLeast(0L)
                if (mahDelta <= 0.0 && durDelta <= 0L) continue
                for ((day, w) in weights) {
                    val ex = existing.global[globalKey(comp, day)]
                    globalRows.add(
                        BatteryGlobalEntity(
                            component = comp,
                            day = day,
                            mah = (ex?.mah ?: 0.0) + mahDelta * w,
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
 * 原子提交一次 batterystats 差分（v0.17.0 引入；v0.17.1 加入 epoch 状态；v0.18 加入
 * 解析健康基线的协调推进）。
 *
 * 单一 Room 事务内完成：批量读旧状态 → 纯差分计算 → 批量写 battery_daily /
 * battery_global → （epoch 变化时删除全部旧快照）→ 推进 battery_snapshot →
 * 推进 epoch 标记（sync_state.KEY_BATTERY_WINDOW_START_TS）→ 推进解析健康基线
 * （KEY_PARSER_BASELINE_UIDS / KEY_PARSER_HAD_WINDOW）。任一步抛异常
 * （含差分计算后注入的故障）整个事务回滚：快照不推进、daily/global 不推进、
 * **epoch 标记与已接受基线也不推进**（P0-4：下一次同步可基于完整旧状态重新计算）。
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
    newBaselineUidCount: Long? = null,
    hadWindowBaseline: Boolean? = null,
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
        // 解析健康基线与数据同事务推进（v0.18）：门控拒绝/事务失败都不得推进已接受基线
        newBaselineUidCount?.let { dao.putState(SyncStateEntity(UsageRepository.KEY_PARSER_BASELINE_UIDS, it)) }
        if (hadWindowBaseline == true) {
            dao.putState(SyncStateEntity(UsageRepository.KEY_PARSER_HAD_WINDOW, 1L))
        }
        out
    }

/**
 * API fallback 会话落库（P0-2，v0.17.1）：单一事务内
 *  1) 只删除 source=API 的旧 fallback 会话——ROOT/LEGACY 权威历史**永不触碰**；
 *  2) 新 API 会话与现存权威会话时间重叠的一律丢弃（只补权威缺席的区间）；
 *  3) 推进事件水位线。
 * root 恢复后，root 全量重建路径（deleteSessionsStartingAfter + ROOT 来源插入）
 * 会自然替换此前的 API fallback 数据。
 * probeAfterCompute 仅供事务回滚测试注入故障。
 */
internal suspend fun applyApiFallbackSessions(
    db: RoomDatabase,
    dao: UsageDao,
    apiSessions: List<UsageSession>,
    windowStart: Long,
    watermarkTs: Long,
    probeBeforeInsert: (suspend () -> Unit)? = null,
) {
    db.withTransaction {
        dao.deleteSessionsBySourceStartingAfter(SESSION_SOURCE_API, windowStart)
        val authority = dao.sessionsActiveSince(windowStart)
        val safe = filterNonOverlapping(apiSessions, authority)
        probeBeforeInsert?.invoke()
        if (safe.isNotEmpty()) dao.insertSessions(safe)
        dao.putState(SyncStateEntity(UsageRepository.KEY_WATERMARK, watermarkTs))
    }
}

/**
 * 重叠过滤（P0-2 纯函数）：候选会话与任一权威会话时间区间相交即丢弃
 * （权威模型同一时刻只有一个前台应用，跨包重叠同样是 API 残缺流的伪影）。
 */
internal fun filterNonOverlapping(
    candidates: List<UsageSession>,
    authority: List<UsageSession>,
): List<UsageSession> {
    if (candidates.isEmpty() || authority.isEmpty()) return candidates
    val sorted = authority.sortedBy { it.startTs }
    return candidates.filter { c ->
        sorted.none { a -> c.startTs < a.endTs && a.startTs < c.endTs }
    }
}
