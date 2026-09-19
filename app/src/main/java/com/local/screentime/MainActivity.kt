package com.local.screentime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.collection.LruCache
import androidx.core.graphics.createBitmap
import com.local.screentime.data.AppDisplay
import com.local.screentime.data.BatteryDailyEntity
import com.local.screentime.data.BatteryGlobalEntity
import com.local.screentime.data.DailyRow
import com.local.screentime.data.DayStatsEntity
import com.local.screentime.data.ReportEntity
import com.local.screentime.data.SyncResult
import com.local.screentime.data.UnlockStatsEntity
import com.local.screentime.data.UsageRepository
import com.local.screentime.data.UsageSession
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

// iOS 风格配色
private val ChartBlue = Color(0xFF3478F6)
private val BarPastLight = Color(0xFFD1D4D9)
private val BarPastDark = Color(0xFF5A5E63)
private val GridLight = Color(0x26000000)
private val GridDark = Color(0x2AFFFFFF)
private val AvgGray = Color(0xFF8E8E93)
private val PALETTE = listOf(
    0xFF3478F6, 0xFFFF9500, 0xFF30B0C7, 0xFF34C759, 0xFFFF2D55,
    0xFFAF52DE, 0xFFFFD60A, 0xFFFF6B22, 0xFF5E5CE6, 0xFF00C7BE,
)
private val CAT_COLORS = mapOf(
    "通讯社交" to Color(0xFF3478F6),
    "影音娱乐" to Color(0xFFFF9500),
    "游戏" to Color(0xFFAF52DE),
    "音乐音频" to Color(0xFF30B0C7),
    "资讯阅读" to Color(0xFF34C759),
    "AI 助手" to Color(0xFFBF5AF2),
    "购物" to Color(0xFFFF2D55),
    "金融" to Color(0xFF00C7BE),
    "地图出行" to Color(0xFF64D2FF),
    "生活服务" to Color(0xFFFFD60A),
    "效率" to Color(0xFF98989D),
    "系统" to Color(0xFF98989D),
    "其他" to Color(0xFFC7C7CC),
)

private fun appColor(pkg: String): Color {
    if (pkg == "其他") return Color(0xFFAEAEB2)
    return Color(PALETTE[kotlin.math.abs(pkg.hashCode()) % PALETTE.size])
}

private fun catColor(c: String): Color = CAT_COLORS[c] ?: appColor(c)

private fun compName(component: String): String = when (component) {
    "screen" -> "屏幕"
    "cpu" -> "处理器"
    "audio" -> "音频"
    "video" -> "视频"
    "camera" -> "相机"
    "gnss" -> "定位"
    "wifi" -> "Wi-Fi"
    "bluetooth", "bt" -> "蓝牙"
    "wakelock" -> "唤醒锁"
    "sensors" -> "传感器"
    "mobile_radio" -> "移动网络"
    "ambient_display" -> "息屏显示"
    "idle" -> "系统空闲"
    "flashlight" -> "手电筒"
    "phone" -> "通话"
    else -> component
}

private val iconCache = LruCache<String, ImageBitmap>(256)

private fun loadIconBitmap(context: Context, pkg: String): ImageBitmap? {
    iconCache.get(pkg)?.let { return it }
    return try {
        val d: Drawable = context.packageManager.getApplicationIcon(pkg)
        val size = 96
        val bmp = createBitmap(size, size)
        val canvas = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        val ib = bmp.asImageBitmap()
        iconCache.put(pkg, ib)
        ib
    } catch (e: Exception) {
        null
    }
}

private fun formatShort(ms: Long): String {
    if (ms <= 0) return "0秒"
    val totalMin = ms / 60000
    val h = totalMin / 60
    val m = totalMin % 60
    val s = (ms % 60000) / 1000
    return when {
        h > 0 -> "${h}小时${m}分"
        m > 0 -> "${m}分${s}秒"
        else -> "${s}秒"
    }
}

private fun formatMinutes(ms: Long): String {
    if (ms <= 0) return "0分钟"
    val totalMin = ms / 60000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}小时${m}分钟" else "${m}分钟"
}

/** 把碎片化会话折叠成“使用轮次”：相邻会话间隔 ≤ gapMs 视为同一次打开。
 *  事件流会把一次使用拆成几十段（推送/切 Activity），直接数段数会严重虚高
 *  “打开 N 次”（实测知乎 19.9 分钟被拆成 321 段）（v0.16.7）。 */
private fun mergeSessionRounds(sessions: List<UsageSession>, gapMs: Long = 90_000L): List<Pair<Long, Long>> {
    if (sessions.isEmpty()) return emptyList()
    val sorted = sessions.sortedBy { it.startTs }
    val out = ArrayList<Pair<Long, Long>>()
    var cs = sorted[0].startTs
    var ce = sorted[0].endTs
    for (i in 1 until sorted.size) {
        val s = sorted[i]
        if (s.startTs - ce <= gapMs) {
            ce = maxOf(ce, s.endTs)
        } else {
            out.add(cs to ce)
            cs = s.startTs
            ce = s.endTs
        }
    }
    out.add(cs to ce)
    return out
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = UsageRepository(applicationContext)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val savedMode = prefs.getString("theme_mode", "system") ?: "system"
        // 在 setContent 之前就把系统栏样式与窗口底色按“app 内设定的主题”定好。
        // 此前是裸调 enableEdgeToEdge()：它按系统明暗取色，与 app 内设定相反时
        // 第一帧会被画成暗色（启动闪黑），要等 LaunchedEffect 生效才纠正 → 现在提前到首帧之前。
        applyTheme(resolveDark(savedMode))
        setContent {
            var themeMode by remember { mutableStateOf(savedMode) }
            val dark = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark) { applyTheme(dark) }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    UsageApp(repo, dark, themeMode) { m ->
                        themeMode = m
                        prefs.edit().putString("theme_mode", m).apply()
                        // 同时告诉系统，这样**下次冷启动的闪屏**也会用对底色（见 App.applyAppNightMode）
                        App.applyAppNightMode(this@MainActivity)
                    }
                }
            }
        }
    }

    /** 非 Compose 环境（onCreate 早期）下判断是否暗色；Compose 内仍用可观察的 isSystemInDarkTheme() */
    private fun resolveDark(mode: String): Boolean = when (mode) {
        "dark" -> true
        "light" -> false
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    /** 系统栏样式 + 窗口底色，二者都必须跟随 app 内的主题设定 */
    private fun applyTheme(dark: Boolean) {
        val scrim = Color.Transparent.toArgb()
        enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(scrim) else SystemBarStyle.light(scrim, scrim),
            navigationBarStyle = if (dark) SystemBarStyle.dark(scrim) else SystemBarStyle.light(scrim, scrim),
        )
        window.setBackgroundDrawable(
            ColorDrawable(if (dark) darkColorScheme().surface.toArgb() else lightColorScheme().surface.toArgb())
        )
    }
}

private data class UiRowInfo(
    val key: String,
    val packageName: String?,
    val label: String,
    val timeMs: Long?,
    val battery: BatteryDailyEntity?,
    val frozen: Boolean,
)

private data class DayLoad(
    val rows: List<DailyRow>,
    val total: Long,
    val sessions: List<UsageSession>,
    val categories: Map<String, String>,
    val battery: Map<String, BatteryDailyEntity>,
    val batteryAll: List<BatteryDailyEntity>,
    val batteryGlobal: List<BatteryGlobalEntity>,
    val dayStats: DayStatsEntity?,
    val unlockTop: List<UnlockStatsEntity>,
    val reports: List<ReportEntity>,
)

@Composable
fun UsageApp(
    repo: UsageRepository,
    dark: Boolean,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val listState = rememberLazyListState()
    val textMeasurer = rememberTextMeasurer()
    val appPrefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val defaultOrder = listOf("hourly", "pickup", "battery", "apps")
    var moduleOrder by remember {
        val saved = appPrefs.getString("module_order", null)?.split(",")?.filter { it in defaultOrder }
        val order = if (saved != null) {
            val merged = saved.toMutableList()
            defaultOrder.filter { it !in merged }.forEach { merged.add(it) }
            merged
        } else defaultOrder
        mutableStateOf(order)
    }
    fun applyOrder(newOrder: List<String>) {
        moduleOrder = newOrder
        appPrefs.edit().putString("module_order", newOrder.joinToString(",")).apply()
    }
    fun moduleName(id: String) = when (id) {
        "hourly" -> "小时分布"
        "pickup" -> "拿起与解锁"
        "battery" -> "电量去向"
        "apps" -> "应用占比"
        else -> id
    }

    var selectedDay by remember { mutableStateOf(LocalDate.now()) }
    var rows by remember { mutableStateOf<List<DailyRow>>(emptyList()) }
    var dayTotal by remember { mutableStateOf(0L) }
    var sessions by remember { mutableStateOf<List<UsageSession>>(emptyList()) }
    var categories by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var batteryByPkg by remember { mutableStateOf<Map<String, BatteryDailyEntity>>(emptyMap()) }
    var batteryAll by remember { mutableStateOf<List<BatteryDailyEntity>>(emptyList()) }
    var batteryGlobal by remember { mutableStateOf<List<BatteryGlobalEntity>>(emptyList()) }
    var dayStats by remember { mutableStateOf<DayStatsEntity?>(null) }
    var unlockTop3 by remember { mutableStateOf<List<UnlockStatsEntity>>(emptyList()) }
    var reportsList by remember { mutableStateOf<List<ReportEntity>>(emptyList()) }
    var sortByBattery by remember { mutableStateOf(false) }
    var detailFor by remember { mutableStateOf<UiRowInfo?>(null) }
    var showNotifs by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("就绪") }
    var syncing by remember { mutableStateOf(false) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val allPackages = remember(rows, batteryAll) {
        (rows.map { it.packageName } + batteryAll.mapNotNull { it.packageName }).distinct()
    }
    val displays by produceState<Map<String, AppDisplay>>(emptyMap(), allPackages) {
        value = withContext(Dispatchers.IO) {
            allPackages.associateWith { repo.resolveDisplay(it) }
        }
    }

    var loadJob by remember { mutableStateOf<Job?>(null) }

    fun loadDayAsync() {
        val targetDay = selectedDay.toEpochDay()
        loadJob?.cancel()
        loadJob = scope.launch {
            val d = withContext(Dispatchers.IO) {
                val r = repo.dailyRows(targetDay)
                DayLoad(
                    rows = r,
                    total = repo.dayTotalMs(targetDay),
                    sessions = repo.sessionsOfDay(targetDay),
                    categories = r.associate { it.packageName to repo.categoryOf(it.packageName) },
                    battery = repo.batteryForPackages(targetDay, r.map { it.packageName }),
                    batteryAll = repo.batteryDailyRows(targetDay),
                    batteryGlobal = repo.batteryGlobalRows(targetDay),
                    dayStats = repo.dayStats(targetDay),
                    unlockTop = repo.unlockTop(targetDay, 100),
                    reports = repo.latestReports(),
                )
            }
            if (selectedDay.toEpochDay() == targetDay) {
                rows = d.rows
                dayTotal = d.total
                sessions = d.sessions
                categories = d.categories
                batteryByPkg = d.battery
                batteryAll = d.batteryAll
                batteryGlobal = d.batteryGlobal
                dayStats = d.dayStats
                unlockTop3 = d.unlockTop
                reportsList = d.reports
            }
        }
    }

    fun sync() {
        if (syncing) return
        scope.launch {
            syncing = true
            statusText = "同步中…"
            val result = withContext(Dispatchers.IO) { repo.syncNow() }
            hasPermission = repo.hasPermission()
            statusText = when (result) {
                is SyncResult.Ok ->
                    "上次同步 " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) +
                        " · " + result.sessionCount + " 段会话" + if (result.rootUsed) " · root 全量" else ""
                SyncResult.NoPermission -> "缺少“使用情况访问”权限"
            }
            loadDayAsync()
            syncing = false
        }
    }

    LaunchedEffect(Unit) {
        launch {
            withContext(Dispatchers.IO) { runCatching { Shell.getShell() } }
        }
        notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        loadDayAsync()   // 先秒出本地已存数据
        sync()           // 再后台对账，完成后自动刷新界面
    }

    fun changeDay(delta: Long) {
        val nd = selectedDay.plusDays(delta)
        if (nd.isAfter(LocalDate.now())) return
        selectedDay = nd
        loadDayAsync()
    }

    val dayLabel = when (selectedDay) {
        LocalDate.now() -> "今天"
        LocalDate.now().minusDays(1) -> "昨天"
        else -> selectedDay.format(DateTimeFormatter.ofPattern("M月d日"))
    }
    val weekday = selectedDay.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.CHINESE)

    val rowsMap = remember(rows) { rows.associate { it.packageName to it.totalMs } }
    val displayRows: List<UiRowInfo> = remember(rows, batteryAll, displays, batteryByPkg, sortByBattery, rowsMap) {
        buildList {
            if (sortByBattery) {
                batteryAll.sortedByDescending { it.totalMah }.forEach { b ->
                    val pkg = b.packageName
                    val disp = pkg?.let { displays[it] }
                    val label = disp?.label
                        ?: pkg
                        ?: repo.systemUidLabel(b.uidKey)
                        ?: "系统进程"
                    add(
                        UiRowInfo(
                            key = if (pkg != null) pkg else "uid:" + b.uidKey,
                            packageName = pkg,
                            label = label,
                            timeMs = pkg?.let { rowsMap[it] },
                            battery = b,
                            frozen = disp?.frozen ?: false,
                        )
                    )
                }
            } else {
                rows.forEach { r ->
                    val disp = displays[r.packageName]
                    add(
                        UiRowInfo(
                            key = r.packageName,
                            packageName = r.packageName,
                            label = disp?.label ?: r.packageName,
                            timeMs = r.totalMs,
                            battery = batteryByPkg[r.packageName],
                            frozen = disp?.frozen ?: false,
                        )
                    )
                }
            }
        }
    }
    val maxBattery = displayRows.maxOfOrNull { it.battery?.totalMah ?: 0.0 } ?: 0.0

    LazyColumn(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp)
            .pointerInput(Unit) {
                var dragTotal = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onHorizontalDrag = { change, amount ->
                        dragTotal += amount
                        change.consume()
                    },
                    onDragEnd = {
                        // 右滑看前一天，左滑看后一天（浏览位置保持不变）
                        if (dragTotal > 140f) changeDay(-1)
                        else if (dragTotal < -140f) changeDay(1)
                    },
                )
            },
        state = listState,
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 手动刷新：同步中原地换成转圈，尺寸保持一致，避免整行图标跳动
                if (syncing) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    HeaderAction(Icons.Filled.Refresh, "刷新") { sync() }
                }
                HeaderAction(Icons.Filled.Notifications, "通知") { showNotifs = true }
                HeaderAction(Icons.Filled.Settings, "设置") { showSettings = true }
                // ⋮ 菜单此前只赋值 menuOpen 从未渲染（诊断导出因此不可达），v0.16.7 补上
                Box {
                    HeaderAction(Icons.Filled.MoreVert, "菜单") { menuOpen = true }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("导出诊断（当日 JSON）") },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    statusText = "生成诊断…"
                                    val json = withContext(Dispatchers.IO) {
                                        runCatching { repo.diagnosticJson(selectedDay.toEpochDay()) }
                                            .getOrElse { "export failed: $it" }
                                    }
                                    statusText = "就绪"
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "ScreenTime 诊断 $selectedDay")
                                        putExtra(Intent.EXTRA_TEXT, json)
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(send, "导出诊断"))
                                    }.onFailure {
                                        Toast.makeText(context, "无法打开分享", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { changeDay(-1) }) { Text("‹") }
                Text(
                    "$dayLabel · $weekday",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = selectedDay.isBefore(LocalDate.now()),
                    onClick = { changeDay(1) }
                ) { Text("›") }
            }
            Text(formatMinutes(dayTotal), fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }

        item(key = "hourly") {
            Spacer(Modifier.height(12.dp))
            UsageCard {
                if (sessions.isEmpty()) {
                    Text("暂无数据", fontSize = 12.sp, modifier = Modifier.padding(vertical = 20.dp))
                } else {
                    HourlyChart(sessions, zone, categories, textMeasurer, dark)
                }
            }
        }

        if (dayStats != null || unlockTop3.isNotEmpty()) item(key = "pickup") {
            Spacer(Modifier.height(12.dp))
            UsageCard {
                Text(
                    "拿起 ${dayStats?.pickups ?: 0} 次",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                // 口径说明：拿起次数 = 解锁(KEYGUARD_HIDDEN)总数（主用户 + 2s防抖）；
                // 下面的分项统计解锁后直接进入或 15 秒内新打开的应用
                val unlockedTotal = unlockTop3.sumOf { it.count }
                Text(
                    if (unlockedTotal > 0) "其中 $unlockedTotal 次解锁后进入/打开了应用"
                    else "拿起 = 息屏后点亮屏幕的次数；多数拿起只停留在桌面，不打开应用",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (unlockTop3.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "解锁后首开 Top 3",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    unlockTop3.take(3).forEach { u ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIconBadge(u.packageName, appColor(u.packageName))
                            Spacer(Modifier.width(6.dp))
                            val nm = displays[u.packageName]?.label ?: u.packageName
                            Text(nm, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            Text("×${u.count}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        item(key = "battery") {
            Spacer(Modifier.height(12.dp))
            UsageCard(title = "电量去向（毫安时 · 估算）") {
                Text(
                    "口径：自上次充满电以来的估算；插电瞬间自动抢拍存档，日汇总不再缺充电前时段",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                if (batteryGlobal.isEmpty()) {
                    Text(
                        "耗电数据积累中",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    val top = batteryGlobal.take(8)
                    val gMax = top.firstOrNull()?.mah ?: 1.0
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        top.forEach { g ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(compName(g.component), fontSize = 11.sp, modifier = Modifier.width(76.dp))
                                Box(Modifier.weight(1f).height(6.dp)) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth((g.mah / gMax.coerceAtLeast(0.01)).toFloat())
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(ChartBlue.copy(alpha = 0.85f))
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                // maxLines=1 防止 "340.6" 这类数值在 38dp 固定宽度里折成两行（v0.16.7）
                                Text(
                                    String.format(Locale.US, "%.1f", g.mah),
                                    fontSize = 11.sp,
                                    modifier = Modifier.width(44.dp),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "apps") {
            Spacer(Modifier.height(12.dp))
            UsageCard(title = "应用占比") {
                if (rows.isEmpty()) {
                    Text(
                        if (hasPermission) "暂无数据" else "缺少“使用情况访问”权限",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        rows.take(8).chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth()) {
                                pair.forEachIndexed { ci, r ->
                                    if (ci == 1) {
                                        VerticalDivider(
                                            Modifier.height(20.dp).padding(horizontal = 8.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    }
                                    Row(
                                        Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AppIconBadge(r.packageName, appColor(r.packageName))
                                        Spacer(Modifier.width(6.dp))
                                        val nm = displays[r.packageName]?.label ?: r.packageName
                                        Text(
                                            nm,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f)
                                        )
                                        val pct = (r.totalMs * 100 / dayTotal.coerceAtLeast(1)).toInt()
                                        Text(
                                            if (pct == 0) "<1%" else "$pct%",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                                if (pair.size == 1) {
                                    VerticalDivider(
                                        Modifier.height(20.dp).padding(horizontal = 8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "ranking_header") {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (sortByBattery) "耗电排行" else "最常使用",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                SortChip("时长", active = !sortByBattery) { sortByBattery = false }
                SortChip("耗电", active = sortByBattery) { sortByBattery = true }
            }
        }
        if (displayRows.isEmpty()) {
            item(key = "ranking_empty") {
                Text(
                    if (hasPermission) "这一天暂无记录" else "还没有“使用情况访问”权限",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        } else {
            items(displayRows, key = { it.key }, contentType = { "row" }) { info ->
                val share = when {
                    sortByBattery -> (info.battery?.totalMah ?: 0.0) / maxBattery.coerceAtLeast(0.01)
                    else -> (info.timeMs ?: 0L).toFloat() / dayTotal.coerceAtLeast(1L)
                }.toFloat().coerceIn(0.02f, 1f)
                UsageRow(
                    info = info,
                    share = share,
                    color = appColor(info.key),
                    sortByBattery = sortByBattery,
                    onClick = { detailFor = info },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    statusText,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { sync() }, enabled = !syncing) { Text("同步") }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("完成") }
            },
            title = { Text("设置") },
            text = {
                Column {
                    Text("外观", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("light" to "白天", "dark" to "黑夜", "system" to "跟随系统").forEach { (v, lbl) ->
                            val active = themeMode == v
                            Text(
                                lbl,
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (active) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerLow
                                    )
                                    .clickable { onThemeModeChange(v) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("模块显示顺序", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "调整各模块在首页的出现顺序（排行列表固定在最后）",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    moduleOrder.forEachIndexed { i, id ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(moduleName(id), fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(enabled = i > 0, onClick = {
                                applyOrder(moduleOrder.toMutableList().apply { add(i - 1, removeAt(i)) })
                            }) { Text("↑") }
                            TextButton(enabled = i < moduleOrder.lastIndex, onClick = {
                                applyOrder(moduleOrder.toMutableList().apply { add(i + 1, removeAt(i)) })
                            }) { Text("↓") }
                        }
                    }
                }
            }
        )
    }

    if (showNotifs) {
        AlertDialog(
            onDismissRequest = { showNotifs = false },
            confirmButton = {
                TextButton(onClick = { showNotifs = false }) { Text("关闭") }
            },
            title = { Text("通知") },
            text = {
                Column {
                    Text(
                        "周报（周日晚）/ 月报（月底）/ 年报（年底）生成时会推送到系统通知栏，这里可随时回看",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    if (reportsList.isEmpty()) {
                        Text("暂无报告", fontSize = 12.sp)
                    } else {
                        reportsList.forEach { r ->
                            var open by remember(r.periodKey) { mutableStateOf(false) }
                            val typeName = when (r.type) {
                                "WEEKLY" -> "周报"
                                "MONTHLY" -> "月报"
                                else -> "年报"
                            }
                            val keyPart = r.periodKey.substringAfter(':')
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { open = true }
                                    .padding(vertical = 6.dp)
                            ) {
                                Text("$typeName · $keyPart", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    r.text.lines().take(2).joinToString("  "),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            if (open) {
                                AlertDialog(
                                    onDismissRequest = { open = false },
                                    confirmButton = {
                                        TextButton(onClick = { open = false }) { Text("关闭") }
                                    },
                                    title = { Text("$typeName · $keyPart", fontSize = 16.sp) },
                                    text = {
                                        Column(
                                            Modifier
                                                .heightIn(max = 360.dp)
                                                .verticalScroll(rememberScrollState())
                                        ) {
                                            Text(r.text, fontSize = 12.sp)
                                        }
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            scope.launch {
                                val made = withContext(Dispatchers.IO) { repo.generateDueReports(force = true) }
                                reportsList = withContext(Dispatchers.IO) { repo.latestReports() }
                                if (made.isNotEmpty()) statusText = "已生成：${made.joinToString()}"
                            }
                        }) { Text("立即生成当前周期报告") }
                    }
                }
            }
        )
    }

    detailFor?.let { info ->
        val appSessions = remember(info.key, sessions) {
            sessions.filter { it.packageName == info.packageName }.sortedBy { it.startTs }
        }
        val cat by produceState("…", info.key) {
            value = info.packageName?.let { repo.categoryOf(it) } ?: "系统"
        }
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        AlertDialog(
            onDismissRequest = { detailFor = null },
            confirmButton = {
                TextButton(onClick = { detailFor = null }) { Text("关闭") }
            },
            title = { Text(info.label, fontSize = 18.sp) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("分类：$cat", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        var catMenu by remember { mutableStateOf(false) }
                        Text(
                            "修改分类",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { catMenu = true }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                        DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                            repo.allCategories().forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(c) },
                                    onClick = {
                                        catMenu = false
                                        info.packageName?.let { pkg ->
                                            repo.setCategoryOverride(pkg, c)
                                            categories = categories + (pkg to c)
                                            loadDayAsync()
                                            detailFor = null
                                        }
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("恢复自动分类") },
                                onClick = {
                                    catMenu = false
                                    info.packageName?.let { pkg ->
                                        repo.setCategoryOverride(pkg, null)
                                        categories = categories - pkg
                                        loadDayAsync()
                                        detailFor = null
                                    }
                                }
                            )
                        }
                    }
                    info.timeMs?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("使用总时长：${formatShort(it)}", fontSize = 13.sp)
                    }
                    info.battery?.let { b ->
                        if (b.totalMah >= 0.5) {
                            Spacer(Modifier.height(4.dp))
                            val bgTxt = if (b.bgMah > 0.5)
                                String.format(Locale.US, "（前台 %.1f / 后台 %.1f）", b.fgMah, b.bgMah) else ""
                            Text(
                                "耗电 " + String.format(Locale.US, "%.1f mAh", b.totalMah) + bgTxt,
                                fontSize = 13.sp
                            )
                        } else {
                            // 系统确实记了但数值极小（如被冻结应用仅零星后台），如实展示而不是隐藏（v0.16.7）
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "耗电 " + String.format(Locale.US, "%.2f mAh（可忽略）", b.totalMah),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (info.battery == null && (info.timeMs ?: 0L) > 0) {
                        // 耗电统计窗口是「自上次充满电」，充电即重置；使用若发生在
                        // 上次同步之后、充电重置之前，那部分耗电永久缺失
                        // （实测：bilibili 17 点用 8.8 分钟，18:05 充电重置 → 数据丢失）（v0.16.7）
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "耗电：无记录（耗电统计自上次充满电起算，充电重置会丢失未被同步时段的估算）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (appSessions.isEmpty()) {
                        Text("无会话明细（超过事件流保留期，仅保留每日总量）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val rounds = remember(appSessions) { mergeSessionRounds(appSessions) }
                        Text("打开 ${rounds.size} 次", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        val sessSum = appSessions.sumOf { it.endTs - it.startTs }
                        if (info.timeMs != null && info.timeMs - sessSum > 60_000) {
                            Text(
                                "历史日：总时长按系统统计回补，下方为保留的会话明细",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Column(
                            Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            rounds.forEach { (rs, re) ->
                                val st = Instant.ofEpochMilli(rs).atZone(ZoneId.systemDefault()).format(timeFmt)
                                val en = Instant.ofEpochMilli(re).atZone(ZoneId.systemDefault()).format(timeFmt)
                                Text(
                                    "$st – $en · ${formatShort(re - rs)}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        )
    }
}

private fun hasPermission(repo: UsageRepository): Boolean = repo.hasPermission()

@Composable
private fun HeaderAction(icon: ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            icon,
            contentDescription = desc,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun AppIconBadge(pkg: String, color: Color) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, pkg) {
        if (pkg != "其他") value = withContext(Dispatchers.IO) { loadIconBitmap(context, pkg) }
    }
    val bmp = icon
    if (pkg == "其他" || bmp == null) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(color))
    } else {
        Image(bmp, contentDescription = null, modifier = Modifier.size(20.dp).clip(CircleShape))
    }
}

@Composable
fun SortChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
fun UsageCard(title: String? = null, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(14.dp)) {
            if (title != null) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

/** 小时柱：按分类堆叠（社交/娱乐/AI…），“其他”固定最后，图例可换行 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HourlyChart(
    sessions: List<UsageSession>,
    zone: ZoneId,
    categories: Map<String, String>,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    dark: Boolean = false,
) {
    val day = sessions.firstOrNull()?.let {
        Instant.ofEpochMilli(it.startTs).atZone(zone).toLocalDate()
    } ?: return
    val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    data class ChartCalculation(
        val perCat: Map<String, LongArray>,
        val catsSorted: List<String>,
        val niceMin: Long,
        val niceMs: Long,
    )
    val calc = remember(sessions, categories, dayStart) {
        val perCat = HashMap<String, LongArray>()
        val catTotals = HashMap<String, Long>()
        for (s in sessions) {
            val cat = categories[s.packageName] ?: "其他"
            val arr = perCat.getOrPut(cat) { LongArray(24) }
            for (h in 0 until 24) {
                val hs = dayStart + h * 3_600_000L
                val he = hs + 3_600_000L
                val ov = minOf(s.endTs, he) - maxOf(s.startTs, hs)
                if (ov > 0) {
                    arr[h] += ov
                    catTotals[cat] = (catTotals[cat] ?: 0L) + ov
                }
            }
        }
        // “其他”固定最后
        val base = catTotals.entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { it.key }
        val catsSorted = base.filter { it != "其他" } + base.filter { it == "其他" }
        val maxMs = LongArray(24) { h -> perCat.values.sumOf { it[h] } }.maxOrNull() ?: 0L
        val maxMin = maxMs / 60000
        val niceMin = (if (maxMin <= 30) 30 else ((maxMin + 29) / 30) * 30).coerceAtLeast(30)
        val niceMs = niceMin * 60_000L
        ChartCalculation(perCat, catsSorted, niceMin, niceMs)
    }
    val perCat = calc.perCat
    val catsSorted = calc.catsSorted
    val niceMin = calc.niceMin
    val niceMs = calc.niceMs
    val labelColor = if (dark) Color(0xFFB8BCC2) else MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = if (dark) Color(0x2AFFFFFF) else Color(0x26000000)

    Box(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            fun measureRight(text: String) = textMeasurer.measure(
                text,
                TextStyle(fontSize = 9.sp, color = labelColor),
                maxLines = 1,
                softWrap = false,
            )
            val topLabel = measureRight("${niceMin}分钟")
            val midLabel = measureRight("${niceMin / 2}分钟")
            val zeroLabel = measureRight("0")
            // 右轴按最宽标注预留宽度，把最右侧 24 时网格线整体左移，避免“分钟”标注压线
            val axisW = maxOf(topLabel.size.width, midLabel.size.width, zeroLabel.size.width) + 12f
            val w = size.width - axisW
            val slot = w / 24f
            val barW = slot * 0.72f
            val chartH = size.height - 8f
            for (h in intArrayOf(0, 6, 12, 18, 24)) {
                val x = (h * slot).coerceAtMost(w)
                drawLine(gridColor, Offset(x, 0f), Offset(x, chartH), 2f)
            }
            drawLine(gridColor, Offset(0f, chartH), Offset(w, chartH), 2f)
            for (h in 0 until 24) {
                val x = h * slot + (slot - barW) / 2f
                var y = chartH
                for (cat in catsSorted) {
                    val ms = perCat[cat]?.get(h) ?: 0
                    if (ms <= 0) continue
                    val segH = chartH * (ms.toFloat() / niceMs)
                    val top = (y - segH).coerceAtLeast(0f)
                    drawRect(catColor(cat), topLeft = Offset(x, top), size = Size(barW, y - top))
                    y = top
                }
            }
            // 横向刻度线（与右侧标注一一对应）
            val y60 = 8f
            val y30 = chartH / 2f
            drawLine(gridColor, Offset(0f, y60), Offset(w, y60), 1f)
            drawLine(gridColor, Offset(0f, y30), Offset(w, y30), 1f)
            drawText(topLabel, topLeft = Offset(size.width - topLabel.size.width - 2f, y60 - 6f))
            drawText(midLabel, topLeft = Offset(size.width - midLabel.size.width - 2f, y30 - 6f))
            drawText(zeroLabel, topLeft = Offset(size.width - zeroLabel.size.width - 2f, chartH - 14f))
            for (h in intArrayOf(0, 6, 12, 18)) {
                val layout = textMeasurer.measure(
                    "${h}时",
                    TextStyle(fontSize = 10.sp, color = labelColor),
                    maxLines = 1,
                    softWrap = false,
                )
                drawText(layout, topLeft = Offset(h * slot + slot / 2f - layout.size.width / 2f, chartH + 6f))
            }
            val lbl24 = textMeasurer.measure(
                "24时",
                TextStyle(fontSize = 10.sp, color = labelColor),
                maxLines = 1,
                softWrap = false,
            )
            drawText(lbl24, topLeft = Offset(w - lbl24.size.width.toFloat(), chartH + 6f))
        }
    }
    if (catsSorted.size > 1) {
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            catsSorted.forEach { cat ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(catColor(cat)))
                    Spacer(Modifier.width(4.dp))
                    Text(cat, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun UsageRow(
    info: UiRowInfo,
    share: Float,
    color: Color,
    sortByBattery: Boolean,
    onClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, info.packageName ?: info.key) {
        value = info.packageName?.let { withContext(Dispatchers.IO) { loadIconBitmap(context, it) } }
    }
    val battery = info.battery

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center
        ) {
            val i = icon
            if (i != null) {
                Image(i, contentDescription = null, modifier = Modifier.size(30.dp))
            } else {
                Box(
                    Modifier.fillMaxSize().background(color.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(info.label.take(1), fontSize = 13.sp, color = color)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(info.label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (info.frozen) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Text("已冻结", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.fillMaxWidth(share).height(3.dp).clip(RoundedCornerShape(2.dp)).background(color)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (sortByBattery && battery != null) {
                Text(
                    String.format(Locale.US, "%.1f mAh", battery.totalMah),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                val bgShare =
                    if (battery.totalMah > 0) (battery.bgMah / battery.totalMah).toFloat() else 0f
                if (info.timeMs != null && info.timeMs > 0) {
                    Text(
                        "用时 " + formatShort(info.timeMs),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (bgShare >= 0.3f) {
                    Text(
                        String.format(Locale.US, "后台 %.0f%%", bgShare * 100),
                        fontSize = 10.sp,
                        color = Color(0xFFD32F2F)
                    )
                }
            } else {
                Text(formatShort(info.timeMs ?: 0L), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                battery?.let { b ->
                    if (b.totalMah >= 0.5) {
                        Text(
                            String.format(Locale.US, "%.1f mAh", b.totalMah),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // 系统确实记了但数值很小（几秒的使用、后台零星），如实显示而不是
                        // 静默隐藏——此前 <0.5 mAh 不渲染，用户看到的是“压根没有耗电”（v0.16.11）
                        Text(
                            String.format(Locale.US, "%.2f mAh", b.totalMah),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
