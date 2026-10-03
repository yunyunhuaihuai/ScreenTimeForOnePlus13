package com.local.screentime.data

import android.app.usage.UsageEvents
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.room.withTransaction
import com.local.screentime.privileged.PrivilegedCommandRunner
import com.local.screentime.privileged.RootCommandRunner
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
        /** battery 分支是否成功（P1-1：usage 成功不代表 battery 成功，二者独立健康） */
        val batteryOk: Boolean = true,
    ) : SyncResult()
}

/** 耗电采集走的通道（root shell 直读 / 应用进程 DUMP 权限），用于持久化「实际同步结果」 */
enum class BatterySource { ROOT, DUMP }

/** 单次 batterystats 采集/差分的结果（P1-1/P1-10：区分语义，供 Worker 决定是否 retry） */
sealed class BatteryCaptureOutcome {
    /** shell 成功、解析非空、差分事务提交成功。observedTs = dump 实际观测时刻
     *  （commandEnd；此前拿锁前的 now 被当成观测时刻，跨午夜会记错日） */
    data class Success(val observedTs: Long) : BatteryCaptureOutcome()

    /** shell 成功但输出为合法空（VALID_EMPTY_OUTPUT：batterystats 尚未就绪等系统状态）——
     *  等下一周期自然重试即可 */
    data object NoData : BatteryCaptureOutcome()

    /** shell 成功、输出非空但解析不出 power 段（PARSE_FAILED：格式变化/非目标输出）——
     *  CaptureDiagnostics 已落盘供诊断 */
    data class ParseFailed(val detail: String) : BatteryCaptureOutcome()

    /** root 与 DUMP 权限都不可用——重试无意义，需要用户侧动作（root 授权/adb 授权） */
    data object SourceUnavailable : BatteryCaptureOutcome()

    /** shell 执行异常/非零退出或差分事务异常（已整体回滚）——真正的临时错误，可 retry */
    data class Failed(val stage: String, val error: String) : BatteryCaptureOutcome()
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
    /** actual drain 的上界（真机实测形如 1954-2072；单值形式时与 low 相同）。
     *  v0.17.1（P1-3）：此前只取第一个数字，把 low 当成唯一实际值。 */
    val actualDrainHigh: Double? = null,
    /** 「自上次充电」统计窗口的起点（dump 里的 Start clock time），窗口可跨天甚至跨多天 */
    val windowStartTs: Long? = null,
    /** 电池容量（Capacity: 5920）。注意：是电池容量，不是本窗口耗电，禁止与 UID sum 直接比较 */
    val batteryCapacityMah: Double? = null,
    /** 传入 parser 的原始行数（P1-2 完整性诊断用） */
    val rawLineCount: Int = 0,
    /** drain 概要行（Capacity/Computed drain/actual drain）是否真的解析到。
     *  v0.18（A）：computed=0 不再一概等同解析失败——刚充满的合法帧 drain 就是 0；
     *  只有整行缺失（结构截断）才算异常。手工构造的测试数据默认视为已解析。 */
    val computedDrainPresent: Boolean = true,
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
class UsageRepository(
    private val context: Context,
    /** 高权限命令后端（P1-3）：默认 libsu root，行为与旧直用 Shell.cmd 完全一致 */
    private val runner: PrivilegedCommandRunner = RootCommandRunner(),
    /** 测试注入用：默认单例文件库；Robolectric 入口级测试传 in-memory 库（规避
     *  Robolectric 文件库跨线程连接 shadow 的已知问题，且不落盘） */
    dbOverride: AppDatabase? = null,
) {

    private val db = dbOverride ?: AppDatabase.get(context)

    /** 可注入时钟（测试用）：采集路径的 attempt/command/observed 时刻统一从这里取，
     *  生产路径等价于 System.currentTimeMillis()。观测时刻绝不复用拿锁前的旧值。 */
    internal var nowMs: () -> Long = System::currentTimeMillis
    private val dao = db.usageDao()
    private val reader = UsageStatsReader(context)
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val displayCache = java.util.concurrent.ConcurrentHashMap<String, AppDisplay>()
    private val uidCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 采样健康状态：每次进入采集路径（持锁）都从 sync_state **重新读取**——
     *  多实例（MainActivity/SyncWorker/BatteryCaptureWorker/PowerEventReceiver 各自
     *  new 仓库）交替写入时，实例缓存会把其他实例刚写入的更新指标覆盖回旧值（P2-2）。 */
    @Volatile
    private var captureHealth: CaptureHealth.State = CaptureHealth.State()

    /** 从 sync_state 重新加载健康状态；调用前提：处于采集路径（持 SYNC_MUTEX） */
    private suspend fun reloadCaptureHealth() {
        captureHealth = CaptureHealth.State(
            lastAttemptTs = dao.getState(KEY_CAP_ATTEMPT),
            lastSuccessTs = dao.getState(KEY_CAP_SUCCESS),
            lastFailureCode = dao.getState(KEY_CAP_FAIL_CODE)?.toInt() ?: CaptureHealth.FAIL_NONE,
            lastFailureTs = dao.getState(KEY_CAP_FAIL_TS),
            maxObservedCaptureGapMs = dao.getState(KEY_CAP_MAX_GAP),
        )
    }

    private suspend fun saveCaptureHealth() {
        val s = captureHealth
        s.lastAttemptTs?.let { dao.putState(SyncStateEntity(KEY_CAP_ATTEMPT, it)) }
        s.lastSuccessTs?.let { dao.putState(SyncStateEntity(KEY_CAP_SUCCESS, it)) }
        dao.putState(SyncStateEntity(KEY_CAP_FAIL_CODE, s.lastFailureCode.toLong()))
        s.lastFailureTs?.let { dao.putState(SyncStateEntity(KEY_CAP_FAIL_TS, it)) }
        s.maxObservedCaptureGapMs?.let { dao.putState(SyncStateEntity(KEY_CAP_MAX_GAP, it)) }
    }

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
        return try {
            SYNC_MUTEX.withLock { syncNowLocked() }
        } catch (e: Exception) {
            // 保留原抛出行为；失败原因进诊断状态（P1-5）
            prefs.edit().putString(PREF_LAST_SYNC_ERROR, "${e.javaClass.simpleName}: ${e.message}").apply()
            throw e
        }
    }

    /**
     * 轻量耗电快照采集：只跑 `dumpsys batterystats --charged` + 差分入库，不做全量同步。
     * 调用方：插电瞬间接收器（PowerEventReceiver）与 15 分钟兜底采样（BatteryCaptureWorker），
     * 目的是在系统充电重置前把累计值存下来，让「日汇总」不缺「上次同步～插电」段（v0.16.8）。
     * root 或 DUMP 权限（adb 可授）任一可用即可；都不可用时 dumpsys 输出解析为空，返回 false。
     */
    /**
     * 轻量耗电快照采集：只跑 `dumpsys batterystats --charged` + 差分入库，不做全量同步。
     * 调用方：插电瞬间接收器（PowerEventReceiver）与 15 分钟兜底采样（BatteryCaptureWorker），
     * 目的是在系统充电重置前把累计值存下来，让「日汇总」不缺「上次同步～插电」段（v0.16.8）。
     * root 或 DUMP 权限（adb 可授）任一可用即可。
     *
     * 时间语义（v0.18，P2-2）：attempt/commandStart/commandEnd/observed 四个时刻分开
     * 记录，全部在**拿到进程锁之后**取值——拿锁前的 now 既不是观测时刻，跨午夜还会把
     * 增量记错日。健康状态持锁重新从 sync_state 读取（多实例不互相覆盖）。
     * 每次尝试都写入有界采集日志 capture_log（P1-1：diagnosticJson 输出）。
     */
    suspend fun captureBatteryNow(): BatteryCaptureOutcome {
        return try {
            SYNC_MUTEX.withLock {
                val attemptTs = nowMs()
                reloadCaptureHealth()
                captureHealth = CaptureHealth.onAttempt(captureHealth, attemptTs)
                saveCaptureHealth()
                // root 与 DUMP 权限都不可用时重试无意义（需用户侧动作），直接归类
                val rootOk = runner.isRootAvailable()
                if (!rootOk && !hasDumpAccess()) {
                    val ts = nowMs()
                    captureHealth = CaptureHealth.onFailure(captureHealth, ts, CaptureHealth.FAIL_NO_SOURCE)
                    saveCaptureHealth()
                    // 无源也是一次 battery failure（P1-1 健康线）
                    dao.putState(SyncStateEntity(KEY_LAST_BATTERY_FAIL_TS, ts))
                    logCapture(attemptTs, ts, ts, BatterySource.ROOT, BatteryCaptureOutcome.SourceUnavailable,
                        parsed = null, detail = "root 与 DUMP 权限均不可用")
                    BatteryCaptureOutcome.SourceUnavailable
                } else {
                    val commandStartTs = nowMs()
                    val outcome = syncBattery(
                        attemptTs, commandStartTs,
                        if (rootOk) BatterySource.ROOT else BatterySource.DUMP,
                    )
                    captureHealth = when (outcome) {
                        is BatteryCaptureOutcome.Success ->
                            CaptureHealth.onSuccess(captureHealth, outcome.observedTs)
                        else ->
                            CaptureHealth.onFailure(captureHealth, nowMs(), CaptureHealth.failCodeOf(outcome))
                    }
                    saveCaptureHealth()
                    outcome
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "captureBatteryNow failed", e)
            runCatching {
                val ts = nowMs()
                logCapture(ts, ts, ts, BatterySource.ROOT,
                    BatteryCaptureOutcome.Failed("capture", "${e.javaClass.simpleName}: ${e.message}"),
                    parsed = null, detail = "capture 异常：${e.message}")
            }
            BatteryCaptureOutcome.Failed("capture", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 有界采集日志（capture_log，上限 64 条）：成功/各类失败都记录，供诊断回放 */
    private suspend fun logCapture(
        attemptTs: Long,
        commandStartTs: Long,
        commandEndTs: Long,
        source: BatterySource,
        outcome: BatteryCaptureOutcome,
        parsed: BatteryParsed?,
        detail: String,
    ) {
        runCatching {
            dao.insertCaptureLog(
                CaptureLogEntity(
                    ts = commandEndTs,
                    attemptTs = attemptTs,
                    commandStartTs = commandStartTs,
                    commandEndTs = commandEndTs,
                    zoneId = ZoneId.systemDefault().id,
                    source = if (source == BatterySource.ROOT) 1 else 2,
                    parserVersion = CaptureDiagnostics.PARSER_VERSION,
                    ledgerVersion = BatteryLedger.LEDGER_VERSION,
                    outcome = when (outcome) {
                        is BatteryCaptureOutcome.Success -> "SUCCESS"
                        is BatteryCaptureOutcome.NoData -> "NO_DATA"
                        is BatteryCaptureOutcome.ParseFailed -> "PARSE_FAILED"
                        is BatteryCaptureOutcome.SourceUnavailable -> "SOURCE_UNAVAILABLE"
                        is BatteryCaptureOutcome.Failed -> "FAILED:${outcome.stage}"
                    },
                    epochWindowStartTs = parsed?.windowStartTs,
                    uidCount = parsed?.uids?.size ?: 0,
                    globalCount = parsed?.globals?.size ?: 0,
                    computedDrainMah = parsed?.computedDrain ?: 0.0,
                    actualDrainLowMah = parsed?.actualDrainLow?.takeIf { it > 0 },
                    actualDrainHighMah = parsed?.actualDrainHigh,
                    sumUidTotalMah = parsed?.uids?.sumOf { it.totalMah } ?: 0.0,
                    detail = detail,
                )
            )
            dao.pruneCaptureLog(CAPTURE_LOG_LIMIT)
        }
    }

    private fun hasDumpAccess(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.DUMP) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private suspend fun syncNowLocked(): SyncResult {
        val now = nowMs()
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
        var batteryOk = true
        // battery 分支与 captureBatteryNow 共用同一健康状态机（P2-2 统一：syncNow 的
        // 耗电采集同样更新 cap_* 健康线；attempt/观测时刻都在锁内取值）
        reloadCaptureHealth()
        captureHealth = CaptureHealth.onAttempt(captureHealth, now)
        saveCaptureHealth()
        var batteryOutcome: BatteryCaptureOutcome? = null
        try {
            val rootOk = runner.isRootAvailable()
            if (rootOk) {
                rootSessionCount = syncViaRoot()
                rootUsed = rootSessionCount > 0
                batteryOutcome = syncBattery(now, nowMs(), BatterySource.ROOT)
                batteryOk = batteryOutcome is BatteryCaptureOutcome.Success
            } else if (hasDumpAccess()) {
                // 无 root 但持有 DUMP（debug 构建 adb 可授）：应用侧 dumpsys 采集耗电，
                // 同样需要在系统充电重置前抢拍（v0.16.8）
                batteryOutcome = syncBattery(now, nowMs(), BatterySource.DUMP)
                batteryOk = batteryOutcome is BatteryCaptureOutcome.Success
            }
        } catch (e: Exception) {
            Log.w(TAG, "root sync failed", e)
            prefs.edit().putString(PREF_LAST_SYNC_ERROR, "root sync: ${e.javaClass.simpleName}: ${e.message}").apply()
        }
        // battery 实际尝试过才更新结果健康线（usage 阶段异常时 battery 未发生，不得记为无源）
        when (val bo = batteryOutcome) {
            null -> Unit
            is BatteryCaptureOutcome.Success ->
                captureHealth = CaptureHealth.onSuccess(captureHealth, bo.observedTs)
            else ->
                captureHealth = CaptureHealth.onFailure(captureHealth, nowMs(), CaptureHealth.failCodeOf(bo))
        }
        saveCaptureHealth()

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
            // API 事件流兜底（P0-2）：ColorOS queryEvents 被深度裁剪，绝不能让残缺 API
            // 数据删除 root 建立的权威历史——只清理自己的旧 fallback 数据，且新 API 会话
            // 与 ROOT/LEGACY 权威会话时间重叠的一律丢弃
            val sessions = buildSessions(events, windowStart, now, SESSION_SOURCE_API)
            sessionCount = sessions.size
            applyApiFallbackSessions(db, dao, sessions, windowStart, now)
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
        // 持久化「实际同步结果」（P1-5 / P1-1）：usage 与 battery 两条独立健康线——
        // UsageStats 成功不等于 batterystats 成功，diagnosticJson 分别呈现。
        // v0.18（P2-2）：只有真正观测到数据（root 事件或 API 事件非空）才写成功并清
        // 错误——此前 source 错误/空 API/捕获异常之后仍无条件写成功，健康线失真
        val usageObserved = rootSessionCount > 0 || (!rootUsed && events.isNotEmpty())
        if (usageObserved) {
            dao.putState(SyncStateEntity(KEY_LAST_USAGE_SOURCE, if (rootUsed) 1L else 2L))
            dao.putState(SyncStateEntity(KEY_LAST_USAGE_SUCCESS_TS, now))
            prefs.edit().putString(PREF_LAST_SYNC_ERROR, null).apply()
        } else {
            val reason = "无观测（rootUsed=$rootUsed rootSessions=$rootSessionCount apiEvents=${events.size}）"
            prefs.edit().putString(PREF_LAST_SYNC_ERROR, "sync: $reason").apply()
            dao.putState(SyncStateEntity(KEY_LAST_USAGE_FAIL_TS, now))
            Log.w(TAG, "sync: $reason")
        }
        Log.d(TAG, "sync: root=$rootUsed apiEvents=${events.size} sessions=$sessionCount batteryOk=$batteryOk")
        return SyncResult.Ok(windowStart, now, events.size, sessionCount, rootUsed, batteryOk)
    }

    /**
     * 展示口径（P0-4 唯一口径）：effective usage = 会话日聚合与 usage_daily 回补值
     * 逐包/逐日取 MAX。历史日（2026-09-04～09-12）的会话被旧版 STOPPED bug 丢
     * 30~90%，由回补值兜底。周报/月报/年报经 dao.effectiveUsageBetween 共用同一
     * 口径，保证报告总量/Top 应用与首页完全一致。
     */
    suspend fun dailyRows(day: Long): List<DailyRow> =
        dao.effectiveUsageDay(day).filter { it.totalMs > 0 }

    suspend fun dayTotalMs(day: Long): Long = dailyRows(day).sumOf { it.totalMs }

    suspend fun sessionsOfDay(day: Long): List<UsageSession> = dao.sessionsOfDay(day)

    suspend fun batteryDailyRows(day: Long): List<BatteryDailyEntity> = dao.batteryDailyRows(day)

    suspend fun batteryGlobalRows(day: Long): List<BatteryGlobalEntity> = dao.batteryGlobalRows(day)

    /**
     * 把当天列表里的包映射到其 UID 耗电记录（v0.17.1 重写，P0-3）。
     *
     * 旧实现 `byUid[uidKeyOf(uid)]` 绕过了写入侧的「不猜 shared UID」约束：
     *  - 共享 UID（多包同 UID）的耗电行会被挂到查询的任意一个包上（双计/误导）；
     *  - 分身场景（真机确认 QQ=u0a351+u999a351、微信=u0a350+u999a350 各两行）
     *    只剩 user0 行，user999 行被静默忽略。
     *
     * 新实现：逐行解析归属（与写入侧 ownerPackageOf 同一判据——UID 唯一对应一个
     * 包才归属；系统 UID/共享 UID 恒 null），再把同 package 的多个「唯一归属 UID」
     * **求和聚合**。usage 会话层只有 packageName、无法区分 user，因此 package 级
     * 口径必须聚合，而不是二选一静默丢弃；shared UID 仍然不拆分。
     */
    suspend fun batteryForPackages(
        day: Long,
        packages: List<String>,
    ): Map<String, BatteryDailyEntity> =
        groupBatteryRowsByOwner(dao.batteryDailyRows(day), ::resolveRowOwnerPackage, packages.toHashSet())

    /** 逐行归属解析：uidKey → 唯一归属包名（系统 UID / 共享 UID → null） */
    private fun resolveRowOwnerPackage(uidKey: String): String? {
        val uid = uidOf(uidKey)
        if (uid < 10000) return null
        return try {
            ownerPackageOf(uid, context.packageManager.getPackagesForUid(uid))
        } catch (e: Exception) {
            null
        }
    }

    suspend fun dayStats(day: Long): DayStatsEntity? = dao.getDayStats(day)

    suspend fun unlockTop(day: Long, limit: Int): List<UnlockStatsEntity> = dao.unlockTop(day, limit)

    suspend fun latestReports(): List<ReportEntity> = dao.latestReports()

    fun hasPermission(): Boolean = reader.hasPermission()

    /**
     * 首页紧凑状态行的数据（v0.18，C）：「数据更新至 / 采集健康」可核验状态。
     * usage 与 battery 两条健康线取最新者作为「更新至」；无观测/长期无成功时
     * 明确呈现而不是把未知说成健康。长时间无成功（> 2 个名义同步周期）提示
     * 可能中断（Android 15 强停后应用自身无法恢复采集，需要用户打开一次）。
     */
    suspend fun dataHealthSummary(): DataHealthSummary = DataHealthSummary(
        lastUsageSuccessTs = dao.getState(KEY_LAST_USAGE_SUCCESS_TS),
        lastUsageFailTs = dao.getState(KEY_LAST_USAGE_FAIL_TS),
        lastBatterySuccessTs = dao.getState(KEY_LAST_BATTERY_SUCCESS_TS),
        lastBatteryFailTs = dao.getState(KEY_LAST_BATTERY_FAIL_TS),
        maxCaptureGapMs = dao.getState(KEY_CAP_MAX_GAP),
        lastUsageSource = when (dao.getState(KEY_LAST_USAGE_SOURCE)) {
            1L -> "ROOT"; 2L -> "API"; else -> null
        },
        lastBatterySource = when (dao.getState(KEY_LAST_BATTERY_SOURCE)) {
            1L -> "ROOT"; 2L -> "DUMP"; else -> null
        },
        lostTailFromTs = dao.getState(KEY_BATTERY_LOST_TAIL_FROM),
        lostTailToTs = dao.getState(KEY_BATTERY_LOST_TAIL_TO),
    )

    data class DataHealthSummary(
        val lastUsageSuccessTs: Long?,
        val lastUsageFailTs: Long?,
        val lastBatterySuccessTs: Long?,
        val lastBatteryFailTs: Long?,
        val maxCaptureGapMs: Long?,
        val lastUsageSource: String?,
        val lastBatterySource: String?,
        val lostTailFromTs: Long?,
        val lostTailToTs: Long?,
    )


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

    /**
     * 诊断导出：某天全部会话 + 聚合 + 持久化的同步/采样健康状态（P1-1/P1-5/P2）。
     * perUid=true 时附按耗电排序的逐 UID 调试段（rawUidKey/归属/组件，截取 top 30 控制体积）；
     * 不包含原始 dump（隐私与体积），需要 raw 时用审计脚本现场取证。
     */
    suspend fun diagnosticJson(day: Long, perUid: Boolean = false): String {
        val sessions = dao.sessionsOfDay(day)
        val rows = dailyRows(day)
        val zone = ZoneId.systemDefault()
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun tsString(ts: Long?): String? =
            ts?.let { Instant.ofEpochMilli(it).atZone(zone).format(fmt) }

        // 持久化的「实际同步结果」：不现场 probe（Shell.isAppGrantedRoot 冷启动为 null，
        // 无法代表上次同步实际用了什么通道，v0.16.10 已证明）
        val usageSource = when (dao.getState(KEY_LAST_USAGE_SOURCE)) {
            1L -> "ROOT"
            2L -> "API"
            else -> "unknown"
        }
        val batterySource = when (dao.getState(KEY_LAST_BATTERY_SOURCE)) {
            1L -> "ROOT"
            2L -> "DUMP"
            else -> "unknown"
        }
        val capture = CaptureHealth.State(
            lastAttemptTs = dao.getState(KEY_CAP_ATTEMPT),
            lastSuccessTs = dao.getState(KEY_CAP_SUCCESS),
            lastFailureCode = dao.getState(KEY_CAP_FAIL_CODE)?.toInt() ?: CaptureHealth.FAIL_NONE,
            lastFailureTs = dao.getState(KEY_CAP_FAIL_TS),
            maxObservedCaptureGapMs = dao.getState(KEY_CAP_MAX_GAP),
        )

        val root = JSONObject()
        root.put("app", context.packageName)
        root.put("generatedAt", Instant.now().toString())
        root.put("day", LocalDate.ofEpochDay(day).toString())
        root.put("hasUsagePermission", reader.hasPermission())
        root.put("lastUsageSource", usageSource)
        root.put("lastUsageSuccessTs", tsString(dao.getState(KEY_LAST_USAGE_SUCCESS_TS)))
        root.put("lastSyncError", prefs.getString(PREF_LAST_SYNC_ERROR, null) ?: JSONObject.NULL)
        val health = JSONObject()
        health.put("lastCaptureAttemptTs", tsString(capture.lastAttemptTs))
        health.put("lastCaptureSuccessTs", tsString(capture.lastSuccessTs))
        health.put("lastCaptureFailure", if (capture.lastFailureCode == CaptureHealth.FAIL_NONE) JSONObject.NULL else
            "${CaptureHealth.failLabel(capture.lastFailureCode)} @ ${tsString(capture.lastFailureTs)}")
        health.put(
            "maxObservedCaptureGapMs",
            capture.maxObservedCaptureGapMs ?: JSONObject.NULL,
        )
        root.put("captureHealth", health)
        // battery 独立健康线（P1-1）+ 最近一帧解析画像（P1-2/P1-4）+ 账本不变量样本（P1-7）
        val battery = JSONObject()
        battery.put("source", batterySource)
        battery.put("lastBatterySuccessTs", tsString(dao.getState(KEY_LAST_BATTERY_SUCCESS_TS)))
        battery.put("lastBatteryFailureTs", tsString(dao.getState(KEY_LAST_BATTERY_FAIL_TS)))
        battery.put("lastBatteryFailure", prefs.getString(PREF_LAST_BATTERY_FAIL, null) ?: JSONObject.NULL)
        battery.put("epochWindowStartTs", tsString(dao.getState(KEY_BATTERY_WINDOW_START_TS)))
        battery.put(
            "lastCaptureDiagnostics",
            prefs.getString(PREF_LAST_BATTERY_DIAG, null)?.let { CaptureDiagnostics.fromJson(it)?.toJson() }
                ?: JSONObject.NULL,
        )
        battery.put("ledgerInconsistent", prefs.getString(PREF_LAST_LEDGER_INCONSISTENT, null) ?: JSONObject.NULL)
        // v0.18（C）：覆盖缺口证据——充电重置导致的「不可恢复尾段」（上次成功采集到
        // 新窗口起点之间的估算耗电已被系统重置丢弃）
        val lostFrom = dao.getState(KEY_BATTERY_LOST_TAIL_FROM)
        val lostTo = dao.getState(KEY_BATTERY_LOST_TAIL_TO)
        if (lostFrom != null && lostTo != null) {
            battery.put("lostTail", JSONObject().apply {
                put("fromTs", tsString(lostFrom))
                put("toTs", tsString(lostTo))
                put("note", "该段估算耗电已被充电重置丢弃，不可回补")
            })
        }
        // v0.18（C）：有界采集日志（最近 8 条）——attempt/command/observed 时刻、
        // 通道、结果、epoch 证据。采集健康（本段）与账本现存异常（下方 audit）分开呈现
        val logArr = JSONArray()
        for (e in dao.recentCaptureLogs(8)) {
            logArr.put(JSONObject().apply {
                put("observedTs", tsString(e.ts))
                put("attemptTs", tsString(e.attemptTs))
                put("commandStartTs", tsString(e.commandStartTs))
                put("commandEndTs", tsString(e.commandEndTs))
                put("zoneId", e.zoneId)
                put("source", if (e.source == 1) "ROOT" else "DUMP")
                put("outcome", e.outcome)
                put("epochWindowStartTs", e.epochWindowStartTs?.let { tsString(it) } ?: JSONObject.NULL)
                put("uidCount", e.uidCount)
                put("globalCount", e.globalCount)
                put("computedDrainMah", e.computedDrainMah)
                put("parserVersion", e.parserVersion)
                put("ledgerVersion", e.ledgerVersion)
                put("detail", e.detail)
            })
        }
        battery.put("captureLog", logArr)
        // v0.18（I/C）：账本现存异常全库审计——与采集健康分开；「历史告警是否已解除」
        // 以本扫描为准（只报当前仍违反的行，历史已修复的行不再出现）
        val audit = BatteryLedger.ledgerInconsistencies(dao.allBatteryDaily())
        battery.put(
            "ledgerAudit",
            JSONObject().apply {
                put("violatingRows", audit.size)
                put("samples", JSONArray(audit.take(5)))
            },
        )
        root.put("battery", battery)
        if (perUid) {
            // 逐 UID 调试段：归属结果（唯一包 / 共享 / 系统）+ 组件明细，top 30 by totalMah
            val uidArr = JSONArray()
            for (r in dao.batteryDailyRows(day).sortedByDescending { it.totalMah }.take(30)) {
                val uid = uidOf(r.uidKey)
                val owner = if (uid >= 10000) try {
                    ownerPackageOf(uid, context.packageManager.getPackagesForUid(uid))
                } catch (e: Exception) {
                    null
                } else null
                val o = JSONObject()
                o.put("rawUidKey", r.uidKey)
                o.put(
                    "attribution",
                    when {
                        uid < 10000 -> "system"
                        owner != null -> owner
                        else -> sharedUidLabel(r.uidKey) ?: "shared"
                    },
                )
                o.put("totalMah", r.totalMah)
                o.put(
                    "components",
                    JSONObject().apply {
                        put("screen", r.screenMah); put("cpu", r.cpuMah); put("audio", r.audioMah)
                        put("video", r.videoMah); put("camera", r.cameraMah); put("gnss", r.gnssMah)
                        put("wifi", r.wifiMah); put("mobile", r.mobileMah); put("bt", r.btMah)
                        put("wakelock", r.wakelockMah); put("sensors", r.sensorsMah)
                        put("flashlight", r.flashlightMah); put("ambient_display", r.ambientDisplayMah)
                        put("idle", r.idleMah); put("phone", r.phoneMah); put("other", r.otherMah)
                    },
                )
                uidArr.put(o)
            }
            root.put("perUidBattery", uidArr)
        }
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
                val text = runner.exec("cat /data/local/tmp/stlog_backfill.json").stdout.joinToString("\n")
                if (text.length > 10) {
                    val arr = JSONObject(text).getJSONArray("rows")
                    val list = ArrayList<DailyUsageEntity>(arr.length())
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(DailyUsageEntity(o.getString("p"), o.getLong("d"), o.getLong("ms"), SOURCE_ROOT))
                    }
                    if (list.isNotEmpty()) {
                        db.withTransaction { dao.upsertDaily(list) }
                        runner.exec("rm -f /data/local/tmp/stlog_backfill.json")
                        dao.putState(SyncStateEntity(KEY_BACKFILL, 1))
                        Log.d(TAG, "backfill: ${list.size} rows")
                    }
                }
            }
        }
        // 保活：把自己加进 Doze 白名单，降低 ColorOS 后台清理概率
        runCatching {
            runner.exec("dumpsys deviceidle whitelist +${context.packageName}")
        }
        // --checkin 在 root 下输出格式不同（解析恒为 0 行），聚合已由方案 A 覆盖，跳过以缩短同步时间
        val dumpRes = runner.exec("dumpsys usagestats")
        val dump = if (dumpRes.success) dumpRes.stdout else run {
            Log.w(TAG, "usagestats shell exit=${dumpRes.exitCode}: ${dumpRes.stderrFirstLine}")
            emptyList()
        }
        val events = parseDumpsysEvents(dump, zone)
        if (events.isNotEmpty()) {
            val start = events.minOf { it.ts }
            // 可信采集上界（P1-2/E）：最后事件时间 ≠ 前台结束时间。仍前台的开放会话
            // 以采集实际时刻为暂定尾段，后续每次 root 全量重建自然纠正
            val observedAt = nowMs()
            val sessions = buildSessions(events, start, observedAt, SESSION_SOURCE_ROOT)
            db.withTransaction {
                // root 全量重建：ROOT 可修正对应窗口内的一切来源（含此前的 API fallback）
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
     *
     * 成功后持久化「实际使用的通道」（P1-5：last_battery_source = ROOT/DUMP），
     * 失败原因写入诊断 prefs——diagnosticJson 输出真实历史状态而非现场探测。
     *
     * v0.18 补充：attemptTs/commandStartTs 分开传入，观测时刻 = commandEnd（shell
     * 返回时刻），差分日归属与成功时间戳一律用它；每个出口写入有界采集日志
     * （capture_log）；诊断门控先识别 epoch 再应用适用判据（见 CaptureDiagnostics）；
     * 解析健康基线在差分事务内协调推进；RESET 成功时记录「不可恢复尾段」。
     */
    internal suspend fun syncBattery(
        attemptTs: Long,
        commandStartTs: Long,
        source: BatterySource,
    ): BatteryCaptureOutcome {
        // 成功时间戳在事务后写；先读旧值供 RESET 时的「不可恢复尾段」记录
        val prevBatterySuccessTs = dao.getState(KEY_LAST_BATTERY_SUCCESS_TS)
        // —— 阶段一：shell + 解析（事务外） ——
        // P1-10：区分 NO_PRIVILEGE（isRootAvailable=false，调用方处理）/ COMMAND_FAILED
        // （exit≠0）/ VALID_EMPTY_OUTPUT（exit=0 且 stdout 空）/ PARSE_FAILED（有输出但无 power 段）
        val res = try {
            runner.exec("dumpsys batterystats --charged")
        } catch (e: Exception) {
            val ts = nowMs()
            recordBatteryFailure("shell", "${e.javaClass.simpleName}: ${e.message}")
            logCapture(attemptTs, commandStartTs, ts, source,
                BatteryCaptureOutcome.Failed("shell", e.message ?: "shell 异常"), parsed = null,
                detail = "shell 异常：${e.message}")
            return BatteryCaptureOutcome.Failed("shell", "${e.javaClass.simpleName}: ${e.message}")
        }
        val commandEndTs = nowMs()
        if (!res.success) {
            val err = res.stderrFirstLine ?: ""
            Log.w(TAG, "battery shell exit=${res.exitCode}: $err")
            recordBatteryFailure("shell", "exit=${res.exitCode} $err")
            logCapture(attemptTs, commandStartTs, commandEndTs, source,
                BatteryCaptureOutcome.Failed("shell", "exit=${res.exitCode} $err"), parsed = null,
                detail = "exit=${res.exitCode} $err")
            return BatteryCaptureOutcome.Failed("shell", "exit=${res.exitCode} $err")
        }
        val lines = res.stdout
        val parsed = parseBatteryPower(lines)
        // 完整性诊断（P1-2）：无论成败先落盘，diagnosticJson 可回查本帧解析画像。
        // epoch 先于门控识别（v0.18）：诊断判据按窗口状态隔离
        val prevWindowStartTs = dao.getState(KEY_BATTERY_WINDOW_START_TS)
        val epoch = BatteryLedger.epochStateOf(prevWindowStartTs, parsed.windowStartTs)
        val diag = CaptureDiagnostics.fromParsed(
            parsed,
            baselineUidCount = dao.getState(KEY_PARSER_BASELINE_UIDS),
            hadWindowBaseline = dao.getState(KEY_PARSER_HAD_WINDOW) != null,
            epoch = epoch,
        )
        prefs.edit().putString(PREF_LAST_BATTERY_DIAG, diag.toJson().toString()).apply()
        if (parsed.uids.isEmpty() && parsed.globals.isEmpty()) {
            return if (lines.isEmpty()) {
                Log.i(TAG, "battery: shell 成功但输出为空（VALID_EMPTY_OUTPUT，batterystats 未就绪？）")
                recordBatteryFailure("parse", "合法空输出（exit=0，0 行）")
                logCapture(attemptTs, commandStartTs, commandEndTs, source, BatteryCaptureOutcome.NoData,
                    parsed = parsed, detail = "合法空输出（exit=0，0 行）")
                BatteryCaptureOutcome.NoData
            } else {
                Log.w(TAG, "battery: 输出 ${lines.size} 行但解析不出 power 段（PARSE_FAILED）")
                recordBatteryFailure("parser", "PARSE_FAILED: ${lines.size} 行无可解析内容")
                logCapture(attemptTs, commandStartTs, commandEndTs, source,
                    BatteryCaptureOutcome.ParseFailed("${lines.size} 行无可解析内容"), parsed = parsed,
                    detail = "PARSE_FAILED: ${lines.size} 行无可解析内容")
                BatteryCaptureOutcome.ParseFailed("stdout ${lines.size} 行无可解析内容")
            }
        }
        // parser anomaly 门控（P1-2）：禁止静默推进部分快照——fgs 字段失配史的教训。
        // 拒绝时基线/epoch 标记/账本一概不动（协调推进原则）
        if (diag.anomalies.isNotEmpty()) {
            Log.w(TAG, "battery parser anomaly: ${diag.anomalies} —— 本帧不提交")
            recordBatteryFailure("parser", "anomaly: ${diag.anomalies.joinToString(";")}")
            logCapture(attemptTs, commandStartTs, commandEndTs, source,
                BatteryCaptureOutcome.Failed("parser", diag.anomalies.joinToString(";")), parsed = parsed,
                detail = "anomaly: ${diag.anomalies.joinToString(";")}")
            return BatteryCaptureOutcome.Failed("parser", diag.anomalies.joinToString(";"))
        }
        val zone = ZoneId.systemDefault()

        // —— 阶段二：单一事务（调用方已持 SYNC_MUTEX，差分基于串行化的快照状态） ——
        // epoch 标记（上一帧窗口起点）在事务内读取、与新帧比较并在事务内推进（P0-1/P0-4）；
        // 解析健康基线同事务推进（v0.18：成功提交才接受新基线）
        val out: BatteryLedger.Result = try {
            applyBatteryLedgerTx(
                db, dao, parsed, commandEndTs, zone,
                pkgOf = ::resolveBatteryOwnerPackage,
                prevWindowStartTs = prevWindowStartTs,
                newBaselineUidCount = parsed.uids.size.toLong(),
                hadWindowBaseline = parsed.windowStartTs != null,
            )
        } catch (e: Exception) {
            // 事务已整体回滚：快照未推进、daily 未写入、epoch 标记未推进、
            // 解析基线未推进，本轮差分作废，下一轮采集会基于完整旧状态重算
            // （宁可少记一轮，不可半提交）
            Log.w(TAG, "battery tx failed (rolled back)", e)
            recordBatteryFailure("tx", "${e.javaClass.simpleName}: ${e.message}")
            logCapture(attemptTs, commandStartTs, commandEndTs, source,
                BatteryCaptureOutcome.Failed("tx", "${e.javaClass.simpleName}: ${e.message}"), parsed = parsed,
                detail = "tx 失败已回滚：${e.message}")
            return BatteryCaptureOutcome.Failed("tx", "${e.javaClass.simpleName}: ${e.message}")
        }
        // 账本一致性不变量（P1-7）：任意单一组件 > total+容差 → LEDGER_INCONSISTENT（仅诊断，不阻断）。
        // 跨帧合并（真机回归发现的覆写缺陷）：干净帧不得抹掉此前帧的检出
        val violations = BatteryLedger.ledgerInconsistencies(out.dailyRows)
        prefs.edit().putString(
            PREF_LAST_LEDGER_INCONSISTENT,
            BatteryLedger.mergeLedgerInconsistencies(
                prefs.getString(PREF_LAST_LEDGER_INCONSISTENT, null),
                violations,
            ),
        ).apply()
        if (violations.isNotEmpty()) {
            Log.w(TAG, "ledger invariant violations=${violations.size} first=${violations.first()}")
        }
        // epoch RESET 成功提交：记录「不可恢复尾段」——上次成功采集（旧 epoch）到新窗口
        // 起点（≈充电时刻）之间的估算耗电已被系统重置永久丢弃，覆盖状态必须如实呈现
        if (out.epochChanged) {
            val lostTo = parsed.windowStartTs
            if (prevBatterySuccessTs != null && lostTo != null && lostTo > prevBatterySuccessTs) {
                dao.putState(SyncStateEntity(KEY_BATTERY_LOST_TAIL_FROM, prevBatterySuccessTs))
                dao.putState(SyncStateEntity(KEY_BATTERY_LOST_TAIL_TO, lostTo))
            }
        }
        dao.putState(SyncStateEntity(KEY_LAST_BATTERY_SUCCESS_TS, commandEndTs))
        dao.putState(SyncStateEntity(KEY_LAST_BATTERY_SOURCE, if (source == BatterySource.ROOT) 1L else 2L))
        prefs.edit().putString(PREF_LAST_BATTERY_FAIL, null).apply()
        logCapture(attemptTs, commandStartTs, commandEndTs, source, BatteryCaptureOutcome.Success(commandEndTs),
            parsed = parsed,
            detail = "uids=${parsed.uids.size} epochChanged=${out.epochChanged}")
        Log.d(
            TAG,
            "battery: uids=${parsed.uids.size} globals=${parsed.globals.size} drain=${parsed.computedDrain} " +
                "src=$source epochChanged=${out.epochChanged} " +
                "window=${parsed.windowStartTs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() } ?: "?"}",
        )
        return BatteryCaptureOutcome.Success(commandEndTs)
    }

    /** 采集失败的持久化记录（人类可读原因进 prefs；时间戳进 sync_state，供 P1-1 健康键拆分） */
    private suspend fun recordBatteryFailure(stage: String, error: String) {
        prefs.edit().putString(PREF_LAST_BATTERY_FAIL, "[$stage] $error").apply()
        dao.putState(SyncStateEntity(KEY_LAST_BATTERY_FAIL_TS, nowMs()))
    }

    /**
     * UID → 耗电归属包名（P1-2）：UID 唯一对应一个包才归属；共享 UID（多个包共用）
     * **不猜**——把整机耗电复制给多个 App 会双计，挂给任意第一个包会误导。
     * 共享 UID 保持 packageName=null，UI 经 sharedUidLabel 展示包列表。
     * 系统 UID（<10000）本就是共享的，交给 systemUidLabel。
     */
    private fun resolveBatteryOwnerPackage(uid: Int): String? =
        ownerPackageOf(uid, try {
            context.packageManager.getPackagesForUid(uid)
        } catch (e: Exception) {
            null
        })

    /** 共享 UID 的展示名（动态解析包列表，不落库、不猜归属）。唯一包名/系统 UID 返回 null。 */
    suspend fun sharedUidLabel(uidKey: String): String? {
        val uid = uidOf(uidKey)
        if (uid < 10000) return null
        val pkgs = try {
            context.packageManager.getPackagesForUid(uid)?.filter { !it.isNullOrBlank() }
        } catch (e: Exception) {
            null
        } ?: return "共享 UID（$uidKey）"
        return when {
            pkgs.isEmpty() -> "共享 UID（$uidKey）"
            else -> "共享 UID：" + pkgs.joinToString(" / ")
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

    /**
     * 报告生成（v0.17.0 重设计，P0-3）。
     *
     * 自动路径（force=false）：为「最近已完整结束的周期」生成正式报告（finalized=true）。
     * 既有记录若生成于周期结束之前（旧版在周期最后一天提前生成的"正式"报告、或手动
     * 预览）视为过早，以正式报告覆盖；按 ReportPeriods.backfillLimit 有界回溯，补上
     * 因后台被杀错过的周期（旧实现的 7 天补生成窗口因 currentPeriods 切换而从未生效）。
     * 仅最新周期推送通知，更早的补生成静默进入历史列表，避免升级首日通知轰炸。
     *
     * force=true（手动）：额外为当前进行中的周期生成预览（finalized=false，数据不完整）。
     */
    suspend fun generateDueReports(force: Boolean): List<String> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = nowMs()
        val made = ArrayList<String>()
        for (type in listOf(ReportPeriods.REPORT_WEEKLY, ReportPeriods.REPORT_MONTHLY, ReportPeriods.REPORT_YEARLY)) {
            // 逐周期检查（P1-12）：已有正式报告的 continue 到更早周期，缺失/过早的补生成
            for ((shift, p) in ReportPeriods.backfillPlan(today, type, lookup = { t, k -> dao.getReport(t, k) }, zone)) {
                val text = buildReportText(p)
                dao.putReport(
                    ReportEntity(type = p.type, periodKey = p.key, generatedTs = now, text = text, finalized = true)
                )
                if (shift == 0L) {
                    Notifications.notifyReport(
                        context,
                        "屏幕时间 · ${p.title.substringAfterLast(' ')}已生成",
                        text.lines().take(3).joinToString("\n")
                    )
                }
                made.add(p.title)
            }
        }
        if (force) {
            for (p in ReportPeriods.currentPreview(today)) {
                val text = buildReportText(p)
                dao.putReport(
                    ReportEntity(type = p.type, periodKey = p.key, generatedTs = now, text = text, finalized = false)
                )
                Notifications.notifyReport(
                    context,
                    "屏幕时间 · ${p.title.substringAfterLast(' ')}预览已生成（周期未结束）",
                    text.lines().take(3).joinToString("\n")
                )
                made.add(p.title + "（预览）")
            }
        }
        return made
    }

    private suspend fun buildReportText(p: ReportPeriod): String {
        val from = p.start.toEpochDay()
        val to = p.end.toEpochDay()
        val days = p.start.datesUntil(p.end.plusDays(1)).count().toInt().coerceAtLeast(1)
        // 统一口径（P0-4）：报告与首页 dailyRows 共用 effective usage
        // （sessions 与 usage_daily 回补值逐包/逐日取 max），不再单独读 sessions 表
        val pkgRows = dao.effectiveUsageBetween(from, to)
        val total = pkgRows.sumOf { it.totalMs }
        val top = pkgRows.take(5)
        val pickups = dao.pickupsBetween(from, to)
        val battery = dao.batteryBetween(from, to)
        val prevTo = from - 1
        val prevFrom = prevTo - days + 1
        val prevPkgSums = dao.effectiveUsageBetween(prevFrom, prevTo).associate { it.packageName to it.totalMs }
        val pm = context.packageManager
        val sb = StringBuilder()
        sb.appendLine("期间：${p.start} ~ ${p.end}（$days 天）")
        if (!p.finalized) sb.appendLine("⚠️ 预览：本周期尚未结束，以下数据不含剩余时间的用量")
        sb.appendLine("总使用：${formatMinutesShort(total)} · 日均 ${formatMinutesShort(total / days)}")
        if (pickups > 0) sb.appendLine("拿起：$pickups 次")
        // P1-11 语义修正：batteryBetween = SUM(battery_daily.totalMah) = UID 归因耗电
        // （UID total 本身已含其 screen/cpu 等组件），不等于整机实际耗电/computedDrain/
        // 电池容量；禁止与 battery_global 求和相加（组件与 UID 归因存在重叠语义）
        if (battery >= 1) sb.appendLine(String.format(Locale.US, "应用归因耗电：%.0f mAh（UID 模型估算，非整机实际耗电）", battery))
        sb.appendLine()
        sb.appendLine("Top 应用（对比上一周期）：")
        if (top.isEmpty()) sb.appendLine("（无数据）")
        top.forEachIndexed { i, t ->
            val prev = prevPkgSums[t.packageName]
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

        // —— 持久化诊断状态（P1-1/P1-5，sync_state 持久化，diagnosticJson 输出） ——
        // usage 与 battery 是两条独立健康线（P1-1）：UsageStats 成功不代表 batterystats 成功
        const val KEY_LAST_USAGE_SOURCE = "last_usage_source"      // 1=ROOT 2=API
        const val KEY_LAST_BATTERY_SOURCE = "last_battery_source"  // 1=ROOT 2=DUMP
        const val KEY_LAST_USAGE_SUCCESS_TS = "last_usage_success_ts"
        const val KEY_LAST_BATTERY_SUCCESS_TS = "last_battery_success_ts"
        const val KEY_LAST_BATTERY_FAIL_TS = "last_battery_fail_ts"
        const val KEY_BATTERY_WINDOW_START_TS = "battery_window_start_ts" // batterystats epoch 标记（P0-1）
        // parser 完整性基线（P1-2）：不设固定 UID 阈值，用本设备近期健康解析做相对参照
        const val KEY_PARSER_BASELINE_UIDS = "parser_baseline_uid_count"
        const val KEY_PARSER_HAD_WINDOW = "parser_had_window_baseline"
        // 人类可读的诊断文本（prefs，sync_state 只能存 Long）
        const val PREF_LAST_SYNC_ERROR = "last_sync_error"
        const val PREF_LAST_BATTERY_FAIL = "last_battery_fail"
        const val PREF_LAST_BATTERY_DIAG = "last_battery_diag"               // CaptureDiagnostics JSON
        const val PREF_LAST_LEDGER_INCONSISTENT = "last_ledger_inconsistent" // LEDGER_INCONSISTENT 样本
        const val KEY_CAP_ATTEMPT = "cap_last_attempt_ts"
        const val KEY_CAP_SUCCESS = "cap_last_success_ts"
        const val KEY_CAP_FAIL_CODE = "cap_last_fail_code"
        const val KEY_CAP_FAIL_TS = "cap_last_fail_ts"
        const val KEY_CAP_MAX_GAP = "cap_max_gap_ms"
        // v0.18（C）：usage 独立失败线 + 充电重置不可恢复尾段（覆盖缺口证据）
        const val KEY_LAST_USAGE_FAIL_TS = "last_usage_fail_ts"
        const val KEY_BATTERY_LOST_TAIL_FROM = "battery_lost_tail_from_ts"
        const val KEY_BATTERY_LOST_TAIL_TO = "battery_lost_tail_to_ts"
        /** capture_log 有界保留条数（P2-2：控制磁盘占用） */
        const val CAPTURE_LOG_LIMIT = 64
        const val SOURCE_EVENT = 0
        const val SOURCE_AGG_API = 1
        const val SOURCE_ROOT = 2
        // 报告类型常量与周期解析已移入 ReportPeriods（v0.17.0）
        private const val TAG = "STLog"
        private const val OVERLAP_MS = 2 * 60 * 60 * 1000L

        /** API 兜底路径允许回看的最长窗口：queryEvents 保留期 ~7 天，再早的删了也重建不回 */
        private const val API_MAX_LOOKBACK_MS = 6 * 24 * 60 * 60 * 1000L
        private const val MIN_SESSION_MS = 2_000L
        /** 同包相邻会话合并间隔（E）：= dump 的时间精度（秒级）。Activity 切换的
         *  同戳三连把一次使用拆成多个 1 秒段，先按同包微间隔合并再套最小阈值，
         *  避免「Activity 切换短段被无差别丢弃」；跨包/大间隔不受影响 */
        private const val SHORT_MERGE_GAP_MS = 2_000L
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

        /**
         * 共享 UID 归属判据（P1-2 纯函数）：UID 唯一对应一个非空包名才归属该包；
         * 多包共享（sharedUserId / 同 UID 的关联进程）与解析失败一律返回 null（不猜）。
         */
        fun ownerPackageOf(uid: Int, packagesForUid: Array<String>?): String? {
            if (uid < 10000) return null
            val pkgs = packagesForUid?.filter { !it.isNullOrBlank() }
            return if (pkgs?.size == 1) pkgs[0] else null
        }

        /**
         * 耗电行按唯一归属包分组并聚合（P0-3 纯函数）。
         * ownerOf：uidKey → 唯一归属包名（null = 系统/共享 UID，不可归属，整行跳过）。
         * 同一包的多个唯一归属 UID（分身场景 user0 与 user999 各一行）求和为一个展示实体；
         * uidKey 以 "+" 连接以示聚合，禁止静默丢弃其中任意一份。
         */
        fun groupBatteryRowsByOwner(
            rows: List<BatteryDailyEntity>,
            ownerOf: (String) -> String?,
            wanted: Set<String>,
        ): Map<String, BatteryDailyEntity> {
            val grouped = LinkedHashMap<String, MutableList<BatteryDailyEntity>>()
            for (row in rows) {
                val owner = ownerOf(row.uidKey) ?: continue
                if (owner !in wanted) continue
                grouped.getOrPut(owner) { ArrayList() }.add(row)
            }
            return grouped.mapValues { (pkg, list) -> sumBatteryDaily(list, pkg) }
        }

        /** 同包多 UID 聚合：全部数值字段求和；uidKey 为各 UID 的 "+" 连接 */
        fun sumBatteryDaily(rows: List<BatteryDailyEntity>, packageName: String?): BatteryDailyEntity {
            require(rows.isNotEmpty()) { "sumBatteryDaily: empty rows" }
            val uidKeys = rows.map { it.uidKey }.distinct()
            return BatteryDailyEntity(
                uidKey = if (uidKeys.size == 1) uidKeys[0] else uidKeys.joinToString("+"),
                day = rows.first().day,
                packageName = packageName,
                totalMah = rows.sumOf { it.totalMah },
                fgMah = rows.sumOf { it.fgMah },
                bgMah = rows.sumOf { it.bgMah },
                fgsMah = rows.sumOf { it.fgsMah },
                screenMah = rows.sumOf { it.screenMah },
                cpuMah = rows.sumOf { it.cpuMah },
                audioMah = rows.sumOf { it.audioMah },
                videoMah = rows.sumOf { it.videoMah },
                cameraMah = rows.sumOf { it.cameraMah },
                gnssMah = rows.sumOf { it.gnssMah },
                wifiMah = rows.sumOf { it.wifiMah },
                btMah = rows.sumOf { it.btMah },
                wakelockMah = rows.sumOf { it.wakelockMah },
                sensorsMah = rows.sumOf { it.sensorsMah },
                mobileMah = rows.sumOf { it.mobileMah },
                flashlightMah = rows.sumOf { it.flashlightMah },
                ambientDisplayMah = rows.sumOf { it.ambientDisplayMah },
                idleMah = rows.sumOf { it.idleMah },
                phoneMah = rows.sumOf { it.phoneMah },
                otherMah = rows.sumOf { it.otherMah },
            )
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

        /** 超过 2 个名义同步周期（6h 对账 + 15min 采样受 Doze 调制）无任何成功观测，
         *  视为「可能中断」；不写绝对化「最大损失 ≤15 分钟」 */
        val STALE_AFTER_MS = 12 * 60 * 60 * 1000L

        /**
         * 首页状态行文本（纯函数，JVM 可测）。
         * 形如「数据更新至 M-d HH:mm · 耗电采集 M-d HH:mm（ROOT）」；
         * 无任何成功观测、或最新成功距 now 过久时给出中断提示（不承诺自动恢复——
         * Android 15 强停后应用自身无法后台运行）。
         */
        fun dataHealthLine(h: DataHealthSummary, now: Long, zone: ZoneId): String {
            fun fmt(ts: Long?): String? = ts?.let {
                Instant.ofEpochMilli(it).atZone(zone)
                    .format(DateTimeFormatter.ofPattern("M-d HH:mm"))
            }
            val lastUsage = h.lastUsageSuccessTs
            val lastBattery = h.lastBatterySuccessTs
            if (lastUsage == null && lastBattery == null) {
                return "尚无采集记录（打开应用或等待后台对账后更新）"
            }
            val latest = listOfNotNull(lastUsage, lastBattery).max()
            val sb = StringBuilder("数据更新至 ").append(fmt(latest))
            if (lastBattery != null && (lastUsage == null || lastBattery > lastUsage)) {
                sb.append(" · 耗电采集 ").append(fmt(lastBattery))
                h.lastBatterySource?.let { sb.append("（").append(it).append("）") }
            } else if (lastUsage != null) {
                h.lastUsageSource?.let { sb.append("（").append(it).append("）") }
            }
            // 实际观测到的最大采样间隔（不是名义 15 分钟的绝对化表述）
            h.maxCaptureGapMs?.takeIf { it > 60 * 60 * 1000L }?.let {
                sb.append(" · 采样最大间隔 ").append(formatMinutesShort(it))
            }
            if (now - latest > STALE_AFTER_MS) {
                sb.append(" · ⚠采集可能已中断：若刚强停/清理过本应用，请打开一次以恢复")
            }
            return sb.toString()
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
            var computedDrainPresent = false
            var actualLow = 0.0
            var actualHigh = 0.0
            var capacity: Double? = null
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
            val drainRe = Regex(
                """Capacity:\s*([0-9.]+),\s*Computed drain:\s*([0-9.]+),\s*actual drain:\s*([0-9.]+)(?:\s*-\s*([0-9.]+))?"""
            )
            // 2 空格缩进的段标题（P1-8 边界判据，见循环内注释）
            val sectionHeaderRe = Regex("""^ {2}\S.*:\s*$""")
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
                // section 边界（P1-8）：真机 ColorOS 15 dump 中，power 段之后是 2 空格缩进的
                // 下一段标题（实测为 "  All kernel wake locks:"，此前还有 Screen/Cellular/Wifi
                // 等 2 空格标题；段内所有内容行 ≥4 空格缩进）。旧实现只认 In-memory/
                // Statistics since last charge 两个结束条件，真机均未命中 → 扫描 10000+ 行，
                // 且后续 section 的形似行有污染 globals/uids 的风险。规则：power 段内一旦
                // 出现 2 空格缩进 + 冒号结尾的标题行即结束，不硬编码任何 OEM 标题。
                if (sectionHeaderRe.matches(line)) break
                val dm = drainRe.find(line)
                if (dm != null) {
                    capacity = dm.groupValues[1].toDoubleOrNull()
                    computedDrain = dm.groupValues[2].toDoubleOrNull() ?: 0.0
                    computedDrainPresent = true
                    actualLow = dm.groupValues[3].toDoubleOrNull() ?: 0.0
                    actualHigh = dm.groupValues[4].toDoubleOrNull() ?: actualLow
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
            return BatteryParsed(
                uids, globals, computedDrain, actualLow, actualHigh, windowStartTs,
                capacity, lines.size, computedDrainPresent,
            )
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

        /**
         * 会话重建：RESUMED 开会话；PAUSED/息屏或下一个 RESUMED 关会话；
         * 跨本地午夜切分；短于 2 秒的碎片丢弃（推送拉起的秒级“使用”）。
         * 注意不能用 ACTIVITY_STOPPED 关会话：ColorOS 应用内切 Activity 恒为
         * PAUSED(旧)→RESUMED(新)→STOPPED(旧) 同戳三连，STOPPED(旧) 与新会话同包，
         * 会把刚开的会话 0 秒关掉，导致多 Activity 应用整段丢时长（实测知乎丢 ~90%）。
         *
         * v0.18（E，P1-2）两处语义修正：
         *  1) **可信采集上界**：参数改为 trustedEnd（= 本次采集实际发生时刻）。
         *     旧实现把「最后一个事件的时间」当窗口结束——最后事件是 RESUMED、
         *     其后无关闭事件时，仍在前台的会话尾段被截断（10:00 RESUMED、10:20
         *     采集 → 少记 20 分钟）。现在开放中的会话按暂定尾段延伸到 trustedEnd；
         *     root 每次同步全量重建窗口，后续事件自然纠正该暂定值。缺关闭事件与
         *     真实仍前台无法在单帧内区分，暂定尾段不对外宣称「精准」。
         *  2) **同包短段合并**：先合并「同包且间隔 ≤ SHORT_MERGE_GAP_MS」的相邻会话，
         *     再套用 2 秒最小阈值。Activity 切换（同戳三连）会把一次真实使用拆成
         *     多个 1 秒段，旧算法逐段丢弃（本机审计：376 段共 376 秒被丢，其中
     *     一部分是真实使用）；独立秒级噪声（前后都有大空档）仍被过滤。
         */
        fun buildSessions(
            events: List<RawEvent>,
            windowStart: Long,
            trustedEnd: Long,
            source: Int = SESSION_SOURCE_ROOT,
        ): List<UsageSession> {
            val zone = ZoneId.systemDefault()
            // —— 收集原始会话（事件序） ——
            val raw = ArrayList<Triple<String, Long, Long>>()  // pkg, start, end
            var openPkg: String? = null
            var openTs = 0L

            fun close(at: Long) {
                val pkg = openPkg ?: return
                val start = openTs
                openPkg = null
                if (at <= start) return
                raw.add(Triple(pkg, start, at))
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
            // 尾段：缺关闭事件时以可信采集上界为暂定结束（后续全量重建可纠正）
            close(trustedEnd)

            // —— 同包短段合并（间隔 ≤ 2s = dump 秒级精度） ——
            raw.sortBy { it.third }
            val merged = ArrayList<Triple<String, Long, Long>>()
            for (r in raw) {
                val last = merged.lastOrNull()
                if (last != null && last.first == r.first && r.second - last.third <= SHORT_MERGE_GAP_MS) {
                    merged[merged.size - 1] = last.copy(third = maxOf(last.third, r.third))
                } else {
                    merged.add(r)
                }
            }

            // —— 最小阈值 + 跨午夜切分 ——
            val out = ArrayList<UsageSession>()
            for ((pkg, start, end) in merged) {
                if (end - start < MIN_SESSION_MS) continue
                var cur = start
                while (cur < end) {
                    val day = Instant.ofEpochMilli(cur).atZone(zone).toLocalDate()
                    val nextDayStart = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val segEnd = minOf(end, nextDayStart)
                    out.add(
                        UsageSession(
                            packageName = pkg, startTs = cur, endTs = segEnd,
                            day = day.toEpochDay(), source = source,
                        )
                    )
                    cur = segEnd
                }
            }
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
