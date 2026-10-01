package com.local.screentime

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.local.screentime.data.AppDatabase
import com.local.screentime.data.SESSION_SOURCE_API
import com.local.screentime.data.SESSION_SOURCE_ROOT
import com.local.screentime.data.UsageDao
import com.local.screentime.data.UsageRepository
import com.local.screentime.data.UsageSession
import com.local.screentime.data.applyApiFallbackSessions
import com.local.screentime.data.filterNonOverlapping
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * P0-2 source-aware 会话权威策略（Robolectric + 真实 Room）：
 * ColorOS 的 queryEvents 被深度裁剪（真机实证），root 临时失效时 API fallback
 * 绝不能删除/覆盖 root 建立的权威历史——只能补权威缺席区间；root 恢复后
 * 全量重建自然回到权威结果。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionAuthorityTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: UsageDao

    /** 2026-09-28（周一）10:00 起的毫秒时刻（相对 day 起点） */
    private val zone = java.time.ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 9, 28).toEpochDay()
    private val dayStart = LocalDate.of(2026, 9, 28).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun t(min: Long) = dayStart + min * 60_000
    private val windowStart = t(0)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.usageDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun session(pkg: String, fromMin: Long, toMin: Long, source: Int) =
        UsageSession(packageName = pkg, startTs = t(fromMin), endTs = t(toMin), day = day, source = source)

    private suspend fun rootSessionsOf(pkg: String): List<UsageSession> =
        dao.sessionsOfDay(day).filter { it.packageName == pkg && it.source != SESSION_SOURCE_API }

    @Test
    fun apiFallback_preservesRootSessions_andFillsOnlyGaps() = runBlocking {
        // root 权威历史：微信 10:00-11:00、抖音 14:00-15:00
        dao.insertSessions(
            listOf(session("com.tencent.mm", 600, 660, SESSION_SOURCE_ROOT))
        )
        // root 临时失效；被裁剪的 API 事件流只重建出：
        //  - 一段与 root 重叠的微信（残缺流伪影，必须丢弃）
        //  - 一段跨包重叠（同一时刻另一个 App，伪影，必须丢弃）
        //  - 一段权威缺席区间的抖音 12:00-12:30（可补）
        val apiSessions = listOf(
            session("com.tencent.mm", 610, 650, SESSION_SOURCE_API),   // 与 root 重叠 → 丢
            session("com.ss.android.ugc.aweme", 620, 640, SESSION_SOURCE_API), // 跨包同时刻重叠 → 丢
            session("com.ss.android.ugc.aweme", 720, 750, SESSION_SOURCE_API), // 缺席区间 → 补
        )
        applyApiFallbackSessions(db, dao, apiSessions, windowStart, watermarkTs = t(800))

        // root 权威历史原样保留（值级）
        val mm = rootSessionsOf("com.tencent.mm")
        assertEquals(1, mm.size)
        assertEquals(t(600), mm[0].startTs)
        assertEquals(t(660), mm[0].endTs)
        assertEquals(SESSION_SOURCE_ROOT, mm[0].source)
        // 只补了缺席区间
        val aweme = dao.sessionsOfDay(day).filter { it.packageName == "com.ss.android.ugc.aweme" }
        assertEquals(1, aweme.size)
        assertEquals(t(720), aweme[0].startTs)
        assertEquals(SESSION_SOURCE_API, aweme[0].source)
        // 水位线推进
        assertEquals(t(800), dao.getState(UsageRepository.KEY_WATERMARK))
    }

    @Test
    fun apiFallback_replacesPreviousApiData_notAuthority() = runBlocking {
        dao.insertSessions(
            listOf(
                session("com.tencent.mm", 600, 660, SESSION_SOURCE_ROOT),
                session("com.ss.android.ugc.aweme", 720, 750, SESSION_SOURCE_API), // 上次 fallback
            )
        )
        // 新一轮 fallback：抖音的 API 会话被更新（同区间）；微信权威行不动
        val apiSessions = listOf(session("com.ss.android.ugc.aweme", 725, 780, SESSION_SOURCE_API))
        applyApiFallbackSessions(db, dao, apiSessions, windowStart, t(900))

        assertEquals(1, rootSessionsOf("com.tencent.mm").size)
        val aweme = dao.sessionsOfDay(day).filter { it.packageName == "com.ss.android.ugc.aweme" }
        assertEquals(1, aweme.size)  // 旧 API 行被替换而非叠加
        assertEquals(t(725), aweme[0].startTs)
    }

    @Test
    fun rootRecovery_rebuildsAuthority_overApiFallback() = runBlocking {
        dao.insertSessions(listOf(session("com.tencent.mm", 605, 650, SESSION_SOURCE_API)))
        // root 恢复：全量重建路径（delete all in window + ROOT 插入）
        val rebuilt = listOf(session("com.tencent.mm", 600, 660, SESSION_SOURCE_ROOT))
        db.withTransaction {
            dao.deleteSessionsStartingAfter(windowStart)
            dao.insertSessions(rebuilt)
        }
        val mm = dao.sessionsOfDay(day)
        assertEquals(1, mm.size)
        assertEquals(SESSION_SOURCE_ROOT, mm[0].source)
        assertEquals(t(600), mm[0].startTs)
    }

    @Test
    fun apiFallback_txRollback_leavesStateUntouched() = runBlocking {
        dao.insertSessions(listOf(session("com.ss.android.ugc.aweme", 720, 750, SESSION_SOURCE_API)))
        dao.putState(com.local.screentime.data.SyncStateEntity(UsageRepository.KEY_WATERMARK, t(500)))
        class Injected : Exception("probe")
        var threw = false
        try {
            applyApiFallbackSessions(
                db, dao, listOf(session("com.tencent.mm", 600, 660, SESSION_SOURCE_API)),
                windowStart, t(900),
                probeBeforeInsert = { throw Injected() },
            )
        } catch (e: Injected) {
            threw = true
        }
        assertTrue(threw)
        // 旧 API 行仍在（删除未提交）、水位线未推进
        assertEquals(1, dao.sessionsOfDay(day).size)
        assertEquals(t(500), dao.getState(UsageRepository.KEY_WATERMARK))
    }

    @Test
    fun filterNonOverlapping_pureCases() {
        val authority = listOf(session("a", 600, 660, SESSION_SOURCE_ROOT))
        assertTrue(filterNonOverlapping(emptyList(), authority).isEmpty())
        // 半开区间语义 [start, end)：相交即丢，贴边不算相交
        assertEquals(0, filterNonOverlapping(listOf(session("x", 610, 630, SESSION_SOURCE_API)), authority).size) // 区间内 → 丢
        assertEquals(0, filterNonOverlapping(listOf(session("x", 650, 700, SESSION_SOURCE_API)), authority).size) // 相交 → 丢
        assertEquals(1, filterNonOverlapping(listOf(session("x", 660, 700, SESSION_SOURCE_API)), authority).size) // start==权威end → 保留
        assertEquals(1, filterNonOverlapping(listOf(session("x", 500, 600, SESSION_SOURCE_API)), authority).size) // end==权威start → 保留
        // 权威为空 → 全部保留
        assertEquals(2, filterNonOverlapping(listOf(session("x", 1, 2, SESSION_SOURCE_API), session("y", 3, 4, SESSION_SOURCE_API)), emptyList()).size)
    }
}
