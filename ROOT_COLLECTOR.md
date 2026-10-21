# 独立 Root 采集器（协议 1）

APK 授权 root 后由 `RootCollector.ensureInstalled()` 安装。只写入固定目录
`/data/adb/screentime-collector`（root 700）与
`/data/adb/service.d/screentime-collector.sh`（root 700）。适用于现有 KernelSU 2.x
`service.d` 启动，不注入 initrc。采集进程不访问 Room，不依赖 APK 的进程存活。

## 生命周期和验证接口

- Kotlin：`ensureInstalled`、`start`、`restart`、`status`、`captureNow`、
  `readPendingFrames(limit)`、`ackThrough(frameId)`、`stop(removeBootEntry)`、
  `uninstall(removeData)`。结果显式包含 success/error/status。
- 真机 shell：`su -c 'sh /data/adb/screentime-collector/collector.sh status'`；将末尾
  `status` 换成 `start` / `restart` / `capture` / `list` / `stop` 可测试相应操作。
- `stop` 写持久 disabled 标志，重启与普通 ensureInstalled 均不会重新启用。
  `start` 或 `restart` 显式解除停用。APK 卸载后 worker 在下一次采集前检查包是否存在，
  包缺失时持久停用；不会自动删除留存证据。`uninstall(false)` 停止并移除启动入口及脚本，
  保留队列；`uninstall(true)` 同时删除固定 collector 数据目录。
- 所有入口统一 KernelSU BusyBox ash（避免 Android mksh 将锁FD设为close-on-exec）。
  supervisor 独立于 worker；worker 退出后重启。flock 内核锁随进程死亡自动释放；状态文件
  记录 PID、启动 tick、boot ID，只向身份仍匹配的进程发信号，避免 stale PID 误杀。
  不因遗留 owner 文件阻塞重启。需要现有 KernelSU BusyBox 的 ash/flock/timeout，缺失显式失败。
  长命子进程在子shell内先永久关闭锁FD再exec，避免ash对临时 `9>&-` 保存原FD为10，导致
  owner被杀后子进程仍拖住锁。重启验证应同时检查owner PID和实际待导入帧增长。

## 生产与消费协议

`capture` 与 worker 使用同一个 capture 锁。45 秒命令超时，加 5 秒终止宽限；非零退出、空输出、
超过 16 MiB 原始帧都不发布。命令前后分别记录真实墙钟；observedTs 为命令完成时间。
完整帧先写 tmp 目录，再在同一文件系统原子 rename 为 `ready/<16位frameId>/`。
每个目录包含 `meta.json` 与 `batterystats.txt.gz`。meta 包含协议版本、frameId、sequence、
commandStartTs/commandEndTs/observedTs、zoneId、bootId、原始 SHA-256、字节数、换行数和压缩字节数。
frameId 使用 max(当前毫秒, 上一ID+1)，保证校时后仍能严格升序消费；它不是原始观测时间。
原始文本后附 `# ST_COLLECTOR_UID_PACKAGES_V1` 与采集当时的 `pm list packages -U -u`，
该辅助命令单独10秒超时；失败追加 `# ST_COLLECTOR_UID_PACKAGES_ERROR exit=N`，metadata
的 uidMapExitCode 同步保留结果。附录也受 SHA-256 保护，缺映射时历史归属应保留未知。

导入方必须与 BatteryLedger 的写入锁串行：先 read 按 ID 升序返回的帧，验证协议/gzip/大小/SHA，
用帧自身的时间与时区解析，再提交账本事务，最后 ack。遇到坏帧立即停止该批，保留坏帧与后续帧；
CollectorReadResult 可以带此前已验证的 frames 及 failureFrameId。**不得越过坏帧 ack。**
仅成功写库后的最高连续 frameId 可以 ack；事务中的“已导入帧 ID”也应持久化，防止提交后 ack 前
应用崩溃带来的重复导入。ack 水位先原子写入，再删相应目录；重启清理恢复中途被打断的删除。

独立采集启用时，App 的 root 路径必须只消费这些帧，不能再额外 direct dumpsys 推进同一账本。
collector 故障若要切 live，必须先显式停 collector，并成功排空所有待导入帧，防止旧 epoch
迟到帧覆盖新基线。DUMP 无 root 备用通道也需遵守这一转换顺序。

## 保留、丢弃与运行边界

未导入帧目标保留 8 天，gzip 队列上限 512 MiB / 4096 帧。硬空间/帧数上限优先，可能缩短
保留时间，因此不承诺任何输出大小下都至少保留7天。删除前持久累计 droppedFrames/droppedBytes、
首尾丢弃 frameId、时间、原因，并保留最近256条 drops.tsv。成功采集不会清除丢弃证据。

熄屏目标10分钟、亮屏目标2分钟；失败按20秒至10分钟退避。等待 sys.boot_completed 与 bootanim
结束后才采集；不开全时 wakelock，深睡/系统暂停可使实际间隔更长。service.sh 只负责启动，
supervisor 本身被杀后仍需 App ensureInstalled、手动 restart 或下一次开机恢复；不承诺永不停止。
电量窗口重置与最后采样仍有竞态，丢失的旧窗口尾段不能从新窗口恢复。独立 root 进程只降低
APK 被系统终止造成的影响，不提供绝对零丢失保证。

## 独立使用历史归档

每6小时在同一 capture 锁下，稳定复制 `/data/system_ce/{0,999}/usagestats` 的 version、mappings
及 daily 数字文件。源文件名、size、mtime、SHA-256 在复制前后必须一致；目标复制SHA也逐文件验证。
90秒总预算及每命令15秒超时；源正在写入、未解锁的CE不可读或权限异常都失败并退避5分钟，整代不发布。
999源确实不存在时写999/ABSENT；不能把读失败当不存在。
源单文件上限 version 1KiB、mappings 4MiB、daily 2MiB；每用户最多64个daily，整代原始内容
最多32MiB，超限整代失败，不先复制超大文件再淘汰。

发布路径 `history/<16位generation>/{0,999}/usagestats/{version,mappings,daily/*}`；manifest.tsv 格式：

```
STH_ARCHIVE_V1<TAB>generation<TAB>observedAtMs
U<TAB>0<TAB>PRESENT
U<TAB>999<TAB>PRESENT|ABSENT
F<TAB>user<TAB>version|mappings|daily/数字[-c]<TAB>byteCount<TAB>sha256
```

manifest.sha256 为整个TSV的SHA-256（64位小写hex+换行）。额外 manifest.json 供诊断。
history/.objects 是不可变内容池，各代文件硬链接复用，Reader只枚举数字generation并保持每代daily与
mappings/version绑定。30天目标、128代及128MiB硬上限（包括对象池），删除代后GC未引用对象。
提前淘汰会在status/archive-drops.tsv记录数量、首尾generation、时间和原因；空间上限优先，不能保证
任意设备输出量下都保存30天。未首次解锁前无法归档CE历史。

独立worker还会尽力发设备本地告警：耗电连续失败且45分钟未成功、归档持续失败或待导入/归档队列
淘汰时使用 `cmd notification post`，最多每6小时尝试一次，恢复时可替换同tag告警。通知权限/命令失败
只记录 notificationFailure，不影响采集。Root进程不存在、设备深睡或系统阻止通知时不能保证告警送达。
