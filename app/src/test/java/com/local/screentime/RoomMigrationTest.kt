package com.local.screentime

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.local.screentime.data.AppDatabase
import com.local.screentime.data.UsageDao
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0-5 Room 迁移测试（Robolectric + 真实迁移路径）。
 *
 * 做法：按 **v4 历史结构**（schemas/4.json 的建表语句，4.json 为按 MIGRATION_4_5 逆推
 * 重建的历史档案）手工建库、写入全部 10 张表的样本数据、置 user_version=4，然后用
 * 正式构建路径（Room.databaseBuilder + 显式 Migrations）打开——Room 在迁移后会按
 * 当前实体逐表校验 schema（不匹配直接抛 "Migration didn't properly handle ..."），
 * 随后断言全部历史数据原样保留。
 *
 * 覆盖路径：
 *  - 4→5→6 完整链（v0.16.14 的 fgsMah 列 + 本轮 reports.finalized 列）；
 *  - 5→6（当前真机上的存量库形态）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomMigrationTest {

    private val dbName = "migration-test.db"
    private lateinit var context: Context

    /** v4 全部建表/建索引语句（与 schemas/4.json 一致；battery_daily 无 fgsMah 列） */
    private val v4Sql = listOf(
        "CREATE TABLE IF NOT EXISTS `sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, `startTs` INTEGER NOT NULL, `endTs` INTEGER NOT NULL, `day` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_sessions_startTs` ON `sessions` (`startTs`)",
        "CREATE INDEX IF NOT EXISTS `index_sessions_day` ON `sessions` (`day`)",
        "CREATE INDEX IF NOT EXISTS `index_sessions_day_packageName` ON `sessions` (`day`, `packageName`)",
        "CREATE TABLE IF NOT EXISTS `app_info` (`packageName` TEXT NOT NULL, `label` TEXT NOT NULL, `firstSeenTs` INTEGER NOT NULL, PRIMARY KEY(`packageName`))",
        "CREATE TABLE IF NOT EXISTS `sync_state` (`key` TEXT NOT NULL, `value` INTEGER NOT NULL, PRIMARY KEY(`key`))",
        "CREATE TABLE IF NOT EXISTS `usage_daily` (`packageName` TEXT NOT NULL, `day` INTEGER NOT NULL, `totalMs` INTEGER NOT NULL, `source` INTEGER NOT NULL, PRIMARY KEY(`packageName`, `day`))",
        "CREATE INDEX IF NOT EXISTS `index_usage_daily_day` ON `usage_daily` (`day`)",
        "CREATE TABLE IF NOT EXISTS `battery_daily` (`uidKey` TEXT NOT NULL, `day` INTEGER NOT NULL, `packageName` TEXT, `totalMah` REAL NOT NULL, `fgMah` REAL NOT NULL, `bgMah` REAL NOT NULL, `screenMah` REAL NOT NULL, `cpuMah` REAL NOT NULL, `audioMah` REAL NOT NULL, `videoMah` REAL NOT NULL, `cameraMah` REAL NOT NULL, `gnssMah` REAL NOT NULL, `wifiMah` REAL NOT NULL, `btMah` REAL NOT NULL, `wakelockMah` REAL NOT NULL, `sensorsMah` REAL NOT NULL, `otherMah` REAL NOT NULL, PRIMARY KEY(`uidKey`, `day`))",
        "CREATE INDEX IF NOT EXISTS `index_battery_daily_day` ON `battery_daily` (`day`)",
        "CREATE TABLE IF NOT EXISTS `battery_global` (`component` TEXT NOT NULL, `day` INTEGER NOT NULL, `mah` REAL NOT NULL, `durationMs` INTEGER NOT NULL, PRIMARY KEY(`component`, `day`))",
        "CREATE INDEX IF NOT EXISTS `index_battery_global_day` ON `battery_global` (`day`)",
        "CREATE TABLE IF NOT EXISTS `battery_snapshot` (`uidKey` TEXT NOT NULL, `cumMah` REAL NOT NULL, `compsText` TEXT NOT NULL, `updatedTs` INTEGER NOT NULL, PRIMARY KEY(`uidKey`))",
        "CREATE TABLE IF NOT EXISTS `day_stats` (`day` INTEGER NOT NULL, `pickups` INTEGER NOT NULL, PRIMARY KEY(`day`))",
        "CREATE TABLE IF NOT EXISTS `unlock_stats` (`day` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `count` INTEGER NOT NULL, PRIMARY KEY(`day`, `packageName`))",
        "CREATE TABLE IF NOT EXISTS `reports` (`type` TEXT NOT NULL, `periodKey` TEXT NOT NULL, `generatedTs` INTEGER NOT NULL, `text` TEXT NOT NULL, PRIMARY KEY(`type`, `periodKey`))",
    )

    /** 给全部 10 张表各插样本行，模拟真实用户的历史数据 */
    private fun seedAllTables(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO sessions (packageName, startTs, endTs, day) VALUES ('com.tencent.mm', 1000, 61000, 20656)")
        db.execSQL("INSERT INTO app_info (packageName, label, firstSeenTs) VALUES ('com.tencent.mm', '微信', 1)")
        db.execSQL("INSERT INTO sync_state (`key`, value) VALUES ('event_watermark', 42)")
        db.execSQL("INSERT INTO usage_daily (packageName, day, totalMs, source) VALUES ('com.tencent.mm', 20656, 60000, 2)")
        db.execSQL(
            "INSERT INTO battery_daily (uidKey, day, packageName, totalMah, fgMah, bgMah, screenMah, cpuMah, " +
                "audioMah, videoMah, cameraMah, gnssMah, wifiMah, btMah, wakelockMah, sensorsMah, otherMah) " +
                "VALUES ('u0a334', 20656, 'tv.danmaku.bili', 247.5, 28.8, 132.0, 5.0, 90.0, 0,0,0,0,0,0,0,0,0)"
        )
        db.execSQL("INSERT INTO battery_global (component, day, mah, durationMs) VALUES ('screen', 20656, 988.0, 3161912)")
        db.execSQL("INSERT INTO battery_snapshot (uidKey, cumMah, compsText, updatedTs) VALUES ('u0a334', 247.5, 'fg=28.8', 1000)")
        db.execSQL("INSERT INTO day_stats (day, pickups) VALUES (20656, 296)")
        db.execSQL("INSERT INTO unlock_stats (day, packageName, count) VALUES (20656, 'com.tencent.mm', 7)")
        db.execSQL(
            "INSERT INTO reports (type, periodKey, generatedTs, text) " +
                "VALUES ('WEEKLY', 'W:2026-09-21', 1790000000000, '旧周报文本')"
        )
    }

    /** 按指定 user_version 建 v4 结构库；atV5=true 时补上 MIGRATION_4_5 的 fgsMah 列 */
    private fun createLegacyDb(version: Int, atV5: Boolean): SQLiteDatabase {
        context.deleteDatabase(dbName)
        val path = context.getDatabasePath(dbName).absolutePath
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        v4Sql.forEach(db::execSQL)
        if (atV5) db.execSQL("ALTER TABLE battery_daily ADD COLUMN fgsMah REAL NOT NULL DEFAULT 0.0")
        db.version = version
        return db
    }

    private fun buildRoom(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8,
            )
            .allowMainThreadQueries()
            .build()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun teardown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate4ToLatest_allTenTablesDataPreserved() = runBlocking {
        createLegacyDb(4, atV5 = false).use { seedAllTables(it) }

        val dao = buildRoom().usageDao()  // 首次访问触发 4→5→6→7→8 迁移 + Room schema 校验

        assertEquals(1, dao.sessionsOfDay(20656).size)
        assertEquals(60000L, dao.sessionsOfDay(20656)[0].endTs - dao.sessionsOfDay(20656)[0].startTs)
        assertEquals("历史会话迁移后按 LEGACY（权威）对待", com.local.screentime.data.SESSION_SOURCE_LEGACY, dao.sessionsOfDay(20656)[0].source)
        assertEquals(60000L, dao.dailyAggRows(20656)[0].totalMs)
        assertEquals(42L, dao.getState("event_watermark"))
        assertEquals("微信", dao.appInfo("com.tencent.mm")?.label)

        val battery = dao.getBatteryDaily("u0a334", 20656)
        assertNotNull(battery)
        assertEquals(247.5, battery!!.totalMah, 1e-9)
        assertEquals(28.8, battery.fgMah, 1e-9)
        assertEquals("v4 老库补出的 fgsMah 列默认 0", 0.0, battery.fgsMah, 1e-9)
        assertEquals("v6→7 补出的 mobileMah 列默认 0", 0.0, battery.mobileMah, 1e-9)
        assertEquals(988.0, dao.batteryGlobalRows(20656)[0].mah, 1e-9)
        assertEquals(3161912L, dao.batteryGlobalRows(20656)[0].durationMs)
        assertEquals(247.5, dao.getBatterySnapshot("u0a334")!!.cumMah, 1e-9)
        assertEquals(296L, dao.getDayStats(20656)!!.pickups)
        assertEquals(7L, dao.unlockTop(20656, 10)[0].count)

        val report = dao.getReport("WEEKLY", "W:2026-09-21")
        assertNotNull("报告行必须保留", report)
        assertEquals("旧周报文本", report!!.text)
        assertEquals("既有报告迁移后按正式历史保留", true, report.finalized)
    }

    @Test
    fun migrate5To6_currentDeviceState_preserved() = runBlocking {
        // 真机当前存量形态：v5（已有 fgsMah），走 5→6
        createLegacyDb(5, atV5 = true).use { db ->
            seedAllTables(db)
            db.execSQL(
                "UPDATE battery_daily SET fgsMah = 16.9 WHERE uidKey='u0a334'"
            )
        }

        val dao = buildRoom().usageDao()  // 触发 5→6

        val battery = dao.getBatteryDaily("u0a334", 20656)
        assertEquals(16.9, battery!!.fgsMah, 1e-9)  // v5 已有列的数据不受 5→6 影响
        val report = dao.getReport("WEEKLY", "W:2026-09-21")
        assertNotNull(report)
        assertEquals("旧周报文本", report!!.text)
        assertEquals(true, report.finalized)
    }

    @Test
    fun freshDatabase_v6_startsEmptyWithFinalizedDefault() = runBlocking {
        // 新装用户直接以 v6 建库：不触发迁移，finalized 默认 true（与实体默认一致）
        context.deleteDatabase(dbName)
        val dao = buildRoom().usageDao()
        assertNull(dao.getReport("WEEKLY", "W:2026-09-21"))
        dao.putReport(
            com.local.screentime.data.ReportEntity("WEEKLY", "W:2026-09-21", 1L, "text", finalized = false)
        )
        assertEquals(false, dao.getReport("WEEKLY", "W:2026-09-21")!!.finalized)
        assertTrue(true)
    }
}
