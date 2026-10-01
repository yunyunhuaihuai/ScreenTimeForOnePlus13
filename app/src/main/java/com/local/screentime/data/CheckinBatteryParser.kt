package com.local.screentime.data

/**
 * batterystats 机器可读格式（checkin）解析器（P1-9，v0.17.1 按真机输出重写）。
 *
 * 行结构（OnePlus 13 / Android 15 真机 `dumpsys batterystats --checkin` 实测，
 * 与 AOSP BatteryStats.dumpCheckin 语法一致）：
 * ```
 * <version>,<uid>,<agg>,<section>,<fields...>
 * 9,0,i,vers,36,215,AP3A...,AP3A...          // 版本信息
 * 9,0,i,uid,1000,com.example.system          // uid→包名映射（隐私敏感，不采集）
 * 9,0,l,pws,5920,5154,6630,7163              // power summary：容量,computed,actualLow,actualHigh
 * 9,0,l,pwi,scrn,912,1,0,0                   // power item：整机组件（uid=0）
 * 9,-5,l,pwi,uid,0,0,0,0                     // 非 0 uid 的 pwi（本 ROM 仅见聚合占位行）
 * ```
 * 旧实现假设 `<ver>,pwi,<uid>,<component>,<mAh>`（pwi 在 cols[1]）——与真机输出完全
 * 不符，是「实现与合成 fixture 一起写错、测试全绿」的实例，已废弃。
 *
 * 真机实测事实（2026-09-18 root `--checkin`，5049 行）：
 *  - pwi 全部为设备级组件（uid=0）；**per-UID 耗电在本 ROM 的 checkin 中不存在**
 *    （与 EnergyConsumer attribution 不可用一致）→ checkin 无法替代 human-readable
 *    的 per-UID 账本，后者保持生产主路径；
 *  - cell/wifi 组件在部分窗口为 0（另一窗口 wifi=72.8 存在）；
 *  - `???` 是 AOSP 对未知 consumer 的占位，可能有多个；
 *  - `-c`（当前统计）与 `--checkin`（completed 统计段）语义不同，行语法相同。
 *
 * 仍然**不接入生产采集路径**（见 HANDOFF 待办：需 `-c` 实测对账后再评估）。
 */
object CheckinBatteryParser {

    /** pws：power summary。actualLow/High 可能为 null（字段缺失时） */
    data class BatterySummary(
        val capacityMah: Double,
        val computedDrainMah: Double?,
        val actualDrainLowMah: Double?,
        val actualDrainHighMah: Double?,
    )

    data class CheckinParsed(
        val batterySummary: BatterySummary?,
        /** 设备级组件（pwi uid=0）→ mAh；consumer 名做最小归一（scrn→screen 等） */
        val deviceComps: Map<String, Double>,
        /** 非 0 uid 的 pwi 行（本 ROM 当前无 per-UID 功率；保留结构以兼容其他 ROM/未来版本） */
        val uidComps: Map<String, Map<String, Double>>,
        val pwiRowCount: Int,
        val pwsRowCount: Int,
    )

    /** 真机/AOSP consumer → 应用内组件名（与 human-readable parser 的 globals 键对齐） */
    private val CONSUMER_ALIASES = mapOf(
        "scrn" to "screen",
        "blue" to "bluetooth",
        "cell" to "mobile_radio",
        "ambi" to "ambient_display",
    )

    private fun normalizeConsumer(raw: String): String = CONSUMER_ALIASES[raw] ?: raw

    fun parse(lines: List<String>): CheckinParsed {
        var summary: BatterySummary? = null
        val device = LinkedHashMap<String, Double>()
        val uidComps = LinkedHashMap<String, MutableMap<String, Double>>()
        var pwiRows = 0
        var pwsRows = 0
        for (raw in lines) {
            // checkin 行是逗号分隔；只认 section 位（cols[3]）为 pws/pwi 的行
            val cols = raw.split(',')
            if (cols.size < 5) continue
            when (cols.getOrNull(3)?.trim()) {
                "pws" -> {
                    // <ver>,<uid>,<agg>,pws,<capacity>,<computed>,<actualLow>,<actualHigh>
                    val cap = cols.getOrNull(4)?.trim()?.toDoubleOrNull() ?: continue
                    summary = BatterySummary(
                        capacityMah = cap,
                        computedDrainMah = cols.getOrNull(5)?.trim()?.toDoubleOrNull(),
                        actualDrainLowMah = cols.getOrNull(6)?.trim()?.toDoubleOrNull(),
                        actualDrainHighMah = cols.getOrNull(7)?.trim()?.toDoubleOrNull(),
                    )
                    pwsRows++
                }
                "pwi" -> {
                    // <ver>,<uid>,<agg>,pwi,<consumer>,<mAh>[,<extra>...]
                    val uid = cols.getOrNull(1)?.trim() ?: continue
                    val consumer = cols.getOrNull(4)?.trim() ?: continue
                    if (consumer.isEmpty()) continue
                    val mah = cols.getOrNull(5)?.trim()?.toDoubleOrNull() ?: continue
                    pwiRows++
                    val key = normalizeConsumer(consumer)
                    if (uid == "0") {
                        device[key] = (device[key] ?: 0.0) + mah   // 同名 consumer（如多个 ???）求和
                    } else {
                        uidComps.getOrPut(uid) { LinkedHashMap() }[key] =
                            (uidComps[uid]?.get(key) ?: 0.0) + mah
                    }
                }
                else -> continue
            }
        }
        return CheckinParsed(summary, device, uidComps, pwiRows, pwsRows)
    }
}
