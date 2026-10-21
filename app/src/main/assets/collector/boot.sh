#!/system/bin/sh
# KernelSU 2.x service.d boot entry. Disabled state survives reboots.
BASE=/data/adb/screentime-collector
[ -x "$BASE/collector.sh" ] || exit 0
[ ! -f "$BASE/disabled" ] || exit 0
/data/adb/ksu/bin/busybox ash "$BASE/collector.sh" start
