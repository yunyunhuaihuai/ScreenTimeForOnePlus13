package com.local.screentime.data

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The dump header certifies its query interval; event extrema alone never certify coverage. */
internal fun dumpCoverage(lines: List<String>, observedAt: Long, zone: ZoneId): Map<Int, List<VerifiedRange>> {
    val result = linkedMapOf<Int, List<VerifiedRange>>()
    val headers = Regex("""timeRange="(\d{4}/\d{1,2}/\d{1,2} \d{1,2}:\d{2})[^0-9]+(\d{4}/\d{1,2}/\d{1,2} \d{1,2}:\d{2})"""
    )
    val format = DateTimeFormatter.ofPattern("yyyy/M/d H:mm")
    var user = -1
    var pending: VerifiedRange? = null
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("user=")) {
            user = trimmed.removePrefix("user=").toIntOrNull() ?: -1
            pending = null
        } else if (user >= 0 && trimmed.startsWith("Last 24 hour events")) {
            val match = headers.find(trimmed) ?: continue
            runCatching {
                // Header precision is one minute; do not certify the uncertain first minute.
                val from = LocalDateTime.parse(match.groupValues[1], format).atZone(zone).toInstant().toEpochMilli() + 60_000
                val untilMinute = LocalDateTime.parse(match.groupValues[2], format).atZone(zone).toInstant().toEpochMilli()
                if (observedAt >= untilMinute && observedAt - untilMinute < 120_000 &&
                    observedAt > from && observedAt - from <= 25 * 3_600_000L) {
                    pending = VerifiedRange(from, observedAt)
                }
            }
        } else if (trimmed.startsWith("In-memory daily stats") && pending != null) {
            // A successful command with an incomplete event section cannot authorize deletion.
            result[user] = listOf(pending!!)
            pending = null
        }
    }
    return result
}

internal data class VerifiedUsageSource(
    val events: List<RawEvent>,
    val ranges: List<VerifiedRange>,
    val failures: List<String>,
)

internal fun combineUsageSources(
    history: HistoryReadResult,
    dumpEvents: List<RawEvent>,
    liveRanges: Map<Int, List<VerifiedRange>>,
): VerifiedUsageSource {
    val perUser = history.coverage.groupBy { it.userId }.mapValues { (_, rows) ->
        mergeRanges(rows.map { VerifiedRange(it.beginTs, it.endTs) })
    }
    val users = (history.users + perUser.keys + history.failures.mapNotNull { it.userId } +
        liveRanges.keys + dumpEvents.map { it.userId }).toSortedSet()
    if (0 !in users) return VerifiedUsageSource(emptyList(), emptyList(), listOf("主用户历史与实时事件均不可用"))
    var common: List<VerifiedRange>? = null
    for (user in users) {
        val ranges = mergeRanges(perUser[user].orEmpty() + liveRanges[user].orEmpty())
        common = common?.let { intersectRanges(it, ranges) } ?: ranges
    }
    // Persisted events keep milliseconds. Only take live events outside the persisted
    // snapshot's certified range, so identical Activity events are never counted twice.
    val events = (history.events + dumpEvents.filter { event ->
        perUser[event.userId].orEmpty().none { event.ts >= it.from && event.ts < it.to }
    }).sortedBy { it.ts }
    return VerifiedUsageSource(events, common.orEmpty(), history.failures.map {
        "user=${it.userId} ${it.file.orEmpty()}: ${it.reason}"
    })
}
