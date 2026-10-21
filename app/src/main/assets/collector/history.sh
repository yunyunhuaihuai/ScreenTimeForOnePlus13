#!/system/bin/sh
# Sourced by collector.sh ONLY while holding capture.lock (single producer).
HISTORY=$BASE/history
HOBJECTS=$HISTORY/.objects
HISTORY_INTERVAL_MS=21600000
HISTORY_RETRY_MS=300000
HISTORY_RETENTION_SEC=2592000
HISTORY_MAX_BYTES=134217728
HISTORY_MAX_GENERATIONS=128

history_check_deadline() {
    local seconds
    seconds=$(cut -d. -f1 /proc/uptime)
    [ "$seconds" -le "$HISTORY_DEADLINE" ]
}

history_ids() {
    local directory hid
    for directory in "$HISTORY"/*; do
        [ -d "$directory" ] || continue
        hid=${directory##*/}
        valid_id "$hid" || continue
        printf '%s\n' "$hid"
    done | sort
}
history_size() { du -sk "$HISTORY" 2>/dev/null | awk '{printf "%.0f\n", $1 * 1024}'; }
history_fail() {
    put_state archive_last_failure_ts "$(now_ms)"
    put_state archive_failures $(( $(get_state archive_failures) + 1 ))
    put_state archive_last_error "$1"
}
history_gc() {
    local object links
    for object in "$HOBJECTS"/*; do
        [ -f "$object" ] || continue
        links=$(stat -c %h "$object" 2>/dev/null)
        [ "${links:-0}" -gt 1 ] || rm -f "$object"
    done
}
history_inventory_file() {
    local huser=$1 relative=$2 output=$3 source before after hash size modified
    source=/data/system_ce/$huser/usagestats/$relative
    history_check_deadline || return 1
    [ -f "$source" ] && [ -r "$source" ] || return 1
    before=$(stat -c '%s:%Y' "$source" 2>/dev/null) || return 1
    size=${before%%:*}
    case "$relative" in
        version) [ "$size" -le 1024 ] || return 3 ;;
        mappings) [ "$size" -le 4194304 ] || return 3 ;;
        daily/*) [ "$size" -le 2097152 ] || return 3 ;;
        *) return 3 ;;
    esac
    hash=$(exec 9>&-; timeout_cmd 15 sha256sum "$source" 2>/dev/null) || return 1
    hash=${hash%% *}
    printf '%s\n' "$hash" | grep -Eq '^[0-9a-f]{64}$' || return 1
    after=$(stat -c '%s:%Y' "$source" 2>/dev/null) || return 1
    [ "$before" = "$after" ] || return 2
    size=${before%%:*}; modified=${before#*:}
    printf 'F\t%s\t%s\t%s\t%s\t%s\n' "$huser" "$relative" "$size" "$hash" "$modified" >> "$output"
}
history_inventory() {
    local output=$1 huser source listing result daily file name daily_count
    : > "$output" || return 1
    for huser in 0 999; do
        source=/data/system_ce/$huser/usagestats
        listing=$(ls -ld "$source" 2>&1); result=$?
        if [ "$result" -ne 0 ]; then
            # ENOKEY, permission errors and command errors must never masquerade as absence.
            if [ "$huser" = 999 ] && printf '%s\n' "$listing" | grep -q 'No such file or directory'; then
                printf 'U\t999\tABSENT\n' >> "$output" || return 1
                continue
            fi
            return 1
        fi
        [ -d "$source" ] && [ -r "$source/daily" ] || return 1
        printf 'U\t%s\tPRESENT\n' "$huser" >> "$output" || return 1
        history_inventory_file "$huser" version "$output" || return 1
        history_inventory_file "$huser" mappings "$output" || return 1
        # Capture find's own exit code; a trailing sort must not hide a partial enumeration.
        daily=$(find "$source/daily" -maxdepth 1 -type f 2>/dev/null) || return 1
        daily=$(printf '%s\n' "$daily" | sort) || return 1
        [ -n "$daily" ] || return 1
        daily_count=0
        for file in $daily; do
            name=${file##*/}
            printf '%s\n' "$name" | grep -Eq '^[0-9]+(-c)?$' || continue
            daily_count=$((daily_count + 1))
            [ "$daily_count" -le 64 ] || return 3
            history_inventory_file "$huser" "daily/$name" "$output" || return 1
        done
        [ "$daily_count" -gt 0 ] || return 1
    done
    awk -F '\t' '$1 == "F" { total += $4 } END { exit(total > 33554432) }' "$output"
}
history_copy_inventory() {
    local inventory=$1 destination=$2 kind huser relative size hash modified object actual source
    while IFS="$(printf '\t')" read -r kind huser relative size hash modified; do
        history_check_deadline || return 1
        if [ "$kind" = U ]; then
            mkdir -p "$destination/$huser" || return 1
            if [ "$relative" = ABSENT ]; then : > "$destination/$huser/ABSENT" || return 1
            else mkdir -p "$destination/$huser/usagestats/daily" || return 1; fi
            continue
        fi
        [ "$kind" = F ] || return 1
        object=$HOBJECTS/$hash
        if [ ! -f "$object" ]; then
            source=/data/system_ce/$huser/usagestats/$relative
            timeout_cmd 15 cp "$source" "$HOBJECTS/.new.$$" || return 1
            actual=$(exec 9>&-; timeout_cmd 15 sha256sum "$HOBJECTS/.new.$$" 2>/dev/null) || return 1
            [ "${actual%% *}" = "$hash" ] || return 2
            [ "$(stat -c %s "$HOBJECTS/.new.$$" 2>/dev/null)" = "$size" ] || return 2
            chmod 600 "$HOBJECTS/.new.$$"
            mv "$HOBJECTS/.new.$$" "$object" || return 1
        else
            # Reused immutable objects are verified as well; corruption cannot spread silently.
            actual=$(exec 9>&-; timeout_cmd 15 sha256sum "$object" 2>/dev/null) || return 1
            [ "${actual%% *}" = "$hash" ] && [ "$(stat -c %s "$object" 2>/dev/null)" = "$size" ] || return 2
        fi
        ln "$object" "$destination/$huser/usagestats/$relative" || return 1
    done < "$inventory"
}
history_prune() {
    local ids count bytes current hid directory age reason first
    history_gc
    ids=$(history_ids)
    count=$(printf '%s\n' "$ids" | grep -c '[0-9]')
    bytes=$(numeric "$(history_size)")
    current=$(date +%s)
    for hid in $ids; do
        directory=$HISTORY/$hid
        age=$((current - $(stat -c %Y "$directory" 2>/dev/null)))
        reason=''
        [ "$age" -le "$HISTORY_RETENTION_SEC" ] || reason=retention_30d
        [ "$count" -le "$HISTORY_MAX_GENERATIONS" ] || reason=generation_limit
        [ "$bytes" -le "$HISTORY_MAX_BYTES" ] || reason=byte_limit
        [ -n "$reason" ] || continue
        # Persist evidence before removing a complete generation.
        put_state archive_dropped_generations $(( $(get_state archive_dropped_generations) + 1 )) || return 1
        put_state archive_last_drop_ts "$(now_ms)" || return 1
        put_state archive_last_drop_reason "$reason" || return 1
        first=$(get_state archive_first_dropped_generation '')
        [ -n "$first" ] || put_state archive_first_dropped_generation "$hid" || return 1
        put_state archive_last_dropped_generation "$hid" || return 1
        printf '%s\t%s\t%s\n' "$(now_ms)" "$hid" "$reason" >> "$STATE/archive-drops.tsv" || return 1
        tail -n 256 "$STATE/archive-drops.tsv" > "$STATE/.archive-drops.$$" && mv -f "$STATE/.archive-drops.$$" "$STATE/archive-drops.tsv" || return 1
        rm -rf "$directory"
        history_gc
        bytes=$(numeric "$(history_size)")
        count=$((count - 1))
    done
    put_state archive_generations "$count"
    put_state archive_bytes "$bytes"
}
history_snapshot() {
    local started staging generation previous finished hash count directory stale logical
    started=$(now_ms)
    HISTORY_DEADLINE=$(( $(cut -d. -f1 /proc/uptime) + 90 ))
    put_state archive_last_attempt_ts "$started"
    [ "$(get_state archive_first_attempt_ts)" -ne 0 ] || put_state archive_first_attempt_ts "$started"
    mkdir -p "$HOBJECTS" || { history_fail mkdir_failed; return 1; }
    chmod 700 "$HISTORY" "$HOBJECTS"
    for stale in "$BASE/tmp"/history.* "$HOBJECTS"/.new.*; do
        [ -e "$stale" ] && rm -rf "$stale"
    done
    history_gc
    staging=$BASE/tmp/history.$$
    mkdir "$staging" || { history_fail staging_failed; return 1; }
    if ! history_inventory "$staging/before.tsv"; then
        rm -rf "$staging"; history_fail source_inventory_failed; return 1
    fi
    if ! history_copy_inventory "$staging/before.tsv" "$staging"; then
        rm -rf "$staging"; history_gc; history_fail copy_or_hash_failed; return 1
    fi
    if ! history_inventory "$staging/after.tsv" || ! cmp -s "$staging/before.tsv" "$staging/after.tsv"; then
        rm -rf "$staging"; history_gc; history_fail source_changed_or_unreadable; return 1
    fi
    finished=$(now_ms)
    previous=$(get_state archive_last_id 0)
    generation=$finished
    [ "$generation" -gt "$previous" ] || generation=$((previous + 1))
    logical=$generation
    generation=$(printf '%016d' "$generation")
    if ! printf 'STH_ARCHIVE_V1\t%s\t%s\n' "$generation" "$finished" > "$staging/manifest.tsv" ||
       ! cut -f1-5 "$staging/before.tsv" >> "$staging/manifest.tsv"; then
        rm -rf "$staging"; history_gc; history_fail manifest_write_failed; return 1
    fi
    hash=$(sha256sum "$staging/manifest.tsv" | awk '{print $1}')
    if ! printf '%s\n' "$hash" | grep -Eq '^[0-9a-f]{64}$' ||
       ! printf '%s\n' "$hash" > "$staging/manifest.sha256"; then
        rm -rf "$staging"; history_gc; history_fail manifest_hash_failed; return 1
    fi
    count=$(grep -c '^F' "$staging/before.tsv")
    if ! printf '{"formatVersion":1,"generation":"%s","startedTs":%s,"observedAtTs":%s,"fileCount":%s,"manifestSha256":"%s"}\n' "$generation" "$started" "$finished" "$count" "$hash" > "$staging/manifest.json"; then
        rm -rf "$staging"; history_gc; history_fail manifest_write_failed; return 1
    fi
    rm -f "$staging/before.tsv" "$staging/after.tsv"
    chmod 600 "$staging/manifest.tsv" "$staging/manifest.sha256" "$staging/manifest.json"
    put_state archive_last_id "$logical" || { rm -rf "$staging"; return 1; }
    if [ -e "$HISTORY/$generation" ] || ! mv "$staging" "$HISTORY/$generation"; then history_fail publish_failed; return 1; fi
    put_state archive_last_success_ts "$finished"
    put_state archive_failures 0
    put_state archive_last_error ''
    history_prune
}
history_maybe_snapshot() {
    local current success attempt elapsed
    current=$(now_ms)
    success=$(get_state archive_last_success_ts)
    elapsed=$((current - success))
    [ "$success" -eq 0 ] || [ "$elapsed" -lt 0 ] || [ "$elapsed" -ge "$HISTORY_INTERVAL_MS" ] || return 0
    attempt=$(get_state archive_last_attempt_ts)
    elapsed=$((current - attempt))
    [ "$attempt" -eq 0 ] || [ "$elapsed" -lt 0 ] || [ "$elapsed" -ge "$HISTORY_RETRY_MS" ] || return 0
    history_snapshot
}
