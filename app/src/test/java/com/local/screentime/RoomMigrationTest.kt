package com.local.screentime

import android.app.Application
import android.content.Context
import android.database.Cursor
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
import org.json.JSONObject
import java.io.File

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
 *  - 4/5/8→10 完整迁移链；
 *  - 9→10 全部旧表值级保全，以及持久化电量缺口的迁移种子。
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
    private fun seedAllTables(db: SQLiteDatabase, freshV9: Boolean = false) {
        db.execSQL("INSERT INTO sessions (packageName, startTs, endTs, day) VALUES ('com.tencent.mm', 1000, 61000, 20656)")
        db.execSQL("INSERT INTO app_info (packageName, label, firstSeenTs) VALUES ('com.tencent.mm', '微信', 1)")
        db.execSQL("INSERT INTO sync_state (`key`, value) VALUES ('event_watermark', 42)")
        db.execSQL("INSERT INTO usage_daily (packageName, day, totalMs, source) VALUES ('com.tencent.mm', 20656, 60000, 2)")
        // 迁移得到的 v5+ fgsMah 带 SQL DEFAULT，归档 v9 直接建库的该列则没有 DEFAULT。
        val fgsColumn = if (freshV9) ", fgsMah" else ""
        val fgsValue = if (freshV9) ", 0.0" else ""
        db.execSQL(
            "INSERT INTO battery_daily (uidKey, day, packageName, totalMah, fgMah, bgMah, screenMah, cpuMah, " +
                "audioMah, videoMah, cameraMah, gnssMah, wifiMah, btMah, wakelockMah, sensorsMah, otherMah$fgsColumn) " +
                "VALUES ('u0a334', 20656, 'tv.danmaku.bili', 247.5, 28.8, 132.0, 5.0, 90.0, 0,0,0,0,0,0,0,0,0$fgsValue)"
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
                AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10,
            )
            .allowMainThreadQueries()
            .build()

    private inline fun <T> withRoom(block: (AppDatabase) -> T): T {
        val room = buildRoom()
        return try {
            block(room)
        } finally {
            room.close()
        }
    }

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

        val dao = buildRoom().usageDao()  // 首次访问触发 4→10 迁移 + Room schema 校验

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
    fun migrate5ToLatest_existingFgsValuePreserved() = runBlocking {
        // 历史 v5（已有 fgsMah），走 5→10
        createLegacyDb(5, atV5 = true).use { db ->
            seedAllTables(db)
            db.execSQL(
                "UPDATE battery_daily SET fgsMah = 16.9 WHERE uidKey='u0a334'"
            )
        }

        val dao = buildRoom().usageDao()  // 触发 5→10

        val battery = dao.getBatteryDaily("u0a334", 20656)
        assertEquals(16.9, battery!!.fgsMah, 1e-9)  // v5 已有列的数据不受 5→6 影响
        val report = dao.getReport("WEEKLY", "W:2026-09-21")
        assertNotNull(report)
        assertEquals("旧周报文本", report!!.text)
        assertEquals(true, report.finalized)
    }

    @Test
    fun freshDatabase_v10_startsEmptyWithFinalizedDefault() = runBlocking {
        // 新装用户直接以 v10 建库，不触发迁移。
        context.deleteDatabase(dbName)
        withRoom { room ->
            val dao = room.usageDao()
            assertEquals(10, room.openHelper.writableDatabase.version)
            assertNull(dao.getReport("WEEKLY", "W:2026-09-21"))
            assertTrue(dao.allUsageCoverage().isEmpty())
            assertTrue(dao.collectionGaps("battery").isEmpty())
            dao.putReport(
                com.local.screentime.data.ReportEntity("WEEKLY", "W:2026-09-21", 1L, "text", finalized = false)
            )
            assertEquals(false, dao.getReport("WEEKLY", "W:2026-09-21")!!.finalized)
        }
    }

    /** v8 建表语句 = v4 结构 + 4→5(fgsMah)/5→6(finalized)/6→7(mobileMah)/7→8(sessions.source) 四列 */
    private fun createLegacyV8Db(): SQLiteDatabase {
        context.deleteDatabase(dbName)
        val path = context.getDatabasePath(dbName).absolutePath
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        v4Sql.forEach(db::execSQL)
        db.execSQL("ALTER TABLE battery_daily ADD COLUMN fgsMah REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE reports ADD COLUMN finalized INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE battery_daily ADD COLUMN mobileMah REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN source INTEGER NOT NULL DEFAULT 0")
        db.version = 8
        return db
    }

    @Test
    fun migrate8To9_currentDeviceState_valuePreservedAndHighWaterSeeded() = runBlocking {
        // 真机当前存量形态：v8（0.17.1）。本轮所有新列/新表都以「值级保全」为验收。
        createLegacyV8Db().use { db ->
            seedAllTables(db)
            // 模拟真机实际数据形态：共享 UID 行 packageName=null、fgs/mobile 已有值
            db.execSQL("INSERT INTO battery_daily (uidKey, day, packageName, totalMah, fgMah, bgMah, fgsMah, " +
                "screenMah, cpuMah, audioMah, videoMah, cameraMah, gnssMah, wifiMah, btMah, wakelockMah, " +
                "sensorsMah, mobileMah, otherMah) VALUES " +
                "('u0a398', 20657, NULL, 3.5, 0,0, 1.2, 0,0,0,0,0,0,0,0,0,0, 0.4, 0.1)")
            db.execSQL("INSERT INTO battery_snapshot (uidKey, cumMah, compsText, updatedTs) " +
                "VALUES ('u0a398', 3.5, 'screen=1.2|fg=0.5', 2000)")
            db.execSQL("INSERT INTO reports (type, periodKey, generatedTs, text, finalized) " +
                "VALUES ('MONTHLY', 'M:2026-9', 1790000000000, '9月月报', 1)")
        }

        val dao = buildRoom().usageDao()  // 触发 8→9→10 + Room schema 校验

        // 既有行值级保全
        val bili = dao.getBatteryDaily("u0a334", 20656)!!
        assertEquals(247.5, bili.totalMah, 1e-9)
        assertEquals(28.8, bili.fgMah, 1e-9)
        val shared = dao.getBatteryDaily("u0a398", 20657)!!
        assertNull("共享 UID 行 packageName=null 保留", shared.packageName)
        assertEquals(3.5, shared.totalMah, 1e-9)
        assertEquals(1.2, shared.fgsMah, 1e-9)
        // 新组件列默认 0
        assertEquals(0.0, shared.flashlightMah, 1e-9)
        assertEquals(0.0, shared.ambientDisplayMah, 1e-9)
        assertEquals(0.0, shared.idleMah, 1e-9)
        assertEquals(0.0, shared.phoneMah, 1e-9)
        // 快照：原始观测保全 + 高水位种子 = 原始观测（升级后第一帧差分与旧语义连续）
        val snap = dao.getBatterySnapshot("u0a334")!!
        assertEquals(247.5, snap.cumMah, 1e-9)
        assertEquals("recordedCumMah 迁移种子 = cumMah", 247.5, snap.recordedCumMah, 1e-9)
        assertEquals("recordedCompsText 迁移种子 = compsText", "fg=28.8", snap.recordedCompsText)
        assertEquals("历史快照 epoch 标记 = 0（未知）", 0L, snap.epochWindowStartTs)
        val snap2 = dao.getBatterySnapshot("u0a398")!!
        assertEquals(3.5, snap2.recordedCumMah, 1e-9)
        assertEquals("screen=1.2|fg=0.5", snap2.recordedCompsText)
        // 统计表 source 默认 LEGACY(0)
        assertEquals(0, dao.getDayStats(20656)!!.source)
        assertEquals(0, dao.unlockTop(20656, 10)[0].source)
        // 报告：文本与 finalized 保全，修订/格式版本默认旧值（后续由重建逻辑刷新）
        val report = dao.getReport("MONTHLY", "M:2026-9")!!
        assertEquals("9月月报", report.text)
        assertEquals(true, report.finalized)
        assertEquals(0L, report.dataRevision)
        assertEquals(1, report.formatVersion)
        // 新表可写可查
        dao.upsertUidPackages(listOf(com.local.screentime.data.UidPackageEntity("u0a398", "com.a", 100, 200)))
        assertEquals(1, dao.uidPackagesFor("u0a398").size)
        dao.insertCaptureLog(
            com.local.screentime.data.CaptureLogEntity(
                ts = 1, attemptTs = 1, commandStartTs = 1, commandEndTs = 1, zoneId = "Asia/Shanghai",
                source = 1, parserVersion = 4, ledgerVersion = 2, outcome = "SUCCESS",
                epochWindowStartTs = null, uidCount = 0, globalCount = 0,
                computedDrainMah = 0.0, actualDrainLowMah = null, actualDrainHighMah = null,
                sumUidTotalMah = 0.0, detail = "",
            )
        )
        assertEquals(1, dao.recentCaptureLogs(10).size)
    }

    /** 直接使用已归档的 v9 schema，避免手写旧结构与正式档案逐渐分叉。 */
    private fun createLegacyV9Db(): Pair<SQLiteDatabase, List<String>> {
        val relative = "schemas/com.local.screentime.data.AppDatabase/9.json"
        val archive = listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("Missing archived v9 schema: $relative")
        val entities = JSONObject(archive.readText()).getJSONObject("database").getJSONArray("entities")
        context.deleteDatabase(dbName)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName).absolutePath, null)
        val tables = buildList {
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                add(table)
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (i in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
        }
        db.version = 9
        return db to tables
    }

    private fun readRows(cursor: Cursor): List<Map<String, Any?>> = cursor.use {
        buildList {
            while (cursor.moveToNext()) {
                add(cursor.columnNames.mapIndexed { index, name ->
                    name to when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                        else -> cursor.getString(index)
                    }
                }.toMap())
            }
        }.sortedBy { it.toString() }
    }

    @Test
    fun migrate9To10_allOldTableValuesPreservedAndBatteryGapSeeded() = runBlocking {
        val (legacy, tables) = createLegacyV9Db()
        val before = legacy.use { db ->
            seedAllTables(db, freshV9 = true)
            // v9 已存在的新增字段也设非默认值，确保 9→10 不重写旧行。
            db.execSQL("UPDATE sessions SET source=1")
            db.execSQL("UPDATE battery_daily SET fgsMah=1.2, mobileMah=0.4, flashlightMah=0.3, ambientDisplayMah=0.2, idleMah=0.1, phoneMah=0.05")
            db.execSQL("UPDATE battery_snapshot SET recordedCumMah=250.0, recordedCompsText='fg=30.0', epochWindowStartTs=900")
            db.execSQL("UPDATE day_stats SET source=1")
            db.execSQL("UPDATE unlock_stats SET source=1")
            db.execSQL("UPDATE reports SET finalized=0, dataRevision=18, formatVersion=3")
            db.execSQL("INSERT INTO uid_packages VALUES ('u0a334','tv.danmaku.bili',100,200)")
            db.execSQL("INSERT INTO capture_log VALUES (7,2000,1900,1950,2000,'Asia/Shanghai',1,4,2,'SUCCESS',900,12,3,250.5,NULL,270.0,247.5,'kept sample')")
            db.execSQL("INSERT INTO sync_state VALUES ('battery_lost_tail_from_ts',1792200671770)")
            db.execSQL("INSERT INTO sync_state VALUES ('battery_lost_tail_to_ts',1792490705000)")
            tables.associateWith { readRows(db.rawQuery("SELECT * FROM `$it`", null)) }
        }
        assertEquals("覆盖 v9 全部 12 张业务表", 12, before.size)
        assertTrue("每张旧表均须有值级保全样本", before.values.all { it.isNotEmpty() })

        withRoom { room ->
            val migrated = room.openHelper.writableDatabase
            assertEquals(10, migrated.version)
            tables.forEach { table ->
                assertEquals("$table 的全部历史字段值须原样保留", before[table], readRows(migrated.query("SELECT * FROM `$table`")))
            }
            val dao = room.usageDao()
            val gap = dao.collectionGaps("battery").single()
            assertEquals(1792200671770L, gap.fromTs)
            assertEquals(1792490705000L, gap.toTs)
            assertEquals(1792490705000L, gap.detectedTs)
            assertTrue("旧时长数据不能凭迁移标成已验证完整", dao.allUsageCoverage().isEmpty())
            assertTrue(dao.collectionGaps("usage").isEmpty())
            // 新表可独立持久化，后续健康时间更新不得删除迁移保留的缺口。
            dao.upsertUsageCoverage(listOf(com.local.screentime.data.UsageCoverageEntity(20747, 1000, 2000, 1, 2100, "root")))
            dao.putState(com.local.screentime.data.SyncStateEntity("last_battery_success_ts", 1792557356513L))
            assertEquals(1, dao.allUsageCoverage().size)
            assertEquals(gap, dao.collectionGaps("battery").single())
        }
    }

    @Test
    fun migrate9To10_invalidBatteryTailDoesNotCreateGap() = runBlocking {
        createLegacyV9Db().first.use { db ->
            db.execSQL("INSERT INTO sync_state VALUES ('battery_lost_tail_from_ts',2000)")
            db.execSQL("INSERT INTO sync_state VALUES ('battery_lost_tail_to_ts',1000)")
        }
        withRoom { room ->
            assertTrue(room.usageDao().collectionGaps("battery").isEmpty())
        }
    }
}
