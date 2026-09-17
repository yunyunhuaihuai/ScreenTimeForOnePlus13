# 屏幕时间（ScreenTimeLog）项目交接文档

> 供后续 AI Agent / 开发者接手。环境与工具链先读机主本机的交接/安装说明（路径不入库），其中含 Clash TUN 断网等必读的坑。

## 1. 项目是什么

一台一加 13（ColorOS 15）上用"冰箱"冻结应用后，**系统自带屏幕使用时间不再显示这些应用的时长**（展示层过滤）。本项目自建记录器：跨三层混合数据源重建完整的使用时长 + 耗电统计，**冻结应用照样显示**，并逐步加入 iOS 屏幕时间风格的可视化。

- 目标设备：OnePlus 13（PJZ110），ColorOS 15 / Android 15（API 35），KernelSU root，屏幕 1440×3168
- 包名：`com.local.screentime`，应用名“屏幕时间”，当前 v0.16.4（versionCode 20）
- 工程：本仓库根目录；技术栈 Kotlin + Compose(M3) + Room + WorkManager + libsu，无 NDK
- minSdk/targetSdk/compileSdk = 35；数据库 Room v3（destructive migration）

## 2. 构建与部署

```bash
# 工具链版本：JDK 21 / Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.10.01
export JAVA_HOME="<JDK 21 安装路径>"
export GRADLE_USER_HOME="<Gradle 缓存目录>"
"<Gradle 8.9 安装路径>/bin/gradle.bat" -p "<本仓库根目录>" --console=plain :app:assembleDebug
adb -s "$ADB_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
```
- 无 wrapper，直接用本机 Gradle 8.9 安装路径（见上）
- 真机 adb 序列号由机主本机记录（`adb devices` 查询）；模拟器 AVD `OP13_API35`（google_apis，可 `adb root`，**用于回归测试**）
- ColorOS 安装需要"USB 安装"开关（已开）；`adb shell appops set` 被 ColorOS 拒绝（shell 无 MANAGE_APP_OPS_MODES）

## 3. 架构：三层数据源（核心设计）

应用内 `UsageRepository.syncNow()` 每次按序执行：

| 层 | 来源 | 用途 | ColorOS 实测情况 |
|---|---|---|---|
| 1. API 事件流 | `UsageStatsManager.queryEvents` | 会话时间线（每次起止） | **被裁剪**：请求窗口内 137 条只返回 12 条；时多时少不可靠 |
| 2. API 聚合 | `queryUsageStats(INTERVAL_DAILY)` | 每包每日总时长 | 基本完整，但"今天"可能偏小 |
| 3. root 直读 | libsu 跑 `dumpsys usagestats` / `batterystats --charged` | 全量事件（~6000 条/天）+ 每UID耗电 | 完整；**这是 root 的核心价值** |

展示策略：**事件重建值与聚合值逐包取 max**（`dailyRows()`）。
耗时统计写入 `sessions` 表 + `usage_daily` 聚合表；耗电写入 `battery_daily` / `battery_global`（快照差分，充满清零自动检测：新值<旧值-0.5 即重置）。

### 表结构（Room v3）
- `sessions(pkg, startTs, endTs, day)` —— 会话（RESUMED/PAUSED 配对，跨午夜切分，<2s 碎片丢弃）
- `usage_daily(pkg, day, totalMs, source[0事件/1聚合API/2root])` —— 每日每包时长
- `battery_daily(uidKey, day, total/fg/bg/screen/cpu/audio/video/camera/gnss/wifi/bt/wakelock/sensors/other mAh)`
- `battery_global(component, day, mah, durationMs)` —— 整机组件耗电（屏幕/CPU/蓝牙/音频/GNSS/息屏显示…）
- `battery_snapshot(uidKey, cumMah, compsText, ts)` —— 差分基线
- `app_info`（label/图标快照，冻结后仍可显示）、`sync_state`（事件水位线）

### 分类
`repo.categoryOf(pkg)`：手动覆盖（SharedPreferences `cat_overrides` JSON）→ 系统 `ApplicationInfo.category` → 内置中国应用字典（`categoryDict`，微信/QQ→通讯社交、抖音/B站→影音娱乐、DeepSeek/豆包→AI 助手…）→ 兜底"其他"。修改入口：应用明细弹窗"修改"。

## 4. 已实现功能（v0.10.0，versionCode 10）

- 每日总量大数字 + 左右箭头/**列表左右滑动**切日期（周一开始的周图：灰柱+今天蓝+虚线均值+右轴刻度右对齐，星期画进图内对齐柱子）
- 小时柱状图：**按分类堆叠**（社交蓝/娱乐橙/AI 紫…，底部图例）；**点任意柱子弹出该小时分类构成 + 应用明细**
- **全天时间轴色带**：0-24 时的会话色带，按分类上色
- **拿起与解锁**卡片：每天拿起次数（KEYGUARD_HIDDEN 计数）+ 解锁后 15 秒内最常打开的 App Top3（表 `day_stats` / `unlock_stats`，root 与 API 事件流双路写入，事件保留约 2 天）
- **报告**：周报（周日晚）/月报（月底）/年报（年底）自动生成（`reports` 表，周期结束 2 天内补生成），含环比上一周期 Top 应用变化、拿起次数、总耗电；卡片可点开全文、可手动立即生成当前周期
- 应用占比：两列"图标+名称+占比%"网格（中间竖分隔线）
- 排行列表：时长/耗电双模式排序；耗电模式含系统条目（Android 系统服务/系统服务…）；行尾显示 mAh + 后台占比（≥30% 标红）
- 应用明细弹窗（点行）：分类（可修改，写 `cat_overrides` 覆盖自动分类）/总时长/打开次数/**全部会话起止与时长（可滚动，无条数上限）**/耗电前后台
- 电量去向卡：整机组件 mAh 排名
- 设置（齿轮）：模块显示顺序 ↑↓（SharedPreferences `module_order` 持久化，模块：weekly/hourly/timeline/pickup/battery/apps/reports/ranking）
- 每 6h WorkManager 后台对账；打开即先读本地库秒出、再后台同步（同步已去掉无用的 --checkin 解析，~1-2s）；Doze 白名单（root 自动添加）
- 诊断导出（菜单）：当天会话+聚合+分类 JSON 复制到剪贴板
- 应用名"屏幕时间"，自适应图标（蓝底白时钟+黄色弧）

## 4.0.4 v0.16.4 变更（启动闪暗色修复 + 右上角手动刷新 + 补回缺失构建文件）

- **修复冷启动闪暗色（根因）**：`AndroidManifest.xml` 原用 `@android:style/Theme.DeviceDefault.NoActionBar`，而 **`Theme.DeviceDefault` 是固定暗色主题**（不是 DayNight）→ 启动窗口底色恒为黑，与 app 内"白天/黑夜"设定完全无关。改为项目自建 `Theme.ScreenTime`：`values/themes.xml` 父主题取 `Theme.DeviceDefault.Light.NoActionBar`，`values-night/themes.xml` 取暗色变体，二者 `android:windowBackground` 均指向 `@color/window_background`（`values/colors.xml` = `#FFFBFE`，`values-night/colors.xml` = `#1C1B1F`，与 M3 colorScheme 的 surface 一致）。
- **首帧即按 app 内主题取色**：新增 `MainActivity.applyTheme(dark)`，把系统栏样式与 `window.setBackgroundDrawable(...)` 提到 **`setContent` 之前**调用。原实现是在 `setContent` 里裸调 `enableEdgeToEdge()`，它按**系统**明暗取色——当 app 内设定与系统相反时首帧会被画成暗色，要等 `LaunchedEffect` 才纠正（这就是"启动过程是暗色"的第二层原因）。另新增 `resolveDark(mode)` 供非 Compose 环境（onCreate 早期）判断明暗；Compose 内仍保留可观察的 `isSystemInDarkTheme()` 以便系统主题变化时重组。
- **新增右上角手动刷新键**：标题栏最左侧加 `Icons.Filled.Refresh`（`HeaderAction` 同款 36dp 命中区），点击调 `sync()`；同步中原地替换为 16dp `CircularProgressIndicator`（尺寸相同，图标不跳动）。底部原有"同步"按钮与状态文字保留。
- **补回两个缺失的构建必需文件**（此前该工程在**任何**机器上都无法构建）：
  - `settings.gradle.kts`：`pluginManagement` + `dependencyResolutionManagement` 仓库（google + mavenCentral + **JitPack**——libsu 只发布在 JitPack，缺它会报 `Could not find com.github.topjohnwu.libsu:core`）、`rootProject.name`、`include(":app")`。
  - `gradle.properties`：`android.useAndroidX=true`（**缺失则构建直接失败**）、`android.nonTransitiveRClass=true`、`org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8`（本机用户名与路径含中文，显式指定编码）。
- **遗留未修（下一版处理）**：标题栏 ⋮ 按钮的 `menuOpen` 只被赋值为 `true`、**从未渲染任何 DropdownMenu** → 按钮点了没反应；第 4 节功能列表中所称"诊断导出（菜单）"实际**不可达**，`UsageRepository.diagnosticJson()` 成为死代码。同批死代码还有：`weeklyTotals()`/`dao.sessionsSumsSince()`、`DonutChart()`、`parseCheckinAggregates()`、`dao.earliestSessionTs()`、`dao.dayTotalMs()`、`AppDisplay.stateDesc`、`UidPower.durs`。

## 4.0.3 v0.16.3 变更（UI 微调 + 系统 UID 耗电命名修正）
- 小时图与"拿起"卡片之间补 12dp 间距；每张卡自带顶距，标题区冗余 Spacer 移除
- "解锁后最常打开"小标题删除（列表直接跟在"拿起 N 次"下）
- "今天·周日"日期文字在左右箭头间居中（TextAlign.Center）
- 小时图横轴补"24时"收尾标注（右对齐到 24 时网格线）
- 修复耗电排行里系统 UID 误标：uid<10000 是共享 UID，之前会挂到该 UID 下第一个包名（如 UID 1000 显示成"安全中心"）。新数据 packageName 置空走 systemUidLabel；存量行用一次性 UPDATE 清理（flag `sysuid_labels_v18`）
- 明细弹窗：仅当总时长与会话明细差 >1 分钟（即历史回补日）显示一行"总时长按系统统计回补"说明
- 注：MainActivity.kt 曾出现一处历史遗留的非法 `},`（item 语句后带逗号），本版一并修掉

## 4.0.2 v0.16.2 变更（历史数据回补）
- **历史日（09-04～09-12）自然日时长已回补**：ColorOS 数字健康（com.coloros.digitalwellbeing）自身不落库（DB 只有设置，UI 是实时算的），真正的原始数据在 `/data/system_ce/0/usagestats/daily/`（protobuf 格式，Android 11+ 从 XML 改为 proto；"-c" 后缀文件非 gzip，直接就是 proto）。文件名=桶 beginTime；顶层 field1=时长ms；field20=PackageStats{field1=token索引, **field4=totalTimeUsed ms**, field6=appLaunchCount}；token→[包名,类名…] 映射在同目录 `mappings`（与 dumpsys "Token N: [...]" 一致）。
- root 读这些文件**不受 data_mirror 影响**（su 命名空间里 /data/user/0 只能看到自己的包目录，但 /data/system_ce 可读；数据传出走 /data/local/tmp，/sdcard 在 root ns 不可用）。
- 回补方法（一次性，PC 端完成）：每桶按旧会话在该桶跨午夜两侧的分布比例切分到自然日（两侧无会话时按时长比例），生成 JSON 由 app 在 syncViaRoot 开头一次性导入 usage_daily(source=2)（flag `backfill_natural_days_v18`，导入后删源文件）；`dailyRows` 对历史日逐包 max(会话, 回补值)。
- 结果：9/05～9/12 每日合计 4.2~7.0h（此前会话口径只有 2.3~3.3h）。**9/3 不可回补**（对应桶文件已轮转删除）；**9/4 只有晚间部分**（[9/3 16:39→9/4 16:39] 桶已删）；今日及以后不用回补（新算法会话即准确）。
- 切分误差说明：跨午夜切分用的是旧会话的分布（多 Activity 应用分布大体无偏），单日误差估计 ±10~20%，ColorOS 界面本身也是事件流实时重建的自然日口径，两者应大体吻合。

## 4.0.1 v0.16.0 变更（时长口径修复，见第 8 节根因分析）
- **buildSessions 不再用 ACTIVITY_STOPPED 关会话**（仅 PAUSED/息屏/下一 RESUMED 关），修复多 Activity 应用整段丢时长（知乎曾丢 ~90%）
- **列表/大数字/应用占比/报告/weeklyTotals 一律以会话表为唯一权威**，`dailyRows` 去掉与 usage_daily 取 max 的逻辑；报告改读 sessions（`sessionsSumBetween`/`topSessionsBetween`）
- `syncAggregatesViaApi` 已删除（ColorOS 滚动桶无法按自然日拆分，写了也是错的）；syncNow 首次运行一次性清空 usage_daily（sync_state 标记 `usage_daily_wiped_v16`）
- 明细弹窗删除"明细合计少于系统记录"提示（同源后永不成立）
- 小时图右轴：按最宽标注实测宽度预留右侧空间，24 时网格线整体左移，不再被"XX分钟"标注压线
- 修复后实测：app 会话 vs 系统 totalTimeUsed 桶逐包差 ≤0.1 分钟；今天自然日合计 6.50h(虚) → 3.36h(实)
- 存量数据：修复前日期的会话仍是旧算法结果（多 Activity 应用偏小），事件日志只留 24h 无法回补

## 4.1 v0.15.0 变更
- 小时柱：横向刻度线（与右侧标注对应）、右标注改为 30分钟/60分钟式数值（随实际最大值自适应，删除“平均”）
- **解锁排行排除桌面启动器**：`syncDayEventStats` 过滤 + `deleteUnlockLauncherRows()` 清理历史行（桌面永远第一，无信息量）
- 拿起卡片：无标题、无数据时整卡隐藏
- 耗电口径说明：蓝牙等系统组件耗电 `apps: 0` 不归属应用，仅出现在“电量去向”整机视图

## 4.2 v0.14.0 变更（应用名/图标/UI 精简 + 暗色模式）
- **暗色模式**：设置 → 外观（白天/黑夜/跟随系统），SharedPreferences `theme_mode`；MaterialTheme 动态切换 + 系统栏样式随主题（enableEdgeToEdge 重入式调用）；图表颜色均带 dark 分支（BarPast/Grid/label）
- **模块精简（用户定稿）**：移除顶部大标题、**周趋势模块（WeeklyChart + repo.weeklyTotals 已删）**、小时卡标题、各类提示文案；“拿起与解锁”卡片无标题且无数据时整卡隐藏；排行列表固定在最后（不参与排序，保持懒加载）
- **解锁后首开排除桌面启动器**（每次解锁必然先到桌面，无信息量；`syncDayEventStats` 过滤 launcher）
- 首页打开流程改为：先读本地库秒出 → 再后台同步 → 完成后自动刷新
- 数据说明：日期跨天后“今天”从零计数属正常；昨天及更早数据在对应日期页签
- **[外部干扰记录]** MainActivity.kt 曾被外部进程反复改写（疑似 IDE 自动保存或同步盘），多次出现编辑丢失/功能消失/混合状态；最终版以整文件原子写入后构建成功。**后续 agent 编辑前务必确认 Android Studio 已关闭该文件**

## 4.10 v0.14.0 交互定稿
- 小时柱：按分类堆叠、柱宽 0.72、网格线 2f 深色、右轴仅“平均/0”（用户要求删除最大值标注）、“其他”固定图例最后、图例 FlowRow 可换行
- 排行列表：位置在左右滑切日时保持（scrollToItem 已移除）
- 明细弹窗：“修改分类”为描边按钮更醒目
- 手势：列表左右滑切日保留；时间轴模块与捏合已删

## 4.11 v0.13.0 及更早
- 应用名改为“屏幕时间”，新增自适应图标（蓝底白时钟 + 黄色弧，`res/mipmap-anydpi-v26`）
- **暗色模式**：设置 → 外观（白天/黑夜/跟随系统），SharedPreferences `theme_mode`；系统栏样式随主题切换（enableEdgeToEdge 重入式调用）；图表颜色均带 dark 分支（BarPast/Grid/label）
- **模块精简**：移除顶部大标题、周趋势模块、时间轴模块、横屏/捏合交互、小时柱点按交互、各类使用提示文案；“拿起与解锁”卡片无标题且无数据时整卡隐藏
- **解锁后首开排除桌面启动器**（每次解锁必然先到桌面，无信息量；`syncDayEventStats` 过滤 launcher）
- 首页打开流程改为：先读本地库秒出 → 再后台同步 → 完成后自动刷新
- 数据说明：日期跨天后“今天”从零计数属正常；昨天及更早数据在对应日期页签

## 4.9 v0.13.0 及更早
- 耗电排行里系统条目命名：`systemUidLabel` 映射（Android 系统服务/电话/蓝牙服务…），共享 UID 解析到主包名
- 解锁排行排除桌面启动器见 v0.14

## 4.12 v0.12.0 及更早数据层变更
- **双指捏合交互**：小时图/时间轴卡上**双指张开** → 进入横屏全屏模式（Activity 转横屏，Dialog `usePlatformDefaultWidth=false` 全屏深色底）；**双指捏合** → 返回竖屏。横屏内点小时柱 → 底部面板显示该小时分类构成 + Top 应用
- 手势：`Modifier.pinchZoom(onZoom, onEnd)`（awaitEachGesture 自定义，仅双指时消费事件，不干扰列表滚动）
- MainActivity 已声明 `android:configChanges="orientation|screenSize|smallestScreenSize"`，转屏不重建、状态不丢
- 文案清理：去掉所有使用提示（"点按放大"/"拿起与解锁"标题/保留期说明等），"拿起与解锁"卡片无数据时整卡隐藏
- 已知：模拟器无法模拟双指（input 命令单点），捏合交互需真机验证；小时图在模拟器上全灰是因为模拟器应用均无分类（真机有字典配色）

## 4.2 v0.11.0 变更
- **报告改为系统通知推送**：`Notifications` 单例（渠道 "reports"）+ POST_NOTIFICATIONS 运行时权限（首次打开申请一次）；报告生成时推送通知，点开回到应用
- **铃铛入口**（标题栏）：历史报告列表（点开全文）+ 手动生成按钮；原"报告"卡片已移除（moduleOrder 默认值不再含 reports）
- **小时柱点击修复**：改为 24 个透明点按格覆盖（此前 Canvas 手势检测在部分场景失效）
- **时间轴可点开放大**：大色带（120dp）+ 全部会话明细列表（名称/起止/时长）
- 展示层合并策略：事件重建与系统聚合**逐包取 max**（两路都可能有缺）

## 4.2 v0.10.0 数据层变更
- DB v4 新增表：`day_stats(day, pickups)`、`unlock_stats(day, packageName, count)`、`reports(type, periodKey, generatedTs, text)`（destructive migration，会清库自动回填）
- `DUMP_EVENT_TYPES` 增加 KEYGUARD_HIDDEN/SHOWN；root 与 API 事件流都会跑 `syncDayEventStats()`
- 报告生成：`repo.generateDueReports(force)`，条件为周期结束日起 2 天内自动、或手动 force；报告文本由 usage_daily/day_stats/battery_daily 汇总（含环比上周期）

## 5. 关键坑与实测结论（重要）

1. **ColorOS 裁剪 queryEvents**：同一窗口 dumpsys 有 137 条事件，API 只回 12 条 → 事件流不可作为日总量来源，只做时间线。
2. **ColorOS 阻止 shell 授权 appops**（SecurityException MANAGE_APP_OPS_MODES）→ "使用情况访问"必须机主在设置页手动开（已开，永久有效）。
3. **API 35 变化**：`ApplicationInfo.enabled` 从 int 变 boolean；`PackageManager.isPackageSuspended(String)` 已公开；`QUERY_ALL_PACKAGES`/`<queries>` 必需，否则查不到其他应用（包可见性）。
4. **冰箱 root 冻结 = `pm disable-user --user 0`**（dumpsys 显示 enabled=3/DISABLED_USER，suspended=false）；冻结应用的事件、聚合、图标、名称都还在，仅展示层过滤。
5. **耗电是模型估算**：设备容量 5920mAh；模型 328mAh vs 实测 414-474（低估 ~25%）。插电使用时"自上次充满"累计很小是正常口径。UID 1000 = Android 系统服务（system_server），耗电前排常客，正常。
6. **root 下 `--checkin` 解析恒为 0 行**（格式与 shell 不同）→ 已移除该调用以缩短同步（同步从 ~4s 降到 1-2s）。
7. **KernelSU 不弹授权框**，需在管理器"超级用户"里手动允许（已允许 app 与 shell 的授权状态：app=root 可用）。
8. **手机有 PIN 锁屏**：自动化（截图/点击）在锁屏下不可用，需机主解锁。
9. 模拟器 queryEvents 不裁剪（可回 57686 条），用于功能回归；模拟器无 su，root 路径在模拟器不可测。
10. 用户反馈过的 UI 问题均已修：滚动位置跨日保留（切日回顶）、右轴文字换行/裁剪（maxLines=1 + 右对齐 + 加宽轴区）、周图星期对齐（画进图内）、周从周一开始。

## 6. 已知问题 / 待办

- **[待确认] 真机上小时图分类颜色**：用户反馈"拖动看每小时构成似乎没实现"。实为静态彩色段，无交互。计划：点某根柱子弹出该小时分类构成；并核查真机上分类色是否正确显示（如大面积灰色=字典外应用落"其他"）。
- **[建议] release 签名包**：debug 包是滚动卡顿的主因之一。
- CSV/JSON 导出到下载目录；Gradle wrapper；速览插件专项（需欢太账号+个人开发者认证申请 Card 权限，见与用户的讨论）。
- 手动改分类后小时图立即变色已实现（categories 状态即时更新）；排行里新增应用需重新进入当日。

## 8. 2026-09-13 「列表时长虚高、明细反而准」根因分析（✅ 已在 v0.16.0 修复）

用户报告：列表行时长明显大于实际，明细弹窗的合计才和 ColorOS 屏幕使用时长对得上。取手机 DB + `dumpsys usagestats` 三方对账（证据文件在机主本机 `analysis/` 目录，不入库：st.db、dumpsys_usagestats.txt），结论是**两个独立 bug + 一个放大器**：

- **根因 A（明细少算）**：`buildSessions()` 把 `ACTIVITY_STOPPED` 当关会话条件。ColorOS 应用内切 Activity 恒为 `PAUSED(旧)→RESUMED(新)→STOPPED(旧)` 同戳三连，STOPPED(旧) 与 openPkg 同包 → 把刚开的会话 0 秒关闭，后续该 Activity 的 PAUSED 又因 openPkg=null 被忽略 → **多 Activity 应用（知乎/小红书/淘宝/美团/B站/微信…）整段整段丢时长**（知乎丢 ~90%，B站丢 ~64%，单 Activity 应用如抖音主界面/DeepSeek 无损）。实测：仅认 PAUSED 重建 24h 窗口，与系统 `totalTimeUsed` 桶逐包一致（B站 28.4=28.4、知乎 9.6≈9.7、美团 2.2=2.2）。
- **根因 B（列表错日）**：ColorOS 的 `queryUsageStats(INTERVAL_DAILY)` 返回的是**以 ~16:40 为锚点的滚动 24h 桶**（dumpsys "daily stats files" 文件名铁证：2026-09-03 16:39:21、09-04 16:39:21…），不是自然日桶。`syncAggregatesViaApi()` 按桶中点归日 → `usage_daily[某天]` 实际是 [前一天16:40 → 当天16:40] 的量。9/13 实测列表 6.5h 中有 **184 分钟是 9/12 傍晚 16:49–24:00 的使用**被并进了"今天"；usage_daily[9/13] 与该滚动桶逐包精确相等（知乎 9.7=9.7、微信 37.4=37.45、抖音 29.7=29.8）。
- **放大器 C**：`dailyRows()` 逐包取 max(会话合计, usage_daily)。A 使会话偏小、B 使聚合错日偏大 → max 恒取聚合值 → 列表=错日的桶值、明细=少算的会话，正是用户看到的现象。
- 连带污染：`dayTotal` 大数字、应用占比、报告（`usageSumBetween`/`topAppsBetween` 全读 usage_daily）；yearly 桶（4/19 起）也会被中点归日写进 6 月某天。
- **修复方向**：A) buildSessions 删掉 ACTIVITY_STOPPED 分支（STOPPED 恒随 PAUSED 之后，不需要它关会话；仅 force-stop 无 PAUSED 时会话顺延到下一个 RESUMED/息屏，可接受）；B) 列表/大数字/占比/报告一律以会话表为唯一权威，`usage_daily` 仅作无 root 兜底并标注口径，删除中点归日；C) dailyRows 去 max。存量 usage_daily 404 行全是错位桶值应清空；存量会话中被 A 丢掉的时长无法恢复（事件日志仅留 24h），修复只对之后的日期生效。
- 修复后预期：列表=明细=系统 totalTimeUsed 口径（±2s 碎片过滤差 1~3%）。注意：多 Activity 应用的明细合计会**变大**，若届时仍与 ColorOS UI 有差，则说明 ColorOS UI 自身也少算，以系统桶为准。
- （2026-09-13 后续）历史日已用系统桶数据回补，见 4.0.2；当日之后新算法会话即为准确口径。

## 7. 验证命令速查

```bash
# 授权状态
adb shell appops get com.local.screentime android:get_usage_stats
# 系统 bottom line 对账（与 app 显示对比）
adb shell dumpsys usagestats | grep -m1 "package=com.ss.android.ugc.aweme totalTimeUsed"
# 冻结状态（冰箱 = disable-user）
adb shell pm list packages -d
# 白名单
adb shell dumpsys deviceidle whitelist | grep screentime
# 数据库（拉到本机用模拟器 sqlite3 查，或 run-as）
adb exec-out run-as com.local.screentime cat databases/screentime.db > db.db
```
