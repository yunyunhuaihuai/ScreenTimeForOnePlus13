package com.local.screentime

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.local.screentime.data.AppDatabase
import com.local.screentime.data.BatteryDailyEntity
import com.local.screentime.data.UidPackageEntity
import com.local.screentime.data.UsageRepository
import com.local.screentime.privileged.RootCommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * H（v0.18，P1-5）：历史 UID 归属不被当前安装状态重写。
 * 采集时持久化成员存档（uid_packages）；读取按「该日有效」解析——
 * 卸载保留历史、共享不拆分猜归属、UID 复用不错挂。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UidPackagesTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: UsageRepository
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val day1 = LocalDate.of(2026, 9, 15).toEpochDay()   // A+B 共享期
    private val day2 = LocalDate.of(2026, 9, 20).toEpochDay()   // B 卸载后
    private val day3 = LocalDate.of(2026, 10, 1).toEpochDay()   // UID 复用给 C

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = UsageRepository(context, RootCommandRunner(), db, useRootCollector = false)
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun dao() = db.usageDao()

    private fun row(uidKey: String, day: Long, total: Double, pkg: String? = null) = BatteryDailyEntity(
        uidKey = uidKey, day = day, packageName = pkg, totalMah = total, fgMah = 0.0,
        bgMah = 0.0, fgsMah = 0.0, screenMah = 0.0, cpuMah = 0.0, audioMah = 0.0,
        videoMah = 0.0, cameraMah = 0.0, gnssMah = 0.0, wifiMah = 0.0, btMah = 0.0,
        wakelockMah = 0.0, sensorsMah = 0.0, otherMah = 0.0,
    )

    private fun want(pkg: String) = setOf(pkg)

    private fun ownersFor(day: Long, rows: List<BatteryDailyEntity>): List<String?> =
        runBlocking {
            val map = repo.batteryForPackages(day, rows.mapNotNull { it.packageName })
            rows.map { r -> map.entries.firstOrNull { it.value.uidKey.contains(r.uidKey) }?.key }
        }

    @Test
    fun sharedUidAttribution_persistsAcrossUninstall() = runBlocking {
        // 场景 1（审查样例）：A+B 共享 UID → B 卸载。共享期耗电不能全归 A，也不消失
        dao().upsertUidPackages(
            listOf(
                UidPackageEntity("u0a100", "com.a", ts(2026, 9, 1, 0, 0), ts(2026, 9, 18, 0, 0)),
                UidPackageEntity("u0a100", "com.b", ts(2026, 9, 1, 0, 0), ts(2026, 9, 18, 0, 0)),
            )
        )
        // 共享期行（packageName=null，写入侧对共享不归属）与卸载后行
        dao().upsertBatteryDaily(listOf(row("u0a100", day1, 10.0), row("u0a100", day2, 12.0)))
        // 共享期：batteryForPackages 不把共享行挂给 A（不拆分猜归属）
        val sharedDay = repo.batteryForPackages(day1, listOf("com.a", "com.b"))
        assertTrue("共享期不归属给单个包", sharedDay["com.a"] == null && sharedDay["com.b"] == null)
        // 卸载后读取共享期数据：存档仍在（卸载保留历史），仍然不拆分
        val later = repo.batteryForPackages(day1, listOf("com.a", "com.b"))
        assertTrue(later["com.a"] == null && later["com.b"] == null)
        // 展示名按日解析：共享期显示 A / B，卸载后各看各的（共享行标签退化为 UID）
        val labelDuring = repo.sharedUidLabel("u0a100", day1)
        assertTrue(labelDuring!!.contains("com.a") && labelDuring.contains("com.b"))
    }

    @Test
    fun uniqueAttribution_survivesUninstall() = runBlocking {
        // 场景 2（审查样例）：A 唯一 → 卸载后历史耗电保留
        dao().upsertUidPackages(
            listOf(UidPackageEntity("u0a200", "com.unique", ts(2026, 9, 1, 0, 0), ts(2026, 9, 19, 0, 0)))
        )
        // 采集时行 packageName=com.unique（采集时归属已冻结）
        dao().upsertBatteryDaily(listOf(row("u0a200", day1, 5.0, pkg = "com.unique")))
        val map = repo.batteryForPackages(day1, listOf("com.unique"))
        assertEquals("行上冻结的采集时归属优先", 5.0, map["com.unique"]!!.totalMah, 1e-9)
    }

    @Test
    fun uidReuse_membershipIntervalsResolveOwner() = runBlocking {
        // 场景 3：UID u0a300 曾是 A（9/1-9/25），后复用给 C（9/28 起）；行 packageName=null
        // （共享/未解析时写入侧置空）。按日解析不得把 A 期耗电挂给 C，反之亦然
        dao().upsertUidPackages(
            listOf(
                UidPackageEntity("u0a300", "com.old", ts(2026, 9, 1, 0, 0), ts(2026, 9, 25, 0, 0)),
                UidPackageEntity("u0a300", "com.reuse", ts(2026, 9, 28, 0, 0), ts(2026, 10, 5, 0, 0)),
            )
        )
        dao().upsertBatteryDaily(listOf(row("u0a300", day1, 3.0), row("u0a300", day3, 4.0)))
        val oldDay = repo.batteryForPackages(day1, listOf("com.old", "com.reuse"))
        assertEquals("旧 epoch 耗电归旧包", 3.0, oldDay["com.old"]!!.totalMah, 1e-9)
        assertNull("旧耗电不得挂给新包", oldDay["com.reuse"])
        val reuseDay = repo.batteryForPackages(day3, listOf("com.old", "com.reuse"))
        assertEquals("复用后耗电归新包", 4.0, reuseDay["com.reuse"]!!.totalMah, 1e-9)
        assertNull("新耗电不得挂给旧包", reuseDay["com.old"])
        // 间隙日（9/26-9/27 无成员）：不确定，不猜
        val gapDay = repo.batteryForPackages(LocalDate.of(2026, 9, 26).toEpochDay(), listOf("com.old", "com.reuse"))
        assertNull(gapDay["com.old"])
        assertNull(gapDay["com.reuse"])
    }

    @Test
    fun uniqueBecomesShared_perDaySemantics() = runBlocking {
        // 场景 4：A 唯一（9/1-9/10）→ 之后 A+B 共享（9/11 起）
        dao().upsertUidPackages(
            listOf(
                UidPackageEntity("u0a400", "com.a", ts(2026, 9, 1, 0, 0), ts(2026, 10, 5, 0, 0)),
                UidPackageEntity("u0a400", "com.b", ts(2026, 9, 11, 0, 0), ts(2026, 10, 5, 0, 0)),
            )
        )
        dao().upsertBatteryDaily(listOf(row("u0a400", day1, 6.0)))
        // day1=9/15 在共享期 → 不归属
        val shared = repo.batteryForPackages(day1, listOf("com.a", "com.b"))
        assertNull(shared["com.a"])
        // 9/5 在唯一期 → 归属 A
        val d5 = LocalDate.of(2026, 9, 5).toEpochDay()
        dao().upsertBatteryDaily(listOf(row("u0a400", d5, 7.0)))
        val unique = repo.batteryForPackages(d5, listOf("com.a", "com.b"))
        assertEquals(7.0, unique["com.a"]!!.totalMah, 1e-9)
    }

    @Test
    fun dualInstance_user0AndUser999_aggregatedPerPackage() = runBlocking {
        // 场景 5（真机 QQ=u0a351+u999a351）：同包两个唯一 UID 聚合求和，不静默丢弃
        dao().upsertUidPackages(
            listOf(
                UidPackageEntity("u0a351", "com.tencent.mobileqq", ts(2026, 9, 1, 0, 0), ts(2026, 10, 5, 0, 0)),
                UidPackageEntity("u999a351", "com.tencent.mobileqq", ts(2026, 9, 1, 0, 0), ts(2026, 10, 5, 0, 0)),
            )
        )
        dao().upsertBatteryDaily(
            listOf(row("u0a351", day1, 4.0, pkg = "com.tencent.mobileqq"), row("u999a351", day1, 3.82, pkg = "com.tencent.mobileqq"))
        )
        val map = repo.batteryForPackages(day1, listOf("com.tencent.mobileqq"))
        val agg = map["com.tencent.mobileqq"]!!
        assertEquals("分身同包多 UID 求和聚合", 4.0 + 3.82, agg.totalMah, 1e-9)
        assertTrue("聚合 uidKey 以 + 连接", agg.uidKey.contains("+"))
    }

    @Test
    fun legacyRowsWithoutArchive_fallBackToCurrentResolution() = runBlocking {
        // 本版本之前的历史行无存档：回退当前 pm（Robolectric 下 com.legacy 不存在 → null，
        // 不猜、不崩溃），与升级前行为一致
        dao().upsertBatteryDaily(listOf(row("u0a900", day1, 2.0)))
        val map = repo.batteryForPackages(day1, emptyList())
        assertTrue("无归属行不进任何包", map.isEmpty())
    }

    @Test
    fun legacyRowWithFrozenPackageName_survivesUninstall() = runBlocking {
        // v9 之前写入、行上已冻结 packageName 但无成员存档的历史行：
        // 包已卸载（current pm 查不到）时，冻结归属优先——历史耗电不得消失（卸载保留历史）
        dao().upsertBatteryDaily(listOf(row("u0a901", day1, 9.5, pkg = "com.uninstalled")))
        val map = repo.batteryForPackages(day1, listOf("com.uninstalled"))
        assertEquals("冻结的采集时归属优先于 current pm", 9.5, map["com.uninstalled"]!!.totalMah, 1e-9)
    }
}
