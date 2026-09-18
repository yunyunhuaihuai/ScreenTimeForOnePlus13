# 屏幕时间（ScreenTimeLog）项目交接文档

> 供后续 AI Agent / 开发者接手。环境与工具链先读机主本机的交接/安装说明（路径不入库），其中含 Clash TUN 断网等必读的坑。

## 1. 项目是什么

一台一加 13（ColorOS 15）上用"冰箱"冻结应用后，**系统自带屏幕使用时间不再显示这些应用的时长**（展示层过滤）。本项目自建记录器：跨三层混合数据源重建完整的使用时长 + 耗电统计，**冻结应用照样显示**，并逐步加入 iOS 屏幕时间风格的可视化。

- 目标设备：OnePlus 13（PJZ110），ColorOS 15 / Android 15（API 35），KernelSU root，屏幕 1440×3168
- 包名：`com.local.screentime`，应用名“屏幕时间”，当前 v0.16.9（versionCode 25）
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
- ⚠️ **升完版本号必须重新构建 + 复核产物版本**：产物版本只以 `app/build/outputs/apk/debug/output-metadata.json`（或 `adb shell dumpsys package com.local.screentime`）为准，`build.gradle.kts` 改完不重建就会打出旧版本号的包（曾踩，详见 4.0.7）

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

## 4. 已实现功能（截至 v0.16.6，versionCode 22；各版本变更按时间倒序见下方 4.0.x）

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
- ~~诊断导出（菜单）~~：**实际不可达**——`UsageRepository.diagnosticJson(day)` 实现完整（当天会话+聚合+root/权限状态 JSON），但标题栏 ⋮ 按钮没有任何弹窗入口，属死代码。详见 4.0.7。
- 应用名"屏幕时间"，自适应图标（蓝底白时钟+黄色弧）

## 4.0.12 v0.16.11 变更（重置误判导致耗电成倍虚高 + 列表 <0.5mAh 静默不显示）

- **虚高（严重）**：系统会在两次 dump 之间对每 UID 估算值整体重算/smeer（真机实测 u0a330 9.9→9.67、u0a333 279→274，-1.8~-2.3%），旧判据 `new < old-0.5` 把这种回落误判成「充电重置」→ `totalDelta = 全额累计` 再记一遍。真机实测：抖音（u0a333）09-18/09-19 两天被记出 **1143mAh，而窗口真实累计只有 274**（虚高 4 倍）；0.16.9/0.16.10 部署当晚调试密集同步（30 分钟 5 轮）放大了它。历史日（09-14/15/17 等的 300+ mAh）同样有此污染。
- **修复**：重置判据改比例——`new < old × 0.5`（真实重置打到接近 0；重算抖动只有百分之几）。UID 行与整机 computedDrain 同改。判据变化已用单测钉死（UsageRepoMathTest：rescaleDip 不触发 / 真实重置触发 / 小数值应用边界）。
- **列表显示**：时长模式行尾 `totalMah >= 0.5` 无 else 分支，<0.5mAh 的行（如 UU远程 0.23、冰箱 0.11）静默不渲染，用户看到「压根没有耗电」。补 else 显示 `%.2f mAh`（详情弹窗 v0.16.7 起已有「可忽略」分支，列表此前没有）。
- **数据修复（一次性，脚本在机主本机 analysis/）**：利用部署当晚留存的 5 份 DB 快照，对每个基线快照 UID 计算 `膨胀量 = 当晚 recorded 增量 − 真实增量(cum_now − cum_base 按夜区间分摊)`，从 battery_daily 中精确扣除；抖音 09-19 修至 0（真实 overnight 用量 ≈ 0）、09-18 回到 ~323；bilibili/QQ 等未误判 UID 校验膨胀量 ≈ 0 不动。历史日的既有污染不回算（量不可精确追溯），下次充电重置后自然回到正确口径。
- 经验：**差分式记账的「累计值」必须用单调计数器，不能信 dump 里的展示值**——AOSP/ColorOS 的每 UID mAh 是模型估算且会被整体重算；若将来仍需更稳，可改对括号内各组件（screen/cpu/…，由单调计时器 × 固定电流构成）求和做差分。

## 4.0.11 v0.16.10 变更（修复冷启动首同步恒降级：`isAppGrantedRoot()` 在 shell 建立前为 null）

- **现象（0.16.9 真机部署时发现）**：冷启动（force-stop 后打开/进程被杀后重建）后的**第一次**同步恒走 API/DUMP 兜底（`rootUsed=false`），同进程第二次同步才走 root。装机当晚实测：首同步写水位线（API 兜底分支）、点刷新（同进程）立刻走 root。
- **根因**：`MainActivity.LaunchedEffect` 里 `Shell.getShell()` warm-up 与 `sync()` 并发；`syncNowLocked` 的 `Shell.isAppGrantedRoot()` 在 su 通道尚未建立时返回 **null（unknown）**，`== true` 判 false → 降级。旧版进程存活期长，首同步之后一切正常，故从未暴露；0.16.9 部署当晚进程频繁重建才现形。
- **修复**：root 检查改为 `Shell.getShell().isRoot`（同步等待 su 握手完成后读权威状态；su 不可用时 getShell() 返回非 root shell，自然降级，无副作用）。warm-up 保留（并行握手）。
- **判别手法（可复用）**：root 路径不写 `event_watermark`（仅 API 兜底分支写）→ 对比 `battery_snapshot.updatedTs` 与水位线时间即可判定某次同步走了哪条路；点刷新按钮（真机坐标 ≈ 920,282，列表需先滑回顶部）可在同进程触发二次同步而不重建进程。
- 部署当晚真机验证：root 路径与 DUMP 路径均解析出 **150 个 UID**（旧解析器只会得到 103 个），bilibili（u0a334，297mAh）与 u0a351（585mAh，含相机 417mAh 视频通话耗电）全额入库，跨午夜增量按 dayWeights 分摊（9-18/9-19 两行），昨日丢失的耗电按机制部分追回。

## 4.0.10 v0.16.9 变更（修复耗电"消失"根因：UID 行含 `fgs:` 字段被正则整行丢弃）

**背景**：v0.16.7 曾把 bilibili 当天耗电缺失定性为「充电重置盲区」，v0.16.8 落地三层抢拍后用户仍反馈 bilibili 等应用耗电消失。2026-09-18 真机取证（DB + `dumpsys batterystats --charged` 对账）找到真正的根因——**解析层静默丢行**，三层抢拍同源致盲：

- `parseBatteryPower` 的 UID 行正则只认 `fg:`/`bg:`/`cached:` 三个可选字段，而 dump 实际可输出 `fgs:`（前台服务耗电，位于 bg 与 cached 之间）：`UID u0a334: 247 fg: 28.8 bg: 132 fgs: 16.9 cached: 12.5 (…)` → 整行失配被静默丢弃。
- 真机当前 dump 有 **46 个 UID 行含 `fgs:`**，与当轮（23:45）成功更新快照的 101 个 UID **交集为空**；每轮只更新 1~3 行的小轮次，更新的都是当时恰好没有前台服务的应用。当前窗口耗电第一的 u0a351（586mAh，fgs: 116）同样在丢弃之列。
- 后果链：应用带前台服务期间（视频播放/下载/导航…）快照停更 → 真实累计持续增长而快照停在旧小值（bilibili 当晚停在 20:58 的 0.0831mAh，真实已 ~247mAh）→ 夜间充电窗口重置后新值 < 旧快照-0.5 不成立（旧快照本身就小，reset 误判 false）→ 差分为负被 `coerceAtLeast(0)` 丢弃 → 该段耗电永久丢失。
- **v0.16.8 的三层抢拍（插电接收器/15 分钟采样/全量同步）共用同一解析器，全部同时致盲**——这就是 v0.16.8 之后问题仍在的原因。
- 附带更正：4.0.8 的「bilibili=u0a182」取证结论有误。DB 里 u0a182 自 09-12 起一直是 `com.coloros.ocs.opencapabilityservice`；bilibili 实际是 u0a334，且其耗电行在 09-18 之前一条都不存在（而会话每天都有）——丢失是长期的，不是 09-17 一天。

**修复**：
- UID 正则改为把 total 之后到 `(` 之间的标注序列整体捕获（`([^()]*)`），再按 label 提取 fg/bg——对字段子集/顺序变化及未来新增字段稳健。
- `deltaFrom`/`dayWeights` 移入 companion object（纯函数，行为不变），使 JVM 可单测；新增 `testImplementation junit`。
- 新增单测：`BatteryParseTest`（真机 dump 原始行逐字节回放；修复前 3 用例失败=bug 钉死，修复后全过）+ `UsageRepoMathTest`（跨午夜/多天/重置/首次同步的日归属分摊 + 「快照过期+充电重置」丢失机制的语义钉死）。

**验证（2026-09-18/19）**：
- 单测 20/20 通过；`assembleDebug` 产物复核 versionCode=25/versionName=0.16.9。
- 模拟器 e2e（OP13_API35，无 root 走 DUMP 兜底路径）：dump 中全部 9 个含 `fgs:` 的 UID 均入快照表（修复前为 0），第二轮差分连续无双计（增量低于 0.001 阈值被正确跳过），API 兜底会话 43 条入库，15min 采样 + 6h 对账双任务注册正常。
- root 与 shell 的 `--charged` 输出格式一致（`fgs:` 字段两种格式都在），修复对两种格式同样生效。
- **待真机验证**：装 0.16.9 后观察 bilibili/抖音等前台服务应用插电过夜后耗电是否留存；预期 u0a334/u0a351 等的快照每 15 分钟都刷新。

## 4.0.9 v0.16.8 变更（耗电自动存档：插电抢拍 + 15 分钟兜底采样，补齐「充电重置丢数据」盲区）


**背景**：v0.16.7 取证定案——`batterystats --charged` 在充电开始时被系统重置，「上次同步～插电」段的估算耗电永久丢失（bilibili 8.8 分钟实测）。v0.16.8 让 app 自动在重置前把累计值存下来，日汇总不再缺段。

**实现（三层）**
1. **插电瞬间抢拍**（`PowerEventReceiver`）：静态注册 `ACTION_POWER_CONNECTED`（隐式广播豁免清单内，重启后仍有效），插电时 `goAsync` + root/DUMP 通道立即读最终累计值、差分入库。与系统重置存在竞态：抢跑成功则零丢失；失败则读到重置后小值（差分逻辑自动识别重置，不产生脏数据）。
2. **15 分钟兜底采样**（`BatteryCaptureWorker`）：WorkManager 周期任务只做耗电快照（毫秒级轻量，不做全量同步），把抢拍失败时的最大丢失窗口从 6h 压到 ≤15 分钟；高频差分同时让日归属更精确。
3. **无 root 备用通道**：manifest 声明 `DUMP` 权限，`syncNow` 在 root 不可用但持有 DUMP 时也执行耗电采集。

**关键发现（API 35 dumpsys 门槛）**：应用进程直接跑 `dumpsys batterystats --charged` 需要 **DUMP + PACKAGE_USAGE_STATS 两个权限**（后者 root 时被 su 绕过，一直没暴露）。debug 构建可 `adb shell pm grant` 双授权后走无 root 采集；release 无 root 时仍需 root 通道。

**模拟器验证（OP13_API35，2026-09-18）**
- ✅ 采集链路：应用侧 dumpsys → 解析（uids/globals/windowStart）→ 快照差分入库，三次采集快照持续累计
- ✅ 两个周期任务注册正常（15min battery-capture + 6h usage-sync）
- ✅ `syncNow` 无 root 时 DUMP 兜底采集生效
- ⚠️ **插电接收器在此镜像上无法验证**：API 35 模拟器镜像系统性拦截 manifest 广播（`skipped by policy: Background execution not allowed`，连 Google Play 服务自己的接收器都被拦，前台/bucket=ACTIVE/appops allow 均无效）→ **待真机验证**（POWER_CONNECTED 是豁免广播，生产镜像对正常应用应无此限制）
- 模拟器耗电数值 0.0000x mAh 量级低于 0.001 记录阈值，日聚合行不写入属正确防垃圾行为（真机 285mAh 量级无此问题）

**真机验证（OnePlus 13 / ColorOS，2026-09-18 16:26，端到端通过 ✅）**
- v0.16.8 已装机（versionName=0.16.8 确认），root 全量同步正常
- 用 `dumpsys battery unplug` → `set ac 1`（测后 `reset` 恢复）模拟拔插电：`dumpsys activity broadcasts` 派发记录显示 `#70 DELIVERED terminal +857ms com.local.screentime.PowerEventReceiver` —— **真机未被后台策略拦截**（与模拟器镜像行为相反）
- 端到端证据：`battery_snapshot` 全部 223 行的 `updatedTs = 16:26:24.241`，与广播派发时刻（16:26:24.213）吻合 → 接收器触发 → dumpsys 解析 → 差分入库，整链路在插电瞬间完成
- 排查手法沉淀：接收器触发的最硬证据看 **DB 时间戳**，logcat 缓冲区会被 ColorOS 海量日志快速轮转覆盖，不可靠

**真机部署**
- 直接安装即可，root 路径无需任何授权动作
- 可选：`adb shell pm grant com.local.screentime android.permission.DUMP` + `pm grant android.permission.PACKAGE_USAGE_STATS`（debug 构建可授）启用无 root 备用通道

## 4.0.8 v0.16.7 变更（五项用户反馈修复：同步互斥/拿起口径/bilibili 耗电真相/⋮菜单/打开次数）

**数据层（UsageRepository / UsageDao）**
1. **同步加 Mutex**：UI 手动刷新与 WorkManager 6h 对账并发时，会话/耗电增量会双计或交错（此前 `syncNow()` 无锁，已修）。
2. **API 兜底降级**：ColorOS 裁剪 `queryEvents`（丢 PAUSED 事件 → 会话跨息屏虚高），API 事件流现在**只在 root 没拿到事件时**才用于重建会话与 day_stats/unlock_stats（它们是整行 REPLACE，残缺数据覆盖 root 完整数据是倒退）。
3. **fg/bg 耗电差分修复**：`fg:`/`bg:` 在 batterystats UID 行**括号之外**，此前误从括号内组件表取值恒得 0 → battery_daily 全表 fg/bg 恒 0。现在快照里单独存 fg/bg 并正确差分（装机验证：知乎 fg=0.5）。
4. **多用户 UID 解析**：`uidKeyOf/uidOf` 只认 `u0a*` 前缀，ColorOS 分身用户 `u999a*` 解析成 0 → 分身应用耗电行 packageName 恒 null。支持 `u<user>a<index>`。
5. 耗电记录阈值 0.01→0.001 mAh；删除死代码 `parseCheckinAggregates`/`dao.dayTotalMs`/`earliestSessionTs`/`sessionsSumsSince`。

**UI 层（MainActivity）**
6. **「拿起 N 次」口径说明**：拿起 = KEYGUARD_HIDDEN 总数；Top 分项只统计"解锁后 15 秒内打开应用"的解锁（现在加载全量算总和）。卡片显示"其中 X 次解锁后 15 秒内打开了应用，其余多为亮屏看桌面/通知"——290 次拿起 vs Top3 之和 67 次不是数据错误，是口径不同。
7. **详情页「打开 N 次」按轮次合并**：相邻会话间隔 ≤90 秒合并为一次打开（此前直接数会话段数，碎片化下知乎 19.9 分钟被拆 321 段 → 显示"打开 321 次"）。明细列表同样按轮次展示。
8. **电量去向 mAh 数值不再换行**：38dp 固定宽度 → 44dp + maxLines=1；卡片加口径说明"自上次充满电以来，充电即重置"。
9. **⋮ 菜单接通**：此前 `menuOpen` 只赋值从未渲染，按钮无响应、诊断导出不可达。现在渲染 DropdownMenu + 「导出诊断（当日 JSON）」（ACTION_SEND 分享 `diagnosticJson`）。
10. **bilibili 不显示耗电 = 机制性真相，非 bug**：取证确认 bilibili=u0a182，当天使用全部在 17:09–17:18，而 18:05 充电触发 batterystats 窗口重置（上次同步 16:18）→ 那 8.8 分钟的估算耗电被**永久丢弃**（系统口径"自上次充满电"的盲区）。数据链路本身是通的（历史行存在，仅 0.02mAh 量级）。UI 改为如实告知：耗电 <0.5mAh 显示"可忽略"；完全无记录时显示"耗电：无记录（充电重置会丢失未被同步时段的估算）"。

**装机验证**（真机 e4e1b43e，2026-09-17）：day_stats 今日 296 次实时更新（此前卡在 09-03）；知乎 fg=0.5 非零；⋮ 菜单弹出正常；bilibili 详情页显示 8分45秒 / 打开 1 次 / 无记录说明。

## 4.0.7 2026-09-17 工程化与取证记录（不含代码变更）

### Git / GitHub 接入（本工程首次入库）
- 此前工程**没有 git**。本日建立仓库并推送到 GitHub（公开）：`git@github.com:yunyunhuaihuai/ScreenTimeForOnePlus13.git`，默认分支 `main`。
- 新增 `.gitignore`（排除 `build/`、`.gradle/`、`.kotlin/`、`local.properties`、`.workbuddy/`、`HANDOFF.local.md`）、`.gitattributes`（行尾规范化）、`README.md`。
- **公开前脱敏**：`HANDOFF.md` 已移除真机 adb 序列号与本机绝对路径（JDK/Gradle/SDK 安装路径、`analysis/` 证据目录），构建命令改为 `<占位符>`；未脱敏原件保留在 `HANDOFF.local.md`（已 gitignore，不入库）。**后续提交请沿用此约定：不写设备序列号、不写本机绝对路径。**
- **认证方式**：仓库级**部署密钥**（只授权这一个仓库）+ 仓库级 `core.sshCommand` 指向该私钥；全局 `core.sshCommand` 不再绑定具体密钥，只保留 known_hosts 处理。**不要**拿账号级 SSH 私钥用于本项目。
  - 坑：本机用户名含中文 → 私钥不能放默认 `~/.ssh`（HOME 编码问题），必须放**纯 ASCII 路径**（具体路径见机主本机交接说明，不入库）。
  - 坑：该密钥目录原 ACL 为 `Everyone:(F)`（任何本机账户可读私钥），已收紧为「用户 + SYSTEM + Administrators」。
  - 坑（环境副作用，非仓库问题）：本沙箱里 `git fetch`/`update-ref` 写 `.git/refs/remotes/origin/main` 会静默失败（reflog 有记录、ref 文件不存在 → `git status` 显示 `[gone]`），但 `push`/`fetch`/`ls-remote` 均正常。
- 约定：此后每次代码改动都 commit + push。

### ⋮ 菜单按钮取证（已确认是死按钮）
- 标题栏右上角一排四个 `HeaderAction`（`MainActivity.kt:485-506`），**从右往左**依次为：**菜单（⋮ / `Icons.Filled.MoreVert`）→ 设置（⚙）→ 通知（🔔）→ 刷新（↻）**。
- 除 ⋮ 外三个均有实现：刷新 → `sync()`（同步中原地换成转圈，尺寸不变）；设置 → `AlertDialog`（715 行起，模块排序 + 主题三档）；通知 → `AlertDialog`（773 行起，历史报告列表 + 手动生成）。
- ⋮ 是死的：`menuOpen` 仅在 340 行声明、505 行赋值 `true`，**全工程再无第三处引用**，没有对应的 `DropdownMenu(expanded = menuOpen, ...)`（879 行那个 `DropdownMenu` 属于分类筛选的 `catMenu`，与之无关）。
- 真机复现（v0.16.6 装机后）：`adb shell input tap 1313 282` 后间隔 1 秒截图，与点击前做**像素级 diff** —— 差异区域仅 `y 54~106` 一条（状态栏时钟秒数在跳），**全程无弹窗**。证据图见机主本机 `uitest-out/`（`v166_menu_where.png` 标注图 / `v166_home.png` / `v166_after_tap.png`，均不入库）。
- 下一步（尚未实现）：给 505 行那颗按钮挂 `DropdownMenu`，第一项调 `repo.diagnosticJson(selectedDay.toEpochDay())` → 写剪贴板 → Toast 提示；可再加一项「导出原始 batterystats」。

### 构建产物版本号坑（重要，别再踩）
- **现象**：`app/build.gradle.kts` 已改成 `versionCode 22 / versionName 0.16.6` 且已提交，但那次 `assembleDebug` 产出的 APK 里仍是 `versionCode 21 / versionName 0.16.5`（`output-metadata.json` 与 `adb shell dumpsys package` 都是 21/0.16.5）——因为**版本号是在构建之后才改的**，二进制没带上。
- **规则**：升完 `versionCode`/`versionName` **必须重新构建**，并用 `app/build/outputs/apk/debug/output-metadata.json` 或 `dumpsys package` **复核**；只看 `build.gradle.kts` 或 git log 会误判版本。重新构建后已确认设备上为 22 / 0.16.6。

### 真机取证环境发现（可复用）
- **ColorOS 不把三方 App 的 `Log.d` 写进 logcat 缓冲区** → 真机上拿不到 app 自身日志，调试要改用「同步前后拉 DB 做前后对比」或 root 侧取证。
- **拉数据库必须 `adb exec-out run-as <pkg> cat databases/screentime.db > db.db`**；用 `adb shell cat` 经管道会被 CRLF 破坏，得到 `database disk image is malformed`。
- `.workbuddy/` 目录下写入的文件**跨命令不持久** → 测试产物一律放仓库外目录（本机 `uitest-out`，不入库）。

## 4.0.6 v0.16.6 变更（耗电日归属修复：跨天窗口不再整体记到今天）

**现象**：用户报「今天基本没使用相机，为什么相机耗电 988mAh」。

**排查结论（真机取证）**：数字本身不假，但**日子错了**——那是前一天晚上的视频通话耗电。
- `dumpsys batterystats --charged` 是「**自上次充电以来**」的窗口，起点不是当天 0 点。本机实测窗口起点 = `2026-09-17 18:05:06`，即上一个窗口跨了 09-16 18:05 → 09-17 18:05。
- 设备逐 UID 相机计时器（`dumpsys batterystats --checkin` 的 `cam` 记录，单位 ms）：QQ `2217697ms/14次`(37.0分)、微信 `951507ms/5次`(15.9分)、相机App `15959ms/2次`(16秒) → 合计 **53.1 分钟**；与 App 记录的 `durationMs=3161912`(52分42秒) 吻合（差额 23s 正是之后那两次 8s+6s+16s 里的后两次）。
- 相机耗电 = `相机开启时长 × camera.avg`（AOSP `CameraPowerCalculator`，纯模型估算）→ 53.1 分 × ~1117mA ≈ **988mAh**，数值完全自洽。
- 时间核对：09-16 20:42–23:48 的 QQ/微信长会话（各 3–9 分钟，共 8 段）正是那 53 分钟的来源；而 **09-17 白天相机真实使用只有 14 秒**（相机服务事件日志：12:54:21-29 的 8s、17:10:58-17:11:05 的 6s，另有微信 17:45:46-17:46:02 的 16s 扫码），且 09-17 逐 UID 相机耗电全为 0。
- 结论：**不是相机待机耗电，是「昨夜的视频通话」被记到了今天。**

**根因（代码层）**：`syncBattery()` 把「窗口累计值 - 上次快照」的增量一律记到 `LocalDate.now()` 那一天。
累计计数器两次观测之差 = 该时间段内真实发生的用量，因此归属应按观测区间与各本地日的重叠比例来算。

**修复**：
- `parseBatteryPower()` 新增解析 `Start clock time:` → `BatteryParsed.windowStartTs`。
- 新增 `deltaFrom()`：增量的观测区间起点 = 上次快照时间；窗口重置/首次同步时用统计窗口起点（更贴近真实发生时间）。
- 新增 `dayWeights()`：把 `[上次快照 → 本次]` 区间按本地日重叠比例拆权重，UID 与整机组件增量都按权重分摊到对应日（跨天窗口 09-16 18:00→09-17 00:00 实测 100% 归 09-16；09-17 06:00→12:00 100% 归 09-17）。
- 日志补打 `window=...`，便于日后核对统计窗口起点。
- 注意：本修复只对**修复后的新数据**生效，历史已错记的行不会自动回算。
- 已知遗留（本次未改）：同一行里 `mah` 是累加值、`durationMs` 只在当天首次插入时写一次，两者口径不一致（`durationMs` 目前未在 UI 展示）。若日后要展示「相机开启时长」，需把 duration 也按增量累加。
- 另一个易被误会的点：Android 的 `camera` 组件 = **相机子系统被占用时长 × camera.avg**（本机约 1117mA），所以**视频通话、扫码、前后台取景都会计入「相机」**，不只是拍照。排查时先看 `dumpsys batterystats --checkin` 的 `cam` 记录（逐 UID 的相机计时，单位 ms）与 `dumpsys media.camera` 的 CONNECT/DISCONNECT 事件日志相互印证。

## 4.0.5 v0.16.5 变更（启动闪屏跟随 app 内主题 + 真机 UI 回归）

- **彻底修复启动闪屏颜色（v0.16.4 只修了一半）**：v0.16.4 把 manifest 主题从固定暗色的 `Theme.DeviceDefault` 换成 `Theme.ScreenTime`（`values/` 亮、`values-night/` 暗）后，闪屏底色变成**跟随「系统」明暗**——但 Android 12+ 的启动闪屏是**系统在进程启动之前**就按资源限定符 `-night` 解析好的，`-night` 只认系统设置，与 app 内的「白天/黑夜」选择无关。于是：
  - 系统亮 + app 设白天 → 闪屏亮（正确，v0.16.4 已修好的那一半）
  - **系统暗 + app 设白天 → 闪屏仍是 `#1C1B1F` 黑底（错）**
  - **系统亮 + app 设黑夜 → 闪屏仍是 `#FFFBFE` 白底（错）**
  真机抓帧实测：冷启动首帧角落色 = `(28,27,31)` = `values-night/colors.xml` 的 `#1C1B1F`，与 app 内的白天设定不符。
- **修法**：`App.onCreate()` 调 `UiModeManager.setApplicationNightMode(...)`（API 31+，本项目 minSdk 35），把 app 内选的主题告诉系统；在设置页切换主题时也调一次（`MainActivity` 的主题回调里），使**下次冷启动**的闪屏立即正确。映射：`light → MODE_NIGHT_NO`、`dark → MODE_NIGHT_YES`、`system → MODE_NIGHT_AUTO`（经真机验证 `MODE_NIGHT_AUTO` 在本 API 上的语义是「跟随系统」，符合预期）。官方文档明确推荐该 API 用于「让系统在启动画面期间匹配主题」。
- **注意**：`MainActivity.applyTheme()` 里的 `window.setBackgroundDrawable(...)` 对启动闪屏**无效**（闪屏由系统绘制、且早于 `onCreate`），它只影响闪屏消失后到首帧 Compose 之间的窗口底色。真正决定闪屏颜色的是 `UiModeManager` 的应用级夜间模式 + `values`/`values-night` 的 `window_background`。
- **真机 UI 回归结论（一加 13 / PJZ110，ColorOS，系统亮色）**：四种组合的闪屏颜色已全部实测通过（白天/黑夜 × 系统亮/暗）；右上角刷新键有效（点击后图标切换为进度弧，约 5s 后恢复，且时长数据由 5小时55分 → 6小时1分，证明确实拉到了新数据）；设置页三档主题切换正常、无崩溃；小时图/分类占比/最常使用/逐应用时长与耗电各模块渲染正常。
- **重新确认的遗留问题**：标题栏 ⋮ 按钮点击后**无任何菜单弹出**（`menuOpen` 仍只赋值、从未渲染 `DropdownMenu`），`diagnosticJson()` 仍是死代码。

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
11. **耗电统计是"窗口口径"，不是"自然日口径"**：`dumpsys batterystats --charged` = 「自上次充电以来」，起点不是当天 0 点、可跨午夜甚至跨多天 → 任何「按今天」的聚合都必须按窗口时间轴分摊（v0.16.6 已修，见 4.0.6）。且各组件耗电全是**模型估算**：`组件 mAh = 该组件计时器时长 × power_profile.xml 里的固定电流常数`；相机的"计时器"= **相机子系统被占用时长**，所以**视频通话、扫码、前后台取景都会计入「相机」**，不只是拍照。
12. 真机取证的两个环境坑（调试时必踩）：ColorOS **不把三方 App 的 `Log.d` 写进 logcat 缓冲区**；拉数据库必须用 `adb exec-out`（用 `adb shell cat` 会被 CRLF 破坏成 `database disk image is malformed`）。详见 4.0.7。

## 6. 已知问题 / 待办（2026-09-17 更新）

### 确认存在、待修（大致按性价比排序）
1. **标题栏 ⋮ 按钮无响应** —— `menuOpen` 只赋值不渲染弹窗，导致「诊断导出」不可达（v0.16.6 真机已复现，取证见 4.0.7）。修法已明确、工作量小，建议下一版直接做。
2. **`syncNow()` 无 Mutex** —— UI 手动刷新与 6h WorkManager 对账可能并发，耗电增量会**双计**。
3. **`dragTotal` 用 Compose state 做手势累加** —— 拖动（左右滑切日）时整棵树重组，是滚动卡顿来源之一。
4. **`loadDayAsync()` 无取消机制** —— 快速连续切日可能乱序覆盖，显示到错误的日期数据。
5. **`day_stats` / `unlock_stats` 用 REPLACE 写"当前窗口"计数** —— root 不可用时会偏小。
6. **`battery_global` 同行内口径不一致** —— `mah` 是累加值，`durationMs` 只在当天首次插入时写一次（目前 UI 未展示 duration）。若日后要展示"相机开启时长"，需把 duration 也改成增量累加。
7. **Room `fallbackToDestructiveMigration()` + `exportSchema=false`** —— 任何表结构变更都会**清空历史数据**，改表前务必先导出。
8. **死代码清单** —— `weeklyTotals()` / `dao.sessionsSumsSince()`、`DonutChart()`、`parseCheckinAggregates()`、`dao.earliestSessionTs()`、`dao.dayTotalMs()`、`AppDisplay.stateDesc`、`UidPower.durs`、`UsageRepository.diagnosticJson()`（最后这个接上菜单即可复活）。
9. **[建议] release 签名包** —— debug 包是滚动卡顿的主因之一。
10. CSV/JSON 导出到下载目录；Gradle wrapper；速览插件专项（需欢太账号 + 个人开发者认证申请 Card 权限）。
11. **文档整理** —— 本文件 4.x 章节编号有重复与乱序（`4.2` 出现 3 次，`4.9/4.10/4.11/4.12` 排序颠倒），且"v0.13.0 及更早"的内容在两节里近乎重复；建议下次顺一遍（只动标题与去重，不改内容）。

### 已澄清 / 已过期的旧待办
- 原「[待确认] 真机上小时图分类颜色 + 拖动看每小时构成没实现」**与第 4 节功能列表冲突**：小时柱自 v0.11.0 起已实现"按分类堆叠 + 点柱子弹出该小时构成与明细"（用 24 个透明点按格覆盖解手势失效）。v0.16.5 真机回归时小时图渲染正常，但**未单独测过"点柱子"这条路**，若仍有问题请按新 bug 重开。
- 手动改分类后小时图立即变色已实现（categories 状态即时更新）；排行里新增应用需重新进入当日。

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

# 耗电：「自上次充电」统计窗口的起点（判断"今天"聚合是否跨天，见 4.0.6）
adb shell dumpsys batterystats --charged | grep -m1 "Start clock time"
# 逐 UID 相机计时器（单位 ms，需 root）+ 设备级相机组件 mAh
adb shell "su -c 'dumpsys batterystats --checkin'" | grep -E ",cam,|,pwi,camera"
# 相机服务 CONNECT/DISCONNECT 事件日志（与上面的计时器互相印证）
adb shell "su -c 'dumpsys media.camera'" | grep -i "CONNECT\|DISCONNECT"

# 验证某个按钮"点了到底有没有反应"：点击后截图做像素 diff（比肉眼可靠）
adb shell input tap <x> <y> && sleep 1 && adb exec-out screencap -p > after.png
```

## 8. 2026-09-13 「列表时长虚高、明细反而准」根因分析（✅ 已在 v0.16.0 修复）

用户报告：列表行时长明显大于实际，明细弹窗的合计才和 ColorOS 屏幕使用时长对得上。取手机 DB + `dumpsys usagestats` 三方对账（证据文件在机主本机 `analysis/` 目录，不入库：st.db、dumpsys_usagestats.txt），结论是**两个独立 bug + 一个放大器**：

- **根因 A（明细少算）**：`buildSessions()` 把 `ACTIVITY_STOPPED` 当关会话条件。ColorOS 应用内切 Activity 恒为 `PAUSED(旧)→RESUMED(新)→STOPPED(旧)` 同戳三连，STOPPED(旧) 与 openPkg 同包 → 把刚开的会话 0 秒关闭，后续该 Activity 的 PAUSED 又因 openPkg=null 被忽略 → **多 Activity 应用（知乎/小红书/淘宝/美团/B站/微信…）整段整段丢时长**（知乎丢 ~90%，B站丢 ~64%，单 Activity 应用如抖音主界面/DeepSeek 无损）。实测：仅认 PAUSED 重建 24h 窗口，与系统 `totalTimeUsed` 桶逐包一致（B站 28.4=28.4、知乎 9.6≈9.7、美团 2.2=2.2）。
- **根因 B（列表错日）**：ColorOS 的 `queryUsageStats(INTERVAL_DAILY)` 返回的是**以 ~16:40 为锚点的滚动 24h 桶**（dumpsys "daily stats files" 文件名铁证：2026-09-03 16:39:21、09-04 16:39:21…），不是自然日桶。`syncAggregatesViaApi()` 按桶中点归日 → `usage_daily[某天]` 实际是 [前一天16:40 → 当天16:40] 的量。9/13 实测列表 6.5h 中有 **184 分钟是 9/12 傍晚 16:49–24:00 的使用**被并进了"今天"；usage_daily[9/13] 与该滚动桶逐包精确相等（知乎 9.7=9.7、微信 37.4=37.45、抖音 29.7=29.8）。
- **放大器 C**：`dailyRows()` 逐包取 max(会话合计, usage_daily)。A 使会话偏小、B 使聚合错日偏大 → max 恒取聚合值 → 列表=错日的桶值、明细=少算的会话，正是用户看到的现象。
- 连带污染：`dayTotal` 大数字、应用占比、报告（`usageSumBetween`/`topAppsBetween` 全读 usage_daily）；yearly 桶（4/19 起）也会被中点归日写进 6 月某天。
- **修复方向**：A) buildSessions 删掉 ACTIVITY_STOPPED 分支（STOPPED 恒随 PAUSED 之后，不需要它关会话；仅 force-stop 无 PAUSED 时会话顺延到下一个 RESUMED/息屏，可接受）；B) 列表/大数字/占比/报告一律以会话表为唯一权威，`usage_daily` 仅作无 root 兜底并标注口径，删除中点归日；C) dailyRows 去 max。存量 usage_daily 404 行全是错位桶值应清空；存量会话中被 A 丢掉的时长无法恢复（事件日志仅留 24h），修复只对之后的日期生效。
- 修复后预期：列表=明细=系统 totalTimeUsed 口径（±2s 碎片过滤差 1~3%）。注意：多 Activity 应用的明细合计会**变大**，若届时仍与 ColorOS UI 有差，则说明 ColorOS UI 自身也少算，以系统桶为准。
- （2026-09-13 后续）历史日已用系统桶数据回补，见 4.0.2；当日之后新算法会话即为准确口径。
