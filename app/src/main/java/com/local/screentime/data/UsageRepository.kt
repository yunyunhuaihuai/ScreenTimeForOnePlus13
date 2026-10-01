package com.local.screentime.data

import android.app.usage.UsageEvents
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.room.withTransaction
import com.topjohnwu.superuser.Shell
import com.local.screentime.Notifications
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

data class AppDisplay(val label: String, val frozen: Boolean)

data class UidPower(
    val uidKey: String,
    val totalMah: Double,
    val fg: Double,
    val bg: Double,
    val fgs: Double = 0.0,
    val comps: Map<String, Double>,
)

data class BatteryParsed(
    val uids: List<UidPower>,
    val globals: Map<String, Pair<Double, Long>>,
    val computedDrain: Double,
    val actualDrainLow: Double,
    /** 「自上次充电」统计窗口的起点（dump 里的 Start clock time），窗口可跨天甚至跨多天 */
    val windowStartTs: Long? = null,
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

    fun uidOfPackage(pkg: String): Int = uidCache[pkg] ?: run {
        val uid = try {
            context.packageManager.getPackageUid(pkg, 0)
        } catch (e: Exception) {
            -1
        }
        // 失败结果不落缓存：包可能稍后才装上/可见，缓存 -1 会永久映射不到
        if (uid >= 0) uidCache[pkg] = uid
        uid
    }

    private val catCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 手动分类覆盖：优先级最高；category=null 表示恢复自动 */
    fun setCategoryOverride(pkg: String, category: String?) {
        // 与 categoryOf 同口径容错：prefs 里存量脏数据不得让点击直接崩溃
        val obj = runCatching {
            JSONObject(prefs.getString("cat_overrides", null) ?: "{}")
        }.getOrElse { JSONObject() }
        if (category == null) obj.remove(pkg) else obj.put(pkg, category)
        prefs.edit().putString("cat_overrides", obj.toString()).apply()
        catCache.remove(pkg)
    }

    fun allCategories(): List<String> = listOf(
        "通讯社交", "影音娱乐", "游戏", "音乐音频", "资讯阅读", "AI 助手",
        "购物", "金融", "地图出行", "生活服务", "效率", "图像", "其他",
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
        return SYNC_MUTEX.withLock { syncNowLocked() }
    }

    /**
     * 轻量耗电快照采集：只跑 `dumpsys batterystats --charged` + 差分入库，不做全量同步。
     * 调用方：插电瞬间接收器（PowerEventReceiver）与 15 分钟兜底采样（BatteryCaptureWorker），
     * 目的是在系统充电重置前把累计值存下来，让「日汇总」不缺「上次同步～插电」段（v0.16.8）。
     * root 或 DUMP 权限（adb 可授）任一可用即可；都不可用时 dumpsys 输出解析为空，返回 false。
     */
    suspend fun captureBatteryNow(): Boolean {
        val ok = try {
            // 与 syncNow 共用进程级互斥（companion 单例锁）：15min 采样、插电抢拍与
            // 6h 全量同步里的 syncBattery 并发交错时，慢的那路会把旧观测值写回快照
            // （cumMah 倒退），污染下次差分基准
            SYNC_MUTEX.withLock { syncBattery(System.currentTimeMillis()) }
        } catch (e: Exception) {
            Log.w(TAG, "captureBatteryNow failed", e)
            false
        }
        if (!ok) Log.i(TAG, "capture: no data (root/DUMP 不可用或 dump 为空)")
        return ok
    }

    private fun hasDumpAccess(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.DUMP) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private suspend fun syncNowLocked(): SyncResult {
        val now = System.currentTimeMillis()

        // 一次性修正：存量耗电行里系统 UID 被写上了共享包名，清空交给 systemUidLabel 命名
        if (dao.getState(KEY_SYSUID_FIX) == null) {
            dao.clearSystemUidPackageNames()
            dao.putState(SyncStateEntity(KEY_SYSUID_FIX, 1))
        }

        // 1) root 全量 + 耗电（权威路径，见类头注释）
        //    getShell() 才会真正建立 su 通道：新进程首同步时 isAppGrantedRoot() 尚为 null
        //    （unknown），会被误判为无 root 而降级到 API/DUMP 兜底（真机实测：冷启动首同步
        //    恒降级、同进程第二次起才走 root，v0.16.10 修复）。这里同步等待 shell 建立后
        //    读权威状态；su 不可用时 getShell() 返回非 root shell，isRoot=false 正常降级。
        var rootUsed = false
        var rootSessionCount = 0
        try {
            val rootOk = try {
                Shell.getShell().isRoot
            } catch (e: Exception) {
                false
            }
            if (rootOk) {
                rootSessionCount = syncViaRoot()
                rootUsed = rootSessionCount > 0
                syncBattery(now)
            } else if (hasDumpAccess()) {
                // 无 root 但持有 DUMP（debug 构建 adb 可授）：应用侧 dumpsys 采集耗电，
                // 同样需要在系统充电重置前抢拍（v0.16.8）
                syncBattery(now)
            }
        } catch (e: Exception) {
            Log.w(TAG, "root sync failed", e)
        }

        // 2) API 事件流兜底：仅当 root 没拿到事件时才用它重建（ColorOS 会裁剪 queryEvents，
        //    丢 PAUSED 事件会使会话跨息屏虚高；root 正常时绝不能让裁剪数据入库/覆盖）
        val watermark = dao.getState(KEY_WATERMARK) ?: 0L
        // 修复：此前水位线只在兜底分支写入，root 装机上恒为 0；root 一旦失效，
        // windowStart 归零 → deleteSessionsStartingAfter(0) 删光 sessions 表，
        // 再用被裁剪的 queryEvents 重建 = 永久丢历史明细。
        // 现 root 成功也推进水位线，且兜底窗口下限钳到 API_MAX_LOOKBACK_MS：
        // queryEvents 保留期仅 ~7 天，删除更早的会话本就拿不回来，无意义且有丢数据风险。
        if (rootUsed) dao.putState(SyncStateEntity(KEY_WATERMARK, now))
        val windowStart = maxOf(watermark - OVERLAP_MS, now - API_MAX_LOOKBACK_MS).coerceAtLeast(0L)
        val events = if (rootUsed) emptyList() else reader.queryEvents(windowStart, now)
        var sessionCount = rootSessionCount
        if (!rootUsed) {
            val sessions = buildSessions(events, windowStart, now)
            sessionCount = sessions.size
            db.withTransaction {
                dao.deleteSessionsStartingAfter(windowStart)
                if (sessions.isNotEmpty()) dao.insertSessions(sessions)
                dao.putState(SyncStateEntity(KEY_WATERMARK, now))
            }
            // day_stats/unlock_stats 是整行 REPLACE：API 事件残缺时算出的拿起/首开次数偏小，
            // 只有 root 路径没拿到事件时才允许 API 兜底（v0.16.7）
            runCatching { syncDayEventStats(events, ZoneId.systemDefault()) }
        }
        runCatching { dao.deleteUnlockLauncherRows() }
        // 3) 一次性清理 usage_daily：ColorOS 的 API 日聚合是 ~16:40 锚点的滚动 24h 桶，
        //    无法按自然日拆分，v0.16 起展示只认会话表，历史桶值全部作废
        if (dao.getState(KEY_AGG_WIPED) == null) {
            db.withTransaction {
                dao.clearUsageDaily()
                dao.putState(SyncStateEntity(KEY_AGG_WIPED, 1))
            }
        }

        refreshAppRegistry()
        runCatching { generateDueReports(force = false) }
        Log.d(TAG, "sync: root=$rootUsed apiEvents=${events.size} sessions=$sessionCount")
        return SyncResult.Ok(windowStart, now, events.size, sessionCount, rootUsed)
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
                !ai.enabled -> AppDisplay(label, true)
                suspended -> AppDisplay(label, true)
                else -> AppDisplay(label, false)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // 包不可见（可见性过滤/已卸载）：回退到注册表快照
            val cached = dao.appInfo(packageName)
            AppDisplay(cached?.label ?: packageName, true)
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

    /** 方案 B：root 下 dumpsys 未裁剪全量（事件 + checkin 按天聚合）。返回重建出的会话数，0=未拿到事件 */
    private suspend fun syncViaRoot(): Int {
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
                dao.deleteSessionsStartingAfter(start)
                if (sessions.isNotEmpty()) dao.insertSessions(sessions)
            }
            runCatching { syncDayEventStats(events, zone) }
            Log.d(TAG, "root: events=${events.size} sessions=${sessions.size}")
            return sessions.size
        }
        Log.d(TAG, "root: events=0")
        return 0
    }

    /**
     * root 读取 batterystats：每 UID 与整机组件耗电，快照差分累计（充满自动重置）。
     *
     * 归属规则（重要）：`--charged` 是「自上次充电以来」的窗口，起点通常不是当天 0 点，且会跨越
     * 午夜甚至多天（本机实测 2026-09-17 18:05 才重置，上一个窗口跨了 09-16 整个晚上）。
     * 累计计数器的两次观测之差 = 该时间段内真实发生的用量，所以按「上次快照 → 本次」区间与各
     * 本地日的重叠比例归属；否则会把昨天的耗电整体记到今天（曾表现为「今天没拍照却 988mAh」）。
     *
     * 原子性（v0.17.0 重构，P0-2）：旧实现「读旧快照 → 算差分 → 先推进快照 → 再写 daily」，
     * 快照与 daily 是两次独立写入，进程在中间被杀会让该段差分永久丢失。现在拆成两个阶段：
     *  1) Room 事务外：shell dumpsys + 纯解析（dumpsys 绝不能进事务：阻塞 DB 连接、拖长持锁时间）；
     *  2) 单一 db.withTransaction：批量读旧状态 → 纯差分（BatteryLedger）→ 批量写
     *     battery_daily / battery_global → 最后推进快照；任何一步失败整体回滚。
     */
    private suspend fun syncBattery(now: Long): Boolean {
        // —— 阶段一：shell + 解析（事务外） ——
        val lines = try {
            Shell.cmd("dumpsys batterystats --charged").exec().out
        } catch (e: Exception) {
            return false
        }
        val parsed = parseBatteryPower(lines)
        if (parsed.uids.isEmpty() && parsed.globals.isEmpty()) return false
        val zone = ZoneId.systemDefault()

        // —— 阶段二：单一事务（调用方已持 SYNC_MUTEX，差分基于串行化的快照状态） ——
        try {
            applyBatteryLedgerTx(db, dao, parsed, now, zone, pkgOf = ::resolveBatteryOwnerPackage)
        } catch (e: Exception) {
            // 事务已整体回滚：快照未推进、daily 未写入，本轮差分作废，
            // 下一轮采集会基于旧快照重算（宁可少记一轮，不可半提交）
            Log.w(TAG, "battery tx failed (rolled back)", e)
            return false
        }
        Log.d(
            TAG,
            "battery: uids=${parsed.uids.size} globals=${parsed.globals.size} drain=${parsed.computedDrain} " +
                "window=${parsed.windowStartTs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() } ?: "?"}",
        )
        return true
    }

    /** UID → 耗电归属包名。系统 UID（<10000）是共享的，挂到任意包名会误导
     *  （如 UID 1000 显示成“安全中心”），保持 null 交给 systemUidLabel。 */
    private fun resolveBatteryOwnerPackage(uid: Int): String? {
        if (uid < 10000) return null
        return try {
            context.packageManager.getPackagesForUid(uid)?.firstOrNull { !it.isNullOrBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** 拿起次数（KEYGUARD_HIDDEN 计数）与“解锁后首开应用”统计 */
    private suspend fun syncDayEventStats(events: List<RawEvent>, zone: ZoneId) {
        if (events.isEmpty()) return
        val res = computeDayEventStats(events, zone)
        // 事件窗口首日通常不从零点起，而 day_stats/unlock_stats 是整行 REPLACE，
        // 写残窗值会把该日既有计数覆盖偏小；随 ~7 天 root 窗口滑动，首日恒残缺。
        // 该日更早的同步里曾位于窗口内被完整写入，跳过首日即可保住正确值。
        val firstDay = Instant.ofEpochMilli(events.minOf { it.ts }).atZone(zone).toLocalDate().toEpochDay()
        val statRows = res.pickups.filterKeys { it != firstDay }.map { (d, c) -> DayStatsEntity(d, c) }
        if (statRows.isNotEmpty()) dao.upsertDayStats(statRows)
        val unlockRows = res.unlockFirst.filterKeys { it != firstDay }
            .flatMap { (d, m) -> m.map { (p, c) -> UnlockStatsEntity(d, p, c) } }
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
            // 补生成窗口从 2 天放宽到 7 天：ColorOS 杀后台激进，年报/周报若恰逢
            // 窗口内一次同步都没跑成，2 天窗口会永久错过生成
            val inWindow = !today.isBefore(p.end) && !today.isAfter(p.end.plusDays(7))
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
        val pkgSums = HashMap<String, Long>()
        var total = 0L
        for (d in from..to) {
            for (r in dailyRows(d)) {
                pkgSums[r.packageName] = (pkgSums[r.packageName] ?: 0L) + r.totalMs
                total += r.totalMs
            }
        }
        val top = pkgSums.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { DailyRow(it.key, 0L, it.value) }
        val pickups = dao.pickupsBetween(from, to)
        val battery = dao.batteryBetween(from, to)
        val prevTo = from - 1
        val prevFrom = prevTo - days + 1
        val prevPkgSums = HashMap<String, Long>()
        for (d in prevFrom..prevTo) {
            for (r in dailyRows(d)) {
                prevPkgSums[r.packageName] = (prevPkgSums[r.packageName] ?: 0L) + r.totalMs
            }
        }
        val prevTop = prevPkgSums
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
        // 进程级同步互斥（v0.17.0 修正）：必须放在 companion 而不是实例上。
        // MainActivity / SyncWorker / BatteryCaptureWorker / PowerEventReceiver 各自 new
        // UsageRepository，实例锁互不感知 —— v0.16.7 的「已解决并发双计」只对同一实例成立，
        // 跨实例并发（15min 采样 vs 6h 对账 vs 插电抢拍）仍可能基于同一份旧快照各算一份差分。
        internal val SYNC_MUTEX = Mutex()

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

        /** API 兜底路径允许回看的最长窗口：queryEvents 保留期 ~7 天，再早的删了也重建不回 */
        private const val API_MAX_LOOKBACK_MS = 6 * 24 * 60 * 60 * 1000L
        private const val MIN_SESSION_MS = 2_000L
        // GLOBAL_SNAPSHOT_KEY / KNOWN_COMPS 已随差分计算移入 BatteryLedger（v0.17.0）

        /** uid ↔ uidKey 支持多用户：ColorOS 分身/隐私空间是 u999a*（曾因只认 u0a 前缀，
         *  分身应用的耗电行 packageName 恒为 null，UI 永远映射不到包）。 */
        fun uidKeyOf(uid: Int): String {
            val user = uid / 100_000
            val appId = uid % 100_000
            return if (appId >= 10000) {
                if (user == 0) "u0a" + (appId - 10000) else "u${user}a" + (appId - 10000)
            } else {
                appId.toString()
            }
        }

        fun uidOf(uidKey: String): Int {
            val m = Regex("""^u(\d+)a(\d+)$""").find(uidKey) ?: return uidKey.toIntOrNull() ?: 0
            val user = m.groupValues[1].toIntOrNull() ?: 0
            val idx = m.groupValues[2].toIntOrNull() ?: return 0
            return user * 100_000 + 10_000 + idx
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

        /** 增量对应的观测区间起点：常规用上次快照时间；重置/首次用统计窗口起点（更贴近真实发生时间）。
         *  纯函数，移入 companion 以便 JVM 单测覆盖（v0.16.9）。 */
        fun deltaFrom(lastTs: Long?, reset: Boolean, windowStartTs: Long?, now: Long): Long {
            val w = windowStartTs ?: 0L
            val validW = if (w in 1 until now) w else null
            return when {
                lastTs == null -> validW ?: now
                reset -> maxOf(lastTs, validW ?: lastTs)
                else -> lastTs
            }
        }

        /** 把区间 [from,to] 按本地日重叠比例拆成 (epochDay, 权重)；from 非法时全归到 to 所在日 */
        fun dayWeights(from: Long, to: Long, zone: ZoneId): List<Pair<Long, Double>> {
            val start = if (from in 1 until to) from else to
            val end = if (to > start) to else start + 1
            val total = (end - start).coerceAtLeast(1L)
            val out = ArrayList<Pair<Long, Double>>(2)
            var cursor = start
            while (cursor < end) {
                val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
                val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val seg = (minOf(nextMidnight, end) - cursor).coerceAtLeast(1L)
                out.add(date.toEpochDay() to seg.toDouble() / total)
                cursor += seg
            }
            return out
        }

        /** 解析 batterystats 的 “Estimated power use (mAh)” 段 */
        fun parseBatteryPower(lines: List<String>): BatteryParsed {
            var inPower = false
            var computedDrain = 0.0
            var actualLow = 0.0
            var windowStartTs: Long? = null
            val uids = ArrayList<UidPower>()
            val globals = LinkedHashMap<String, Pair<Double, Long>>()
            // 统计窗口起点：本机 dump 形如 "  Start clock time: 2026-09-17-18-05-06"（本地时间）
            val windowRe = Regex("""Start clock time:\s*(\d{4})-(\d{2})-(\d{2})-(\d{2})-(\d{2})-(\d{2})""")
            val compRe = Regex("""^\s+([a-z_]+): ([0-9.]+)(?: apps: ([0-9.]+))?(?: duration: (.+))?\s*$""")
            // UID 行 total 之后、括号之前是可选标注字段序列（本机实测顺序 fg:/bg:/fgs:/cached:，
            // 子集与顺序不保证）。旧正则只认 fg/bg/cached 三个，含 fgs:（前台服务耗电）的行
            // 整行失配被静默丢弃 → bilibili 等前台服务应用的快照停更，夜间充电重置后差分变负
            // 被丢，表现为“耗电消失”（v0.16.9 修复，附真机原始行回放测试 BatteryParseTest）。
            // 因此把 total 后到 "(" 之间的标注序列整体捕获，再按 label 提取 fg/bg。
            val uidRe = Regex("""^\s*UID (\S+): ([0-9.]+)([^()]*)\((.*)\)\s*$""")
            val fgRe = Regex("""(?:^|\s)fg: ([0-9.]+)""")
            val bgRe = Regex("""(?:^|\s)bg: ([0-9.]+)""")
            val fgsRe = Regex("""(?:^|\s)fgs: ([0-9.]+)""")
            val drainRe = Regex("""Capacity:\s*[0-9.]+,\s*Computed drain:\s*([0-9.]+),\s*actual drain:\s*([0-9.]+)""")
            val detailRe = Regex("""(?:^|\s)([a-z_]+)=([0-9.]+)(?: \(([^)]*)\))?""")
            for (raw in lines) {
                val line = raw.trimEnd()
                if (windowStartTs == null) {
                    val wm = windowRe.find(line)
                    if (wm != null) {
                        windowStartTs = try {
                            LocalDateTime.of(
                                wm.groupValues[1].toInt(), wm.groupValues[2].toInt(), wm.groupValues[3].toInt(),
                                wm.groupValues[4].toInt(), wm.groupValues[5].toInt(), wm.groupValues[6].toInt(),
                            ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
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
                    val details = um.groupValues[4]
                    for (dm in detailRe.findAll(details)) {
                        val k = dm.groupValues[1]
                        val v = dm.groupValues[2].toDoubleOrNull() ?: continue
                        comps[k] = v
                    }
                    val headerTags = um.groupValues[3]
                    uids.add(
                        UidPower(
                            uidKey = um.groupValues[1],
                            totalMah = um.groupValues[2].toDoubleOrNull() ?: 0.0,
                            fg = fgRe.find(headerTags)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0,
                            bg = bgRe.find(headerTags)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0,
                            fgs = fgsRe.find(headerTags)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0,
                            comps = comps,
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
            return BatteryParsed(uids, globals, computedDrain, actualLow, windowStartTs)
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

        data class DayEventStatsResult(
            val pickups: Map<Long, Long>,
            val unlockFirst: Map<Long, Map<String, Long>>
        )

        /**
         * 纯函数：根据事件流计算每日拿起次数与解锁后应用统计。
         * 1. 拿起防抖：连续 <= 2000ms 内的 KEYGUARD_HIDDEN 视为同一次解锁（防抖去重）。
         * 2. 解锁应用匹配：
         *    a. 优先捕获解锁后 (0, 15000] ms 内新启动的非 Launcher 应用（桌面启动场景）；
         *    b. 若未新启动应用，回溯检查 [-2000, 0] ms 内伴随解锁恢复的前台非 Launcher 应用（直接解锁恢复场景）。
         */
        fun computeDayEventStats(events: List<RawEvent>, zone: ZoneId): DayEventStatsResult {
            if (events.isEmpty()) return DayEventStatsResult(emptyMap(), emptyMap())
            val sorted = events.sortedBy { it.ts }
            val pickups = HashMap<Long, Long>()
            val unlockFirst = HashMap<Long, MutableMap<String, Long>>()

            // 1. 提取所有有效的解锁时刻（带 2000ms 防抖）
            data class UnlockPoint(val index: Int, val ts: Long, val day: Long)
            val unlockPoints = ArrayList<UnlockPoint>()
            var lastUnlockTs = -10_000L

            for (i in sorted.indices) {
                val ev = sorted[i]
                if (ev.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) {
                    if (ev.ts - lastUnlockTs > 2000L) {
                        val day = Instant.ofEpochMilli(ev.ts).atZone(zone).toLocalDate().toEpochDay()
                        unlockPoints.add(UnlockPoint(i, ev.ts, day))
                        pickups[day] = (pickups[day] ?: 0L) + 1
                        lastUnlockTs = ev.ts
                    }
                }
            }

            // 2. 为每个解锁点匹配对应的应用
            for (up in unlockPoints) {
                // a. 前向搜索：解锁后 (0, 15_000] ms 内首次 RESUMED 的非 Launcher 应用
                var matchedPkg: String? = null
                for (j in (up.index + 1) until sorted.size) {
                    val ev = sorted[j]
                    val gap = ev.ts - up.ts
                    if (gap > 15_000L) break
                    // 若中途发生另一次有效解锁（>2000ms），停止本轮前向搜索
                    if (ev.eventType == UsageEvents.Event.KEYGUARD_HIDDEN && gap > 2000L) break
                    if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                        val isLauncher = ev.packageName.contains("launcher", ignoreCase = true)
                        if (!isLauncher) {
                            matchedPkg = ev.packageName
                            break
                        }
                    }
                }

                // b. 若未在前向找到（用户可能直接解锁回到之前正在使用的 App），回溯 [-2000, 0] ms
                if (matchedPkg == null) {
                    for (j in up.index downTo maxOf(0, up.index - 10)) {
                        val ev = sorted[j]
                        val gap = up.ts - ev.ts
                        if (gap > 2000L) break
                        if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                            val isLauncher = ev.packageName.contains("launcher", ignoreCase = true)
                            if (!isLauncher) {
                                matchedPkg = ev.packageName
                                break
                            }
                        }
                    }
                }

                if (matchedPkg != null) {
                    val m = unlockFirst.getOrPut(up.day) { mutableMapOf() }
                    m[matchedPkg] = (m[matchedPkg] ?: 0L) + 1
                }
            }

            return DayEventStatsResult(pickups, unlockFirst)
        }

        /** 解析 root dumpsys 的事件行：time="yyyy-MM-dd HH:mm:ss" type=X package=Y */
        fun parseDumpsysEvents(lines: List<String>, zone: ZoneId): List<RawEvent> {
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            val re = Regex("""time="([^"]+)" type=([A-Z_]+) package=(\S+)""")
            val out = ArrayList<RawEvent>()
            var isUser0 = true
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("user=")) {
                    // dumpsys usagestats 按用户分段打印，形如 "user=0" 或 "user=999"
                    isUser0 = (trimmed == "user=0")
                    continue
                }
                val m = re.find(line) ?: continue
                val type = DUMP_EVENT_TYPES[m.groupValues[2]] ?: continue

                // 锁屏/亮屏等系统级状态由系统按用户广播，user=999 等分身用户会产生重复事件导致拿起次数翻倍与切断会话，仅提取主用户 (user=0)；
                // 应用级事件（RESUMED/PAUSED/STOPPED）属于用户实际操作，保留分身用户以统计双开应用时长
                if (!isUser0 && (type == UsageEvents.Event.KEYGUARD_HIDDEN ||
                        type == UsageEvents.Event.SCREEN_INTERACTIVE ||
                        type == UsageEvents.Event.SCREEN_NON_INTERACTIVE)) {
                    continue
                }

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
    }
}
