#!/system/bin/sh
# ScreenTime independent collector, protocol 1. All writable paths are fixed below.
# Runs as root outside the APK's process. Never opens the app's Room database.
# Android mksh marks persistent fd 9 close-on-exec. Use the installed KernelSU ash
# consistently, including manual `sh collector.sh ...` entry points and child roles.
BB=/data/adb/ksu/bin/busybox
if [ "${ST_COLLECTOR_ASH_PID:-}" != "$$" ]; then
    [ -x "$BB" ] || { printf 'KernelSU BusyBox unavailable\n' >&2; exit 127; }
    ST_COLLECTOR_ASH_PID=$$
    ASH_STANDALONE=1
    export ST_COLLECTOR_ASH_PID ASH_STANDALONE
    exec "$BB" ash "$0" "$@"
fi
umask 077
BASE=/data/adb/screentime-collector
SCRIPT=$BASE/collector.sh
READY=$BASE/ready
STATE=$BASE/state
BOOT=/data/adb/service.d/screentime-collector.sh
VERSION=1
RETENTION_SEC=691200 # Eight days; hard byte/frame limits can shorten this, visibly.
MAX_BYTES=536870912
MAX_FRAMES=4096
MAX_RAW_BYTES=16777216
TARGET_SECONDS=600
AWAKE_SECONDS=120
BOOT_ID=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)

mkdir -p "$READY" "$STATE" "$BASE/tmp" || exit 1
chmod 700 "$BASE" "$READY" "$STATE" "$BASE/tmp"

now_ms() {
    v=$(date +%s%3N 2>/dev/null)
    case "$v" in ''|*[!0-9]*) v=$(date +%s)000 ;; esac
    printf '%s\n' "$v"
}
get_state() { cat "$STATE/$1" 2>/dev/null || printf '%s\n' "${2-0}"; }
put_state() { printf '%s\n' "$2" > "$STATE/.$1.$$" && mv -f "$STATE/.$1.$$" "$STATE/$1"; }
numeric() { case "$1" in ''|*[!0-9]*) printf 0 ;; *) printf '%s' "$1" ;; esac; }
proc_tick() { sed 's/^.*) //' "/proc/$1/stat" 2>/dev/null | awk '{print $20}'; }
owner_of() { printf '%s %s %s\n' "$$" "$(proc_tick $$)" "$BOOT_ID"; }
owner_alive() {
    [ -f "$1/owner" ] || return 1
    read opid otick oboot < "$1/owner"
    case "$opid:$otick" in *[!0-9:]*|:*) return 1 ;; esac
    [ "$oboot" = "$BOOT_ID" ] && [ -n "$otick" ] && [ "$(proc_tick "$opid")" = "$otick" ]
}
acquire_lock() {
    lock=$1
    remaining=${2:-1}
    # Kernel flock is released on death, including SIGKILL: no PID-reuse/stale-mkdir race.
    # owner is status only; PID + start ticks + boot ID prevents signalling a recycled PID.
    mkdir -p "$lock" || return 1
    exec 9>"$lock.flock"
    while [ "$remaining" -gt 0 ]; do
        if try_flock; then
            owner_of > "$lock/owner"
            return 0
        fi
        remaining=$((remaining - 1))
        [ "$remaining" -gt 0 ] && sleep 1
    done
    exec 9>&-
    return 1
}
try_flock() {
    "$BB" flock -n 9
}
release_lock() {
    [ "$(cat "$1/owner" 2>/dev/null)" = "$(owner_of)" ] && rm -f "$1/owner"
    exec 9>&-
    return 0
}
kill_owner() {
    if owner_alive "$1"; then
        read opid otick oboot < "$1/owner"
        kill -"${2:-15}" "$opid" 2>/dev/null
    fi
}
timeout_cmd() (
    # A temporary `command 9>&-` lets ash save fd 9 as fd 10 in children. Close it
    # permanently inside the child before exec, so killing the owner releases flock.
    exec 9>&-
    seconds=$1; shift
    exec "$BB" timeout -k 5 "$seconds" "$@"
)
pause_without_lock() (
    exec 9>&-
    exec "$BB" sleep "$1"
)
package_present() {
    package_output=$(exec 9>&-; timeout_cmd 10 /system/bin/pm list packages --user 0 com.local.screentime 2>/dev/null)
    package_result=$?
    [ "$package_result" -eq 0 ] || return 2
    printf '%s\n' "$package_output" | grep -qx 'package:com.local.screentime'
}
system_ready() { [ "$(getprop sys.boot_completed)" = 1 ] && [ "$(getprop init.svc.bootanim)" != running ]; }
list_ids() {
    ack=$(get_state last_ack '')
    for directory in "$READY"/*; do
        [ -d "$directory" ] || continue
        id=${directory##*/}
        case "$id" in *[!0-9]*|'') continue ;; esac
        [ "${#id}" -eq 16 ] || continue
        [ -z "$ack" ] || [ "$id" \> "$ack" ] || continue
        printf '%s\n' "$id"
    done | sort
}
record_drop() {
    drop_id=$1; drop_reason=$2; drop_bytes=$3
    put_state dropped_frames $(( $(get_state dropped_frames) + 1 )) || return 1
    put_state dropped_bytes $(( $(get_state dropped_bytes) + drop_bytes )) || return 1
    put_state last_drop_ts "$(now_ms)" || return 1
    put_state last_drop_reason "$drop_reason" || return 1
    if [ "$(get_state first_dropped_id 0)" = 0 ]; then put_state first_dropped_id "$drop_id" || return 1; fi
    put_state last_dropped_id "$drop_id" || return 1
    printf '%s\t%s\t%s\t%s\n' "$(now_ms)" "$drop_id" "$drop_reason" "$drop_bytes" >> "$STATE/drops.tsv" || return 1
    tail -n 256 "$STATE/drops.tsv" > "$STATE/.drops.$$" && mv -f "$STATE/.drops.$$" "$STATE/drops.tsv"
}
prune_queue() {
    # Acknowledgement is durable before deletion; recover a crash between those steps.
    ack=$(get_state last_ack '')
    if [ -n "$ack" ]; then
        for directory in "$READY"/*; do
            [ -d "$directory" ] || continue
            id=${directory##*/}
            case "$id" in *[!0-9]*|'') continue ;; esac
            [ "${#id}" -eq 16 ] || continue
            if [ "$id" = "$ack" ] || [ "$id" \< "$ack" ]; then rm -rf "$directory"; fi
        done
    fi
    ids=$(list_ids)
    count=$(printf '%s\n' "$ids" | grep -c '[0-9]')
    bytes=$(du -sk "$READY" 2>/dev/null | awk '{print $1 * 1024}')
    bytes=$(numeric "$bytes")
    current=$(date +%s)
    for id in $ids; do
        directory=$READY/$id
        modified=$(stat -c %Y "$directory" 2>/dev/null)
        age=$(( current - ${modified:-current} ))
        reason=''
        [ "$age" -le "$RETENTION_SEC" ] || reason=retention_8d
        [ "$count" -le "$MAX_FRAMES" ] || reason=frame_limit
        [ "$bytes" -le "$MAX_BYTES" ] || reason=byte_limit
        [ -n "$reason" ] || continue
        size=$(du -sk "$directory" 2>/dev/null | awk '{print $1 * 1024}')
        size=$(numeric "$size")
        # Record before deletion; a crash may over-report one drop, never hide one.
        record_drop "$id" "$reason" "$size" || return 1
        rm -rf "$directory"
        count=$((count - 1)); bytes=$((bytes - size))
    done
}
capture_once() {
    [ ! -f "$BASE/disabled" ] || return 20
    system_ready || return 21
    acquire_lock "$BASE/capture.lock" 65 || return 22
    command_pid=''
    trap '[ -z "$command_pid" ] || kill "$command_pid" 2>/dev/null; release_lock "$BASE/capture.lock"; exit 143' TERM INT HUP
    started=$(now_ms)
    zone=$(getprop persist.sys.timezone | tr -cd 'A-Za-z0-9_+./:-')
    [ -n "$zone" ] || zone=UTC
    put_state last_attempt_ts "$started"
    [ "$(get_state first_attempt_ts)" -ne 0 ] || put_state first_attempt_ts "$started"
    # The capture flock excludes any live producer: safely remove abandoned staging frames.
    for abandoned in "$BASE/tmp"/frame.*; do
        [ -d "$abandoned" ] && rm -rf "$abandoned"
    done
    staging=$BASE/tmp/frame.$$
    rm -rf "$staging"
    mkdir "$staging" || { release_lock "$BASE/capture.lock"; return 1; }
    ( exec 9>&-; exec "$BB" timeout -k 5 45 /system/bin/dumpsys batterystats --charged ) > "$staging/batterystats.txt" 2> "$staging/stderr.txt" &
    command_pid=$!
    wait "$command_pid"; result=$?
    command_pid=''
    ended=$(now_ms)
    raw_bytes=$(wc -c < "$staging/batterystats.txt" | tr -d ' ')
    if [ "$result" -ne 0 ] || [ "$raw_bytes" -eq 0 ] || [ "$raw_bytes" -gt "$MAX_RAW_BYTES" ]; then
        put_state last_failure_ts "$ended"
        put_state last_failure_code "$result"
        put_state last_failure_reason command_or_size
        rm -rf "$staging"
        release_lock "$BASE/capture.lock"
        trap - TERM INT HUP
        return 23
    fi
    # Freeze UID ownership at capture time; never resolve old frames using today's packages.
    # observedTs remains the batterystats command's end, before this auxiliary lookup.
    printf '\n# ST_COLLECTOR_UID_PACKAGES_V1\n' >> "$staging/batterystats.txt"
    ( exec 9>&-; exec "$BB" timeout -k 5 10 /system/bin/pm list packages -U -u ) > "$staging/uidmap.txt" 2>/dev/null &
    command_pid=$!
    wait "$command_pid"; map_result=$?
    command_pid=''
    if [ "$map_result" -eq 0 ] && [ -s "$staging/uidmap.txt" ]; then
        cat "$staging/uidmap.txt" >> "$staging/batterystats.txt"
    else
        printf '# ST_COLLECTOR_UID_PACKAGES_ERROR exit=%s\n' "$map_result" >> "$staging/batterystats.txt"
    fi
    rm -f "$staging/uidmap.txt"
    raw_bytes=$(wc -c < "$staging/batterystats.txt" | tr -d ' ')
    if [ "$raw_bytes" -gt "$MAX_RAW_BYTES" ]; then
        put_state last_failure_ts "$(now_ms)"
        put_state last_failure_reason frame_size
        rm -rf "$staging"; release_lock "$BASE/capture.lock"; return 23
    fi
    raw_sha=$(sha256sum "$staging/batterystats.txt" | awk '{print $1}')
    if ! printf '%s\n' "$raw_sha" | grep -Eq '^[0-9a-f]{64}$'; then
        rm -rf "$staging"; release_lock "$BASE/capture.lock"; return 24
    fi
    raw_lines=$(wc -l < "$staging/batterystats.txt" | tr -d ' ')
    gzip -c "$staging/batterystats.txt" > "$staging/batterystats.txt.gz" || {
        rm -rf "$staging"; release_lock "$BASE/capture.lock"; return 24;
    }
    gzip_bytes=$(wc -c < "$staging/batterystats.txt.gz" | tr -d ' ')
    previous=$(get_state last_id 0)
    logical=$ended
    [ "$logical" -gt "$previous" ] || logical=$((previous + 1))
    id=$(printf '%016d' "$logical")
    if ! cat > "$staging/meta.json" <<EOF
{"formatVersion":1,"frameId":"$id","sequence":$logical,"commandStartTs":$started,"commandEndTs":$ended,"observedTs":$ended,"zoneId":"$zone","bootId":"$BOOT_ID","source":"ROOT","command":"batterystats --charged","uidMapExitCode":$map_result,"rawSha256":"$raw_sha","rawByteCount":$raw_bytes,"rawLineCount":$raw_lines,"gzipByteCount":$gzip_bytes}
EOF
    then rm -rf "$staging"; release_lock "$BASE/capture.lock"; return 24; fi
    rm -f "$staging/batterystats.txt" "$staging/stderr.txt"
    chmod 600 "$staging/meta.json" "$staging/batterystats.txt.gz" || { rm -rf "$staging"; release_lock "$BASE/capture.lock"; return 24; }
    put_state last_id "$logical" || { rm -rf "$staging"; release_lock "$BASE/capture.lock"; return 24; }
    if [ -e "$READY/$id" ] || ! mv "$staging" "$READY/$id"; then release_lock "$BASE/capture.lock"; return 25; fi
    put_state last_success_ts "$ended"
    put_state last_frame_id "$id"
    put_state consecutive_failures 0
    prune_queue
    # Archive work shares this capture flock and never publishes a half generation.
    if [ -r "$BASE/history.sh" ]; then
        . "$BASE/history.sh"
        history_maybe_snapshot
    else
        put_state archive_last_failure_ts "$(now_ms)"
        put_state archive_last_error missing_history_script
    fi
    release_lock "$BASE/capture.lock"
    trap - TERM INT HUP
    printf '%s\n' "$id"
}
worker() {
    acquire_lock "$BASE/worker.lock" 3 || exit 0
    trap 'release_lock "$BASE/worker.lock"; exit 0' TERM INT HUP
    failures=0
    while [ ! -f "$BASE/disabled" ]; do
        put_state worker_heartbeat_ts "$(now_ms)"
        if ! system_ready; then pause_without_lock 15; continue; fi
        package_present; presence=$?
        if [ "$presence" -eq 1 ]; then
            put_state stop_reason package_missing
            touch "$BASE/disabled"
            break
        fi
        if [ "$presence" -ne 0 ]; then
            put_state last_failure_ts "$(now_ms)"
            put_state last_failure_reason package_check_unavailable
            pause_without_lock 30
            continue
        fi
        ( exec 9>&-; exec "$BB" ash "$SCRIPT" capture ) >/dev/null 2>&1 &
        capture_pid=$!
        trap 'kill "$capture_pid" 2>/dev/null; release_lock "$BASE/worker.lock"; exit 0' TERM INT HUP
        wait "$capture_pid"; result=$?
        if [ "$result" -eq 0 ]; then
            failures=0
            interval=$TARGET_SECONDS
            power_output=$(exec 9>&-; timeout_cmd 5 /system/bin/dumpsys power 2>/dev/null)
            case "$power_output" in *mWakefulness=Awake*) interval=$AWAKE_SECONDS ;; esac
        else
            failures=$((failures + 1)); [ "$failures" -le 6 ] || failures=6
            put_state consecutive_failures "$failures"
            interval=$((10 * (1 << failures))); [ "$interval" -le 600 ] || interval=600
        fi
        collector_notify
        # Polling heartbeat lets status distinguish a sleeping worker from a dead one.
        # This does not hold a wake lock or guarantee sampling while the CPU is suspended.
        elapsed=0
        while [ "$elapsed" -lt "$interval" ] && [ ! -f "$BASE/disabled" ]; do
            ( exec 9>&-; exec "$BB" sleep 15 ) & capture_pid=$!; wait "$capture_pid"
            elapsed=$((elapsed + 15))
            put_state worker_heartbeat_ts "$(now_ms)"
        done
    done
    release_lock "$BASE/worker.lock"
}
collector_notify() {
    # Device-local, best effort. Posting failure must never stop collection.
    local current last_success first_attempt age reason fingerprint message title previous sent result
    current=$(now_ms)
    reason=''; fingerprint=''; message=''
    last_success=$(get_state last_success_ts)
    first_attempt=$(get_state first_attempt_ts)
    age=$((current - last_success))
    [ "$last_success" -ne 0 ] || age=$((current - first_attempt))
    if [ "$(get_state consecutive_failures)" -ge 3 ] && [ "$age" -gt 2700000 ]; then
        reason=battery_failure
        fingerprint=battery_failure_$(get_state consecutive_failures)
        message='独立耗电采集连续失败，已超过45分钟没有成功记录。请打开屏幕时间查看采集状态。'
    fi
    if [ "$(get_state archive_failures)" -ge 3 ]; then
        age=$((current - $(get_state archive_last_success_ts)))
        [ "$(get_state archive_last_success_ts)" -ne 0 ] || age=$((current - $(get_state archive_first_attempt_ts)))
        if [ "$age" -gt 2700000 ]; then
            reason=archive_failure
            fingerprint=archive_failure_$(get_state archive_failures)
            message='使用历史归档持续失败。系统历史仍会轮转，请打开屏幕时间检查归档状态。'
        fi
    fi
    if [ "$(get_state dropped_frames)" -gt "$(get_state notification_seen_frame_drops)" ]; then
        reason=queue_loss
        fingerprint=queue_loss_$(get_state dropped_frames)
        message='尚未导入的耗电记录被队列上限或保留策略淘汰，已记录覆盖缺口。请打开屏幕时间。'
    fi
    if [ "$(get_state archive_dropped_generations)" -gt "$(get_state notification_seen_archive_drops)" ] && [ "$(get_state archive_last_drop_reason '')" != retention_30d ]; then
        reason=archive_loss
        fingerprint=archive_loss_$(get_state archive_dropped_generations)
        message='使用历史归档因空间或代数上限提前淘汰，长期停用时仍可能形成缺口。请检查归档状态。'
    fi
    previous=$(get_state notification_last_reason '')
    title='屏幕时间采集中断'
    if [ -z "$reason" ]; then
        # Recovery replaces the same local notification, once. Retained gaps remain visible.
        case "$previous" in battery_failure|archive_failure)
            reason=recovered; fingerprint=recovered
            title='屏幕时间采集已恢复'
            message='独立采集已恢复，历史覆盖缺口仍保留在状态页。' ;;
            *) return 0 ;;
        esac
    fi
    [ "$fingerprint" != "$(get_state notification_last_fingerprint '')" ] || return 0
    sent=$(get_state notification_last_attempt_ts)
    age=$((current - sent))
    # At most one attempt in six hours, including permission failures.
    [ "$sent" -eq 0 ] || [ "$age" -lt 0 ] || [ "$age" -ge 21600000 ] || return 0
    put_state notification_last_attempt_ts "$current"
    timeout_cmd 5 /system/bin/cmd notification post -t "$title" screentime-collector "$message" >/dev/null 2>&1
    result=$?
    if [ "$result" -eq 0 ]; then
        put_state notification_last_sent_ts "$current"
        put_state notification_last_reason "$reason"
        put_state notification_last_fingerprint "$fingerprint"
        put_state notification_seen_frame_drops "$(get_state dropped_frames)"
        put_state notification_seen_archive_drops "$(get_state archive_dropped_generations)"
        put_state notification_failure ''
    else
        put_state notification_failure notification_post_failed
        put_state notification_failure_code "$result"
        put_state notification_failure_ts "$current"
        put_state notification_failures $(( $(get_state notification_failures) + 1 ))
    fi
    return 0
}
supervise() {
    acquire_lock "$BASE/supervisor.lock" 3 || exit 0
    trap 'kill_owner "$BASE/worker.lock"; release_lock "$BASE/supervisor.lock"; exit 0' TERM INT HUP
    while [ ! -f "$BASE/disabled" ]; do
        put_state supervisor_heartbeat_ts "$(now_ms)"
        # Adopt a surviving worker after a supervisor restart instead of spinning duplicates.
        if owner_alive "$BASE/worker.lock"; then
            ( exec 9>&-; exec "$BB" sleep 5 ) & child=$!; wait "$child"
            continue
        fi
        ( exec 9>&-; exec "$BB" ash "$SCRIPT" worker ) & child=$!
        wait "$child"
        [ ! -f "$BASE/disabled" ] || break
        put_state worker_restarts $(( $(get_state worker_restarts) + 1 ))
        ( exec 9>&-; exec "$BB" sleep 5 ) & child=$!; wait "$child"
    done
    release_lock "$BASE/supervisor.lock"
}
start_service() {
    [ ! -f "$BASE/disabled" ] || return 20
    if ! owner_alive "$BASE/supervisor.lock"; then
        # nohup detaches lifetime from libsu / the app's process and closes its pipes.
        command -v nohup >/dev/null 2>&1 || return 127
        if ! command -v flock >/dev/null 2>&1 && [ ! -x /data/adb/ksu/bin/busybox ]; then return 127; fi
        nohup "$BB" ash "$SCRIPT" supervise </dev/null >/dev/null 2>&1 &
        tries=0
        while [ "$tries" -lt 5 ]; do
            if owner_alive "$BASE/supervisor.lock" && owner_alive "$BASE/worker.lock"; then return 0; fi
            sleep 1; tries=$((tries + 1))
        done
        return 26
    fi
}
stop_service() {
    touch "$BASE/disabled" || return 28
    put_state stop_reason explicit_stop
    kill_owner "$BASE/capture.lock"
    kill_owner "$BASE/worker.lock"
    kill_owner "$BASE/supervisor.lock"
    tries=0
    while [ "$tries" -lt 5 ]; do
        if ! owner_alive "$BASE/capture.lock" && ! owner_alive "$BASE/worker.lock" && ! owner_alive "$BASE/supervisor.lock"; then return 0; fi
        sleep 1; tries=$((tries + 1))
    done
    # A shell in a synchronous system command may defer TERM. Escalate only after verifying
    # PID + process start tick + boot ID again; never signal another process through a stale PID.
    kill_owner "$BASE/capture.lock" 9
    kill_owner "$BASE/worker.lock" 9
    kill_owner "$BASE/supervisor.lock" 9
    tries=0
    while [ "$tries" -lt 5 ]; do
        if ! owner_alive "$BASE/capture.lock" && ! owner_alive "$BASE/worker.lock" && ! owner_alive "$BASE/supervisor.lock"; then return 0; fi
        sleep 1; tries=$((tries + 1))
    done
    return 27
}
status() {
    sup=false; work=false
    owner_alive "$BASE/supervisor.lock" && sup=true
    owner_alive "$BASE/worker.lock" && work=true
    enabled=true; [ ! -f "$BASE/disabled" ] || enabled=false
    boot=false; [ ! -x "$BOOT" ] || boot=true
    count=$(list_ids | grep -c '[0-9]')
    bytes=$(du -sk "$READY" 2>/dev/null | awk '{print $1 * 1024}')
    cat <<EOF
{"formatVersion":1,"installed":true,"enabled":$enabled,"bootInstalled":$boot,"supervisorRunning":$sup,"workerRunning":$work,"queuedFrames":$count,"queuedBytes":$(numeric "$bytes"),"lastAttemptTs":$(get_state last_attempt_ts),"lastSuccessTs":$(get_state last_success_ts),"lastFailureTs":$(get_state last_failure_ts),"lastFailureCode":$(get_state last_failure_code),"workerHeartbeatTs":$(get_state worker_heartbeat_ts),"supervisorHeartbeatTs":$(get_state supervisor_heartbeat_ts),"consecutiveFailures":$(get_state consecutive_failures),"workerRestarts":$(get_state worker_restarts),"droppedFrames":$(get_state dropped_frames),"droppedBytes":$(get_state dropped_bytes),"lastDropTs":$(get_state last_drop_ts),"firstDroppedFrameId":"$(get_state first_dropped_id '')","lastDroppedFrameId":"$(get_state last_dropped_id '')","lastDropReason":"$(get_state last_drop_reason '')","lastAckFrameId":"$(get_state last_ack '')","stopReason":"$(get_state stop_reason '')","targetIntervalSeconds":$TARGET_SECONDS,"retentionSeconds":$RETENTION_SEC,"maxQueueBytes":$MAX_BYTES,"archiveLastAttemptTs":$(get_state archive_last_attempt_ts),"archiveLastSuccessTs":$(get_state archive_last_success_ts),"archiveLastFailureTs":$(get_state archive_last_failure_ts),"archiveFailures":$(get_state archive_failures),"archiveLastError":"$(get_state archive_last_error '')","archiveGenerations":$(get_state archive_generations),"archiveBytes":$(get_state archive_bytes),"archiveDroppedGenerations":$(get_state archive_dropped_generations),"archiveLastDropTs":$(get_state archive_last_drop_ts),"archiveLastDropReason":"$(get_state archive_last_drop_reason '')","archiveFirstDroppedGeneration":"$(get_state archive_first_dropped_generation '')","archiveLastDroppedGeneration":"$(get_state archive_last_dropped_generation '')","notificationFailure":"$(get_state notification_failure '')","notificationFailureTs":$(get_state notification_failure_ts),"notificationLastSentTs":$(get_state notification_last_sent_ts)}
EOF
}
valid_id() { [ "${#1}" -eq 16 ] && case "$1" in *[!0-9]*) return 1 ;; *) return 0 ;; esac; }

case "${1:-status}" in
    start) start_service ;;
    enable|restart)
        stop_service || exit $?
        rm -f "$BASE/disabled"
        put_state stop_reason ''
        start_service ;;
    stop) stop_service ;;
    status) status ;;
    supervise) supervise ;;
    worker) worker ;;
    capture) capture_once ;;
    list) list_ids ;;
    read)
        valid_id "$2" && [ -d "$READY/$2" ] || exit 30
        [ -s "$READY/$2/meta.json" ] && [ -s "$READY/$2/batterystats.txt.gz" ] || exit 34
        base64 "$READY/$2/meta.json" | tr -d '\n'; printf '\n'
        base64 "$READY/$2/batterystats.txt.gz" | tr -d '\n'; printf '\n' ;;
    ack)
        valid_id "$2" || exit 31
        [ -d "$READY/$2" ] || [ "$(get_state last_ack '')" = "$2" ] || exit 32
        old=$(get_state last_ack '')
        [ -z "$old" ] || [ "$2" = "$old" ] || [ "$2" \> "$old" ] || exit 33
        put_state last_ack "$2" || exit 35
        # Only remove acknowledged frames; producer owns retention/drop counters.
        for directory in "$READY"/*; do
            [ -d "$directory" ] || continue
            id=${directory##*/}; valid_id "$id" || continue
            if [ "$id" = "$2" ] || [ "$id" \< "$2" ]; then rm -rf "$directory"; fi
        done ;;
    *) printf 'unknown command\n' >&2; exit 64 ;;
esac
