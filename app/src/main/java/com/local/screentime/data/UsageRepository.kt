package com.local.screentime.data

import android.app.usage.UsageEvents
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.room.withTransaction
import com.topjohnwu.superuser.Shell
import com.local.screentime.Notifications
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed class SyncResult {
    data object NoPermission : SyncResult()
    data class Ok(
        val windowStart: Long,
        val windowEnd: Long,
        val eventCount: Int,
        val sessionCount: Int,
        val rootUsed: Boolean = false,
    ) : SyncResult()
}

data class AppDisplay(val label: String, val frozen: Boolean, val stateDesc: String?)

data class UidPower(
    val uidKey: String,
    val totalMah: Double,
    val fg: Double,
    val bg: Double,
    val comps: Map<String, Double>,
    val durs: Map<String, Long>,
)

data class BatteryParsed(
    val uids: List<UidPower>,
    val globals: Map<String, Pair<Double, Long>>,
    val computedDrain: Double,
    val actualDrainLow: Double,
)

/**
 * 三层数据源：
 *  1) UsageStatsManager.queryEvents —— ColorOS 上被深度裁剪（实测请求窗口内 137 条只返回 12 条），
 *     只用于重建“会话时间线”，尽力而为；
 *  2) queryUsageStats(INTERVAL_DAILY) —— ColorOS 返回的是 ~16:40 锚点的滚动 24h 桶（非自然日），
 *     整桶无法按自然日拆分，不再参与展示（usage_daily 仅留诊断）；
 *  3) root 下 dumpsys usagestats —— 未裁剪的全量事件，重建出的会话表是唯一展示权威
 *     （实测与系统 totalTimeUsed 逐包一致，见 HANDOFF 第 8 节）。
 */
class UsageRepository(private val context: Context) {

    private val db = AppDatabase.get(context)
    private val dao = db.usageDao()
    private val reader = UsageStatsReader(context)
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val displayCache = java.util.concurrent.ConcurrentHashMap<String, AppDisplay>()
    private val uidCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun uidOfPackage(pkg: String): Int = uidCache.getOrPut(pkg) {
        try {
            context.packageManager.getPackageUid(pkg, 0)
        } catch (e: Exception) {
            -1
        }
    }

    suspend fun weeklyTotals(): List<Pair<LocalDate, Long>> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val sums = dao.sessionsSumsSince(monday.toEpochDay()).associate { it.day to it.totalMs }
        return (0..6).map { i ->
            val d = monday.plusDays(i.toLong())
            d to (sums[d.toEpochDay()] ?: 0L)
        }
    }

    private val catCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 手动分类覆盖：优先级最高；category=null 表示恢复自动 */
    fun setCategoryOverride(pkg: String, category: String?) {
        val obj = JSONObject(prefs.getString("cat_overrides", null) ?: "{}")
        if (category == null) obj.remove(pkg) else obj.put(pkg, category)
        prefs.edit().putString("cat_overrides", obj.toString()).apply()
        catCache.remove(pkg)
    }

    fun allCategories(): List<String> = listOf(
        "通讯社交", "影音娱乐", "游戏", "音乐音频", "资讯阅读", "AI 助手",
        "购物", "金融", "地图出行", "生活服务", "效率", "其他",
    )

    /** 应用分类：手动覆盖优先 → 系统声明的 category → 内置中国常用应用字典 → 兜底“其他” */
    suspend fun categoryOf(pkg: String): String {
        catCache[pkg]?.let { return it }
        runCatching {
            val override = JSONObject(prefs.getString("cat_overrides", null) ?: "{}").optString(pkg, "")
            if (override.isNotEmpty()) {
                catCache[pkg] = override
                return override
            }
        }
        val pm = context.packageManager
        val ai = try {
            pm.getApplicationInfo(pkg, 0)
        } catch (e: Exception) {
            null
        }
        val result = when (ai?.category) {
            android.content.pm.ApplicationInfo.CATEGORY_GAME -> "游戏"
            android.content.pm.ApplicationInfo.CATEGORY_AUDIO -> "音乐音频"
            android.content.pm.ApplicationInfo.CATEGORY_VIDEO -> "影音娱乐"
            android.content.pm.ApplicationInfo.CATEGORY_IMAGE -> "图像"
            android.content.pm.ApplicationInfo.CATEGORY_SOCIAL -> "通讯社交"
            android.content.pm.ApplicationInfo.CATEGORY_NEWS -> "资讯阅读"
            android.content.pm.ApplicationInfo.CATEGORY_MAPS -> "地图出行"
            android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY -> "效率"
            else -> categoryDict(pkg) ?: "其他"
        }
        catCache[pkg] = result
        return result
    }

    private fun categoryDict(pkg: String): String? {
        val table = listOf(
            // 通讯社交
            "tencent.mm" to "通讯社交", "mobileqq" to "通讯社交", "telegram" to "通讯社交",
            "whatsapp" to "通讯社交", "dingtalk" to "通讯社交", "wework" to "通讯社交",
            "sinaweibo" to "通讯社交", "xingin" to "通讯社交", "byr" to "通讯社交",
            "tieba" to "通讯社交", "coolapk" to "通讯社交", "mail" to "通讯社交",
            "gm" to "通讯社交", "whalecloud" to "通讯社交",
            // 影音娱乐
            "ugc.aweme" to "影音娱乐", "bilibili" to "影音娱乐", "kuaishou" to "影音娱乐",
            "tencent.video" to "影音娱乐", "qiyi" to "影音娱乐", "youku" to "影音娱乐",
            "lemon.lv" to "影音娱乐", "music" to "影音娱乐", "spotify" to "影音娱乐",
            "lifeservices" to "影音娱乐", "qqmusic" to "影音娱乐",
            // 资讯阅读
            "zhihu" to "资讯阅读", "koodoreader" to "资讯阅读", "cnki" to "资讯阅读",
            "chaoxing" to "资讯阅读", "fenbi" to "资讯阅读", "wisedu" to "资讯阅读",
            "news" to "资讯阅读", "areader" to "资讯阅读", "kreading" to "资讯阅读",
            // 购物
            "taobao" to "购物", "tmall" to "购物", "jingdong" to "购物",
            "xunmeng" to "购物", "idlefish" to "购物", "vip.com" to "购物",
            "suning" to "购物", "wudaokou" to "购物",
            // 金融
            "alipay" to "金融", "unionpay" to "金融", "icbc" to "金融",
            "ccb" to "金融", "cmb.pb" to "金融", "bank" to "金融", "wallet" to "金融",
            // 地图出行
            "autonavi" to "地图出行", "baidumaps" to "地图出行", "didi" to "地图出行",
            "subway" to "地图出行", "bmac" to "地图出行", "daxiaamu" to "地图出行",
            // 生活服务
            "meituan" to "生活服务", "sankuai" to "生活服务", "ele" to "生活服务",
            "dianping" to "生活服务", "tmri" to "生活服务", "health" to "生活服务",
            // AI 助手
            "deepseek" to "AI 助手", "tongyi" to "AI 助手", "larus.nova" to "AI 助手",
            "moonshot" to "AI 助手", "openai" to "AI 助手", "doubao" to "AI 助手",
            // 效率
            "tasks" to "效率", "ticktick" to "效率", "notion" to "效率",
            "microsoft" to "效率", "wps" to "效率", "skydrive" to "效率",
            "onenote" to "效率", "word" to "效率",
        )
        val p = pkg.lowercase(Locale.US)
        return table.firstOrNull { p.contains(it.first) }?.second
    }

    suspend fun syncNow(): SyncResult {
        if (!reader.hasPermission()) return SyncResult.NoPermission
        val now = System.currentTimeMillis()

        // 一次性修正：存量耗电行里系统 UID 被写上了共享包名，清空交给 systemUidLabel 命名
        if (dao.getState(KEY_SYSUID_FIX) == null) {
            dao.clearSystemUidPackageNames()
            dao.putState(SyncStateEntity(KEY_SYSUID_FIX, 1))
        }

        // 1) API 事件流：时间线尽力而为
        val watermark = dao.getState(KEY_WATERMARK) ?: 0L
        val windowStart = (watermark - OVERLAP_MS).coerceAtLeast(0L)
        val events = reader.queryEvents(windowStart, now)
        val sessions = buildSessions(events, windowStart, now)
        db.withTransaction {
            dao.deleteSessionsEndedAfter(windowStart)
            if (sessions.isNotEmpty()) dao.insertSessions(sessions)
            dao.putState(SyncStateEntity(KEY_WATERMARK, now))
        }
        runCatching { syncDayEventStats(events, ZoneId.systemDefault()) }
        runCatching { dao.deleteUnlockLauncherRows() }
        // 2) 一次性清理 usage_daily：ColorOS 的 API 日聚合是 ~16:40 锚点的滚动 24h 桶，
        //    无法按自然日拆分，v0.16 起展示只认会话表，历史桶值全部作废
        if (dao.getState(KEY_AGG_WIPED) == null) {
            db.withTransaction {
                dao.clearUsageDaily()
                dao.putState(SyncStateEntity(KEY_AGG_WIPED, 1))
            }
        }

        // 3) root 全量 + 耗电
        var rootUsed = false
        try {
            if (Shell.isAppGrantedRoot() == true) {
                rootUsed = syncViaRoot()
                syncBattery(now)
            }
        } catch (e: Exception) {
            Log.w(TAG, "root sync failed", e)
        }

        refreshAppRegistry()
        runCatching { generateDueReports(force = false) }
        Log.d(TAG, "sync: apiEvents=${events.size} sessions=${sessions.size} root=$rootUsed")
        return SyncResult.Ok(windowStart, now, events.size, sessions.size, rootUsed)
    }

    /** 展示用：会话表为主；usage_daily 若有历史自然日回补值（source=2，系统桶按会话分布切分）则逐包取较大值。
     *  历史日（2026-09-04～09-12）的会话被旧版 STOPPED bug 丢了 30~90%，取 max 后由回补值兜底。 */
    suspend fun dailyRows(day: Long): List<DailyRow> {
        val out = LinkedHashMap<String, Long>()
        for (r in dao.dailyUsage(day)) out[r.packageName] = maxOf(out[r.packageName] ?: 0L, r.totalMs)
        for (r in dao.dailyAggRows(day)) out[r.packageName] = maxOf(out[r.packageName] ?: 0L, r.totalMs)
        return out.map { DailyRow(it.key, day, it.value) }
            .filter { it.totalMs > 0 }
            .sortedByDescending { it.totalMs }
    }

    suspend fun dayTotalMs(day: Long): Long = dailyRows(day).sumOf { it.totalMs }

    suspend fun sessionsOfDay(day: Long): List<UsageSession> = dao.sessionsOfDay(day)

    suspend fun batteryDailyRows(day: Long): List<BatteryDailyEntity> = dao.batteryDailyRows(day)

    suspend fun batteryGlobalRows(day: Long): List<BatteryGlobalEntity> = dao.batteryGlobalRows(day)

    /** 把当天列表里的包映射到其 UID 耗电记录 */
    suspend fun batteryForPackages(
        day: Long,
        packages: List<String>,
    ): Map<String, BatteryDailyEntity> {
        val byUid = dao.batteryDailyRows(day).associateBy { it.uidKey }
        val pm = context.packageManager
        val out = HashMap<String, BatteryDailyEntity>()
        for (p in packages) {
            val uid = uidOfPackage(p)
            if (uid <= 0) continue
            byUid[uidKeyOf(uid)]?.let { out[p] = it }
        }
        return out
    }

    suspend fun dayStats(day: Long): DayStatsEntity? = dao.getDayStats(day)

    suspend fun unlockTop(day: Long, limit: Int): List<UnlockStatsEntity> = dao.unlockTop(day, limit)

    suspend fun latestReports(): List<ReportEntity> = dao.latestReports()

    fun hasPermission(): Boolean = reader.hasPermission()

    /** 冻结/卸载后的包依然能拿到 label（PackageManager 对 disabled 包照常返回）。
     *  API 35 起 ApplicationInfo.enabled 是 boolean（不再区分禁用方式），
     *  挂起式冻结用公开的 isPackageSuspended 检测。 */
    suspend fun resolveDisplay(packageName: String): AppDisplay {
        displayCache[packageName]?.let { return it }
        val result = computeDisplay(packageName)
        displayCache[packageName] = result
        return result
    }

    private suspend fun computeDisplay(packageName: String): AppDisplay {
        val pm = context.packageManager
        return try {
            val ai = pm.getApplicationInfo(packageName, 0)
            val label = ai.loadLabel(pm).toString().ifBlank { packageName }
            val suspended = try {
                pm.isPackageSuspended(packageName)
            } catch (e: Exception) {
                false
            }
            when {
                !ai.enabled -> AppDisplay(label, true, "已冻结")
                suspended -> AppDisplay(label, true, "已冻结(挂起)")
                else -> AppDisplay(label, false, null)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // 包不可见（可见性过滤/已卸载）：回退到注册表快照
            val cached = dao.appInfo(packageName)
            AppDisplay(cached?.label ?: packageName, true, "已卸载/不可见")
        }
    }

    /** 系统级 UID 的展示名（耗电排行里和普通应用并列） */
    fun systemUidLabel(uidKey: String): String? = when (uidKey) {
        "0" -> "Android 系统"
        "1000" -> "Android 系统服务"
        "1001" -> "电话"
        "1002" -> "蓝牙服务"
        "1021" -> "网络共享"
        "1023" -> "媒体存储"
        "1041" -> "音频服务"
        "u0" -> "Root"
        else -> null
    }

    /** 应用注册表：出现在会话/聚合里的包 + 所有有桌面入口的包 */
    private suspend fun refreshAppRegistry() {
        val pm = context.packageManager
        val existing = dao.allApps().associateBy { it.packageName }
        val pkgs = LinkedHashSet(dao.distinctPackages())
        try {
            for (info in pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))) {
                if (pm.getLaunchIntentForPackage(info.packageName) != null) pkgs.add(info.packageName)
            }
        } catch (e: Exception) {
            // 某些 ROM 对 getInstalledPackages 有限制时，注册表退化为“会话里出现过的包”
        }
        val now = System.currentTimeMillis()
        val entities = pkgs.map { pkg ->
            val prev = existing[pkg]
            val label = try {
                pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString().ifBlank { pkg }
            } catch (e: Exception) {
                pkg
            }
            AppInfoEntity(pkg, label, prev?.firstSeenTs ?: now)
        }
        if (entities.isNotEmpty()) dao.upsertApps(entities)
    }

    /** 诊断导出：某天全部会话 + 聚合 + 冻结状态 */
    suspend fun diagnosticJson(day: Long): String {
        val sessions = dao.sessionsOfDay(day)
        val rows = dailyRows(day)
        val zone = ZoneId.systemDefault()
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val root = JSONObject()
        root.put("app", context.packageName)
        root.put("generatedAt", Instant.now().toString())
        root.put("day", LocalDate.ofEpochDay(day).toString())
        root.put("hasUsagePermission", reader.hasPermission())
        root.put("rootDataUsed", Shell.isAppGrantedRoot() == true)
        val arr = JSONArray()
        for (s in sessions) {
            val o = JSONObject()
            o.put("package", s.packageName)
            o.put("start", Instant.ofEpochMilli(s.startTs).atZone(zone).format(fmt))
            o.put("end", Instant.ofEpochMilli(s.endTs).atZone(zone).format(fmt))
            o.put("ms", s.endTs - s.startTs)
            arr.put(o)
        }
        root.put("sessions", arr)
        val agg = JSONArray()
        for (r in dao.dailyAggRows(day)) {
            val o = JSONObject()
            o.put("package", r.packageName)
            o.put("ms", r.totalMs)
            o.put("source", r.source)
            agg.put(o)
        }
        root.put("aggregates", agg)
        val apps = JSONObject()
        for (r in rows) {
            val d = resolveDisplay(r.packageName)
            val o = JSONObject()
            o.put("label", d.label)
            o.put("frozen", d.frozen)
            o.put("totalMs", r.totalMs)
            apps.put(r.packageName, o)
        }
        root.put("apps", apps)
        return root.toString(2)
    }

    /** 方案 B：root 下 dumpsys 未裁剪全量（事件 + checkin 按天聚合） */
    private suspend fun syncViaRoot(): Boolean {
        val zone = ZoneId.systemDefault()
        // 一次性历史回补：导入 PC 端生成的自然日数据（来源 /data/system_ce/0/usagestats 的系统桶，
        // 按会话分布切分到自然日，覆盖 2026-09-04～09-12；那些天的会话被旧版 STOPPED bug 丢掉 30~90%）
        if (dao.getState(KEY_BACKFILL) == null) {
            runCatching {
                val text = Shell.cmd("cat /data/local/tmp/stlog_backfill.json").exec().out.joinToString("\n")
                if (text.length > 10) {
                    val arr = JSONObject(text).getJSONArray("rows")
                    val list = ArrayList<DailyUsageEntity>(arr.length())
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(DailyUsageEntity(o.getString("p"), o.getLong("d"), o.getLong("ms"), SOURCE_ROOT))
                    }
                    if (list.isNotEmpty()) {
                        db.withTransaction { dao.upsertDaily(list) }
                        Shell.cmd("rm -f /data/local/tmp/stlog_backfill.json")
                        dao.putState(SyncStateEntity(KEY_BACKFILL, 1))
                        Log.d(TAG, "backfill: ${list.size} rows")
                    }
                }
            }
        }
        // 保活：把自己加进 Doze 白名单，降低 ColorOS 后台清理概率
        runCatching {
            Shell.cmd("dumpsys deviceidle whitelist +${context.packageName}").exec()
        }
        // --checkin 在 root 下输出格式不同（解析恒为 0 行），聚合已由方案 A 覆盖，跳过以缩短同步时间
        val dump = Shell.cmd("dumpsys usagestats").exec().out
        val events = parseDumpsysEvents(dump, zone)
        if (events.isNotEmpty()) {
            val start = events.minOf { it.ts }
            val end = events.maxOf { it.ts }
            val sessions = buildSessions(events, start, end)
            db.withTransaction {
                dao.deleteSessionsEndedAfter(start)
                if (sessions.isNotEmpty()) dao.insertSessions(sessions)
            }
            runCatching { syncDayEventStats(events, zone) }
        }
        Log.d(TAG, "root: events=${events.size}")
        return events.isNotEmpty()
    }

    /** root 读取 batterystats：每 UID 与整机组件耗电，快照差分累计（充满自动重置） */
    private suspend fun syncBattery(now: Long): Boolean {
        val lines = try {
            Shell.cmd("dumpsys batterystats --charged").exec().out
        } catch (e: Exception) {
            return false
        }
        val parsed = parseBatteryPower(lines)
        if (parsed.uids.isEmpty() && parsed.globals.isEmpty()) return false
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone).toEpochDay()
        val pm = context.packageManager

        val rows = ArrayList<BatteryDailyEntity>()
        for (u in parsed.uids) {
            val last = dao.getBatterySnapshot(u.uidKey)
            val reset = last != null && u.totalMah < last.cumMah - 0.5
            val prevComps = decodeComps(last?.compsText ?: "")

            fun compDelta(key: String): Double {
                val nowV = u.comps[key] ?: 0.0
                val prevV = if (last == null || reset) 0.0 else (prevComps[key] ?: 0.0)
                return (nowV - prevV).coerceAtLeast(0.0)
            }
            val totalDelta = if (last == null || reset) {
                u.totalMah
            } else {
                (u.totalMah - last.cumMah).coerceAtLeast(0.0)
            }
            dao.putBatterySnapshot(
                BatterySnapshotEntity(
                    uidKey = u.uidKey,
                    cumMah = u.totalMah,
                    compsText = encodeComps(u.comps + mapOf("fg" to u.fg, "bg" to u.bg)),
                    updatedTs = now,
                )
            )
            if (totalDelta <= 0.01) continue
            val ex = dao.getBatteryDaily(u.uidKey, today)
            val uid = uidOf(u.uidKey)
            val pkg = if (uid >= 10000) try {
                pm.getPackagesForUid(uid)?.firstOrNull { !it.isNullOrBlank() }
            } catch (e: Exception) {
                null
            } else null  // 系统 UID（<10000）是共享的，随便挂某个包名会误导（如 UID 1000 显示成“安全中心”），交给 systemUidLabel
            rows.add(
                BatteryDailyEntity(
                    uidKey = u.uidKey,
                    day = today,
                    packageName = pkg,
                    totalMah = (ex?.totalMah ?: 0.0) + totalDelta,
                    fgMah = (ex?.fgMah ?: 0.0) + compDelta("fg"),
                    bgMah = (ex?.bgMah ?: 0.0) + compDelta("bg"),
                    screenMah = (ex?.screenMah ?: 0.0) + compDelta("screen"),
                    cpuMah = (ex?.cpuMah ?: 0.0) + compDelta("cpu"),
                    audioMah = (ex?.audioMah ?: 0.0) + compDelta("audio"),
                    videoMah = (ex?.videoMah ?: 0.0) + compDelta("video"),
                    cameraMah = (ex?.cameraMah ?: 0.0) + compDelta("camera"),
                    gnssMah = (ex?.gnssMah ?: 0.0) + compDelta("gnss"),
                    wifiMah = (ex?.wifiMah ?: 0.0) + compDelta("wifi"),
                    btMah = (ex?.btMah ?: 0.0) + compDelta("bluetooth"),
                    wakelockMah = (ex?.wakelockMah ?: 0.0) + compDelta("wakelock"),
                    sensorsMah = (ex?.sensorsMah ?: 0.0) + compDelta("sensors"),
                    otherMah = (ex?.otherMah ?: 0.0) + u.comps.keys
                        .filter { it !in KNOWN_COMPS }
                        .sumOf { compDelta(it) },
                )
            )
        }
        if (rows.isNotEmpty()) dao.upsertBatteryDaily(rows)

        // 全局组件：以模型估算总量为重置信号
        if (parsed.computedDrain > 0) {
            val gLast = dao.getBatterySnapshot(GLOBAL_SNAPSHOT_KEY)
            val reset = gLast != null && parsed.computedDrain < gLast.cumMah - 0.5
            dao.putBatterySnapshot(
                BatterySnapshotEntity(GLOBAL_SNAPSHOT_KEY, parsed.computedDrain, encodeComps(parsed.globals.mapValues { it.value.first }), now)
            )
            val prevComps = decodeComps(gLast?.compsText ?: "")
            val grow = ArrayList<BatteryGlobalEntity>()
            for ((comp, pair) in parsed.globals) {
                val nowV = pair.first
                val prevV = if (gLast == null || reset) 0.0 else (prevComps[comp] ?: 0.0)
                val delta = (nowV - prevV).coerceAtLeast(0.0)
                if (delta <= 0.01) continue
                val ex = dao.getBatteryGlobal(comp, today)
                grow.add(
                    BatteryGlobalEntity(
                        component = comp,
                        day = today,
                        mah = (ex?.mah ?: 0.0) + delta,
                        durationMs = if (ex == null) pair.second else ex.durationMs,
                    )
                )
            }
            if (grow.isNotEmpty()) dao.upsertBatteryGlobal(grow)
        }
        Log.d(TAG, "battery: uids=${parsed.uids.size} globals=${parsed.globals.size} drain=${parsed.computedDrain}")
        return true
    }

    /** 拿起次数（KEYGUARD_HIDDEN 计数）与“解锁后 15 秒内首个应用”统计 */
    private suspend fun syncDayEventStats(events: List<RawEvent>, zone: ZoneId) {
        if (events.isEmpty()) return
        val pickups = HashMap<Long, Long>()
        val unlockFirst = HashMap<Long, MutableMap<String, Long>>()
        var lastUnlockTs = -1L
        var lastUnlockDay = -1L
        for (ev in events.sortedBy { it.ts }) {
            val day = Instant.ofEpochMilli(ev.ts).atZone(zone).toLocalDate().toEpochDay()
            when (ev.eventType) {
                UsageEvents.Event.KEYGUARD_HIDDEN -> {
                    lastUnlockTs = ev.ts
                    lastUnlockDay = day
                    pickups[day] = (pickups[day] ?: 0L) + 1
                }
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // 解锁后必然先回到桌面，桌面启动器不计入“解锁后首开”
                    val isLauncher = ev.packageName.contains("launcher", ignoreCase = true)
                    val gap = ev.ts - lastUnlockTs
                    if (lastUnlockTs > 0 && gap in 1..15_000 && !isLauncher) {
                        val d = if (lastUnlockDay >= 0) lastUnlockDay else day
                        val m = unlockFirst.getOrPut(d) { mutableMapOf() }
                        m[ev.packageName] = (m[ev.packageName] ?: 0L) + 1
                        lastUnlockTs = -1L
                    }
                }
            }
        }
        val statRows = pickups.map { (d, c) -> DayStatsEntity(d, c) }
        if (statRows.isNotEmpty()) dao.upsertDayStats(statRows)
        val unlockRows = unlockFirst.flatMap { (d, m) -> m.map { (p, c) -> UnlockStatsEntity(d, p, c) } }
        if (unlockRows.isNotEmpty()) dao.upsertUnlockStats(unlockRows)
    }

    data class ReportPeriod(
        val type: String,
        val key: String,
        val title: String,
        val start: LocalDate,
        val end: LocalDate,
    )

    fun currentPeriods(today: LocalDate): List<ReportPeriod> {
        val weeklyEnd = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY))
        val weeklyStart = weeklyEnd.minusDays(6)
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        val monthStart = today.withDayOfMonth(1)
        val yearEnd = LocalDate.of(today.year, 12, 31)
        val yearStart = LocalDate.of(today.year, 1, 1)
        return listOf(
            ReportPeriod(
                REPORT_WEEKLY, "W:$weeklyStart",
                "$weeklyStart ~ $weeklyEnd 周报", weeklyStart, weeklyEnd
            ),
            ReportPeriod(
                REPORT_MONTHLY, "M:${today.year}-${today.monthValue}",
                "${today.year}年${today.monthValue}月 月报", monthStart, monthEnd
            ),
            ReportPeriod(
                REPORT_YEARLY, "Y:${today.year}",
                "${today.year} 年报", yearStart, yearEnd
            ),
        )
    }

    /** 周期结束时（含其后 2 天内补生成）自动生成；force=true 立即生成当前周期（数据可能未完整） */
    suspend fun generateDueReports(force: Boolean): List<String> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()
        val made = ArrayList<String>()
        for (p in currentPeriods(today)) {
            val exists = dao.getReport(p.type, p.key) != null
            val inWindow = !today.isBefore(p.end) && !today.isAfter(p.end.plusDays(2))
            if (exists || !(force || inWindow)) continue
            val text = buildReportText(p)
            dao.putReport(
                ReportEntity(type = p.type, periodKey = p.key, generatedTs = now, text = text)
            )
            Notifications.notifyReport(
                context,
                "屏幕时间 · ${p.title.substringAfterLast(' ')}已生成",
                text.lines().take(3).joinToString("\n")
            )
            made.add(p.title)
        }
        return made
    }

    private suspend fun buildReportText(p: ReportPeriod): String {
        val from = p.start.toEpochDay()
        val to = p.end.toEpochDay()
        val days = p.start.datesUntil(p.end.plusDays(1)).count().toInt().coerceAtLeast(1)
        val total = dao.sessionsSumBetween(from, to)
        val pickups = dao.pickupsBetween(from, to)
        val battery = dao.batteryBetween(from, to)
        val top = dao.topSessionsBetween(from, to, 5)
        val prevTo = from - 1
        val prevFrom = prevTo - days + 1
        val prevTop = dao.topSessionsBetween(prevFrom, prevTo, 100).associate { it.packageName to it.totalMs }
        val pm = context.packageManager
        val sb = StringBuilder()
        sb.appendLine("期间：${p.start} ~ ${p.end}（$days 天）")
        sb.appendLine("总使用：${formatMinutesShort(total)} · 日均 ${formatMinutesShort(total / days)}")
        if (pickups > 0) sb.appendLine("拿起：$pickups 次")
        if (battery >= 1) sb.appendLine(String.format(Locale.US, "总耗电：%.0f mAh（模型估算）", battery))
        sb.appendLine()
        sb.appendLine("Top 应用（对比上一周期）：")
        if (top.isEmpty()) sb.appendLine("（无数据）")
        top.forEachIndexed { i, t ->
            val prev = prevTop[t.packageName]
            val delta = when {
                prev == null -> "（新增）"
                prev > 0 -> {
                    val chg = ((t.totalMs - prev).toDouble() / prev * 100).toInt()
                    if (chg >= 0) "（环比 +$chg%）" else "（环比 $chg%）"
                }
                else -> ""
            }
            val label = try {
                pm.getApplicationInfo(t.packageName, 0).loadLabel(pm).toString()
            } catch (e: Exception) {
                t.packageName.substringAfterLast('.')
            }
            sb.appendLine("${i + 1}. $label ${formatMinutesShort(t.totalMs)} $delta")
        }
        return sb.toString()
    }

    companion object {
        const val KEY_WATERMARK = "event_watermark"
        const val KEY_AGG_WIPED = "usage_daily_wiped_v16"
        const val KEY_BACKFILL = "backfill_natural_days_v18"
        const val KEY_SYSUID_FIX = "sysuid_labels_v18"
        const val SOURCE_EVENT = 0
        const val SOURCE_AGG_API = 1
        const val SOURCE_ROOT = 2
        const val REPORT_WEEKLY = "WEEKLY"
        const val REPORT_MONTHLY = "MONTHLY"
        const val REPORT_YEARLY = "YEARLY"
        private const val TAG = "STLog"
        private const val OVERLAP_MS = 2 * 60 * 60 * 1000L
        private const val MIN_SESSION_MS = 2_000L
        private const val GLOBAL_SNAPSHOT_KEY = "__global__"

        private val KNOWN_COMPS = setOf(
            "screen", "cpu", "audio", "video", "camera", "gnss", "wifi", "bluetooth", "bt",
            "wakelock", "sensors", "mobile_radio", "ambient_display", "idle", "flashlight", "phone",
        )

        fun uidKeyOf(uid: Int): String =
            if (uid >= 10000) "u0a" + (uid - 10000) else uid.toString()

        fun uidOf(uidKey: String): Int =
            if (uidKey.startsWith("u0a")) {
                10000 + (uidKey.removePrefix("u0a").toIntOrNull() ?: 0)
            } else {
                uidKey.toIntOrNull() ?: 0
            }

        fun encodeComps(comps: Map<String, Double>): String =
            comps.entries.joinToString("|") { "${it.key}=${it.value}" }

        fun decodeComps(text: String): Map<String, Double> =
            text.split("|").mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null
                else it.take(i) to (it.substring(i + 1).toDoubleOrNull() ?: return@mapNotNull null)
            }.toMap()

        fun formatMinutesShort(ms: Long): String {
            if (ms <= 0) return "0分钟"
            val totalMin = ms / 60000
            val h = totalMin / 60
            val m = totalMin % 60
            return if (h > 0) "${h}小时${m}分钟" else "${m}分钟"
        }

        fun parseDurationMs(s: String?): Long {
            if (s.isNullOrBlank()) return 0L
            var ms = 0L
            for (tok in s.trim().split(" ")) {
                val num = tok.takeWhile { it.isDigit() }.toLongOrNull() ?: continue
                when {
                    tok.endsWith("ms") -> ms += num
                    tok.endsWith("h") -> ms += num * 3_600_000L
                    tok.endsWith("m") -> ms += num * 60_000L
                    tok.endsWith("s") -> ms += num * 1_000L
                }
            }
            return ms
        }

        /** 解析 batterystats 的 “Estimated power use (mAh)” 段 */
        fun parseBatteryPower(lines: List<String>): BatteryParsed {
            var inPower = false
            var computedDrain = 0.0
            var actualLow = 0.0
            val uids = ArrayList<UidPower>()
            val globals = LinkedHashMap<String, Pair<Double, Long>>()
            val compRe = Regex("""^\s+([a-z_]+): ([0-9.]+)(?: apps: ([0-9.]+))?(?: duration: (.+))?\s*$""")
            val uidRe = Regex(
                """^\s*UID (\S+): ([0-9.]+)(?: fg: ([0-9.]+))?(?: bg: ([0-9.]+))?(?: cached: ([0-9.]+))?\s*\((.*)\)\s*$"""
            )
            val drainRe = Regex("""Capacity:\s*[0-9.]+,\s*Computed drain:\s*([0-9.]+),\s*actual drain:\s*([0-9.]+)""")
            val detailRe = Regex("""(?:^|\s)([a-z_]+)=([0-9.]+)(?: \(([^)]*)\))?""")
            for (raw in lines) {
                val line = raw.trimEnd()
                if (line.contains("Estimated power use (mAh)")) {
                    inPower = true
                    continue
                }
                if (!inPower) continue
                if (line.contains("In-memory") || line.contains("Statistics since last charge")) break
                val dm = drainRe.find(line)
                if (dm != null) {
                    computedDrain = dm.groupValues[1].toDoubleOrNull() ?: 0.0
                    actualLow = dm.groupValues[2].toDoubleOrNull() ?: 0.0
                    continue
                }
                val um = uidRe.find(line)
                if (um != null) {
                    val comps = HashMap<String, Double>()
                    val durs = HashMap<String, Long>()
                    val details = um.groupValues[6]
                    for (dm in detailRe.findAll(details)) {
                        val k = dm.groupValues[1]
                        val v = dm.groupValues[2].toDoubleOrNull() ?: continue
                        comps[k] = v
                        dm.groupValues[3]?.let { durs[k] = parseDurationMs(it) }
                    }
                    uids.add(
                        UidPower(
                            uidKey = um.groupValues[1],
                            totalMah = um.groupValues[2].toDoubleOrNull() ?: 0.0,
                            fg = um.groupValues[3].toDoubleOrNull() ?: 0.0,
                            bg = um.groupValues[4].toDoubleOrNull() ?: 0.0,
                            comps = comps,
                            durs = durs,
                        )
                    )
                    continue
                }
                val cm = compRe.find(line)
                if (cm != null) {
                    val mah = cm.groupValues[2].toDoubleOrNull() ?: continue
                    globals[cm.groupValues[1]] = Pair(mah, parseDurationMs(cm.groupValues[4]))
                }
            }
            return BatteryParsed(uids, globals, computedDrain, actualLow)
        }

        private val DUMP_EVENT_TYPES = mapOf(
            "ACTIVITY_RESUMED" to UsageEvents.Event.ACTIVITY_RESUMED,
            "ACTIVITY_PAUSED" to UsageEvents.Event.ACTIVITY_PAUSED,
            "ACTIVITY_STOPPED" to UsageEvents.Event.ACTIVITY_STOPPED,
            "SCREEN_INTERACTIVE" to UsageEvents.Event.SCREEN_INTERACTIVE,
            "SCREEN_NON_INTERACTIVE" to UsageEvents.Event.SCREEN_NON_INTERACTIVE,
            "KEYGUARD_HIDDEN" to UsageEvents.Event.KEYGUARD_HIDDEN,
            "KEYGUARD_SHOWN" to UsageEvents.Event.KEYGUARD_SHOWN,
        )

        /** 会话重建：RESUMED 开会话；PAUSED/息屏或下一个 RESUMED 关会话；
         *  跨本地午夜切分；短于 2 秒的碎片丢弃（推送拉起的秒级“使用”）。
         *  注意不能用 ACTIVITY_STOPPED 关会话：ColorOS 应用内切 Activity 恒为
         *  PAUSED(旧)→RESUMED(新)→STOPPED(旧) 同戳三连，STOPPED(旧) 与新会话同包，
         *  会把刚开的会话 0 秒关掉，导致多 Activity 应用整段丢时长（实测知乎丢 ~90%）。 */
        fun buildSessions(events: List<RawEvent>, windowStart: Long, windowEnd: Long): List<UsageSession> {
            val zone = ZoneId.systemDefault()
            val out = ArrayList<UsageSession>()
            var openPkg: String? = null
            var openTs = 0L

            fun close(at: Long) {
                val pkg = openPkg ?: return
                val start = openTs
                openPkg = null
                if (at <= start) return
                if (at - start < MIN_SESSION_MS) return
                var cur = start
                while (cur < at) {
                    val day = Instant.ofEpochMilli(cur).atZone(zone).toLocalDate()
                    val nextDayStart = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val end = minOf(at, nextDayStart)
                    out.add(UsageSession(packageName = pkg, startTs = cur, endTs = end, day = day.toEpochDay()))
                    cur = end
                }
            }

            for (ev in events) {
                when (ev.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        close(ev.ts)
                        openPkg = ev.packageName
                        openTs = ev.ts
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                    -> {
                        if (ev.packageName == openPkg || ev.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                            close(ev.ts)
                        }
                    }
                }
            }
            close(windowEnd)
            return out
        }

        /** 解析 root dumpsys 的事件行：time="yyyy-MM-dd HH:mm:ss" type=X package=Y */
        fun parseDumpsysEvents(lines: List<String>, zone: ZoneId): List<RawEvent> {
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            val re = Regex("""time="([^"]+)" type=([A-Z_]+) package=(\S+)""")
            val out = ArrayList<RawEvent>()
            for (line in lines) {
                val m = re.find(line) ?: continue
                val type = DUMP_EVENT_TYPES[m.groupValues[2]] ?: continue
                val ts = try {
                    LocalDateTime.parse(m.groupValues[1], fmt).atZone(zone).toInstant().toEpochMilli()
                } catch (e: Exception) {
                    continue
                }
                out.add(RawEvent(ts, type, m.groupValues[3]))
            }
            out.sortBy { it.ts }
            return out
        }

        /** 解析 checkin 的按天聚合行：package=X totalTimeUsed=<ms> lastTimeUsed=<epoch>。
         *  只匹配裸数字形式（In-memory 段是带引号的 HH:MM:SS，天然跳过）；
         *  日期按 lastTimeUsed 归属（已用抖音周累计 383 分钟精确校验过）。 */
        fun parseCheckinAggregates(lines: List<String>, zone: ZoneId): List<DailyUsageEntity> {
            val re = Regex("""package=(\S+) totalTimeUsed=(\d+) lastTimeUsed=(\d+)""")
            val rows = ArrayList<DailyUsageEntity>()
            for (line in lines) {
                val m = re.find(line) ?: continue
                val ms = m.groupValues[2].toLong()
                if (ms <= 0) continue
                val day = Instant.ofEpochMilli(m.groupValues[3].toLong()).atZone(zone).toLocalDate().toEpochDay()
                rows.add(DailyUsageEntity(m.groupValues[1], day, ms, SOURCE_ROOT))
            }
            return rows
        }
    }
}
