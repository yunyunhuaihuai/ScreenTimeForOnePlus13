package com.local.screentime

import com.local.screentime.data.UsageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 真机原始行回放测试：数据取自 OnePlus 13（ColorOS 15 / Android 15）
 * 2026-09-18 23:48 `dumpsys batterystats --charged`，逐字节未改。
 * 证据文件：analysis/power-20260918/batterystats_charged.txt（不入库）。
 *
 * 核心回归：UID 行含 `fgs:`（前台服务耗电）字段时不得被丢弃 ——
 * bilibili/抖音等带前台服务的应用曾因此整行漏解析，快照停更，
 * 夜间充电窗口重置后差分变负被丢，表现为"耗电消失"。
 */
class BatteryParseTest {

    private fun parse(vararg lines: String) = UsageRepository.parseBatteryPower(lines.toList())

    @Test
    fun uidLineWithFgs_isParsed_bilibiliRealLine() {
        val p = parse(headerLine, drainLine, uidU0a334)
        val u = p.uids.firstOrNull { it.uidKey == "u0a334" }
        assertNotNull("含 fgs 字段的 UID 行必须能解析（bilibili u0a334 曾被整行丢弃）", u)
        assertEquals(247.0, u!!.totalMah, 1e-9)
        assertEquals(28.8, u.fg, 1e-9)
        assertEquals(132.0, u.bg, 1e-9)
        assertEquals(16.9, u.fgs, 1e-9)
        assertEquals(34.5, u.comps["screen"]!!, 1e-9)
        assertEquals(170.0, u.comps["cpu"]!!, 1e-9)
        assertEquals(18.5, u.comps["audio"]!!, 1e-9)
        assertEquals(22.9, u.comps["video"]!!, 1e-9)
        assertEquals(0.0, u.comps["mobile_radio"]!!, 1e-9)
        assertEquals(0.00000722, u.comps["wakelock"]!!, 1e-12)
    }

    @Test
    fun uidLineWithFgsOnly_noFgField_isParsed() {
        val p = parse(headerLine, drainLine, uidU0a182)
        val u = p.uids.firstOrNull { it.uidKey == "u0a182" }
        assertNotNull("bg+fgs+cached（无 fg）的行必须能解析（u0a182 曾被丢弃）", u)
        assertEquals(0.0431, u!!.totalMah, 1e-9)
        assertEquals(0.0, u.fg, 1e-12)
        assertEquals(0.0000610, u.bg, 1e-12)
        assertEquals(0.00135, u.fgs, 1e-9)
        assertEquals(0.0431, u.comps["cpu"]!!, 1e-9)
    }

    @Test
    fun uidLineWithBigFgs_topConsumer_isParsed() {
        val p = parse(headerLine, drainLine, uidU0a351)
        val u = p.uids.firstOrNull { it.uidKey == "u0a351" }
        assertNotNull("当前窗口耗电第一的应用（586mAh，含 fgs: 116）必须能解析", u)
        assertEquals(586.0, u!!.totalMah, 1e-9)
        assertEquals(151.0, u.fg, 1e-9)
        assertEquals(12.9, u.bg, 1e-9)
        assertEquals(116.0, u.fgs, 1e-9)
    }

    @Test
    fun uidLineFgBgCached_stillParsed_regression() {
        val p = parse(headerLine, drainLine, uidU0a333)
        val u = p.uids.firstOrNull { it.uidKey == "u0a333" }
        assertNotNull(u)
        assertEquals(279.0, u!!.totalMah, 1e-9)
        assertEquals(65.1, u.fg, 1e-9)
        assertEquals(9.15, u.bg, 1e-9)
        assertEquals(154.0, u.comps["screen"]!!, 1e-9)
        assertEquals(41.6, u.comps["audio"]!!, 1e-9)
    }

    @Test
    fun uidLineFgBg_only_stillParsed_regression() {
        val p = parse(headerLine, drainLine, uidU0a350)
        val u = p.uids.firstOrNull { it.uidKey == "u0a350" }
        assertNotNull(u)
        assertEquals(45.5, u!!.totalMah, 1e-9)
        assertEquals(3.16, u.fg, 1e-9)
        assertEquals(24.4, u.bg, 1e-9)
        assertEquals(17.4, u.comps["screen"]!!, 1e-9)
    }

    @Test
    fun systemUidLine_parsed() {
        val p = parse(headerLine, drainLine, uid1000)
        val u = p.uids.firstOrNull { it.uidKey == "1000" }
        assertNotNull(u)
        assertEquals(147.0, u!!.totalMah, 1e-9)
        assertEquals(4.62, u.fg, 1e-9)
        assertEquals(133.0, u.comps["cpu"]!!, 1e-9)
    }

    @Test
    fun windowStartAndDrain_parsed() {
        val p = parse(windowLine, headerLine, drainLine)
        val expect = LocalDateTime.of(2026, 9, 18, 18, 5, 19)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expect, p.windowStartTs)
        assertEquals(2460.0, p.computedDrain, 1e-9)
    }

    @Test
    fun globalComponents_parsed() {
        val p = parse(headerLine, drainLine, gScreen, gCpu, gCamera, gWifi)
        assertEquals(303.0, p.globals["screen"]!!.first, 1e-9)
        assertEquals(7237958L, p.globals["screen"]!!.second)
        assertEquals(689.0, p.globals["cpu"]!!.first, 1e-9)
        assertEquals(417.0, p.globals["camera"]!!.first, 1e-9)
        assertEquals(1288862L, p.globals["camera"]!!.second)
        assertEquals(60.9, p.globals["wifi"]!!.first, 1e-9)
    }

    @Test
    fun uidKeyOf_uidOf_roundtrip_multiUser() {
        assertEquals("u0a330", UsageRepository.uidKeyOf(10330))
        assertEquals("u0a334", UsageRepository.uidKeyOf(10334))
        assertEquals("u999a351", UsageRepository.uidKeyOf(999 * 100_000 + 10351))
        assertEquals(999 * 100_000 + 10351, UsageRepository.uidOf("u999a351"))
        assertEquals(10334, UsageRepository.uidOf("u0a334"))
        assertEquals("1000", UsageRepository.uidKeyOf(1000))
        assertEquals(1000, UsageRepository.uidOf("1000"))
    }

    // ===== 以下为真机 dump 原始行（逐字节未改）=====

    private val windowLine = """  Start clock time: 2026-09-18-18-05-19"""
    private val headerLine = """  Estimated power use (mAh):"""
    private val drainLine = """    Capacity: 5920, Computed drain: 2460, actual drain: 3256-3493"""
    private val gScreen = """      screen: 303 apps: 303 duration: 2h 0m 37s 958ms """
    private val gCpu = """      cpu: 689 apps: 688"""
    private val gCamera = """      camera: 417 apps: 417 duration: 21m 28s 862ms """
    private val gWifi = """      wifi: 60.9 apps: 30.0 duration: 3h 27m 23s 968ms """
    private val uidU0a351 = """    UID u0a351: 586 fg: 151 bg: 12.9 fgs: 116 ( screen=50.9 (19m 57s 771ms) cpu=32.8 cpu:fg=12.6 cpu:bg=12.3 cpu:fgs=7.81 camera=417 (21m 28s 862ms) camera:fg=114 camera:bg=0.268 camera:fgs=89.2 audio=38.6 (21m 24s 262ms) audio:fg=11.0 audio:bg=0.115 audio:fgs=8.15 video=42.1 (21m 17s 876ms) video:fg=11.7 video:bg=0.0557 video:fgs=9.02 mobile_radio=0 (5m 8s 125ms) sensors=0.0291 (12m 25s 397ms) wifi=3.37 (1m 2s 859ms) wifi:fg=1.70 wifi:bg=0.186 wifi:fgs=1.48 wakelock=0.847 (7m 49s 174ms) ) """
    private val uidU0a333 = """    UID u0a333: 279 fg: 65.1 bg: 9.15 cached: 13.1 ( screen=154 (1h 0m 18s 8ms) cpu=50.9 cpu:fg=32.5 cpu:bg=6.54 cpu:cached=10.1 audio=41.6 (23m 15s 482ms) audio:fg=19.2 audio:bg=0.524 audio:cached=0.592 video=18.9 (9m 26s 943ms) video:fg=8.27 video:bg=0.453 video:cached=0.599 mobile_radio=0 (4m 5s 937ms) sensors=0.110 (46m 5s 62ms) gnss=9.31 (3m 49s 134ms) gnss:fg=2.06 gnss:bg=0.926 gnss:cached=1.22 wifi=4.40 (1m 19s 507ms) wifi:fg=3.03 wifi:bg=0.706 wifi:cached=0.614 ) """
    private val uidU0a334 = """    UID u0a334: 247 fg: 28.8 bg: 132 fgs: 16.9 cached: 12.5 ( screen=34.5 (13m 31s 894ms) cpu=170 cpu:fg=9.99 cpu:bg=131 cpu:fgs=16.1 cpu:cached=11.8 audio=18.5 (10m 15s 907ms) audio:fg=8.33 audio:bg=0.181 audio:fgs=0.114 audio:cached=0.651 video=22.9 (11m 25s 861ms) video:fg=10.1 video:bg=0.339 video:fgs=0.643 video:cached=0.0583 mobile_radio=0 (6m 26s 80ms) sensors=0.0754 (24m 21s 408ms) wifi=0.888 (17s 6ms) wifi:fg=0.454 wifi:bg=0.263 wifi:fgs=0.0342 wifi:cached=0.0187 wakelock=0.00000722 (4ms) ) """
    private val uid1000 = """    UID 1000: 147 fg: 4.62 ( cpu=133 mobile_radio=0 (3m 29s 393ms) sensors=0.903 (1d 10h 25m 11s 964ms) gnss=7.33 (2m 54s 712ms) wifi=4.62 (1m 19s 273ms) wifi:fg=4.62 wakelock=0.390 (3m 35s 949ms) ) """
    private val uidU0a350 = """    UID u0a350: 45.5 fg: 3.16 bg: 24.4 ( screen=17.4 (6m 48s 422ms) cpu=27.8 cpu:fg=3.16 cpu:bg=24.4 mobile_radio=0 (3m 9s 122ms) sensors=0.0575 (17m 35s 297ms) wifi=0.0369 (654ms) wifi:fg=0.0000983 wifi:bg=0.0368 wakelock=0.285 (2m 37s 703ms) ) """
    private val uidU0a182 = """    UID u0a182: 0.0431 bg: 0.0000610 fgs: 0.00135 cached: 0.0305 ( cpu=0.0431 cpu:bg=0.0000610 cpu:fgs=0.00135 cpu:cached=0.0305 mobile_radio=0 (28ms) ) """
}
