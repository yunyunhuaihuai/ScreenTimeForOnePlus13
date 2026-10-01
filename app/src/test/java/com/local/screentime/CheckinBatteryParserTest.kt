package com.local.screentime

import com.local.screentime.data.CheckinBatteryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-9 checkin parser 测试。
 *
 * fixture 1（真实 OnePlus 13 脱敏）：取自 2026-09-18 真机 root `dumpsys batterystats
 * --checkin` 的 pws/pwi 行（5049 行中的 power 段，数值逐字未改；不含 uid→包名映射行，
 * 无设备隐私）。钉死真机列结构：`<ver>,<uid>,<agg>,pwi,<consumer>,<mAh>,...`。
 *
 * fixture 2（AOSP 语法）：按 AOSP BatteryStats.dumpCheckin 语法构造的另一组数值，
 * 验证 parser 只依赖语法而非本机数值。
 *
 * 旧实现的错误（pwi 当 cols[1]、合成 fixture 与实现一起写错）由 malformed 用例钉死：
 * 旧式行 `9,pwi,scrn,912` 不得解析出任何内容。
 *
 * 注意：checkin（completed/`-c` 统计）与 human-readable `--charged` 的统计窗口不同，
 * 数值不可跨格式对账（同日 fixture 也分属不同窗口）——一致性验证只做语法级与
 * consumer 键对齐级，不做数值相等。
 */
class CheckinBatteryParserTest {

    /** 真机 power 段原始行（数值未改；`???` 为 AOSP 未知 consumer 占位） */
    private val onePlus13Fixture = listOf(
        "9,0,i,vers,36,215,AP3A.240617.008,AP3A.240617.008",
        "9,0,l,pws,5920,5154,6630,7163",
        "9,0,l,pwi,scrn,912,1,0,0",
        "9,0,l,pwi,cpu,0,0,0,0",
        "9,0,l,pwi,blue,172,0,0,0",
        "9,0,l,pwi,camera,123,0,0,0",
        "9,0,l,pwi,audio,80.6,0,0,0",
        "9,0,l,pwi,video,39.9,0,0,0",
        "9,0,l,pwi,flashlight,0,0,0,0",
        "9,0,l,pwi,???,0,0,0,0",
        "9,0,l,pwi,cell,0,1,0,0",
        "9,0,l,pwi,sensors,7.00,0,0,0",
        "9,0,l,pwi,gnss,14.7,0,0,0",
        "9,0,l,pwi,wifi,72.8,0,0,0",
        "9,0,l,pwi,???,17.9,0,0,0",
        "9,0,l,pwi,memory,0,0,0,0",
        "9,0,l,pwi,phone,0,0,0,0",
        "9,0,l,pwi,ambi,34.2,1,0,0",
        "9,0,l,pwi,idle,67.4,1,0,0",
        "9,0,l,pwi,???,0,0,0,0",
        "9,-5,l,pwi,uid,0,0,0,0",
    )

    @Test
    fun onePlus13Fixture_pws_parsed() {
        val p = CheckinBatteryParser.parse(onePlus13Fixture)
        assertEquals(1, p.pwsRowCount)
        val s = p.batterySummary!!
        assertEquals(5920.0, s.capacityMah, 1e-9)
        assertEquals(5154.0, s.computedDrainMah!!, 1e-9)
        assertEquals(6630.0, s.actualDrainLowMah!!, 1e-9)
        assertEquals(7163.0, s.actualDrainHighMah!!, 1e-9)
    }

    @Test
    fun onePlus13Fixture_deviceComponents_parsedAndNormalized() {
        val p = CheckinBatteryParser.parse(onePlus13Fixture)
        assertEquals(19, p.pwiRowCount)
        val d = p.deviceComps
        assertEquals(912.0, d["screen"]!!, 1e-9)        // scrn → screen
        assertEquals(0.0, d["cpu"]!!, 1e-9)
        assertEquals(172.0, d["bluetooth"]!!, 1e-9)     // blue → bluetooth
        assertEquals(123.0, d["camera"]!!, 1e-9)
        assertEquals(80.6, d["audio"]!!, 1e-9)
        assertEquals(39.9, d["video"]!!, 1e-9)
        assertEquals(0.0, d["mobile_radio"]!!, 1e-9)    // cell=0（真机事实：射频无 attribution）
        assertEquals(7.0, d["sensors"]!!, 1e-9)
        assertEquals(14.7, d["gnss"]!!, 1e-9)
        assertEquals(72.8, d["wifi"]!!, 1e-9)
        assertEquals(34.2, d["ambient_display"]!!, 1e-9) // ambi → ambient_display
        assertEquals(67.4, d["idle"]!!, 1e-9)
        assertEquals(17.9, d["???"]!!, 1e-9)             // 三个 ??? 占位行求和（0+17.9+0）
        assertEquals(0.0, d["flashlight"]!!, 1e-9)
        assertEquals(0.0, d["memory"]!!, 1e-9)
        assertEquals(0.0, d["phone"]!!, 1e-9)
        assertEquals(16, d.size)
    }

    @Test
    fun onePlus13Fixture_nonZeroUidPwi_staysOutOfDeviceComps() {
        // `9,-5,l,pwi,uid,...`：非 0 uid 行进 uidComps，不污染设备级组件
        val p = CheckinBatteryParser.parse(onePlus13Fixture)
        assertEquals(1, p.uidComps.size)
        assertEquals(0.0, p.uidComps["-5"]!!["uid"]!!, 1e-9)
        assertNull(p.deviceComps["uid"])
    }

    @Test
    fun aospFixture_parsed() {
        val p = CheckinBatteryParser.parse(
            listOf(
                "9,0,i,vers,36,215,BUILD1,BUILD1",
                "9,0,i,uid,1000,com.example.system",
                "9,0,l,pws,6000,2000,2500,2600",
                "9,0,l,pwi,scrn,500,1,0,0",
                "9,0,l,pwi,cpu,300,0,0,0",
                "9,u0a334,l,pwi,scrn,20,0,0,0",
            )
        )
        assertEquals(6000.0, p.batterySummary!!.capacityMah, 1e-9)
        assertEquals(2000.0, p.batterySummary!!.computedDrainMah!!, 1e-9)
        assertEquals(500.0, p.deviceComps["screen"]!!, 1e-9)
        assertEquals(300.0, p.deviceComps["cpu"]!!, 1e-9)
        assertEquals(20.0, p.uidComps["u0a334"]!!["screen"]!!, 1e-9)
    }

    @Test
    fun legacyWrongColumnLayout_doesNotParse() {
        // 旧实现错误假设的行结构（pwi 在 cols[1]）：必须解析不出任何内容——钉死 P1-9 修复
        val p = CheckinBatteryParser.parse(
            listOf(
                "9,pwi,scrn,912",
                "9,pwi,0,cpu,300",
                "9,pws,5920,5154",
            )
        )
        assertEquals(0, p.pwiRowCount)
        assertEquals(0, p.pwsRowCount)
        assertNull(p.batterySummary)
        assertTrue(p.deviceComps.isEmpty())
    }

    @Test
    fun malformedRows_ignored() {
        val p = CheckinBatteryParser.parse(
            listOf(
                "",
                "9,0,l,pwi,scrn",          // 列数不足
                "9,0,l,pwi,cpu,notanumber", // 数值非法
                "9,0,l,pwi,,1.0",           // consumer 为空
                "9,0,l,pwl,foo,1,0",        // 非 pws/pwi section
            )
        )
        assertEquals(0, p.pwiRowCount)
        assertEquals(0, p.pwsRowCount)
    }
}
