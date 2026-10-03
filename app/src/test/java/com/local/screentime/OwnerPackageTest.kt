package com.local.screentime

import com.local.screentime.data.BatteryCaptureOutcome
import com.local.screentime.data.CaptureHealth
import com.local.screentime.data.UsageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P1-2 共享 UID 耗电归属判据：UID 唯一对应一个包才归属；多包共享不猜（不复制给
 * 多个 App 造成双计，也不挂给任意第一个包造成误导）。
 * P1-1 采样健康状态机：attempt/success/failure 与最大成功间隔（实际损失窗口）。
 */
class OwnerPackageTest {

    // ---------- 共享 UID 归属 ----------

    @Test
    fun uniquePackage_attributed() {
        assertEquals(
            "com.example.single",
            UsageRepository.ownerPackageOf(10334, arrayOf("com.example.single")),
        )
    }

    @Test
    fun sharedUid_multiPackages_notAttributedToAny() {
        // 真机共享 UID 形态：Google 服务进程族共享一个 UID。整份耗电不得挂给第一个包
        assertNull(
            UsageRepository.ownerPackageOf(
                10234,
                arrayOf("com.google.process.gms", "com.google.android.gms", "com.google.android.gsf"),
            )
        )
    }

    @Test
    fun blankEntries_filtered_beforeDeciding() {
        // 单个有效包 + 若干空串/空白：应过滤后归属
        assertEquals(
            "com.example.app",
            UsageRepository.ownerPackageOf(10345, arrayOf("", "com.example.app", " ")),
        )
        // 过滤后仍多包：不归属
        assertNull(
            UsageRepository.ownerPackageOf(10345, arrayOf("", "com.a", "com.b")),
        )
    }

    @Test
    fun nullOrEmpty_orUnresolvable_notAttributed() {
        assertNull(UsageRepository.ownerPackageOf(10346, null))
        assertNull(UsageRepository.ownerPackageOf(10346, arrayOf<String>()))
    }

    @Test
    fun systemUid_neverAttributedToPackage() {
        // UID 1000（system_server）等系统 UID 是共享的：即使拿到包列表也不归属
        assertNull(UsageRepository.ownerPackageOf(1000, arrayOf("android")))
        assertNull(UsageRepository.ownerPackageOf(0, arrayOf("root")))
    }

    // ---------- 采样健康状态机 ----------

    @Test
    fun captureHealth_successRecordsGap_andKeepsMax() {
        var s = CaptureHealth.State()
        s = CaptureHealth.onAttempt(s, now = 1000L)
        s = CaptureHealth.onSuccess(s, now = 2000L)   // 首次成功无间隔
        assertNull(s.maxObservedCaptureGapMs)
        assertEquals(2000L, s.lastSuccessTs)
        assertEquals(CaptureHealth.FAIL_NONE, s.lastFailureCode)

        s = CaptureHealth.onSuccess(s, now = 2000L + 10 * 60_000)  // 10 分钟后再次成功
        assertEquals(10 * 60_000L, s.maxObservedCaptureGapMs)
        s = CaptureHealth.onSuccess(s, now = 2000L + 10 * 60_000 + 3 * 60_000)  // 3 分钟间隔
        assertEquals("更短的间隔不得回退最大值", 10 * 60_000L, s.maxObservedCaptureGapMs)
        s = CaptureHealth.onSuccess(s, now = 2000L + 10 * 60_000 + 3 * 60_000 + 20 * 60_000)  // 20 分钟
        assertEquals(20 * 60_000L, s.maxObservedCaptureGapMs)
    }

    @Test
    fun captureHealth_failureThenSuccess_clearsFailureCode() {
        var s = CaptureHealth.State(lastFailureCode = CaptureHealth.FAIL_SHELL, lastFailureTs = 100L)
        s = CaptureHealth.onSuccess(s, now = 200L)
        assertEquals(CaptureHealth.FAIL_NONE, s.lastFailureCode)
    }

    // ---------- 采样健康状态机（P1-1） ----------

    @Test
    fun captureHealth_failCodeMapping() {
        assertEquals(
            CaptureHealth.FAIL_NO_SOURCE,
            CaptureHealth.failCodeOf(BatteryCaptureOutcome.SourceUnavailable),
        )
        assertEquals(
            CaptureHealth.FAIL_SHELL,
            CaptureHealth.failCodeOf(BatteryCaptureOutcome.Failed("shell", "boom")),
        )
        assertEquals(
            CaptureHealth.FAIL_TX,
            CaptureHealth.failCodeOf(BatteryCaptureOutcome.Failed("tx", "boom")),
        )
        assertEquals(
            CaptureHealth.FAIL_NO_DATA,
            CaptureHealth.failCodeOf(BatteryCaptureOutcome.NoData),
        )
        assertEquals(
            CaptureHealth.FAIL_NONE,
            CaptureHealth.failCodeOf(BatteryCaptureOutcome.Success(0L)),
        )
    }

    // ---------- batteryForPackages 归属/聚合（P0-3 纯函数） ----------
    private fun row(uidKey: String, total: Double, day: Long = 100L) =
        com.local.screentime.data.BatteryDailyEntity(
            uidKey = uidKey, day = day, packageName = null, totalMah = total, fgMah = total / 2,
            bgMah = 0.0, fgsMah = 0.0, screenMah = 0.0, cpuMah = total / 2, audioMah = 0.0,
            videoMah = 0.0, cameraMah = 0.0, gnssMah = 0.0, wifiMah = 0.0, btMah = 0.0,
            wakelockMah = 0.0, sensorsMah = 0.0, otherMah = 0.0,
        )

    @Test
    fun groupRows_sharedUid_neverAttachedToAnyPackage() {
        // 真机：42 个非唯一映射 UID（系统+共享）。共享 UID 行必须整行跳过，不得挂给查询包
        val rows = listOf(row("u0a334", 319.0), row("10235", 88.0))
        val out = UsageRepository.groupBatteryRowsByOwner(
            rows,
            ownerOf = { k -> if (k == "u0a334") "tv.danmaku.bili" else null },  // 10235 共享 → null
            wanted = setOf("tv.danmaku.bili", "com.google.process.gms"),
        )
        assertEquals(1, out.size)
        assertEquals(319.0, out["tv.danmaku.bili"]!!.totalMah, 1e-9)
    }

    @Test
    fun groupRows_multiUserSamePackage_aggregatedNotDropped() {
        // 真机：QQ=u0a351+u999a351 两行唯一归属同一包名 —— 求和聚合，不得静默覆盖/忽略
        val rows = listOf(row("u0a351", 261.0), row("u999a351", 40.0), row("u0a334", 319.0))
        val out = UsageRepository.groupBatteryRowsByOwner(
            rows,
            ownerOf = { k ->
                when (k) {
                    "u0a351", "u999a351" -> "com.tencent.mobileqq"
                    "u0a334" -> "tv.danmaku.bili"
                    else -> null
                }
            },
            wanted = setOf("com.tencent.mobileqq", "tv.danmaku.bili"),
        )
        assertEquals(301.0, out["com.tencent.mobileqq"]!!.totalMah, 1e-9)
        assertEquals("u0a351+u999a351", out["com.tencent.mobileqq"]!!.uidKey)
        assertEquals(319.0, out["tv.danmaku.bili"]!!.totalMah, 1e-9)
    }

    @Test
    fun groupRows_systemUidAndUnwanted_excluded() {
        val rows = listOf(row("1000", 147.0), row("u0a334", 319.0), row("u0a999", 5.0))
        val out = UsageRepository.groupBatteryRowsByOwner(
            rows,
            ownerOf = { k ->
                when (k) {
                    "u0a334" -> "tv.danmaku.bili"
                    "u0a999" -> "com.other.app"
                    else -> null  // 1000 系统 UID
                }
            },
            wanted = setOf("tv.danmaku.bili"),
        )
        assertEquals(1, out.size)
        assertNull(out["com.other.app"])
    }

    @Test
    fun sumBatteryDaily_sumsAllFields() {
        val s = UsageRepository.sumBatteryDaily(listOf(row("u0a351", 261.0), row("u999a351", 40.0)), "com.tencent.mobileqq")
        assertEquals(301.0, s.totalMah, 1e-9)
        assertEquals(150.5, s.fgMah, 1e-9)   // total/2 求和
        assertEquals(150.5, s.cpuMah, 1e-9)
        assertEquals("com.tencent.mobileqq", s.packageName)
    }
}
