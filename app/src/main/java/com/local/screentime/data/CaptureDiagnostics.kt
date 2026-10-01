package com.local.screentime.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 batterystats 采集的完整性诊断（P1-2/P1-4）。
 *
 * 背景：历史上 fgs 字段变化曾造成部分 UID 正则静默失配 → 快照停更 → 充电重置后
 * 差分永久丢失（v0.16.9）。旧失败判据只有「UID 和 global 全空」，太弱。本诊断
 * 记录解析的完整画像并在异常时**禁止静默推进部分快照**。
 *
 * 异常判据（不设固定 UID 数阈值；baseline 来自本设备近期健康解析，见
 * UsageRepository 的 KEY_PARSER_BASELINE_UIDS / KEY_PARSER_HAD_WINDOW）：
 *  - computedDrain 无法解析；
 *  - 本设备此前可解析窗口起点，本帧却缺失；
 *  - UID 数量相对近期健康 baseline 灾难性下降（<50%）；
 *  - power section 整体未解析出内容。
 *
 * 能量口径（P1-4，不要再用 screen+apps+system 之类重叠公式）：
 *  - [sumUidTotalMah] = 本帧全部 UID totalMah 之和（UID 归因耗电；UID total 本身
 *    已包含其 screen/cpu/camera 等组件）；
 *  - [computedDrainMah] = 整机模型估算；[actualDrainLowMah]/[actualDrainHighMah] =
 *    实测放电区间（真机 1522 vs 1954-2072，差值 432~550 mAh 是模型缺口的区间表述，
 *    不要写成固定值）；
 *  - [batteryCapacityMah] = 电池容量（5920），是容量不是本窗口耗电，禁止与 UID sum 比较；
 *  - [uidAttributionRatio] = UID 归因 / 整机模型；[modelCoverageLow]/[modelCoverageHigh]
 *    = 模型 / 实测区间。
 */
data class CaptureDiagnostics(
    val parserVersion: Int,
    val rawLineCount: Int,
    val parsedUidCount: Int,
    val parsedGlobalCount: Int,
    val windowStartTs: Long?,
    val batteryCapacityMah: Double?,
    val computedDrainMah: Double,
    val actualDrainLowMah: Double?,
    val actualDrainHighMah: Double?,
    val sumUidTotalMah: Double,
    val anomalies: List<String>,
) {
    val uidAttributionRatio: Double?
        get() = if (computedDrainMah > 0) sumUidTotalMah / computedDrainMah else null

    val modelCoverageLow: Double?
        get() = actualDrainLowMah?.takeIf { it > 0 }?.let { computedDrainMah / it }

    val modelCoverageHigh: Double?
        get() = actualDrainHighMah?.takeIf { it > 0 }?.let { computedDrainMah / it }

    fun toJson(): JSONObject = JSONObject().apply {
        put("parserVersion", parserVersion)
        put("rawLineCount", rawLineCount)
        put("parsedUidCount", parsedUidCount)
        put("parsedGlobalCount", parsedGlobalCount)
        put("windowStartTs", windowStartTs ?: JSONObject.NULL)
        put("batteryCapacityMah", batteryCapacityMah ?: JSONObject.NULL)
        put("computedDrainMah", computedDrainMah)
        put("actualDrainLowMah", actualDrainLowMah ?: JSONObject.NULL)
        put("actualDrainHighMah", actualDrainHighMah ?: JSONObject.NULL)
        put("sumUidTotalMah", sumUidTotalMah)
        put("uidAttributionRatio", uidAttributionRatio ?: JSONObject.NULL)
        put("modelCoverageLow", modelCoverageLow ?: JSONObject.NULL)
        put("modelCoverageHigh", modelCoverageHigh ?: JSONObject.NULL)
        put("anomalies", JSONArray(anomalies))
    }

    companion object {
        /** parser 结构版本：section 边界/字段变更时递增，随诊断落盘 */
        const val PARSER_VERSION = 3

        fun fromParsed(
            parsed: BatteryParsed,
            baselineUidCount: Long?,
            hadWindowBaseline: Boolean,
        ): CaptureDiagnostics = CaptureDiagnostics(
            parserVersion = PARSER_VERSION,
            rawLineCount = parsed.rawLineCount,
            parsedUidCount = parsed.uids.size,
            parsedGlobalCount = parsed.globals.size,
            windowStartTs = parsed.windowStartTs,
            batteryCapacityMah = parsed.batteryCapacityMah,
            computedDrainMah = parsed.computedDrain,
            actualDrainLowMah = parsed.actualDrainLow.takeIf { it > 0 },
            actualDrainHighMah = parsed.actualDrainHigh,
            sumUidTotalMah = parsed.uids.sumOf { it.totalMah },
            anomalies = detectAnomalies(parsed, baselineUidCount, hadWindowBaseline),
        )

        /** 异常检测（纯函数）。baseline 为 null/0 表示尚无健康基线，跳过相对判据 */
        fun detectAnomalies(
            parsed: BatteryParsed,
            baselineUidCount: Long?,
            hadWindowBaseline: Boolean,
        ): List<String> {
            val a = ArrayList<String>()
            if (parsed.uids.isEmpty() && parsed.globals.isEmpty()) {
                a.add("power_section_unparsed")
            }
            if (parsed.computedDrain <= 0.0) a.add("computed_drain_missing")
            if (parsed.windowStartTs == null && hadWindowBaseline) a.add("window_start_missing")
            val baseline = baselineUidCount ?: 0L
            if (baseline > 0 && parsed.uids.size < baseline * 0.5) {
                a.add("uid_count_drop(baseline=$baseline,parsed=${parsed.uids.size})")
            }
            return a
        }

        fun fromJson(text: String): CaptureDiagnostics? = try {
            val o = JSONObject(text)
            CaptureDiagnostics(
                parserVersion = o.optInt("parserVersion", 0),
                rawLineCount = o.optInt("rawLineCount", 0),
                parsedUidCount = o.optInt("parsedUidCount", 0),
                parsedGlobalCount = o.optInt("parsedGlobalCount", 0),
                windowStartTs = if (o.isNull("windowStartTs")) null else o.optLong("windowStartTs"),
                batteryCapacityMah = if (o.isNull("batteryCapacityMah")) null else o.optDouble("batteryCapacityMah"),
                computedDrainMah = o.optDouble("computedDrainMah", 0.0),
                actualDrainLowMah = if (o.isNull("actualDrainLowMah")) null else o.optDouble("actualDrainLowMah"),
                actualDrainHighMah = if (o.isNull("actualDrainHighMah")) null else o.optDouble("actualDrainHighMah"),
                sumUidTotalMah = o.optDouble("sumUidTotalMah", 0.0),
                anomalies = o.optJSONArray("anomalies")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
                } ?: emptyList(),
            )
        } catch (e: Exception) {
            null
        }
    }
}
