package com.local.screentime.data

import androidx.room.withTransaction
import java.time.Instant
import java.time.ZoneId

/** All intervals are half-open and describe verified source coverage, not first/last use. */
data class VerifiedRange(val from: Long, val to: Long) {
    init { require(from >= 0 && to > from) }
}

internal fun mergeRanges(ranges: List<VerifiedRange>): List<VerifiedRange> {
    val out = ArrayList<VerifiedRange>()
    for (range in ranges.sortedBy { it.from }) {
        val last = out.lastOrNull()
        if (last != null && range.from <= last.to) {
            out[out.lastIndex] = VerifiedRange(last.from, maxOf(last.to, range.to))
        } else out.add(range)
    }
    return out
}

internal fun intersectRanges(a: List<VerifiedRange>, b: List<VerifiedRange>): List<VerifiedRange> =
    mergeRanges(a.flatMap { x -> b.mapNotNull { y ->
        val from = maxOf(x.from, y.from)
        val to = minOf(x.to, y.to)
        if (to > from) VerifiedRange(from, to) else null
    } })

internal fun subtractRanges(range: VerifiedRange, covered: List<VerifiedRange>): List<VerifiedRange> {
    var remaining = listOf(range)
    for (cut in mergeRanges(covered)) remaining = remaining.flatMap { r ->
        if (cut.to <= r.from || cut.from >= r.to) listOf(r) else buildList {
            if (cut.from > r.from) add(VerifiedRange(r.from, cut.from))
            if (cut.to < r.to) add(VerifiedRange(cut.to, r.to))
        }
    }
    return remaining
}

/**
 * Replaces only certified intervals, splitting old sessions at both edges. Session rows,
 * day stats, coverage, revision and event watermark commit together. No source command
 * runs inside this transaction. A missing/truncated bucket cannot erase a whole day.
 */
internal suspend fun applyVerifiedUsage(
    db: AppDatabase,
    dao: UsageDao,
    events: List<RawEvent>,
    ranges: List<VerifiedRange>,
    observedAt: Long,
    zone: ZoneId,
    source: String,
    probeBeforeCommit: (suspend () -> Unit)? = null,
): Int {
    val merged = mergeRanges(ranges)
    if (merged.isEmpty()) return 0
    val sortedEvents = events.sortedBy { it.ts }
    // A session already open before the first available event cannot be reconstructed.
    // Keep its old prefix until the first definite foreground/idle transition.
    val safeRanges = merged.mapNotNull { range ->
        val firstBoundary = sortedEvents.firstOrNull {
            it.ts >= range.from && it.ts < range.to && (it.eventType == 1 || it.eventType == 16)
        }?.ts ?: return@mapNotNull null
        VerifiedRange(maxOf(range.from, firstBoundary), range.to)
    }
    val rebuilt = safeRanges.flatMap { range ->
        UsageRepository.buildSessions(sortedEvents.filter { it.ts >= range.from && it.ts < range.to },
            range.from, range.to, SESSION_SOURCE_ROOT, zone)
    }
    return db.withTransaction {
        var changed = false
        val touched = linkedSetOf<Long>()
        for (range in safeRanges) {
            val old = dao.sessionsOverlapping(range.from, range.to)
            val replacement = rebuilt.filter { it.startTs >= range.from && it.startTs < range.to }
            val oldInside = old.mapNotNull { row ->
                val start = maxOf(row.startTs, range.from)
                val end = minOf(row.endTs, range.to)
                if (end > start) Triple(row.packageName, start, end) else null
            }.sortedWith(compareBy({ it.second }, { it.third }, { it.first }))
            val newInside = replacement.map { Triple(it.packageName, it.startTs, it.endTs) }
                .sortedWith(compareBy({ it.second }, { it.third }, { it.first }))
            if (oldInside != newInside) {
                changed = true
                val edges = old.flatMap { row -> buildList {
                    if (row.startTs < range.from) add(row.copy(id = 0, endTs = range.from))
                    if (row.endTs > range.to) add(row.copy(id = 0, startTs = range.to))
                } }
                dao.deleteSessionsOverlapping(range.from, range.to)
                if (edges.isNotEmpty()) dao.insertSessions(edges)
                if (replacement.isNotEmpty()) dao.insertSessions(replacement)
            }
            var day = Instant.ofEpochMilli(range.from).atZone(zone).toLocalDate()
            val last = Instant.ofEpochMilli(range.to - 1).atZone(zone).toLocalDate()
            while (!day.isAfter(last)) { touched.add(day.toEpochDay()); day = day.plusDays(1) }
        }
        // Coverage and day counters follow source certificates, independently of a session
        // whose foreground was already open at the left edge of the read window.
        for (range in merged) {
            var day = Instant.ofEpochMilli(range.from).atZone(zone).toLocalDate()
            val last = Instant.ofEpochMilli(range.to - 1).atZone(zone).toLocalDate()
            while (!day.isAfter(last)) { touched.add(day.toEpochDay()); day = day.plusDays(1) }
        }
        val stats = UsageRepository.computeDayEventStats(sortedEvents, zone)
        val coverage = ArrayList<UsageCoverageEntity>()
        for (dayValue in touched) {
            val day = java.time.LocalDate.ofEpochDay(dayValue)
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), observedAt)
            val full = merged.any { it.from <= start && it.to >= end }
            val dayRanges = merged.mapNotNull { r ->
                val f = maxOf(start, r.from); val t = minOf(end, r.to)
                if (t > f) VerifiedRange(f, t) else null
            }
            if (dayRanges.isEmpty()) continue
            val previous = dao.coverageOfDay(dayValue)
            if (full || previous?.status != 2) coverage.add(UsageCoverageEntity(dayValue,
                dayRanges.minOf { it.from }, dayRanges.maxOf { it.to }, if (full) 2 else 1, observedAt, source))
            if (full) {
                val pickups = stats.pickups[dayValue] ?: 0L
                val unlock = stats.unlockFirst[dayValue].orEmpty()
                val oldUnlock = dao.unlockStatsForDay(dayValue).associate { it.packageName to it.count }
                if (dao.getDayStats(dayValue)?.pickups != pickups || oldUnlock != unlock) changed = true
                dao.deleteDayStatsFor(dayValue)
                dao.deleteUnlockStatsFor(dayValue)
                dao.upsertDayStats(listOf(DayStatsEntity(dayValue, pickups, SESSION_SOURCE_ROOT)))
                if (unlock.isNotEmpty()) dao.upsertUnlockStats(unlock.map { (pkg, count) ->
                    UnlockStatsEntity(dayValue, pkg, count, SESSION_SOURCE_ROOT)
                })
            }
        }
        dao.upsertUsageCoverage(coverage)
        val unresolved = dao.collectionGaps("usage").flatMap { gap ->
            subtractRanges(VerifiedRange(gap.fromTs, gap.toTs), merged).map {
                CollectionGapEntity("usage", it.from, it.to, gap.detectedTs)
            }
        }
        dao.clearCollectionGaps("usage")
        if (unresolved.isNotEmpty()) dao.upsertCollectionGaps(unresolved)
        val verifiedTo = merged.maxOf { it.to }
        val prev = dao.getState(UsageRepository.KEY_WATERMARK) ?: 0
        dao.putState(SyncStateEntity(UsageRepository.KEY_WATERMARK, maxOf(prev, verifiedTo)))
        if (changed) dao.putState(SyncStateEntity(UsageRepository.KEY_DATA_REVISION,
            (dao.getState(UsageRepository.KEY_DATA_REVISION) ?: 0) + 1))
        probeBeforeCommit?.invoke()
        rebuilt.size
    }
}
