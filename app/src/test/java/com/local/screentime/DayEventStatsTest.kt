package com.local.screentime

import android.app.usage.UsageEvents
import com.local.screentime.data.RawEvent
import com.local.screentime.data.UsageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 拿起次数与解锁首开应用（v0.16.12）的核心逻辑测试：
 * 1. user=999 分身用户隔离（dumpsys 避免拿起次数翻倍）；
 * 2. 连续 <= 2000ms 的 KEYGUARD_HIDDEN 防抖去重；
 * 3. 解锁后 (0, 15s] 桌面启动非 Launcher 应用正常计入；
 * 4. 息屏直接解锁恢复 [-2s, 0s] 前台非 Launcher 应用正常计入；
 * 5. 仅在桌面未打开应用时不计入 unlockFirst。
 */
class DayEventStatsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun ms(hour: Int, minute: Int, second: Int): Long =
        LocalDateTime.of(2026, 9, 19, hour, minute, second).atZone(zone).toInstant().toEpochMilli()

    private val testDay = LocalDate.of(2026, 9, 19).toEpochDay()

    @Test
    fun parseDumpsysEvents_filtersUser999_andKeepsUser0() {
        val lines = listOf(
            "user=0",
            """time="2026-09-19 10:00:00" type=KEYGUARD_HIDDEN package=android flags=0x0""",
            """time="2026-09-19 10:00:02" type=ACTIVITY_RESUMED package=com.tencent.mm flags=0x0""",
            "user=999",
            """time="2026-09-19 10:00:00" type=KEYGUARD_HIDDEN package=android flags=0x0"""
        )
        val events = UsageRepository.parseDumpsysEvents(lines, zone)
        // 应该只有来自 user=0 的 2 条事件，user=999 的 KEYGUARD_HIDDEN 必须被过滤
        assertEquals(2, events.size)
        assertEquals(UsageEvents.Event.KEYGUARD_HIDDEN, events[0].eventType)
        assertEquals("android", events[0].packageName)
        assertEquals(UsageEvents.Event.ACTIVITY_RESUMED, events[1].eventType)
        assertEquals("com.tencent.mm", events[1].packageName)
    }

    @Test
    fun keyguardHidden_debouncesWithin2Seconds() {
        val events = listOf(
            RawEvent(ms(10, 0, 0), UsageEvents.Event.KEYGUARD_HIDDEN, "android"),
            // 1秒后再次触发 KEYGUARD_HIDDEN（例如生物识别/动画重复派发），应被去重
            RawEvent(ms(10, 0, 1), UsageEvents.Event.KEYGUARD_HIDDEN, "android"),
            RawEvent(ms(10, 0, 3), UsageEvents.Event.ACTIVITY_RESUMED, "com.tencent.mm")
        )
        val res = UsageRepository.computeDayEventStats(events, zone)
        assertEquals(1L, res.pickups[testDay])
        assertEquals(1L, res.unlockFirst[testDay]?.get("com.tencent.mm"))
    }

    @Test
    fun launchFromLauncher_within15Seconds_isCounted() {
        val events = listOf(
            RawEvent(ms(10, 0, 0), UsageEvents.Event.KEYGUARD_HIDDEN, "android"),
            RawEvent(ms(10, 0, 0), UsageEvents.Event.ACTIVITY_RESUMED, "com.android.launcher"),
            // 3秒后打开微信
            RawEvent(ms(10, 0, 3), UsageEvents.Event.ACTIVITY_RESUMED, "com.tencent.mm")
        )
        val res = UsageRepository.computeDayEventStats(events, zone)
        assertEquals(1L, res.pickups[testDay])
        assertEquals(1L, res.unlockFirst[testDay]?.get("com.tencent.mm"))
    }

    @Test
    fun directUnlockIntoExistingApp_sameSecondOrMinus1Second_isCounted() {
        // 在微信前台锁屏，解锁时微信在 KEYGUARD_HIDDEN 前1秒或同秒 RESUMED
        val events = listOf(
            RawEvent(ms(10, 0, 0), UsageEvents.Event.ACTIVITY_RESUMED, "com.tencent.mm"),
            RawEvent(ms(10, 0, 1), UsageEvents.Event.KEYGUARD_HIDDEN, "android")
            // 之后用户一直在微信中聊天，无新 RESUMED
        )
        val res = UsageRepository.computeDayEventStats(events, zone)
        assertEquals(1L, res.pickups[testDay])
        assertEquals(1L, res.unlockFirst[testDay]?.get("com.tencent.mm"))
    }

    @Test
    fun directUnlockAndThenSwitchApp_countsNewApp() {
        // 解锁时前台原本有闲鱼，但 2 秒内用户立刻切到微信
        val events = listOf(
            RawEvent(ms(10, 0, 0), UsageEvents.Event.ACTIVITY_RESUMED, "com.taobao.idlefish"),
            RawEvent(ms(10, 0, 0), UsageEvents.Event.KEYGUARD_HIDDEN, "android"),
            RawEvent(ms(10, 0, 0), UsageEvents.Event.ACTIVITY_RESUMED, "com.android.launcher"),
            RawEvent(ms(10, 0, 2), UsageEvents.Event.ACTIVITY_RESUMED, "com.tencent.mm")
        )
        val res = UsageRepository.computeDayEventStats(events, zone)
        assertEquals(1L, res.pickups[testDay])
        // 应该优先记入解锁后用户明确打开的目标应用 (微信)
        assertEquals(1L, res.unlockFirst[testDay]?.get("com.tencent.mm"))
        assertNull(res.unlockFirst[testDay]?.get("com.taobao.idlefish"))
    }

    @Test
    fun unlockOnlyToLauncher_noAppCounted() {
        val events = listOf(
            RawEvent(ms(10, 0, 0), UsageEvents.Event.KEYGUARD_HIDDEN, "android"),
            RawEvent(ms(10, 0, 0), UsageEvents.Event.ACTIVITY_RESUMED, "com.android.launcher")
            // 15秒内无任何非 Launcher 应用
        )
        val res = UsageRepository.computeDayEventStats(events, zone)
        assertEquals(1L, res.pickups[testDay])
        assertNull(res.unlockFirst[testDay])
    }
}
