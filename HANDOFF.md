# 屏幕时间（ScreenTimeLog）项目交接文档

> 供后续 AI Agent / 开发者接手。环境与工具链先读机主本机的交接/安装说明（路径不入库），其中含 Clash TUN 断网等必读的坑。

## 1. 项目是什么

一台一加 13（ColorOS 15）上用"冰箱"冻结应用后，**系统自带屏幕使用时间不再显示这些应用的时长**（展示层过滤）。本项目自建记录器：跨三层混合数据源重建完整的使用时长 + 耗电统计，**冻结应用照样显示**，并逐步加入 iOS 屏幕时间风格的可视化。

- 目标设备：OnePlus 13（PJZ110），ColorOS 15 / Android 15（API 35），KernelSU root，屏幕 1440×3168
- 包名：`com.local.screentime`，应用名“屏幕时间”，当前 v0.18.0（versionCode 34）
- 工程：本仓库根目录；技术栈 Kotlin + Compose(M3) + Room + WorkManager + libsu，无 NDK
- minSdk/targetSdk/compileSdk = 35；数据库 Room v9（显式迁移 MIGRATION_4_5/5_6/6_7/7_8/8_9，schema JSON 已入库 schemas/，**已移除全局销毁式迁移**——v4+ 升级必须写显式 Migration，缺迁移会显式失败而不是清库）

## 2. 构建与部署

```bash
# 工具链版本：JDK 21 / Gradle 8.9 (Wrapper) / AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.10.01
export JAVA_HOME="<JDK 21 安装路径>"
# 调试构建：
./gradlew :app:assembleDebug
adb -s "$ADB_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk

# 正式优化构建（推荐，运行更流畅）：
./gradlew :app:assembleRelease
adb -s "$ADB_SERIAL" install -r app/build/outputs/apk/release/app-release.apk
```
- 已内置 Gradle 8.9 Wrapper（`gradlew` / `gradlew.bat`），无需再硬编码本机 Gradle 绝对路径
- 真机 adb 序列号由机主本机记录（`adb devices` 查询）；模拟器 AVD `OP13_API35`（google_apis，可 `adb root`，**用于回归测试**）
- ColorOS 安装需要"USB 安装"开关（已开）；`adb shell appops set` 被 ColorOS 拒绝（shell 无 MANAGE_APP_OPS_MODES）
- ⚠️ **升完版本号必须重新构建 + 复核产物版本**：产物版本只以 `output-metadata.json`（或 `adb shell dumpsys package com.local.screentime`）为准，`build.gradle.kts` 改完不重建就会打出旧版本号的包（曾踩，详见 4.0.7）

## 3. 架构：三层数据源（核心设计）

应用内 `UsageRepository.syncNow()` 每次按序执行：

| 层 | 来源 | 用途 | ColorOS 实测情况 |
|---|---|---|---|
| 1. API 事件流 | `UsageStatsManager.queryEvents` | 会话时间线（每次起止） | **被裁剪**：请求窗口内 137 条只返回 12 条；时多时少不可靠 |
| 2. API 聚合 | `queryUsageStats(INTERVAL_DAILY)` | 每包每日总时长 | 基本完整，但"今天"可能偏小 |
| 3. root 直读 | libsu 跑 `dumpsys usagestats` / `batterystats --charged` | 全量事件（~6000 条/天）+ 每UID耗电 | 完整；**这是 root 的核心价值** |

展示策略：**事件重建值与聚合值逐包取 max**（`dailyRows()`）。
耗时统计写入 `sessions` 表 + `usage_daily` 聚合表；耗电写入 `battery_daily` / `battery_global`（快照差分，充满清零自动检测：新值<旧值-0.5 即重置）。

### 表结构（Room v9）
- `sessions(pkg, startTs, endTs, day, source[0LEGACY/1ROOT/2API])` —— 会话（RESUMED/PAUSED 配对，跨午夜切分，<2s 碎片丢弃但同包 ≤2s 短段先合并；缺关闭事件时尾段延伸到可信采集上界；重建时按 startTs >= windowStart 清除，保留跨左边界的历史会话）
- `usage_daily(pkg, day, totalMs, source[0事件/1聚合API/2root])` —— 每日每包时长
- `battery_daily(uidKey, day, total/fg/bg/fgs/screen/cpu/audio/video/camera/gnss/wifi/bt/wakelock/sensors/mobile/flashlight/ambientDisplay/idle/phone/other mAh)` —— 每日每 UID 耗电（v0.16.14 fgsMah；v0.17 mobileMah；**v0.18 补 flashlight/ambientDisplay/idle/phone 四列**，组件字段全映射；**v0.17.0 起共享 UID 行 packageName=null**）
- `battery_global(component, day, mah, durationMs)` —— 整机组件耗电（屏幕/CPU/蓝牙/音频/GNSS/息屏显示…；durationMs 持续差分累加）
- `battery_snapshot(uidKey, cumMah, compsText, ts, recordedCumMah, recordedCompsText, epochWindowStartTs)` —— 差分基线（含 `__global__`/`__global___dur`）；**v0.18 起原始观测(cumMah/compsText)与已入账高水位(recorded*)分离**，epochWindowStartTs 记录所属 epoch
- `uid_packages(uidKey, packageName, firstSeenTs, lastSeenTs)` —— **v0.18 新增**：采集时 UID→包名成员存档，历史归属按有效期解析（不随当前安装状态漂移）
- `capture_log(id, ts, attemptTs, commandStartTs, commandEndTs, zoneId, source, parserVersion, ledgerVersion, outcome, epochWindowStartTs, uidCount, ...)` —— **v0.18 新增**：有界（64 条）结构化采集日志，区分 attempt/command/observed 时刻
- `app_info`（label/图标快照，冻结后仍可显示）、`sync_state`（事件水位线 + 同步/采样诊断状态：last_usage_source、last_battery_source、last_usage/battery_success/fail_ts、cap_* 采样健康、battery_window_start_ts epoch 标记、battery_lost_tail_* 不可恢复尾段、data_revision 数据修订版本、parser_baseline_uid_count 解析基线）
- `day_stats(day, pickups, source)`、`unlock_stats(day, packageName, count, source)` —— **v0.18 加 source**：ROOT 权威可整日原子替换，API 残缺流不覆盖 ROOT 完整日
- `reports(type, periodKey, generatedTs, text, finalized, dataRevision, formatVersion)` —— v6 finalized；**v0.18 加 dataRevision/formatVersion**：数据修订或文案格式落后时静默重建

### 展示与报告的唯一口径（v0.17.0）
`effective usage = 逐包逐日 max(会话聚合, usage_daily 回补值)`，实现在 `UsageDao.effectiveUsageDay/effectiveUsageBetween`；首页 `dailyRows`、周报/月报/年报的总量/Top/环比共用同一查询——UI 与报告不再各自维护一套逻辑，历史回补日（2026-09-04～09-12）数据计入报告。

### 分类
`repo.categoryOf(pkg)`：手动覆盖（SharedPreferences `cat_overrides` JSON）→ 系统 `ApplicationInfo.category` → 内置中国应用字典（`categoryDict`，微信/QQ→通讯社交、抖音/B站→影音娱乐、DeepSeek/豆包→AI 助手…）→ 兜底"其他"。修改入口：应用明细弹窗"修改"。

## 4. 已实现功能（截至 v0.16.6，versionCode 22；各版本变更按时间倒序见下方 4.0.x）

- 每日总量大数字 + 左右箭头/**列表左右滑动**切日期（周一开始的周图：灰柱+今天蓝+虚线均值+右轴刻度右对齐，星期画进图内对齐柱子）
- 小时柱状图：**按分类堆叠**（社交蓝/娱乐橙/AI 紫…，底部图例）；**点任意柱子弹出该小时分类构成 + 应用明细**
- **全天时间轴色带**：0-24 时的会话色带，按分类上色
- **拿起与解锁**卡片：每天拿起次数（KEYGUARD_HIDDEN 计数）+ 解锁后 15 秒内最常打开的 App Top3（表 `day_stats` / `unlock_stats`，root 与 API 事件流双路写入，事件保留约 2 天）
- **报告**：周报/月报/年报自动生成（`reports` 表）。**v0.17.0 语义修正**：正式报告一律针对「最近已完整结束的周期」（周报=上一个 Mon-Sun，周一及以后；月报=上一自然月，次月 1 日起；年报=上一自然年，次年 1 月 1 日起），按 8 周/6 月/2 年有界回溯补生成，错过的周期不会永久错过；手动生成改为「当前周期预览」（finalized=0，与正式报告区分，周期结束后被正式报告覆盖）。卡片可点开全文，含环比上一周期 Top 应用变化、拿起次数、总耗电
- 应用占比：两列"图标+名称+占比%"网格（中间竖分隔线）
- 排行列表：时长/耗电双模式排序；耗电模式含系统条目（Android 系统服务/系统服务…）；行尾显示 mAh + 后台占比（≥30% 标红）
- 应用明细弹窗（点行）：分类（可修改，写 `cat_overrides` 覆盖自动分类）/总时长/打开次数/**全部会话起止与时长（可滚动，无条数上限）**/耗电前后台
- 电量去向卡：整机组件 mAh 排名
- 设置（齿轮）：模块显示顺序 ↑↓（SharedPreferences `module_order` 持久化，模块：weekly/hourly/timeline/pickup/battery/apps/reports/ranking）
- 每 6h WorkManager 后台对账；打开即先读本地库秒出、再后台同步（同步已去掉无用的 --checkin 解析，~1-2s）；Doze 白名单（root 自动添加）
- ~~诊断导出（菜单）~~：**实际不可达**——`UsageRepository.diagnosticJson(day)` 实现完整（当天会话+聚合+root/权限状态 JSON），但标题栏 ⋮ 按钮没有任何弹窗入口，属死代码。详见 4.0.7。
- 应用名"屏幕时间"，自适应图标（蓝底白时钟+黄色弧）

## 4.0.18 v0.18.0 变更（数据准确性与采集可靠性专项：agent/v017-data-integrity 分支续）

基线 = 上一轮 HEAD ca1c973（v0.17.1/vc33，Room v8）。本轮针对 2026-10-03 本机取证审查
（`analysis/2026-10-03-data-integrity/accuracy-review.txt`）列出的 9 组准确性/可靠性问题
（A–I）逐项闭环：历史不丢、增量不重复、采集缺口可见、不同展示口径可解释。数据库
Room v8→v9（MIGRATION_8_9，值级保全测试覆盖真机存量形态）。**所有修复先写能证明真实
问题的回归测试再改**；JVM/Robolectric 171 tests 全过；assembleDebug/Release 产物复核
versionCode=34/versionName=0.18.0。手机未连接，真机回归项明确标注「待验证」。

### A（P0-1 复审）诊断基线按 epoch 隔离
- **问题**：`CaptureDiagnostics` 对所有帧先应用 `parsed < baseline*0.5` 再谈 epoch——旧
  142 UID 基线遇到新充电窗口合法的 20 UID 帧会被拒绝，epoch 标记与健康基线永远无法推进；
  且 `computed=0` 被一概当作解析失败（刚充满的合法帧 drain 就是 0）。
- **修复**：`detectAnomalies` 增加 epoch 参数，`uid_count_drop` 只对 SAME/UNKNOWN 生效，
  RESET 帧不继承旧窗口 UID 下限；`BatteryParsed.computedDrainPresent` 区分「drain 行存在
  但值为 0」（合法零耗电）与「整行缺失」（结构截断）；解析健康基线（UID 数/有无窗口）从
  syncBattery 预提交点挪进 `applyBatteryLedgerTx`，与 epoch 标记/快照/日账本**同事务**推进，
  门控拒绝或事务失败都不推进已接受基线。PARSER_VERSION 3→4。
- **测试**：BatteryCaptureEntryTest 穿过真实 syncBattery 入口+诊断门控+Room 事务（旧142→
  新20→同新30 全成功 / 同epoch截断拒绝且基线不动 / 重试入账 / 重复帧零增量 / UID 延迟重现
  不与旧基线做差）。

### B（P0-2/P1-1）账本高水位记账——回落不二次累计、微量增量守恒
- **问题**：`max(new-old,0)` 后写回更低快照，100→97→100 会再记 3（二次入账）；`delta<=0.001`
  跳过落库但快照已推进，100 次各 +0.0005 永久丢失 0.05 mAh。
- **修复（原始观测与已入账高水位分离）**：battery_snapshot 新增 `recordedCumMah`/
  `recordedCompsText`（已入账高水位）与原始观测 `cumMah`/`compsText` 并存；每帧增量一律
  `max(0, 观测−高水位)`——同 epoch 模型回算不推进高水位，恢复时增量为 0（不二次入账）；
  **取消记录阈值**，存储层任何有限正增量都落库（阈值只留给展示层）；total 无变化时组件/
  fg-bg-fgs/global功率/global时长的独立变化照常入账（各自高水位）。
- **选择与局限（记录在案）**：高水位对「模型永久下调且不再恢复」的极端情形会高估（上限=
  回调幅度，实测 ~2% smear）；不做带符号回冲——负增量无法在 dayWeights 时间归属下无歧义
  落到某一天，且会改写已提交历史行。账本不变量 `ledgerInconsistencies` 持续监督；历史 46 条
  screen>total 行保留诊断、**不擅自用 maxComponent 反推真实 total**（无原始采集帧证据）。
- **测试**：dip/recover 全序列（total/组件/fg-bg-fgs/global/global时长）、100×+0.0005 累计
  0.05、total=0 组件变化、迁移兜底（recorded* 未初始化按旧语义取原始观测）、epoch 标记。

### C（P2-2）采集健康与数据覆盖成为可核验状态
- **时间语义**：attempt/commandStart/commandEnd(=观测时刻) 全部在**拿到进程锁之后**取值；
  差分日归属与成功时间戳用观测时刻（commandEnd），不再复用拿锁前的 now（跨午夜会记错日）；
  `UsageRepository.nowMs` 可注入时钟供测试。健康状态持锁**重新从 sync_state 读取**（删除
  实例缓存），多实例交替写入不再互相覆盖。
- **syncNow 诚实记录**：battery 分支与 captureBatteryNow 共用同一健康状态机（此前 syncNow
  不更新 cap_*）；只有真正观测到数据（root 事件或 API 事件非空）才写 last_usage_success 并
  清错误，否则记 last_usage_fail_ts + 原因——此前 source 错误/空 API/捕获异常后仍无条件写成功。
- **有界采集日志 capture_log（64 条）**：每次尝试一行，区分 attempt/command/observed 时刻、
  时区、epoch 证据、parser/ledger 版本、解析画像与失败原因（结构化可审计观测）。
- **不可恢复尾段**：epoch RESET 成功提交时记录 `[上次成功采集, 新窗口起点]`——充电重置丢弃
  的估算耗电有明确覆盖缺口证据（diagnosticJson `battery.lostTail`）。
- **首页紧凑状态行**：大数字下方「数据更新至 / 耗电采集(通道) / 采样实际最大间隔」；超 12h
  无成功观测提示可能中断（**Android 15 强停后应用自身无法后台运行，需用户打开一次恢复**，
  不做自动运行承诺）。报告对无观测日不输出成可信 0（见 G）。
- **采集健康诊断与账本现存异常分开**：diagnosticJson `captureHealth`/`captureLog` 与
  `ledgerAudit`（全库现存违反行扫描，历史告警是否已解除可查）分段呈现。

### D（P1-4）轮次与真实使用时长分离
- **问题**：明细把合并区间**跨度**当使用时长——9/15 QQ 真实 65分19秒显示成跨度 95分35秒
  （空档 30分16秒 被计入）；9/24 微信一轮真实 16分17秒显示 26 分钟（跨度 26分37秒）。
- **修复**：`mergeSessionRounds` 返回 `SessionRound(start,end,activeMs)`，activeMs = 轮次内
  会话区间**并集**（对 ROOT+API 罕见重叠防御性取并集，绝不把空档计入使用）；明细主值显示
  真实使用时长，跨度仅在明显大于使用时附加「（跨度 X）」。小时图对总量回补日无法定位到小时
  的部分如实单列「另有 N 分钟为系统总量回补，无法定位到小时」，**不合成虚假会话填图**。
- **测试**：SessionRoundsTest（10:00-10:01+10:02-10:03 → 1轮/使用2分/跨度3分、QQ 样例
  active=会话合计、重叠取并集、间隔分组边界）。

### E（P1-2）ROOT 未闭合尾段与短会话
- **问题**：`syncViaRoot` 用「最后事件时间」当窗口结束——最后事件是 RESUMED、其后无关闭
  事件时仍前台会话尾段被截断；同帧多 Activity 三连把一次使用拆成多个 1 秒段被逐段丢弃。
- **修复**：`buildSessions(events, windowStart, trustedEnd, source)` 第三参数改为「可信采集
  上界」=本次采集实际时刻；缺关闭事件时按**暂定尾段**延伸到 trustedEnd，root 每次全量重建
  窗口后续事件自然纠正（**不宣称长尾精准**——缺关闭事件与真实仍前台在单帧内不可区分，已写入
  注释）；同包相邻会话间隔 ≤2s（dump 秒级精度）先合并再套 2 秒最小阈值，Activity 切换碎片
  不再无差别丢弃，独立秒级噪声（前后大空档）仍过滤。滑动窗口左边界/跨午夜/息屏不计前台保持。
- **真机量化（本机取证 usagestats.txt，4746 事件，脱敏不入测试）**：旧算法 776 会话/16,806s，
  新算法 422 会话/16,993s——净回收约 187s（其中 ~60s 尾段上界修正、~127s Activity 切换碎片
  回收），其余片段属独立噪声保持过滤。**与审查「376 段/376 秒」一致，不夸大为全部真实漏记**。
- **测试**：SessionBuildTest 9 场景（末帧 RESUMED 尾段/正常关闭/息屏/跨午夜/多 Activity 同戳
  合并/独立噪声过滤/同帧重复/双开同包/API 路径上界一致）。

### F（P1-3）拿起/首开统计按来源与覆盖原子替换
- **问题**：API fallback 保护只覆盖 sessions，拿起/首开仍是整行 REPLACE 且无来源——多日残缺
  API 流可把完整 ROOT 日统计覆盖偏小；首日跳过仅防左边界不防数据源降级；REPLACE 不清除已
  消失包的首开旧行。
- **修复**：day_stats/unlock_stats 落库来源（v9 source 列）；写入经 `planDayEventStatsWrites`
  纯函数计划——可写日 = 窗口从本地零点完整覆盖 或 该日是「今天」（已过时段被窗口完整覆盖）；
  窗口首日部分覆盖不替换；API 来源只写「无任何已存行」或「已是 API」的日期，ROOT/LEGACY 权威
  日一律保护；ROOT 可写任何可写日（**可纠错，不以 max 锁死历史过计数**）；替换在单事务内先删
  该日全部行再插入（已消失包首开旧行随之清除）；ROOT 纠错实际改变值时 bumpDataRevision。
- **测试**：DayStatsSourceTest（计划器 4 场景 + 入口级 2 场景：整日替换清除消失包 /
  ROOT完整→API残缺→ROOT恢复）。

### G（P1-4）正式报告与数据修订一致
- **问题**：环比按「本期天数向前平移」（30 天月比较到上月中间、闰年不正确）；9/12 提前生成、
  finalized=1 的 Y:2026 旧年度报告仍冒充正式结论；存量月报仍是旧「总耗电」文案；ROOT 修正/
  回补后报告不自动失效；9/25~9/28 无观测日被当成可信 0 参与日均与环比。
- **修复**：`ReportPeriods.previousPeriod` 环比按上一自然周/月（28/30/31 与闰年自动对齐）/年；
  预览对照上一周期同期天数；`periodFromKey` 重建周期，启动时 `demotePrematureFinalizedReports`
  把「周期未结束却 finalized=1」的存量报告降级为预览（周期已结束的过早报告由正式化重生成处理）；
  `needsFinalizedGeneration` 扩展数据修订版本/文本格式版本落后也触发**静默重建**（通知仅限最新
  周期「新正式化」，不重复打扰）；报告新增「数据覆盖：X/Y 天」，未观测日明确不计为使用，上一
  周期完全无观测时不输出「（新增）」改「（上一周期无数据）」。REPORT_FORMAT_VERSION=2。
- **定向验证（P2-2）**：usage_daily 清理挪到回补导入**之前**——两标志均缺失的新库先导入后清理
  不再删掉刚导入的回补（该路径当前手机未触发，存量未受影响）。
- **测试**：ReportRevisionTest 7 场景 + ReportPeriodsTest 既有 18 场景保持通过。

### H（P1-5）历史 UID 归属不被当前安装状态重写
- **问题**：读历史耗电用当前 `getPackagesForUid()`，忽略采集时归属——共享 A+B 后 B 卸载旧记录
  可能全归 A；卸载唯一包历史消失；UID 复用把旧耗电挂给新包。
- **修复**：新表 uid_packages（v9），采集成功时 `persistUidPackages` 持久化每个 UID 当时的包
  成员及有效期 [firstSeenTs,lastSeenTs]；`historicalOwnerOf` 按「该日有效」存档解析（唯一→归属；
  多包→共享不拆分；有存档但该日无覆盖→不确定不猜；从未存档的旧行→回退 current pm 现状兼容）；
  batteryForPackages 归属优先级 = **行上冻结的 packageName（采集时归属，v9 前历史行唯一可靠
  证据，卸载后 current pm 查不到也不丢历史）** → 成员存档按日解析 → current pm；sharedUidLabel
  支持按日用存档展示。**旧行来源无法追溯则保留不确定，不盲目回填**。
- **测试**：UidPackagesTest 7 场景（A+B共享→B卸载不拆分 / 唯一→卸载保留历史 / UID复用按区间
  解析 / 唯一→后续共享按日 / 分身 user0+user999 聚合求和不双计 / 无存档旧行回退 / 冻结
  packageName 优先于 current pm）。

### I（P2-1/P2-2）组件明细完整、估算语义、证据保存
- **组件映射表 COMPONENT_COLUMNS**：每个已解析字段**有且仅有一个**存储目的地；battery_daily
  新增 flashlight/ambient_display/idle/phone 四列（KNOWN_COMPS 已含却无处落库，真机 UID1000
  flashlight=1.34 曾被静默丢弃——修组件完整性，非 total 少记）；bluetooth/bt 别名同列求和；
  未知字段唯一兜底 otherMah。**真机取证 dump 验证：163 UID 全部组件字段 unmappedComps=[]，
  UID1000 flashlight=1.34 已入列**。
- **快照记录 epoch**（epochWindowStartTs）+ 有界采集日志（parser/ledger 版本随帧落盘）。
- **估算语义**：保留 actual 区间/computed/UID 归因不同口径，禁止将重叠 UID/global 相加；报告
  「应用归因耗电（UID 模型估算，非整机实际耗电）」；跨日累计按 dayWeights 墙钟均摊标估计。

### 数据库 v8→v9（MIGRATION_8_9，schema 9.json 入库）
- battery_snapshot +recordedCumMah/recordedCompsText/epochWindowStartTs（高水位与原始观测分离）；
  battery_daily +flashlight/ambientDisplay/idle/phoneMah；day_stats/unlock_stats +source；
  reports +dataRevision/formatVersion；新表 uid_packages、capture_log。
- 迁移测试覆盖 v8（真机当前形态）→9 全表值级保全 + 高水位种子=cumMah（升级后首帧差分与旧
  语义连续）；旧迁移链 4→8 测试保持通过；RoomMigrationTest 全链。

### 真机取证 dump 离线验证（本机 analysis/ 只读副本，脱敏数值入测试）
- `batterystats-charged.txt`：parseBatteryPower 得 **163 UID / 12 global / computedDrain=2094 /
  windowStart=2026-10-02 18:05:44**，与审查基线逐项吻合；`unmappedComps=[]`、`UID1000
  flashlight=1.34` 已映射；新账本对真实帧首同步 `LEDGER_INCONSISTENT=0`（46 条历史异常不复现）。
- `usagestats.txt`：4746 事件，新旧会话算法对比见 E（净回收 ~187s）。

### 待真机验证（手机未连接，不得标注通过）
1. **自然充电 epoch 切换**（模拟插电 unplug→set ac 不触发该 ROM 窗口重置）：观察 epoch 标记
   更新、旧快照全清、当帧 UID 全量入账（抖音式 148→15 吞量不再发生）；epoch 首帧缺席 UID
   延迟重现不与旧基线做差。
2. **高水位记账**：同 epoch 连续采集回落→恢复不二次入账；微量增量累计入库；多次振荡不增长。
3. **采集健康**：强停后打开应用，首页状态行从「中断」恢复到「数据更新至」；diagnosticJson
   captureLog/lostTail/ledgerAudit 与实际一致；syncNow 无观测时不写假成功。
4. **事件统计来源**：root 失效降级期 API 残缺流不覆盖 ROOT 完整日拿起/首开；root 恢复可纠错。
5. **报告**：周一/次月 1 日上一周期补生成；旧进行中 Y:2026 降级为预览；数据修订后相关报告
   静默重建且文案更新为「应用归因耗电」；无观测日报告显示覆盖天数不误报下降。
6. **归属**：卸载应用后历史耗电仍在（不消失）；UID 复用后旧/新耗电按日各归其主；分身双 UID
   聚合展示；共享 UID 不拆分。
- **严禁**：`dumpsys batterystats --reset`/`--reset-all`；给 UID 功率乘经验系数；把 Actual−
  Computed 缺口按比例摊给 App；为迁移清库；用 maxComponent 反推真实 total。

### 4.0.18.1 真机验证记录（2026-10-03 中午，OnePlus 13 e4e1b43e，v0.18.0 debug 构建）

方法同 4.0.16.1/4.0.17.1：装机前 `run-as` 拉 db/-wal/-shm 三件套作基线（pre，user_version=8、
integrity ok），装 v0.18.0 触发 v8→v9 迁移 + 首次同步后拉 post，本机 Node `node:sqlite` 逐位对比。
截图 `analysis/2026-10-03-v018-verify/home_v018.png` 确认 UI。

- ✅ **迁移 v8→v9 值级保全**：历史行（day<今天）battery_daily 2765 行/28797.46507972709、
  battery_global 242 行/29580.87509819、day_stats 23 行/2259、usage_daily 153837128 **逐位一致**；
  reports 17/app_info 140 不变；post integrity ok、user_version=9。总量增长（battery_daily
  30600→31651）全部来自装后真实同步的新数据，非迁移丢失。
- ✅ **新 schema 落地**：battery_snapshot.recordedCumMah/recordedCompsText/epochWindowStartTs、
  battery_daily.flashlight/ambientDisplay/idle/phoneMah、day_stats.source、reports.dataRevision/
  formatVersion、新表 uid_packages（134 UID）/capture_log 全部存在并有数据。
- ✅ **首次同步生效**：last_usage/battery_success_ts 推进、battery_window_start_ts=1790935544000
  （=2026-10-02 18:05:44，与取证窗口一致）、data_revision=1、capture_log 记
  parserVersion=4/ledgerVersion=2/uidCount=170/computedDrain=4152。
- ✅ **A 诊断门控**：170 UID 帧被接受（旧 142 基线不再误拒新窗口小样本）；parserVersion=4 在跑。
- ✅ **B 高水位**：7 个快照 recorded≠cum（模型回算 dip 被拦截、未二次入账）；**今天 0 条新
  LEDGER_INCONSISTENT**（46 条历史行保留为诊断，新版不再产生）。
- ✅ **C 采集健康**：capture_log 四时刻真实可区分（attempt<commandStart<commandEnd=observed）、
  zoneId=Asia/Shanghai、epoch 标记一致；首页状态行真机渲染「数据更新至 10-3 12:55 · 耗电采集
  10-3 12:55（ROOT）· 采样最大间隔 14小时32分钟」——14h32m 即审查发现的停采间隔，如实呈现。
- ✅ **F 统计来源**：day_stats 历史 24 行 source=0(LEGACY)、今天 1 行 source=1(ROOT)。
- ✅ **G 报告**：Y:2026 过早「正式」报告降级 finalized=0（预览）；Y:2025/Y:2024 完整年报保持
  finalized=1 且 dataRevision=1/formatVersion=2（文案已更新）。
- ✅ **H 归属**：QQ 分身 u0a351+u999a351 均归档 com.tencent.mobileqq；共享 UID u0a141/u0a64
  （各 5 包）等多包成员完整存档。
- ⚠️→待自然发生 **epoch 切换**：battery_window_start_ts 当前稳定在 10-02 18:05:44；模拟插电不
  触发该 ROM 窗口重置，须等真实充电后观察 RESET 全清+全量入账。
- ⚠️ **强停恢复/状态行中断提示**：需用户强停后打开验证（本轮未强停）。
- ⚠️ **root 降级期 API 不覆盖 ROOT 统计**：需撤 KernelSU 授权模拟（本轮未做，行为由
  DayStatsSourceTest 钉死）。
- 冒烟：装机后 MainActivity 前台存活（topResumedActivity）、无 FATAL；小时图/拿起/首开/电量
  去向渲染正常。新组件列今日为 0 属预期（delta 语义，该窗口无新增 flashlight 等耗电；本机
  UID mobile_radio=0）。

## 4.0.16 v0.17.0 变更（数据正确性与可靠性专项：agent/v017-data-integrity 分支）

本版不新增功能，只修数据正确性。分析基线 main=ea9cd53（v0.16.13），实际基于最新「千问」分支（v0.16.15，vc31）开发——真机数据库已是 Room v5，从 main（v4）重建会回退。开发前对照代码核验了 HANDOFF 旧结论，其中两条与实际不符：

- **「v0.16.7 已解决同步并发双计」只对同一实例成立（P0-1，本轮修正）**：
  - **根因**：`syncMutex` 是 UsageRepository 的**实例成员**，而 MainActivity / SyncWorker / BatteryCaptureWorker / PowerEventReceiver 各自 new 仓库实例，实例锁互不感知；captureBatteryNow 虽在「千问」分支已进锁（f8e1156），但跨实例仍无保护。两个 capture 同时基于同一旧快照启动 → 后提交者整行 REPLACE（丢增量）或快照 cumMah 倒退（下轮差分双计）。
  - **修复**：锁移到 companion（`SYNC_MUTEX` 进程级单例）；syncNow 与 captureBatteryNow 全部串行化于它。测试：BatteryLedgerTest 两个 capture 基于同一旧快照的序列化场景（不双计/不丢增量/快照不回退）+ 独立锁漏洞对照复现。
- **「v0.16.13」任务清单中『captureBatteryNow 未进锁』已过时**：千问分支已修，本轮核验后保留其结构并升级为进程级锁。
- **batterystats 差分原子事务（P0-2）**：
  - **根因**：旧 syncBattery 顺序为 读旧快照 → 算差分 → **先推进快照** → 写 daily（两次独立写入），进程在中间被杀 → 快照已指向新累计值、daily 未落库，该段差分永久丢失。
  - **修复**：拆两阶段——事务外 shell+纯解析（dumpsys 绝不进 Room 事务）；单一 `db.withTransaction` 内「批量读旧状态（candidateDays 保证覆盖全部触碰日期）→ 纯差分（新模块 BatteryLedger）→ 批量写 battery_daily/global → **最后**推进快照」，任一步失败整体回滚。UID 与 GLOBAL（含 `_dur`）快照同规则。差分语义逐条对齐旧实现（重置比例判据/0.001 阈值/other 归并），消除了逐 UID 的数据库 round trip。
  - **测试**：BatteryLedgerTxTest（Robolectric 真实 Room）：差分计算后注入故障 → 快照与 daily/global 全部回滚，不出现半提交；多天窗口批量读取不重写既有行。
- **报告周期 resolver 重设计（P0-3）**：
  - **根因**：旧 `currentPeriods(today)` 返回进行中周期 + `today ∈ [end, end+7]` 补生成判断——周期一切换（周一/次月 1 日/次年 1 月 1 日）currentPeriods 已指向新周期，上一周期**永远补不上**；反而会在周期最后一天（周日晚/月底/12-31）把未结束的周期当正式报告提前生成。
  - **修复**：新 `ReportPeriods`——正式报告只针对「最近已完整结束的周期」（周报=上一 Mon-Sun；月报=上一自然月；年报=上一自然年），按 8 周/6 月/2 年有界回溯补生成；手动生成改为「当前周期预览」（ReportEntity 新增 finalized 字段，Room v6 MIGRATION_5_6 列默认 1，**既有报告全部按正式历史保留**）。旧版提前生成的报告按「生成时间早于周期结束=过早」规则在升级后自动以完整口径重生成。periodKey 格式不变。
  - **测试**：ReportPeriodsTest 覆盖 周日 00:01/23:59、周一、周六、月末最后一天、次月 1 日、闰月、12-31、1-1、过早/预览升级规则、周一起始不变量（2026 全季度逐日）。
- **effective usage 唯一口径（P0-4）**：
  - **根因**：UI `dailyRows` 取 max(会话, usage_daily 回补) 修复了 2026-09-04~09-12 旧 STOPPED bug 历史日；但报告（v0.16.14 起改为逐日调 dailyRows）与 UI 仍各跑一套合并逻辑，且效率低（周报 14 次查询）。
  - **修复**：DAO 新增 `effectiveUsageDay` / `effectiveUsageBetween`（sessions 聚合 UNION ALL usage_daily 逐包逐日取 MAX，区间对每日 effective 求和）——首页与报告共用同一 SQL 口径，报告总量/Top/环比与首页逐日求和**完全一致**（单测钉死），周报查询从 14 次降到 2 次。删除死代码 sessionsSumBetween/topSessionsBetween。
- **停止销毁式迁移（P0-5）**：移除 `fallbackToDestructiveMigration()`；schema JSON（4/5/6.json）入库，4.json 为按 MIGRATION_4_5 逆推重建的历史档案；仅 v1-3（早期开发版、无档案）定向 fallback。RoomMigrationTest（Robolectric 真实迁移路径）：v4/v5 结构手工建库 + 全部 10 张表样本数据 → Room 打开迁移 → schema 校验 + 数据值级断言（sessions/usage_daily/battery_daily 含 fgsMah/battery_global/battery_snapshot/day_stats/unlock_stats/reports/app_info/sync_state 全保留）。
- **采集语义与健康监控（P1-1）**：captureBatteryNow 返回四类结果（Success/NoData/SourceUnavailable/Failed），Worker 仅对临时错误 retry（上限 3 次），权限不可用/解析为空不 retry；文档删除「最大损失窗口 ≤15 分钟」绝对表述（PeriodicWorkRequest 名义 15 分钟，实际受 Doze 与调度影响）。新增采样健康状态（sync_state 持久化）：lastCaptureAttemptTs / lastCaptureSuccessTs / lastCaptureFailure（错误码+时刻）/ maxObservedCaptureGapMs（实际观测到的最大成功间隔=实际损失窗口），diagnosticJson 输出。
- **共享 UID 耗电归属（P1-2）**：`getPackagesForUid` 唯一对应一个包才归属；多包共享 UID 不再挂给任意第一个包（保持 packageName=null，UI 动态解析包列表显示「共享 UID：A / B / …」），杜绝整机耗电复制给多个 App 双计。存量已误挂的行不做回算（不可追溯），新数据正确。测试 OwnerPackageTest。
- **高权限命令抽象（P1-3）**：新增 `PrivilegedCommandRunner` 接口 + `RootCommandRunner`（libsu 直移封装，行为等价）；UsageRepository 不再散落 Shell.cmd。Shizuku 后端留待独立 PR。
- **checkin 格式脚手架（P1-4）**：`CheckinBatteryParser`（仅解析高置信度 pwi 行）+ 合成 fixture + 与 human-readable 真机行的一致性测试。**未经 OnePlus 13 实测对账，不接入生产**；human-readable parser（经真机验证）保持生产地位。注意 `-c` ≠ `--checkin`（后者带旧 completed 统计段特殊语义）。
- **持久化同步来源（P1-5）**：last_usage_source(ROOT/API)、last_battery_source(ROOT/DUMP)、last_sync_success_ts、last_sync_error/last_battery_fail 持久化；diagnosticJson 不再用 `Shell.isAppGrantedRoot()` 现场猜测（冷启动 null 恒误报，v0.16.10 已证明）。
- **P2 核验记录**：① battery_global.durationMs 增量累计——v0.16.14（gemini）已修（`__global___dur` 差分快照），待办销项；② allCategories 已含「图像」；③ README 已写 Gradle Wrapper；④ release 仍用 debug signingConfig（个人测试用，勿当正式发行签名）；⑤ MainActivity ~1400 行 / UsageRepository ~1100 行，架构拆分见「架构重构原则」待办。
- **测试规模**：JVM 单测 76/76 通过（较 v0.16.15 的 25 个新增 51 个）；`:app:assembleDebug` 产物复核 versionCode=32/versionName=0.17.0。
- **待真机验证（未做，不得标注通过）**：① 真机升级 v4→6/v5→6 迁移后历史数据完整（模拟器/JVM 已验证迁移逻辑）；② 进程级锁下 15min 采样与手动同步并发无双计（DB 前后对比法，见第 7 节）；③ 共享 UID 行显示与归属；④ 周一/次月 1 日的上一周期补生成与通知；⑤ 诊断 JSON 的 lastUsageSource/lastBatterySource 与实际通道一致。

### 4.0.17 v0.17.1 变更（真机审计驱动的数据完整性修复：epoch 重置 / 会话权威 / 归属 / parser / 诊断）

本轮全部修复由真机（OnePlus 13，ColorOS 15.0.0.861）数据审计确认后实施，非假设案例。审计工具（export_battery_diagnostic.py / battery_diagnostic_raw.json / batterystats.txt）含设备隐私，已 gitignore 不入库；关键数值以脱敏形式进入测试 fixture。

### P0-1 Reset Swallow（吞量）bug——真机实锤与修复

- **实锤**：抖音 u0a333 旧 epoch 快照 ≈132.8 mAh → 充电周期切换后新 epoch dump 148 mAh。旧判据 `new < old×0.5`（148 < 66.4 = false）不触发 → delta = 148−132.8 = **15.2 mAh 入账**，新 epoch 前段 ~132.8 mAh 被永久吞掉，且快照被推进到 148 无法回补。同日 screenMah 23.03 > totalMah 15.24 是同一错误基线的衍生症状。
- **修复**：epoch 判定取代数值判据——持久化上一帧窗口起点（sync_state.`battery_window_start_ts`），与本帧解析的 `Start clock time` 比较，明确不同 = **GLOBAL RESET**：所有旧快照（UID/GLOBAL/GLOBAL_DUR，含本帧未出现的 UID）在同一事务内统一失效重建。unplug 广播不可靠，禁止作为判据；窗口起点缺失时才退回旧比例 heuristic；SAME epoch 禁止任何数值型 reset（100→97 是模型回算）。epoch 标记与数据写入同事务（失败三者全部回滚）。
- 真机事实：窗口起点 2026-10-01 18:05:32、capacity 5920、computed 1522、actual 1954~2072、UID 142 个；bilibili 319 / QQ 261 / 抖音 148 mAh，而抖音当天入库仅 15.24——修复后同类场景 delta=全量。

### P0-2 root→API 降级覆盖权威历史（会话 source-aware）

- UsageSession 新增 `source` 列（v8）：LEGACY(0，历史行默认，按权威对待)/ROOT(1)/API(2)。
- API fallback（`applyApiFallbackSessions`，单事务）：只删 source=API 的旧 fallback；新 API 会话与权威会话时间相交即丢弃（`filterNonOverlapping`，半开区间）；只补权威缺席区间。root 恢复后全量重建自然回到权威结果。

### P0-3 归属修复

- `batteryForPackages` 重写：逐行按 `ownerPackageOf` 归属（共享 UID/系统 UID 恒 null 整行跳过），分身同包多 UID（QQ=u0a351+u999a351、微信=u0a350+u999a350，真机确认）**求和聚合**，不再静默覆盖；`groupBatteryRowsByOwner`/`sumBatteryDaily` 纯函数可测。

### P1 各项

- **P1-1**：usage/battery 两条独立健康线（last_usage_success_ts / last_battery_success_ts / last_battery_fail_ts）；SyncResult.Ok.batteryOk；CaptureHealth 新增 FAIL_PARSE。
- **P1-2**：CaptureDiagnostics（rawLineCount/UID 数/全局数/窗口起点/capacity/computed/actualLow/High/sumUidTotal/parserVersion）+ 基于本设备健康基线的异常检测（不设固定阈值）；anomaly 帧禁止提交（fgs 失配史教训）。
- **P1-3**：actual drain 解析区间 `1954-2072`（上界此前被丢）；单值形式 high=low。
- **P1-4/P1-5**：能量口径比率 uidAttributionRatio / modelCoverageLow/High；**真机事实**：Global 无有效 wifi/mobile_radio power、UID mobile_radio=0、checkin pwi cell=0（09-18 窗口 wifi=72.8 存在）、EnergyConsumer on-battery stats 不支持。设备无法提供有效 WiFi/cellular UID power attribution 是模型缺口的**重要候选来源**——缺口区间（computed 1522 vs actual 1954~2072，差 432~550 mAh）不得精确归因给任何组件或 App，严禁按比例摊派。
- **P1-6**：battery_daily.mobileMah（v7）；修组件明细完整性，非 total 少记。
- **P1-7**：账本不变量 LEDGER_INCONSISTENT（任意单一组件 > total+0.05 mAh 即标记；仅诊断不阻断）。
- **P1-8**：power 段边界=2 空格缩进段标题（真机实测段后为 `  All kernel wake locks:`）；旧实现只认 In-memory/Statistics（真机未命中）→ 扫描 10000+ 行。不硬编码 OEM 标题。
- **P1-9**：checkin parser 按真机重写：行结构 `<ver>,<uid>,<agg>,<section>,<fields...>`（pwi/pws 是 cols[3]）；真机 fixture（2026-09-18 root --checkin，脱敏）+ AOSP fixture 双钉死；旧实现的错误列结构由 negative 用例锁死。**真机事实：checkin 的 pwi 全部为设备级组件（uid=0），per-UID 耗电在该 ROM checkin 中不存在** → checkin 无法替代 human-readable 的 per-UID 账本，后者保持生产主路径。
- **P1-10**：CommandResult(stdout/stderr/exitCode/success)；区分 COMMAND_FAILED / VALID_EMPTY_OUTPUT（→NoData）/ PARSE_FAILED（→新 outcome）/ NO_PRIVILEGE。
- **P1-11**：报告改「应用归因耗电（UID 模型估算，非整机实际耗电）」——SUM(battery_daily.totalMah) 是 UID 归因口径，不得与 battery_global 相加（重叠语义），更非 computedDrain/5920（容量≠窗口耗电）。
- **P1-12**：backfillPlan 逐周期检查（break→continue）——中间缺失周期（连杀两周后台后恢复的场景）现在会被补齐；通知仍只推最新周期。

### 数据库 v6→v8

- v7：battery_daily.mobileMah（MIGRATION_6_7）；v8：sessions.source（MIGRATION_7_8）。schema 7/8.json 入库；迁移测试覆盖 4→8 全链（全部表值级保留 + 新列默认值 + 历史会话 source=LEGACY）。

### 测试与验证

- JVM 单测 115/115（v0.17.0 的 76 → 新增 39，含 epoch 6 场景、会话权威、checkin 双 fixture、异常检测、invariant、backfill 计划）。
- 本轮验证限于 JVM/Robolectric 与构建（手机未连接）；**真机回归待办**（连接后按 HANDOFF 第 7 节取证手法执行）：
  1. 同一 epoch 连续两次采集：第二次只增加真实 delta；
  2. 自然发生的下一次 epoch 切换：所有 UID 全局重置，不再出现抖音式 148→15 吞量；
  3. epoch 首帧缺席的 UID 重新出现时不与旧基线做差；
  4. 任意单一 component 不再明显大于 totalMah（LEDGER_INCONSISTENT 诊断应为空或仅历史行）；
  5. shared UID 不挂给任意单 App；6. u0/u999 同包聚合展示；
  7. root 临时不可用（可临时撤权模拟）时 API fallback 不破坏 ROOT sessions；
  8. battery shell/parser/tx 失败时 diagnosticJson 的 battery 段独立显示 failure。
- 严禁：`dumpsys batterystats --reset` / `--reset-all`；给 UID 功率乘经验系数；把 Actual−Computed 缺口按比例摊给 App；为迁移清库。

### 4.0.17.1 真机回归记录（2026-10-02 上午，OnePlus 13 e4e1b43e，v0.17.1 debug 构建）

方法同 4.0.16.1：run-as 拉库三件套 + node:sqlite 分析。基线 A=v6（升级前），B=装 0.17.1 首次同步，C=二次同步，D=`dumpsys battery unplug`→`set ac 1` 模拟插电（后 `dumpsys battery reset` 恢复）。

- ✅ **迁移 v6→v8**：user_version=8；历史冻结校验——battery_global/usage_daily/reports/app_info 逐位一致；battery_daily(day<today) +0.32 mAh/+6 行为跨午夜窗口 dayWeights 的正确归属（非丢失）；sessions(day<today) +2 行/+629s 为 root 24h 窗口滑过午夜的正常重建。新列验证：历史会话 source=LEGACY(0)、历史行 mobileMah=0。
- ✅ **诊断状态**：battery_window_start_ts=1790849132000（=2026-10-01 18:05:32，与审计窗口一致）；parser_baseline_uid_count=150；parser_had_window_baseline=1；last_usage/battery_source=ROOT；prefs 中的 last_battery_diag 完整（parserVersion 3、rawLineCount 11528、UID 150、computed 2492、actual **3197-3374**（P1-3 区间在真机生效）、uidAttributionRatio 0.633、anomalies=[]）。
- ✅ **Item 1（同 epoch 二次采集）**：epoch 标记不变、快照 255 行 0 回退、batteryDailyTotal B→C delta=0.000000（第二次采集零幻影增量=无双计；间隔仅 2 分钟且设备闲置，所有 UID 增量低于 0.001 阈值属预期）。
- ⚠️→待自然发生 **Item 2/3（epoch 切换）**：模拟插电（unplug→set ac 1）**不会**触发该 ROM 的 batterystats 窗口重置（epoch 标记与快照集不变，采集本身成功执行）——窗口重置只在真实充电事件发生。epoch 切换的正确性由 6 个单测场景钉死 + 真机已验证「无误重置」侧；**自然充电周期后的验证待办**：观察 epoch 标记更新、旧快照全清、当帧 UID 全量入账（即抖音式 148→15 吞量不再发生）。
- ✅ **Item 4（账本不变量）**：真机数据检出 QQ u0a351 cpu 4.6 > total 4.0（系统自身 cpu/total 估算器分歧，非本应用 bug）；历史吞量遗留 screen>total 行 12 条（预期内不可回补）。**发现并修复记录缺陷**：诊断原来只存最后一帧，干净帧会抹掉此前检出——已改为跨帧合并（mergeLedgerInconsistencies，去重保序上限 50 行），单测钉死。
- ✅ **Item 5（共享 UID）**：当日新增行中 u0a398/u0a259/u999a259/u0a64/u0a88 等共享 UID 均 packageName=null（不挂给任意单 App）。
- ✅ **Item 6（分身双 UID）**：QQ=u0a351（4.0 mAh）+u999a351（3.82 mAh）两行并存且均归属 com.tencent.mobileqq，展示层聚合（batteryForPackages 求和），无静默覆盖。
- ⚠️ **Item 7（root 失效降级）**：真机模拟需撤销 KernelSU 授权（用户侧动作，本轮未做）；行为由 SessionAuthorityTest（Robolectric）钉死。
- ⚠️ **Item 8（failure 显示）**：成功路径的 battery 诊断段真机确认完整；failure 路径（shell/parse/tx）显示逻辑由单测覆盖，待一次真实失败事件在 diagnosticJson 中复核。
- 冒烟：修复版装机后启动正常（今日 1h13m、小时图/拿起/电量去向渲染正常，进程存活）。UI 未做自动化操作（设备在机主手中）。

## 4.0.16.1 真机验证记录（2026-10-01 深夜，OnePlus 13 e4e1b43e，ColorOS 15）

验证方法：升级前用 `run-as` 拉取 v5 库三件套（db/-wal/-shm，WAL 未 checkpoint 也一并保留）作基线 A；装 0.17.0（debug 构建，与 release 同 debug 签名，覆盖安装保留数据且 run-as 可用）；启动后拉 B（迁移+首次同步）、二次启动拉 C（第二次同步）、`dumpsys battery unplug`→`set ac 1` 模拟插电触发抢拍后拉 D（之后 `dumpsys battery reset` 恢复）。DB 分析用本机 Node 24 内置 `node:sqlite`（可回放 WAL），快照/逐行 diff 全程脚本化。快照总和含 `__global___dur` 行的毫秒时长值，**不能**当 mAh 看待。

- ✅ **① v5→6 迁移数据零丢失**：user_version 5→6；10 张表行数与值级聚合逐位一致——battery_daily 2748 行 / 28796.525521581098 mAh、battery_global 242 行 / 29579.71509819 mAh、usage_daily 351 行 / 153837128 ms、battery_snapshot 255 行、day_stats 拿起 2259、unlock_stats 1944、app_info 140 全部精确相等；sessions 19142→19144（+2 为升级当次真实使用的新会话，非迁移产物）。
- ✅ **② 同步/采集无双计、无丢增量、快照无回退**：两次全量同步 + 一次插电抢拍；抢拍后 14 行 battery_daily 增量（如 u0a432 +0.1、u0a330 +0.15、UID 1000 +0.1）与各自快照推进**逐 UID 精确相等**，当天增量总和 0.5901 mAh 与总差值一致且全部归属 2026-10-01（dayWeights 正确）；255 行快照 0 回退、0 误判重置（重置比例判据下系统 smearing 正常存活）；报告补生成幂等（17 条稳定，二次同步不再新增）。
- ✅ **③ shared UID 数据层**：新规则生效（uidKey LIKE 'u%a%' 且 packageName IS NULL 的行存在，共享 UID 不再挂给任意第一个包）；**UI 标签目视确认未完成**——验证时设备在机主手中使用（UI 自动化会与手持操作冲突，已主动停止），耗电排行切「耗电」后应见「共享 UID：…」条目，待机主自查。
- ✅ **④ 周期补生成**：reports 4→17。W:2026-09-21（上一完整周）、M:2026-9（上一自然月）、Y:2025（上一自然年）按新语义生成，旧版在周期最后一天提前生成的过早报告已被完整口径重生成（generatedTs 更新为升级当日）；8 周/6 月/2 年回溯各就位，全部 finalized=1；通知仅最新一条（回溯静默）。
- ✅ **⑤ 持久化诊断状态**：last_usage_source=1(ROOT)、last_battery_source=1(ROOT)、last_sync_success_ts 随每次同步推进；插电抢拍后 cap_last_attempt_ts / cap_last_success_ts 写入、cap_last_fail_code=0、cap_max_gap_ms=null（首次成功无间隔，语义正确）；`dumpsys activity broadcasts` 可见 PowerEventReceiver 派发记录（沿用 v0.16.8 的 DB 时间戳+广播派发取证手法）。
- 补充说明：验证装机为 **debug 构建**（run-as 需要）；日常可换回 release 包，同签名覆盖安装不丢数据。UI 整体渲染正常（首页大数字/小时图/拿起/电量去向/占比模块，无崩溃）。

## 4.0.15 v0.16.14 变更（修复数据处理与统计口径错误，由 Gemini 修复）

- **分身双开应用使用时长丢损（严重）**：
  - **根因**：v0.16.12 为解决分身空间（`user=999`）系统事件导致拿起翻倍，在 `parseDumpsysEvents` 顶层增加了 `isUser0` 判断，粗暴丢弃了所有非主用户的行。导致用户在分身微信、分身淘宝等双开空间中的前台使用事件（`ACTIVITY_RESUMED` / `PAUSED`）被全量丢弃，分身应用使用时长和会话 100% 丢失。
  - **修复**：事件流精细化过滤——仅针对系统广播类事件（`KEYGUARD_HIDDEN`、`SCREEN_INTERACTIVE`、`SCREEN_NON_INTERACTIVE`）过滤非 `user=0`（防止拿起翻倍与误切断会话）；应用级活跃事件保留全部用户空间，恢复分身微信等使用时长的正常统计。
- **跨 24h 窗口左边界历史会话永久蒸发（严重）**：
  - **根因**：`dumpsys usagestats` 仅保留最近 24 小时事件。旧版重算会话时执行 `DELETE FROM sessions WHERE endTs >= :start`。若某会话在 24h 起点前开始（如 `start - 10min`）、在起点后结束（`start + 5min`），该完整历史会话被直接从库中删除；但在新的 24h dump 中该应用只有 PAUSED 而缺失 RESUMED，导致其无法被重建。随着每日多次同步推进，滑动窗口左边缘的跨界会话被持续蚕食永久丢失。
  - **修复**：在 `UsageDao` 中新增 `deleteSessionsStartingAfter(fromTs)`（`WHERE startTs >= :fromTs`），非 root 和 root 路径均改用该方法清除重算会话，确保起点在窗口之外的跨界历史会话完整保留。
- **整机组件使用时长（`battery_global.durationMs`）永久冻结（中度）**：
  - **根因**：`grow.add(BatteryGlobalEntity(..., durationMs = if (ex == null) pair.second else ex.durationMs))`，当当天数据库已有记录（`ex != null`）后，`durationMs` 永远被写回旧值，导致屏幕开启时长、相机时长在清晨首轮同步后全天永久冻结不再更新。
  - **修复**：引入全局组件时长的差分快照（`GLOBAL_SNAPSHOT_KEY + "_dur"`），每次同步计算当期时长增量 `durDelta`，按日重叠权重加权累加更新，使整机硬件时长持续精准增长。
- **前台服务耗电（`fgs`）被完全抛弃（中度）**：
  - **根因**：Android 12+ 将前台服务耗电单列为 `fgs: xxx`。旧代码未提取该字段且 `BatteryDailyEntity` 表中缺失 `fgsMah`。导致听歌（网易云/QQ音乐）、导航（高德）、视频后台播放等大头耗电凭空消失，UI 详情弹窗中前台+后台远小于总耗电，后台占比严重失真。
  - **修复**：在 `BatteryDailyEntity` 中扩展 `fgsMah` 字段；`AppDatabase` 升级到 Version 5 并提供平滑迁移 `MIGRATION_4_5`（`ALTER TABLE battery_daily ADD COLUMN fgsMah REAL NOT NULL DEFAULT 0.0`，无损保留所有历史数据）；在 `parseBatteryPower` 中采集 `fgs` 差分累加；UI 弹窗中清晰展示 `（前台 X / 前台服务 Y / 后台 Z）`。
- **周期报表与首页统计口径脱节（中度）**：
  - **根因**：首页时长采用 `dailyRows` 逐包取 `maxOf(sessions, usage_daily)` 兼容系统回补数据；而周报/月报的 `buildReportText` 直接查询 `sessionsSumBetween` 与 `topSessionsBetween`，仅查 `sessions` 表，导致包含历史回补期的周报/月报总时长比首页每日累加值少 30%~50%。
  - **修复**：`buildReportText` 重构为复用 `dailyRows(day)` 逐日聚合总时长与 Top 5 应用，保证周报、月报汇总数据与用户在首页每日看到的数据 100% 严格一致、自洽。
- **分支与部署**：独立分支 `fix/data-calculation-and-metrics-by-gemini`（commit `5840cbb`）；单元测试 25/25 全过；Release 优化包（v0.16.14，versionCode 30）已通过 adb 安装至真机（OnePlus 13，`e4e1b43e`），Room 数据库平滑升级至 v5。

## 4.0.14 v0.16.13 变更（短期与中期性能优化 + 工程化 Wrapper + Release 构建）

- **手势重组掉帧消除**：移除 `HomeScreen` 顶层的 `dragTotal` Compose State，改为 `pointerInput(Unit)` 内部局部变量累加，彻底消除左右滑动时每一像素都触发整页重组调度的卡顿。
- **快速切日防竞态**：`loadDayAsync()` 增加 `loadJob?.cancel()` 机制与目标日期双向核验，防止快速连续切日时慢请求乱序覆盖最新日期的数据。
- **小时图计算缓存**：`HourlyChart` 24 小时区间循环及分类统计增加 `remember(sessions, categories, dayStart)`，避免组件频繁重组时重复循环与数组分配。
- **耗电排行后台 App 解析完善**：`displays` 加载逻辑由原先仅读取 `rows` 扩展至合并 `batteryAll` 中出现的全部包名；“仅有后台耗电而无屏幕会话”的应用不再降级显示为原始英文包名，完整展示中文名称与图标；预构建 `rowsMap` 将排行行时长的匹配开销从 $O(N \times M)$ 压至 $O(1)$。
- **工程化 Gradle Wrapper**：正式生成并入库标准 Gradle 8.9 Wrapper（`gradlew` / `gradlew.bat`），消除对外部硬编码绝对路径的依赖。
- **Release 构建与签名支持**：`build.gradle.kts` 配置 release signingConfig（使用兼容签名通道），正式打出 `app-release.apk` 优化包，真机 Compose 运行更加流畅。

## 4.0.13 v0.16.12 变更（修复多用户导致拿起次数翻倍 + 息屏直入应用未计入）

- **拿起翻倍（严重）**：`dumpsys usagestats` 在开启应用分身或多用户时会分段输出 `user=0` 与 `user=999`。系统级事件（如 `KEYGUARD_HIDDEN`）在每个用户下都会记录一份。旧版解析器未做用户分区过滤，两段事件被全量解析并按时间排序，导致**每次解锁产生两条同秒的 `KEYGUARD_HIDDEN`，拿起总次数直接翻倍**（例如真实拿起 117 次显示为 234 次，143 次显示为 286 次）。
- **息屏直入应用漏记（严重）**：此前开发者误以为「拿起次数远大于解锁打开应用数」是因为「用户多数拿起停留在桌面/通知」。真机全量 dump 取证彻底推翻该假设：当用户在非桌面 App（微信/抖音/高德等）前台息屏后解锁时，系统直接恢复该 App，Android 在 `KEYGUARD_HIDDEN` 前后（`[-2s, 0s]`）发出 `ACTIVITY_RESUMED`。旧代码限定 `gap in 1..15_000`（严格大于 0ms），导致这类直入 App 时差 `<=0` 全部落空；且后续 15 秒内无新启动，多达 36.4%（真机 143 次样本中 52 次）的有效使用被误判为“仅看桌面”。
- **修复**：
  1. `parseDumpsysEvents` 增加 `user=` 分区过滤，仅保留 `user=0`（主用户）事件流，杜绝分身二次广播导致的拿起翻倍。
  2. 提取纯函数 `computeDayEventStats` 放入 companion object；增加 `KEYGUARD_HIDDEN` 2000ms 防抖去重。
  3. 解锁归属状态机升级：优先捕获解锁后 `(0, 15000]ms` 从桌面新启动的 App；若未新启动，回溯匹配 `[-2000, 0]ms` 伴随解锁恢复的前台非 Launcher App。真机 143 次解锁样本回放匹配率从 35% 提升至 91.6%（仅看桌面/通知仅 7 次，完全符合真实使用习惯）。
  4. UI 副标题文案同步修正为「其中 X 次解锁后进入/打开了应用」。
  5. 新增单测 `DayEventStatsTest`（涵盖用户隔离、拿起防抖、桌面启动、直入恢复、切应用优先级全场景）。

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

## 6. 已知问题 / 待办（2026-10-01 更新）

### 待办 / 后续建议（大致按优先级排序）
1. **架构拆分（P0 全部完成后的重构轮）**：UsageRepository → UsageSyncRepository / BatteryRepository / ReportRepository / AppMetadataRepository；shell/parser 拆纯模块；MainActivity（~1400 行）→ HomeViewModel + 图表/Dialog/Settings 独立 Compose 文件。每次拆分必须行为等价并跑回归测试，不做一次性大重构。
2. **正式数据导出**：SQLite backup / JSON / CSV / diagnostic bundle（Storage Access Framework），导出 schema 必须带 version 字段；不得把设备敏感信息、本机绝对路径、ADB serial、密钥写入仓库。
3. **Shizuku 后端**：基于 P1-3 的 PrivilegedCommandRunner 接口实现 ShizukuCommandRunner，独立 PR。
4. **checkin batterystats 真机对账（P1-4 收尾）**：拿 OnePlus 13 真机 `dumpsys batterystats -c` 原始样本逐行对账（注意 `-c` 与 `--checkin` 语义不同），对账通过前 CheckinBatteryParser 不接入生产。**严禁运行 `dumpsys batterystats --reset` / `--reset-all`。**
5. ~~**shared UID 展示优化**~~：✅ v0.18.0 已实现 uid_packages 持久化成员存档（采集时落库、按日解析历史归属，卸载保留历史/UID 复用不错挂）。详见 4.0.18 H。
6. **速览插件专项** —— 需欢太账号 + 个人开发者认证申请 Card 权限。
7. **release 签名**：当前仍用 debug signingConfig（个人测试用，勿当正式发行签名发布）。

### 近期已解决项
- ✅ **数据准确性与采集可靠性专项（v0.18.0，本分支）**：诊断基线按 epoch 隔离（A/P0-1复审）、账本高水位记账回落不二次入账+微量增量守恒（B/P0-2/P1-1）、采集健康统一+有界采集日志+丢失尾段（C/P2-2）、轮次真实时长与跨度分离（D/P1-4）、ROOT 可信采集上界+同包短段合并（E/P1-2）、拿起/首开按来源覆盖原子替换（F/P1-3）、报告自然周期环比+修订/格式版本重建+覆盖天数（G/P1-4）、历史 UID 归属持久化（H/P1-5）、组件明细完整映射（I/P2-1）。Room v8→v9。详见 4.0.18。
- ✅ **数据正确性专项（v0.17.0，本分支）**：进程级同步互斥（P0-1）、差分原子事务（P0-2）、周期 resolver 重设计（P0-3）、effective usage 唯一口径（P0-4）、停止销毁式迁移（P0-5）、采集健康监控/共享 UID/持久化诊断来源（P1-1/2/5）、命令执行抽象（P1-3）、checkin 脚手架（P1-4）。详见 4.0.16。
- ✅ **手势重组卡顿**（v0.16.13）：移除 `dragTotal` Compose State，改为 `pointerInput` 内部局部变量累加。
- ✅ **切日异步竞态**（v0.16.13）：`loadDayAsync()` 增加 Job 取消机制与目标日期双向核验。
- ✅ **小时图重绘性能**（v0.16.13）：`HourlyChart` 24 小时区间循环增加 `remember` 缓存。
- ✅ **后台耗电 App 名称显示**（v0.16.13）：`displays` 预加载合并 `batteryAll` 全部包名；$O(1)$ 映射时长。
- ✅ **工程化 Gradle Wrapper**（v0.16.13）：生成并入库标准 Gradle 8.9 Wrapper。
- ✅ **Release 优化包构建**（v0.16.13）：配置 release signingConfig，支持 `./gradlew :app:assembleRelease`。
- ✅ **分身多用户导致拿起次数翻倍**（v0.16.12）：`parseDumpsysEvents` 过滤 `user=999`，增加 2s 防抖。
- ✅ **息屏直入应用漏记**（v0.16.12）：解锁归属状态机支持回溯捕获 `[-2s, 0s]` 直接恢复的前台 App。
- ✅ **重置误判虚高 & 小耗电隐藏**（v0.16.11）：重置判据改比例（`< 0.5 * old`），补齐 `<0.5mAh` 格式化。
- ✅ **冷启动首同步 root 降级**（v0.16.10）：改用 `Shell.getShell().isRoot` 同步等待 su 握手。
- ✅ **UID 行含 fgs 耗电消失**（v0.16.9）：UID 行正则重构，全额捕获前台服务耗电。
- ✅ **标题栏 ⋮ 菜单无响应**（v0.16.7）：接通 DropdownMenu 并支持当日诊断 JSON 导出。
- ⚠️→✅ **同步并发双计**（v0.16.7 标记解决 → **v0.17.0 真正解决**）：v0.16.7 只给 `syncNow` 加了**实例级** Mutex，而四个调用方各自构建 UsageRepository 实例，跨实例并发（15min 采样 vs 6h 对账 vs 插电抢拍）从未被串行化；v0.16.8~v0.16.14 期间该问题一直存在。v0.17.0 移到 companion 级进程锁 + 差分原子事务 + 并发/回滚测试（BatteryLedgerTest / BatteryLedgerTxTest）后，本条才可标记为已解决。

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
