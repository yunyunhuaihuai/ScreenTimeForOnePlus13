# 屏幕时间 ScreenTimeLog

Android 端自建的屏幕使用时长 + 耗电记录器。针对**用「冰箱」冻结应用后系统自带数字健康不再统计时长**的场景，从底层重建完整数据，冻结应用照样显示。

> 个人自用项目，目标设备 OnePlus 13（ColorOS 15 / Android 15），依赖 KernelSU root 才能发挥全部能力。

## 为什么需要它

ColorOS 的数字健康在应用被冻结（`pm disable-user`）后，会在**展示层**过滤掉这些应用的时长——数据其实还在系统里，只是不给你看。本项目跨三层数据源重建口径：

| 层 | 来源 | 用途 |
|---|---|---|
| 1 | `UsageStatsManager.queryEvents` | 会话时间线（ColorOS 会裁剪，不可靠） |
| 2 | root 跑 `dumpsys usagestats` | 全量事件（~6000 条/天），核心数据源 |
| 3 | root 跑 `batterystats` | 每 UID / 每组件耗电 |

## 功能

- 每日总量 + 日期切换、按分类堆叠的小时柱状图（点柱子看该小时构成）、全天会话色带
- 拿起与解锁：每日拿起次数、解锁后 15 秒内最常打开的应用
- 应用排行：时长 / 耗电双模式，后台占比标注
- 应用明细：分类可手动修正、全部会话起止记录
- 周报 / 月报 / 年报：周期完整结束后自动生成正式报告并推送通知（上一个完整周/自然月/自然年），支持手动生成当前周期预览
- 暗色模式、模块顺序自定义

## 数据安全

- 长期历史数据优先：Room schema 版本化（`app/schemas/`），升级必须走显式 Migration，**没有销毁式迁移兜底**——v4+ 升级缺迁移会显式失败而不是清库
- UI 与报告共用同一 "effective usage" 口径（逐包逐日 max(会话, 系统回补值)），报告总量/Top 与首页完全一致
- 耗电差分在单一数据库事务内提交（快照推进放最后），进程中途被杀不会出现"快照已推进、日汇总未写入"的丢数据窗口

## 技术栈

Kotlin + Jetpack Compose (Material 3) + Room + WorkManager + libsu，无 NDK。minSdk = targetSdk = 35。
工具链：JDK 21 / Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21。

## 构建

使用仓库自带的 Gradle Wrapper（8.9）：

```bash
export JAVA_HOME="<JDK 21 安装路径>"
./gradlew.bat :app:assembleDebug   # Windows
# 或 ./gradlew :app:assembleDebug  # Git Bash
adb -s "$ADB_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
```

首次使用需在系统设置中手动授予「使用情况访问」权限（ColorOS 禁止 shell 授权 appops）。

## 文档

架构设计、数据源实测结论、已知坑与验证命令都在 **[HANDOFF.md](HANDOFF.md)**，接手前建议通读。
