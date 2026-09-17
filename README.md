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
- 周报 / 月报 / 年报自动生成并推送通知
- 暗色模式、模块顺序自定义

## 技术栈

Kotlin + Jetpack Compose (Material 3) + Room + WorkManager + libsu，无 NDK。minSdk = targetSdk = 35。
工具链：JDK 21 / Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21。

## 构建

无 Gradle wrapper，使用本机 Gradle 8.9：

```bash
export JAVA_HOME="<JDK 21 安装路径>"
export GRADLE_USER_HOME="<Gradle 缓存目录>"
"<Gradle 8.9 安装路径>/bin/gradle.bat" -p "<本仓库根目录>" --console=plain :app:assembleDebug
adb -s "$ADB_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
```

首次使用需在系统设置中手动授予「使用情况访问」权限（ColorOS 禁止 shell 授权 appops）。

## 文档

架构设计、数据源实测结论、已知坑与验证命令都在 **[HANDOFF.md](HANDOFF.md)**，接手前建议通读。
