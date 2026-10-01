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
            CaptureHealth.failCodeOf(BatteryCaptureOutcome.Success),
        )
    }
}
