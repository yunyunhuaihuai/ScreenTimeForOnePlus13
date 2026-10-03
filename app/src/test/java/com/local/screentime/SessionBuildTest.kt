package com.local.screentime

import com.local.screentime.data.RawEvent
import com.local.screentime.data.SESSION_SOURCE_ROOT
import com.local.screentime.data.UsageRepository
import android.app.usage.UsageEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * E（v0.18，P1-2）：root 会话重建的可信采集上界与短段策略。
 *
 * 场景取自 2026-10-03 审查 §P1-2：旧实现用「最后事件时间」当窗口结束——最后事件是
 * RESUMED、其后无关闭事件时仍前台的会话尾段被截断；同帧多 Activity 三连把一次使用
 * 拆成多个 1 秒段被无差别丢弃（本机 376 段/376 秒，含真实使用）。
 */
class SessionBuildTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    // 2026-10-03 00:00 起的毫秒基准（跨午夜用例要用凌晨附近的时刻）
    private val base = LocalDateTime.of(2026, 10, 2, 10, 0).atZone(zone).toInstant().toEpochMilli()

    private fun ev(offsetMs: Long, type: Int, pkg: String) = RawEvent(base + offsetMs, type, pkg)

    private val RESUMED = UsageEvents.Event.ACTIVITY_RESUMED
    private val PAUSED = UsageEvents.Event.ACTIVITY_PAUSED
    private val SCREEN_OFF = UsageEvents.Event.SCREEN_NON_INTERACTIVE

    private fun build(events: List<RawEvent>, trustedEnd: Long, windowStart: Long = 0) =
        UsageRepository.buildSessions(events, windowStart, trustedEnd, SESSION_SOURCE_ROOT)

    @Test
    fun lastEventResumed_tailExtendsToTrustedEnd_notLastEventTs() {
        // 复现审查样例：10:00 RESUMED 后无关闭事件，10:20 采集 ——
        // 旧实现截断在 10:00（少记 20 分钟）；新实现暂定尾段延伸到采集时刻
        val sessions = build(
            listOf(ev(0, RESUMED, "com.aweme")),
            trustedEnd = base + 20 * 60_000L,
        )
        assertEquals(1, sessions.size)
        assertEquals("暂定尾段延伸到可信采集上界", base + 20 * 60_000L, sessions[0].endTs)
        assertEquals(20 * 60_000L, sessions[0].endTs - sessions[0].startTs)
    }

    @Test
    fun closedAfterResume_endAtPauseEvent_trustedEndDoesNotInflate() {
        // 有关闭事件：会话在 PAUSED 事件处结束，trustedEnd 不再外延
        val sessions = build(
            listOf(
                ev(0, RESUMED, "com.aweme"),
                ev(5 * 60_000, PAUSED, "com.aweme"),
                ev(6 * 60_000, RESUMED, "com.zhihu"),
            ),
            trustedEnd = base + 20 * 60_000L,
        )
        assertEquals(2, sessions.size)
        assertEquals("抖音会话在 PAUSED 处结束", base + 5 * 60_000L, sessions[0].endTs)
        // 知乎开放到采集时刻（暂定尾段）
        assertEquals(base + 20 * 60_000L, sessions[1].endTs)
    }

    @Test
    fun screenOff_closesSession_noTailBeyond() {
        // 息屏不计入前台：SCREEN_NON_INTERACTIVE 关闭会话，尾段不外延
        val sessions = build(
            listOf(
                ev(0, RESUMED, "com.aweme"),
                ev(10 * 60_000, SCREEN_OFF, ""),
            ),
            trustedEnd = base + 60 * 60_000L,
        )
        assertEquals(1, sessions.size)
        assertEquals("息屏即结束，不把息屏时间算前台", base + 10 * 60_000L, sessions[0].endTs)
    }

    @Test
    fun midnightCrossing_sessionSplitByLocalDay() {
        // 跨午夜会话按本地日切分（左边界/跨午夜保护保持既有语义）
        val b = LocalDateTime.of(2026, 10, 2, 23, 50).atZone(zone).toInstant().toEpochMilli()
        val events = listOf(
            RawEvent(b, RESUMED, "com.aweme"),
            RawEvent(b + 20 * 60_000L, SCREEN_OFF, ""),  // 00:10 息屏
        )
        val sessions = UsageRepository.buildSessions(events, 0, b + 30 * 60_000L, SESSION_SOURCE_ROOT)
        assertEquals("23:50-00:10 跨午夜 → 两天各一段", 2, sessions.size)
        assertEquals(LocalDate.of(2026, 10, 2).toEpochDay(), sessions[0].day)
        assertEquals(LocalDate.of(2026, 10, 3).toEpochDay(), sessions[1].day)
    }

    @Test
    fun multiActivitySameTsFragments_mergedIntoOneSession() {
        // 同戳三连（PAUSED(旧)→RESUMED(新)→STOPPED(旧)）把一次使用拆成 1 秒段：
        // 同包微间隔合并后再套 2 秒阈值 → 真实使用不丢（此前 3 段全被丢弃）
        val events = listOf(
            ev(0, RESUMED, "com.zhihu"),                 // Activity A
            ev(1_000, PAUSED, "com.zhihu"),              // 切 Activity（1 秒段）
            ev(1_000, RESUMED, "com.zhihu"),             // Activity B
            ev(2_000, PAUSED, "com.zhihu"),
            ev(2_000, RESUMED, "com.zhihu"),             // Activity C
            ev(60_000, SCREEN_OFF, ""),
        )
        val sessions = build(events, trustedEnd = base + 60_000)
        assertEquals("同包 1 秒段合并为一条连续会话", 1, sessions.size)
        assertEquals(60_000L, sessions[0].endTs - sessions[0].startTs)
    }

    @Test
    fun isolatedSubSecondNoise_stillDropped() {
        // 独立秒级噪声（前后都有大空档，非 Activity 切换）仍按 2 秒阈值过滤
        val events = listOf(
            ev(0, RESUMED, "com.aweme"),
            ev(10 * 60_000, PAUSED, "com.aweme"),
            ev(30 * 60_000, RESUMED, "com.noise"),   // 推送拉起 1 秒
            ev(30 * 60_000 + 1_000, PAUSED, "com.noise"),
            ev(31 * 60_000, RESUMED, "com.aweme"),
            ev(40 * 60_000, SCREEN_OFF, ""),
        )
        val sessions = build(events, trustedEnd = base + 40 * 60_000)
        assertEquals("噪声段不保留", 2, sessions.size)
        assertTrue(sessions.none { it.packageName == "com.noise" })
    }

    @Test
    fun duplicateResumeSameTs_sameFrameRepeated_noInflation() {
        // 同帧重复 RESUMED（dump 去重前的重复行）：不产生额外会话/时长
        val events = listOf(
            ev(0, RESUMED, "com.aweme"),
            ev(0, RESUMED, "com.aweme"),
            ev(10 * 60_000, SCREEN_OFF, ""),
        )
        val sessions = build(events, trustedEnd = base + 10 * 60_000)
        assertEquals(1, sessions.size)
        assertEquals(10 * 60_000L, sessions[0].endTs - sessions[0].startTs)
    }

    @Test
    fun dualInstanceSamePackage_interleavedSessions_keptPerPackage() {
        // 双开同包（user0 + user999 同包名，事件流只到包级）：两段都会保留；
        // 间隔 > 合并阈值时不强行拼成长会话
        val events = listOf(
            ev(0, RESUMED, "com.tencent.mobileqq"),
            ev(60_000, PAUSED, "com.tencent.mobileqq"),
            ev(70_000, RESUMED, "com.tencent.mobileqq"),   // 分身空间同包
            ev(130_000, PAUSED, "com.tencent.mobileqq"),
        )
        val sessions = build(events, trustedEnd = base + 130_000)
        assertEquals(2, sessions.size)
        assertEquals(60_000L, sessions[0].endTs - sessions[0].startTs)
        assertEquals(60_000L, sessions[1].endTs - sessions[1].startTs)
    }

    @Test
    fun apiFallbackPath_usesCaptureTimeAsTrustedEnd() {
        // API 兜底路径同样以采集时刻为上界（windowEnd=now 的既有语义保持一致）
        val sessions = UsageRepository.buildSessions(
            listOf(ev(0, RESUMED, "com.aweme")),
            windowStart = base - 1,
            trustedEnd = base + 15 * 60_000L,
            source = com.local.screentime.data.SESSION_SOURCE_API,
        )
        assertEquals(1, sessions.size)
        assertEquals(com.local.screentime.data.SESSION_SOURCE_API, sessions[0].source)
        assertEquals(base + 15 * 60_000L, sessions[0].endTs)
    }
}
