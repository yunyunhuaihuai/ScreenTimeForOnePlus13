package com.local.screentime

import com.local.screentime.data.CheckinBatteryParser
import com.local.screentime.data.UsageRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1-4 一致性测试：同一份合成状态分别以 human-readable（真机格式，行样本逐字节取自
 * OnePlus 13 真机 dump）与 checkin（按 AOSP 规范构造，**未经真机实测对账**）表达，
 * 两个解析器在重叠字段上必须给出一致结果。
 *
 * ⚠️ checkin fixture 是脚手架：用于钉死解析器行为与格式理解，不代表 ColorOS 15 的
 * 真实 `-c` 输出。在拿到 OnePlus 13 真机 `dumpsys batterystats -c` 原始样本并逐行
 * 对账之前，本解析器不得接入生产采集路径；human-readable parser（经真机验证）
 * 保持生产地位不动。
 */
class CheckinBatteryParserTest {

    // —— human-readable 侧：真机原始行（见 BatteryParseTest 注释的取证来源） ——
    private val humanLines = listOf(
        """  Start clock time: 2026-09-18-18-05-19""",
        """  Estimated power use (mAh):""",
        """    Capacity: 5920, Computed drain: 2460, actual drain: 3256-3493""",
        """    UID u0a334: 247 fg: 28.8 bg: 132 fgs: 16.9 cached: 12.5 ( screen=34.5 (13m 31s 894ms) cpu=170 cpu:fg=9.99 cpu:bg=131 cpu:fgs=16.1 ) """,
        """    UID 1000: 147 fg: 4.62 ( cpu=133 ) """,
        """      screen: 303 apps: 303 duration: 2h 0m 37s 958ms """,
    )

    // —— checkin 侧：按 AOSP checkin 规范构造的同一状态（合成，未经真机对账） ——
    private val checkinLines = listOf(
        "9,0,i,vers,11,116,AP2A.240905.003,AP2A.240905.003",
        "9,0,i,ds,1,1,2026-09-18-18-05-19",
        "9,pwi,u0a334,fg,28.8",
        "9,pwi,u0a334,bg,132",
        "9,pwi,u0a334,fgs,16.9",
        "9,pwi,u0a334,screen,34.5",
        "9,pwi,u0a334,cpu,170",
        "9,pwi,1000,cpu,133",
        "9,pwi,0,screen,303",
    )

    @Test
    fun checkinParser_uidComponents_matchHumanReadable() {
        val human = UsageRepository.parseBatteryPower(humanLines)
        val checkin = CheckinBatteryParser.parse(checkinLines)

        val u334 = checkin.uidComps["u0a334"]!!
        assertEquals(human.uids.first { it.uidKey == "u0a334" }.fg, u334["fg"]!!, 1e-9)
        assertEquals(human.uids.first { it.uidKey == "u0a334" }.bg, u334["bg"]!!, 1e-9)
        assertEquals(human.uids.first { it.uidKey == "u0a334" }.fgs, u334["fgs"]!!, 1e-9)
        assertEquals(human.uids.first { it.uidKey == "u0a334" }.comps["screen"]!!, u334["screen"]!!, 1e-9)
        assertEquals(human.uids.first { it.uidKey == "u0a334" }.comps["cpu"]!!, u334["cpu"]!!, 1e-9)
    }

    @Test
    fun checkinParser_systemUidAndDeviceComponents_match() {
        val human = UsageRepository.parseBatteryPower(humanLines)
        val checkin = CheckinBatteryParser.parse(checkinLines)

        assertEquals(human.uids.first { it.uidKey == "1000" }.comps["cpu"]!!, checkin.uidComps["1000"]!!["cpu"]!!, 1e-9)
        // 整机组件：human-readable 全局段 screen: 303 ↔ checkin pwi,0,screen,303
        assertEquals(human.globals["screen"]!!.first, checkin.deviceComps["screen"]!!, 1e-9)
        assertEquals(7, checkin.pwiRowCount)
    }

    @Test
    fun checkinParser_nonPwiOrMalformedLines_ignored() {
        val parsed = CheckinBatteryParser.parse(
            listOf(
                "9,0,i,vers,11,116,X",
                "9,pwi,u0a1,screen",        // 列数不足：忽略
                "9,pwi,u0a1,cpu,notanumber", // 数值非法：忽略
                "9,pwl,u0a1,screen,1.0",     // 非 pwi 行：忽略
                "",
            )
        )
        assertEquals(0, parsed.pwiRowCount)
        assertEquals(0, parsed.uidComps.size)
        assertEquals(0, parsed.deviceComps.size)
    }
}
