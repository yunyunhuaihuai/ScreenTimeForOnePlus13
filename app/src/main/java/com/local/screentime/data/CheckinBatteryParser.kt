package com.local.screentime.data

/**
 * batterystats 机器可读格式（checkin）实验解析器（P1-4）。
 *
 * 背景：human-readable `dumpsys batterystats --charged` 已经过真机验证并踩过
 * `fgs:` 字段变化的坑（v0.16.9）。机器可读格式 `-c`（checkin，输出逗号分隔行）
 * 理论上跨版本更稳定，但**未经 OnePlus 13 / ColorOS 15 实测对账**：
 *  - `-c` = 输出「当前统计」的 checkin 格式；
 *  - `--checkin` 带有「旧 completed 统计段」的特殊语义，两者不能混用；
 *  - 各 ROM 的行类型（vers/ds/pwi/cam/pwl…）与字段顺序存在差异。
 *
 * 因此本解析器**不接入生产采集路径**，仅作为数据源接口脚手架：
 * 用 AOSP checkin 规范构造 fixture 钉死解析行为（CheckinBatteryParserTest），
 * 待真机 `dumpsys batterystats -c` 原始样本对账后再决定是否启用，届时禁止删除
 * 仍在生产的 human-readable parser。
 *
 * 解析范围（刻意最小化，只认 high-confidence 的 pwi 行）：
 *   `<ver>,pwi,<uid>,<component>,<mAh>[,...]`
 * uid=0 的 pwi 行是整机组件耗电（对应 human-readable 的全局组件段）。
 */
object CheckinBatteryParser {

    data class CheckinParsed(
        /** 应用/系统 UID → 组件 → mAh（pwi 行按 (uid, component) 聚合） */
        val uidComps: Map<String, Map<String, Double>>,
        /** 整机组件耗电（uid=0 的 pwi 行） */
        val deviceComps: Map<String, Double>,
        /** 成功识别的 pwi 行数（0 通常意味着格式与预期不符，需人工核对原始 dump） */
        val pwiRowCount: Int,
    )

    fun parse(lines: List<String>): CheckinParsed {
        val uidComps = LinkedHashMap<String, MutableMap<String, Double>>()
        val deviceComps = LinkedHashMap<String, Double>()
        var pwiRows = 0
        for (raw in lines) {
            val line = raw.trim()
            if (!line.contains(",pwi,")) continue
            val cols = line.split(',')
            // <ver>,pwi,<uid>,<component>,<mAh>
            if (cols.size < 5 || cols[1] != "pwi") continue
            val mah = cols[4].toDoubleOrNull() ?: continue
            val uid = cols[2].trim()
            val comp = cols[3].trim()
            if (uid.isEmpty() || comp.isEmpty()) continue
            pwiRows++
            if (uid == "0") {
                deviceComps[comp] = (deviceComps[comp] ?: 0.0) + mah
            } else {
                uidComps.getOrPut(uid) { LinkedHashMap() }[comp] =
                    (uidComps[uid]?.get(comp) ?: 0.0) + mah
            }
        }
        return CheckinParsed(uidComps, deviceComps, pwiRows)
    }
}
