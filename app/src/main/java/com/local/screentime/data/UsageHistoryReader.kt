package com.local.screentime.data

import com.local.screentime.privileged.PrivilegedCommandRunner
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.ZoneId
import java.util.Base64
import java.util.concurrent.CancellationException

/** endTs is exclusive. Only complete (closed, validated) buckets authorize historical replacement. */
data class HistoryCoverage(
    val userId: Int,
    val file: String,
    val beginTs: Long,
    val endTs: Long,
    val complete: Boolean,
    /** The protobuf's inclusive end plus one; retained separately from the rollover boundary. */
    val recordedEndTs: Long,
    val nextBeginTs: Long? = null,
    val databaseVersion: Int = 5,
    val majorVersion: Int = 1,
    val minorVersion: Int = 1,
)

data class HistoryReadFailure(val userId: Int?, val file: String?, val reason: String)

data class HistoryReadResult(
    val events: List<RawEvent>,
    val coverage: List<HistoryCoverage>,
    val failures: List<HistoryReadFailure>,
    /** A present secondary user with unreadable files must not be confused with an absent user. */
    val users: Set<Int> = emptySet(),
    val absentUsers: Set<Int> = emptySet(),
)

/**
 * Reads the persisted v5 UsageStats database, rather than dumpsys's last-24-hours view.
 * Android's v2 obfuscated protobuf schema is decoded without a new runtime dependency.
 *
 * The shell protocol has fixed paths/users, numeric basenames, bounded file counts/bytes,
 * SHA-256 before/after each read, and before/after manifests. No phone-derived text becomes
 * a new shell command. A failed/locked/unmapped/truncated bucket never becomes empty coverage.
 */
class UsageHistoryReader(private val runner: PrivilegedCommandRunner) {
    @Suppress("UNUSED_PARAMETER") // Stored epoch timestamps must not be reinterpreted in the caller's zone.
    suspend fun read(zone: ZoneId, observedAt: Long): HistoryReadResult {
        if (observedAt <= 0) return failed("invalid observedAt")
        return try {
            if (!runner.isRootAvailable()) return failed("root unavailable")
            val result = runner.exec(isolateCommand(readCommand()))
            val current = if (!result.success) {
                failed("history command exit=${result.exitCode}: ${result.stderrFirstLine.orEmpty().take(160)}")
            } else {
                decodeSnapshot(result.stdout, observedAt)
            }
            readArchives(current, observedAt)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failed("history read ${e.javaClass.simpleName}: ${e.message.orEmpty().take(160)}")
        }
    }

    private suspend fun readArchives(current: HistoryReadResult, observedAt: Long): HistoryReadResult {
        var knownArchiveUsers: Set<Int> = emptySet()
        return try {
            val listing = runner.exec(isolateCommand(archiveCatalogCommand()))
            require(listing.success) { "archive catalogue exit=${listing.exitCode}" }
            val catalogue = parseArchiveCatalog(listing.stdout, observedAt)
            knownArchiveUsers = catalogue.flatMap { it.users }.toSet()
            val selection = selectArchives(catalogue, current)
            val loaded = if (selection.files.isEmpty()) emptyList() else {
                val payload = runner.exec(isolateCommand(archivePayloadCommand(selection.files, catalogue)))
                require(payload.success) { "archive payload exit=${payload.exitCode}" }
                parseArchivePayload(payload.stdout, selection.files, catalogue, observedAt)
            }
            mergeHistoryBuckets(current, loaded, selection.failures,
                catalogue.flatMap { it.users }.toSet(), catalogue.flatMap { it.absent }.toSet())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            current.copy(users = current.users + knownArchiveUsers, absentUsers = current.absentUsers - knownArchiveUsers,
                failures = current.failures + HistoryReadFailure(null, null,
                    "archive read: ${e.message.orEmpty().take(160)}"))
        }
    }

    companion object {
        internal const val MAX_FILES_PER_USER = 32
        internal const val MAX_DAILY_BYTES = 2 * 1024 * 1024
        internal const val MAX_MAPPINGS_BYTES = 4 * 1024 * 1024
        internal const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
        internal const val MAX_EVENTS_PER_BUCKET = 100_000
        internal const val MAX_TOTAL_EVENTS = 500_000
        private const val HOUR_MS = 3_600_000L
        private const val MAX_BUCKET_MS = 26 * HOUR_MS
        private const val MAX_PROTO_FIELDS = 1_000_000
        private const val MAX_MAPPINGS = 8192
        private const val MAX_MAPPING_STRINGS = 65_536
        private const val MAX_STRING_BYTES = 16_384
        private val USERS = setOf(0, 999)
        private val KEPT_TYPES = setOf(1, 2, 15, 16, 17, 18, 23)
        private val SECONDARY_SYSTEM_TYPES = setOf(15, 16, 18)
        private val NUMERIC_NAME = Regex("[0-9]{13}(?:-c)?")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val BASE64_LINE = Regex("[A-Za-z0-9+/=]+")

        private fun failed(reason: String) = HistoryReadResult(
            emptyList(), emptyList(), listOf(HistoryReadFailure(null, null, reason)),
        )

        /** libsu reuses its parent shell: exit, exported locale and helper functions must stay local. */
        internal fun isolateCommand(command: String): String = "(\n$command\n)"

        /** Only numeric bounds and fixed strings are interpolated into this script. */
        internal fun readCommand(): String = """
            # ST_USAGE_HISTORY_V1
            export LC_ALL=C
            printf 'STH1\n'
            if ! command -v base64 >/dev/null 2>&1 || ! command -v sha256sum >/dev/null 2>&1; then
              printf 'ERROR 0 - TOOLS_UNAVAILABLE\nEND\n'
              exit 0
            fi
            total_bytes=0
            list_daily() {
              daily_names=''
              daily_count=0
              for history_path in "${'$'}1"/daily/*; do
                [ -e "${'$'}history_path" ] || continue
                history_name=${'$'}{history_path##*/}
                history_number=${'$'}{history_name%-c}
                case "${'$'}history_number" in ''|*[!0-9]*) continue ;; esac
                daily_count=${'$'}((daily_count + 1))
                if [ "${'$'}daily_count" -gt $MAX_FILES_PER_USER ]; then return 1; fi
                daily_names="${'$'}daily_names ${'$'}history_name"
              done
              printf '%s' "${'$'}daily_names"
            }
            emit_file() {
              history_user=${'$'}1
              history_kind=${'$'}2
              history_name=${'$'}3
              history_path=${'$'}4
              history_limit=${'$'}5
              if [ ! -f "${'$'}history_path" ] || [ -L "${'$'}history_path" ]; then
                printf 'ERROR %s %s NOT_REGULAR_FILE\n' "${'$'}history_user" "${'$'}history_name"
                return
              fi
              history_size=${'$'}(wc -c < "${'$'}history_path") || return 1
              case "${'$'}history_size" in ''|*[!0-9]*) return 1 ;; esac
              if [ "${'$'}history_size" -le 0 ] || [ "${'$'}history_size" -gt "${'$'}history_limit" ]; then
                printf 'ERROR %s %s SIZE_LIMIT\n' "${'$'}history_user" "${'$'}history_name"
                return
              fi
              total_bytes=${'$'}((total_bytes + history_size))
              if [ "${'$'}total_bytes" -gt $MAX_TOTAL_BYTES ]; then return 1; fi
              history_hash=${'$'}(sha256sum < "${'$'}history_path") || return 1
              history_hash=${'$'}{history_hash%% *}
              printf 'FILE %s %s %s %s %s\n' "${'$'}history_user" "${'$'}history_kind" "${'$'}history_name" "${'$'}history_size" "${'$'}history_hash"
              base64 < "${'$'}history_path" || return 1
              history_after_size=${'$'}(wc -c < "${'$'}history_path") || return 1
              history_after_hash=${'$'}(sha256sum < "${'$'}history_path") || return 1
              history_after_hash=${'$'}{history_after_hash%% *}
              printf '\nEND_FILE %s %s\n' "${'$'}history_after_size" "${'$'}history_after_hash"
            }
            for history_uid in 0 999; do
              history_base="/data/system_ce/${'$'}history_uid/usagestats"
              if [ ! -d "/data/system_ce/${'$'}history_uid" ]; then
                if [ "${'$'}history_uid" = 999 ]; then
                  printf 'USER 999 ABSENT\n'
                else
                  printf 'ERROR 0 - CE_UNAVAILABLE\n'
                fi
                continue
              fi
              if [ ! -d "${'$'}history_base/daily" ] || [ -L "${'$'}history_base" ] || [ -L "${'$'}history_base/daily" ]; then
                printf 'ERROR %s - CE_UNAVAILABLE\n' "${'$'}history_uid"
                continue
              fi
              history_names=${'$'}(list_daily "${'$'}history_base") || exit 2
              printf 'USER %s PRESENT%s\n' "${'$'}history_uid" "${'$'}history_names"
              emit_file "${'$'}history_uid" VERSION version "${'$'}history_base/version" 1024 || exit 3
              emit_file "${'$'}history_uid" MAPPINGS mappings "${'$'}history_base/mappings" $MAX_MAPPINGS_BYTES || exit 3
              for history_daily_name in ${'$'}history_names; do
                emit_file "${'$'}history_uid" DAILY "${'$'}history_daily_name" "${'$'}history_base/daily/${'$'}history_daily_name" $MAX_DAILY_BYTES || exit 3
              done
              history_after_names=${'$'}(list_daily "${'$'}history_base") || exit 2
              history_map_hash=${'$'}(sha256sum < "${'$'}history_base/mappings") || exit 3
              history_map_hash=${'$'}{history_map_hash%% *}
              history_version_hash=${'$'}(sha256sum < "${'$'}history_base/version") || exit 3
              history_version_hash=${'$'}{history_version_hash%% *}
              printf 'USER_END %s HASH %s %s%s\n' "${'$'}history_uid" "${'$'}history_map_hash" "${'$'}history_version_hash" "${'$'}history_after_names"
            done
            printf 'END\n'
        """.trimIndent()

        private data class Snapshot(
            val manifests: Map<Int, List<String>>,
            val absent: Set<Int>,
            val files: Map<Pair<Int, String>, ByteArray>,
            val failures: List<HistoryReadFailure>,
        )

        private fun parseTransport(lines: List<String>): Snapshot {
            require(lines.size <= 600_000) { "transport line limit" }
            require(lines.sumOf { it.length.toLong() } <= MAX_TOTAL_BYTES.toLong() * 2 + 100_000) { "transport byte limit" }
            val nonempty = lines.filter { it.isNotEmpty() }
            require(nonempty.firstOrNull() == "STH1" && nonempty.lastOrNull() == "END") { "incomplete history protocol" }
            val manifests = linkedMapOf<Int, List<String>>()
            val absent = linkedSetOf<Int>()
            val ended = mutableSetOf<Int>()
            val files = linkedMapOf<Pair<Int, String>, ByteArray>()
            val failures = mutableListOf<HistoryReadFailure>()
            var total = 0L
            var i = 1
            while (i < nonempty.lastIndex) {
                val words = nonempty[i++].split(' ')
                when (words[0]) {
                    "USER" -> {
                        require(words.size >= 3) { "bad USER frame" }
                        val user = words[1].toInt()
                        require(user in USERS && user !in manifests && user !in absent) { "duplicate/unknown user" }
                        when (words[2]) {
                            "ABSENT" -> {
                                require(user == 999 && words.size == 3) { "primary user cannot be absent" }
                                absent.add(user)
                            }
                            "PRESENT" -> {
                                val names = words.drop(3)
                                require(names.size <= MAX_FILES_PER_USER && names.all { NUMERIC_NAME.matches(it) } && names == names.distinct().sorted()) { "invalid daily manifest" }
                                manifests[user] = names
                            }
                            else -> error("unknown user status")
                        }
                    }
                    "USER_END" -> {
                        require(words.size >= 5 && words[2] == "HASH" && SHA256.matches(words[3]) && SHA256.matches(words[4])) { "bad USER_END" }
                        val user = words[1].toInt()
                        require(user in manifests && ended.add(user)) { "unmatched USER_END" }
                        if (words.drop(5) != manifests[user]) {
                            failures.add(HistoryReadFailure(user, null, "daily manifest changed during read"))
                        }
                        val mapping = files[user to "mappings"]
                        val version = files[user to "version"]
                        if ((mapping != null && sha256(mapping) != words[3]) || (version != null && sha256(version) != words[4])) {
                            failures.add(HistoryReadFailure(user, null, "mappings/version changed during snapshot"))
                        }
                    }
                    "ERROR" -> {
                        require(words.size == 4 && words[1].toInt() in USERS) { "bad ERROR frame" }
                        failures.add(HistoryReadFailure(words[1].toInt(), words[2].takeUnless { it == "-" }, words[3].take(160)))
                    }
                    "FILE" -> {
                        require(words.size == 6) { "bad FILE frame" }
                        val user = words[1].toInt()
                        val kind = words[2]
                        val name = words[3]
                        require(user in manifests && user !in ended) { "FILE outside user" }
                        val limit = when (kind) {
                            "VERSION" -> { require(name == "version"); 1024 }
                            "MAPPINGS" -> { require(name == "mappings"); MAX_MAPPINGS_BYTES }
                            "DAILY" -> { require(name in manifests.getValue(user)); MAX_DAILY_BYTES }
                            else -> error("unknown FILE kind")
                        }
                        val size = words[4].toInt()
                        val hash = words[5]
                        require(size in 1..limit && SHA256.matches(hash)) { "invalid FILE size/hash" }
                        total += size
                        require(total <= MAX_TOTAL_BYTES && (user to name) !in files) { "duplicate file/total size limit" }
                        val encoded = StringBuilder(((size + 2) / 3) * 4)
                        while (i < nonempty.lastIndex && !nonempty[i].startsWith("END_FILE ")) {
                            val line = nonempty[i++]
                            require(BASE64_LINE.matches(line) && encoded.length + line.length <= ((size + 2) / 3) * 4) { "invalid/oversized base64" }
                            encoded.append(line)
                        }
                        require(i < nonempty.lastIndex) { "missing END_FILE" }
                        val tail = nonempty[i++].split(' ')
                        require(tail.size == 3) { "bad END_FILE" }
                        try {
                            require(tail[1].toInt() == size && tail[2] == hash) { "file changed during read" }
                            val data = Base64.getDecoder().decode(encoded.toString())
                            require(data.size == size && sha256(data) == hash) { "decoded size/checksum mismatch" }
                            files[user to name] = data
                        } catch (e: Exception) {
                            failures.add(HistoryReadFailure(user, name, e.message.orEmpty().take(160)))
                        }
                    }
                    else -> error("unknown protocol frame")
                }
            }
            require(ended == manifests.keys) { "unterminated user snapshot" }
            require(0 in manifests || failures.any { it.userId == 0 }) { "missing primary user status" }
            require(999 in manifests || 999 in absent || failures.any { it.userId == 999 }) { "missing secondary user status" }
            return Snapshot(manifests, absent, files, failures)
        }

        internal fun decodeSnapshot(lines: List<String>, observedAt: Long): HistoryReadResult {
            val snapshot = try { parseTransport(lines) } catch (e: Exception) {
                return failed("history protocol: ${e.message.orEmpty().take(160)}")
            }
            val failures = snapshot.failures.toMutableList()
            val coverage = mutableListOf<HistoryCoverage>()
            val events = mutableListOf<RawEvent>()
            for ((user, names) in snapshot.manifests) {
                // A changing manifest is an incoherent snapshot; no bucket from that user is authoritative.
                if (failures.any { it.userId == user && it.file == null }) continue
                val mappings: Map<Int, List<String>>
                try {
                    val version = snapshot.files[user to "version"] ?: error("version unavailable")
                    require(utf8(version).lineSequence().first().trim() == "5") { "unsupported usage database version" }
                    mappings = parseMappings(snapshot.files[user to "mappings"] ?: error("mappings unavailable"))
                } catch (e: Exception) {
                    failures.add(HistoryReadFailure(user, null, e.message.orEmpty().take(160)))
                    continue
                }
                if (names.isEmpty() && user == 0) failures.add(HistoryReadFailure(0, null, "no daily files"))
                val duplicates = names.groupBy { bucketBegin(it) }.filterValues { it.size > 1 }.keys
                duplicates.forEach { failures.add(HistoryReadFailure(user, "$it", "conflicting checked-in/unchecked bucket files")) }
                for ((index, name) in names.withIndex()) {
                    try {
                        val begin = bucketBegin(name)
                        if (begin in duplicates) continue
                        require(begin in 1..observedAt) { "invalid/future bucket begin" }
                        val bucket = parseBucket(snapshot.files[user to name] ?: error("daily file unavailable"), begin, observedAt, mappings, user)
                        val next = names.getOrNull(index + 1)?.let(::bucketBegin)
                        val closed = next != null && next <= observedAt && next - begin <= MAX_BUCKET_MS &&
                            bucket.recordedEndTs - begin >= 23 * HOUR_MS && next >= bucket.recordedEndTs
                        if (next != null && !closed) failures.add(HistoryReadFailure(user, name, "non-contiguous or overlapping closed bucket"))
                        require(events.size + bucket.events.size <= MAX_TOTAL_EVENTS) { "total event limit" }
                        coverage.add(HistoryCoverage(user, name, begin, if (closed) next!! else bucket.recordedEndTs,
                            closed, bucket.recordedEndTs, next, 5, bucket.major, bucket.minor))
                        events.addAll(bucket.events)
                    } catch (e: Exception) {
                        failures.add(HistoryReadFailure(user, name, e.message.orEmpty().take(160)))
                    }
                }
            }
            val presentOrUnavailable = snapshot.manifests.keys + failures.mapNotNull { it.userId }
            return HistoryReadResult(events.sortedBy { it.ts }, coverage, failures, presentOrUnavailable, snapshot.absent)
        }

        private fun bucketBegin(name: String): Long = name.removeSuffix("-c").toLong()

        private const val ARCHIVE_ROOT = "/data/adb/screentime-collector/history"
        private const val MAX_GENERATIONS = 128
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_CATALOGUE_BYTES = 4 * 1024 * 1024
        private val GENERATION_NAME = Regex("[0-9]{16}")
        private data class ArchivedFile(val generation: String, val user: Int, val path: String, val size: Int, val hash: String)
        private data class ArchiveHead(val endOffset: Long, val major: Int, val minor: Int)
        private data class ArchiveGeneration(
            val generation: String, val observedAt: Long, val hash: String, val users: Set<Int>,
            val absent: Set<Int>, val files: List<ArchivedFile>, val heads: Map<Pair<Int, String>, ArchiveHead>,
        )
        private data class ArchiveSelection(val files: List<ArchivedFile>, val failures: List<HistoryReadFailure>)
        private data class HistoryBucket(val coverage: HistoryCoverage, val events: List<RawEvent>, val system: Boolean)

        /** Metadata and 32-byte protobuf headers only; full duplicate daily files are never streamed. */
        internal fun archiveCatalogCommand(): String = """
            # ST_USAGE_ARCHIVE_CATALOG_V1
            export LC_ALL=C
            printf 'STAC1\n'
            archive_root='$ARCHIVE_ROOT'
            if [ ! -e "${'$'}archive_root" ]; then printf 'END\n'; exit 0; fi
            [ -d "${'$'}archive_root" ] && [ ! -L "${'$'}archive_root" ] || exit 2
            archive_count=0
            archive_bytes=0
            for archive_dir in "${'$'}archive_root"/*; do
              [ -d "${'$'}archive_dir" ] || continue
              archive_gen=${'$'}{archive_dir##*/}
              case "${'$'}archive_gen" in ''|*[!0-9]*) continue ;; esac
              [ "${'$'}{#archive_gen}" = 16 ] && [ ! -L "${'$'}archive_dir" ] || exit 2
              archive_count=${'$'}((archive_count + 1))
              [ "${'$'}archive_count" -le $MAX_GENERATIONS ] || exit 2
              [ -f "${'$'}archive_dir/manifest.tsv" ] && [ ! -L "${'$'}archive_dir/manifest.tsv" ] && [ ! -L "${'$'}archive_dir/manifest.sha256" ] || exit 3
              archive_size=${'$'}(wc -c < "${'$'}archive_dir/manifest.tsv") || exit 3
              case "${'$'}archive_size" in ''|*[!0-9]*) exit 3 ;; esac
              [ "${'$'}archive_size" -gt 0 ] && [ "${'$'}archive_size" -le $MAX_MANIFEST_BYTES ] || exit 3
              archive_bytes=${'$'}((archive_bytes + archive_size))
              [ "${'$'}archive_bytes" -le $MAX_CATALOGUE_BYTES ] || exit 3
              archive_hash=${'$'}(cat "${'$'}archive_dir/manifest.sha256") || exit 3
              printf 'MANIFEST %s %s %s\n' "${'$'}archive_gen" "${'$'}archive_size" "${'$'}archive_hash"
              base64 < "${'$'}archive_dir/manifest.tsv" || exit 3
              archive_after=${'$'}(sha256sum < "${'$'}archive_dir/manifest.tsv") || exit 3
              printf '\nEND_MANIFEST %s\n' "${'$'}{archive_after%% *}"
              for archive_user in 0 999; do
                for archive_file in "${'$'}archive_dir/${'$'}archive_user/usagestats/daily"/*; do
                  [ -e "${'$'}archive_file" ] || continue
                  archive_name=${'$'}{archive_file##*/}
                  archive_number=${'$'}{archive_name%-c}
                  case "${'$'}archive_number" in ''|*[!0-9]*) continue ;; esac
                  [ -f "${'$'}archive_file" ] && [ ! -L "${'$'}archive_file" ] || exit 4
                  archive_head=${'$'}(head -c 32 < "${'$'}archive_file" | base64 | tr -d '\n')
                  printf 'HEADER %s %s %s %s\n' "${'$'}archive_gen" "${'$'}archive_user" "${'$'}archive_name" "${'$'}archive_head"
                done
              done
              archive_after=${'$'}(sha256sum < "${'$'}archive_dir/manifest.tsv") || exit 3
              printf 'GEN_END %s %s\n' "${'$'}archive_gen" "${'$'}{archive_after%% *}"
            done
            printf 'END\n'
        """.trimIndent()

        private fun decodeBase64(lines: List<String>, expectedSize: Int, expectedHash: String): ByteArray {
            val text = StringBuilder(((expectedSize + 2) / 3) * 4)
            for (line in lines) {
                require(BASE64_LINE.matches(line) && text.length + line.length <= ((expectedSize + 2) / 3) * 4) { "invalid bounded base64" }
                text.append(line)
            }
            val data = Base64.getDecoder().decode(text.toString())
            require(data.size == expectedSize && sha256(data) == expectedHash) { "archive manifest checksum/size mismatch" }
            return data
        }

        private fun parseArchiveCatalog(lines: List<String>, observedAt: Long): List<ArchiveGeneration> {
            require(lines.size <= 100_000 && lines.sumOf { it.length.toLong() } <= MAX_CATALOGUE_BYTES * 2L) { "archive catalogue limit" }
            val rows = lines.filter { it.isNotEmpty() }
            require(rows.firstOrNull() == "STAC1" && rows.lastOrNull() == "END") { "incomplete archive catalogue" }
            val result = mutableListOf<ArchiveGeneration>()
            var i = 1
            while (i < rows.lastIndex) {
                val header = rows[i++].split(' ')
                require(header.size == 4 && header[0] == "MANIFEST" && GENERATION_NAME.matches(header[1]) && SHA256.matches(header[3])) { "bad archive manifest frame" }
                val generation = header[1]
                require(result.size < MAX_GENERATIONS && result.none { it.generation == generation }) { "archive generation limit/duplicate" }
                val size = header[2].toInt()
                require(size in 1..MAX_MANIFEST_BYTES) { "archive manifest size limit" }
                val encoded = mutableListOf<String>()
                while (i < rows.lastIndex && !rows[i].startsWith("END_MANIFEST ")) encoded.add(rows[i++])
                require(i < rows.lastIndex && rows[i++] == "END_MANIFEST ${header[3]}") { "archive manifest changed" }
                val manifest = utf8(decodeBase64(encoded, size, header[3])).lineSequence().filter { it.isNotEmpty() }.toList()
                require(manifest.size in 3..(MAX_FILES_PER_USER * 2 + 8)) { "archive manifest row limit" }
                val first = manifest.first().split('\t')
                require(first.size == 3 && first[0] == "STH_ARCHIVE_V1" && first[1] == generation) { "unsupported archive manifest version/generation" }
                val capturedAt = first[2].toLong()
                require(capturedAt in 1..observedAt) { "future/invalid archive observation" }
                val users = linkedSetOf<Int>()
                val absent = linkedSetOf<Int>()
                val files = mutableListOf<ArchivedFile>()
                for (line in manifest.drop(1)) {
                    val parts = line.split('\t')
                    when (parts[0]) {
                        "U" -> {
                            require(parts.size == 3 && parts[1].toInt() in USERS) { "bad archive user" }
                            val user = parts[1].toInt()
                            require(user !in users && user !in absent) { "duplicate archive user" }
                            when (parts[2]) {
                                "PRESENT" -> users.add(user)
                                "ABSENT" -> { require(user == 999); absent.add(user) }
                                else -> error("bad archive user status")
                            }
                        }
                        "F" -> {
                            require(parts.size == 5 && parts[1].toInt() in USERS && SHA256.matches(parts[4])) { "bad archive file row" }
                            val user = parts[1].toInt()
                            val path = parts[2]
                            val bytes = parts[3].toInt()
                            val limit = when {
                                path == "version" -> 1024
                                path == "mappings" -> MAX_MAPPINGS_BYTES
                                path.startsWith("daily/") && NUMERIC_NAME.matches(path.removePrefix("daily/")) -> MAX_DAILY_BYTES
                                else -> error("unsafe archive relative path")
                            }
                            require(bytes in 1..limit && files.none { it.user == user && it.path == path }) { "archive duplicate/size limit" }
                            files.add(ArchivedFile(generation, user, path, bytes, parts[4]))
                        }
                        else -> error("unknown archive manifest row")
                    }
                }
                require(0 in users && (999 in users || 999 in absent) && files.all { it.user in users }) { "incomplete archive user manifest" }
                for (user in users) {
                    require(files.any { it.user == user && it.path == "version" } && files.any { it.user == user && it.path == "mappings" }) { "archive missing version/mappings" }
                    require(files.count { it.user == user && it.path.startsWith("daily/") } <= MAX_FILES_PER_USER) { "archive daily limit" }
                }
                val heads = linkedMapOf<Pair<Int, String>, ArchiveHead>()
                while (i < rows.lastIndex && rows[i].startsWith("HEADER ")) {
                    val parts = rows[i++].split(' ')
                    require(parts.size == 5 && parts[1] == generation && parts[2].toInt() in users && NUMERIC_NAME.matches(parts[3])) { "bad archive header frame" }
                    val key = parts[2].toInt() to parts[3]
                    require(key !in heads && parts[4].length <= 44 && BASE64_LINE.matches(parts[4])) { "duplicate/oversized archive header" }
                    val proto = Proto(Base64.getDecoder().decode(parts[4]))
                    val end = proto.next() ?: error("empty archive header")
                    val major = proto.next() ?: error("short archive header")
                    val minor = proto.next() ?: error("short archive header")
                    require(end.number == 1 && end.integer() in 0..MAX_BUCKET_MS && major.number == 2 && major.integer() == 1L && minor.number == 3 && minor.integer() in 0..1) { "unsupported archive interval header" }
                    heads[key] = ArchiveHead(end.integer(), major.integer().toInt(), minor.integer().toInt())
                }
                val dailyKeys = files.filter { it.path.startsWith("daily/") }.map { it.user to it.path.removePrefix("daily/") }.toSet()
                require(heads.keys == dailyKeys) { "archive manifest/header inventory mismatch" }
                require(i < rows.lastIndex && rows[i++] == "GEN_END $generation ${header[3]}") { "incomplete/changed archive generation" }
                result.add(ArchiveGeneration(generation, capturedAt, header[3], users, absent, files, heads))
            }
            return result
        }

        private fun selectArchives(catalogue: List<ArchiveGeneration>, current: HistoryReadResult): ArchiveSelection {
            val failures = mutableListOf<HistoryReadFailure>()
            val candidates = catalogue.flatMap { gen -> gen.files.filter { it.path.startsWith("daily/") }.map { file ->
                Triple(gen, file, gen.heads.getValue(file.user to file.path.removePrefix("daily/")))
            } }.groupBy { (_, file, _) -> file.user to bucketBegin(file.path.removePrefix("daily/")) }
            require(candidates.size <= 128) { "unique archive bucket limit" }
            val selected = mutableListOf<ArchivedFile>()
            for ((key, versions) in candidates) {
                val ordered = versions.sortedBy { it.first.generation }
                val duplicateName = ordered.groupBy { it.first.generation }.values.any { it.size > 1 }
                val regressed = ordered.zipWithNext().any { (a,b) -> b.third.endOffset < a.third.endOffset }
                val equalEndConflict = ordered.groupBy { it.third.endOffset }.values.any { same -> same.map { it.second.hash }.distinct().size > 1 }
                if (duplicateName || regressed || equalEndConflict) {
                    failures.add(HistoryReadFailure(key.first, "${key.second}", "archive bucket conflict/version rollback"))
                    continue
                }
                val best = ordered.maxWith(compareBy({ it.third.endOffset }, { it.first.generation }))
                val system = current.coverage.firstOrNull { it.userId == key.first && it.beginTs == key.second }
                val end = key.second + best.third.endOffset + 1
                if (system == null || end > system.recordedEndTs) selected.add(best.second)
            }
            val needed = selected.flatMap { file ->
                val gen = catalogue.first { it.generation == file.generation }
                listOf(file) + gen.files.filter { it.user == file.user && it.path in setOf("version", "mappings") }
            }.distinct()
            require(needed.sumOf { it.size.toLong() } <= MAX_TOTAL_BYTES) { "selected archive byte limit" }
            return ArchiveSelection(needed, failures)
        }

        private fun archivePayloadCommand(selection: List<ArchivedFile>, catalogue: List<ArchiveGeneration>): String {
            // Every interpolated path component has already passed numeric/fixed-path validation.
            val emitter = readCommand().substringAfter("total_bytes=0").substringBefore("for history_uid in 0 999;")
            return buildString {
                appendLine("# ST_USAGE_ARCHIVE_PAYLOAD_V1")
                appendLine("export LC_ALL=C\ntotal_bytes=0")
                appendLine(emitter)
                appendLine("printf 'STAP1\\n'")
                for ((id, files) in selection.groupBy { it.generation }.toSortedMap()) {
                    require(GENERATION_NAME.matches(id))
                    val gen = catalogue.first { it.generation == id }
                    appendLine("archive_base='$ARCHIVE_ROOT/$id'")
                    appendLine("archive_hash=\$(sha256sum < \"\$archive_base/manifest.tsv\") || exit 3")
                    appendLine("printf 'SOURCE $id %s\\n' \"\${archive_hash%% *}\"\nprintf 'STH1\\n'")
                    for (user in USERS.sorted()) {
                        if (user in gen.absent) { appendLine("printf 'USER $user ABSENT\\n'"); continue }
                        val userFiles = files.filter { it.user == user }
                        val names = userFiles.filter { it.path.startsWith("daily/") }.map { it.path.removePrefix("daily/") }.sorted()
                        appendLine("printf 'USER $user PRESENT${if (names.isEmpty()) "" else " " + names.joinToString(" ")}\\n'")
                        for (file in userFiles.sortedBy { it.path }) {
                            val kind = when (file.path) { "version" -> "VERSION"; "mappings" -> "MAPPINGS"; else -> "DAILY" }
                            val name = file.path.removePrefix("daily/")
                            require(name in setOf("version", "mappings") || NUMERIC_NAME.matches(name))
                            val limit = if (kind == "VERSION") 1024 else if (kind == "MAPPINGS") MAX_MAPPINGS_BYTES else MAX_DAILY_BYTES
                            appendLine("emit_file $user $kind '$name' \"\$archive_base/$user/usagestats/${file.path}\" $limit || exit 3")
                        }
                        val mh = gen.files.first { it.user == user && it.path == "mappings" }.hash
                        val vh = gen.files.first { it.user == user && it.path == "version" }.hash
                        appendLine("printf 'USER_END $user HASH $mh $vh${if (names.isEmpty()) "" else " " + names.joinToString(" ")}\\n'")
                    }
                    appendLine("printf 'END\\n'")
                    appendLine("archive_hash=\$(sha256sum < \"\$archive_base/manifest.tsv\") || exit 3")
                    appendLine("printf 'SOURCE_END $id %s\\n' \"\${archive_hash%% *}\"")
                }
                appendLine("printf 'END_ARCHIVE\\n'")
            }
        }

        private fun parseArchivePayload(lines: List<String>, selection: List<ArchivedFile>, catalogue: List<ArchiveGeneration>, observedAt: Long): List<HistoryBucket> {
            require(lines.size <= 600_000 && lines.sumOf { it.length.toLong() } <= MAX_TOTAL_BYTES * 2L + 100_000) { "archive payload limit" }
            val rows = lines.filter { it.isNotEmpty() }
            require(rows.firstOrNull() == "STAP1" && rows.lastOrNull() == "END_ARCHIVE") { "incomplete archive payload" }
            val out = mutableListOf<HistoryBucket>()
            val seen = mutableSetOf<String>()
            var i = 1
            var totalEvents = 0
            while (i < rows.lastIndex) {
                val source = rows[i++].split(' ')
                require(source.size == 3 && source[0] == "SOURCE" && source[1] !in seen) { "bad/duplicate archive source" }
                val gen = catalogue.firstOrNull { it.generation == source[1] } ?: error("unknown archive source")
                require(source[2] == gen.hash) { "archive manifest changed since catalogue" }
                seen.add(gen.generation)
                val start = i
                while (i < rows.lastIndex && !rows[i].startsWith("SOURCE_END ")) i++
                require(i < rows.lastIndex && rows[i] == "SOURCE_END ${gen.generation} ${gen.hash}") { "incomplete/changed archive payload generation" }
                val snapshot = parseTransport(rows.subList(start, i++))
                require(snapshot.failures.isEmpty()) { "archive file read/integrity failure" }
                val expected = selection.filter { it.generation == gen.generation }
                val expectedKeys = expected.map { it.user to it.path.removePrefix("daily/") }.toSet()
                require(snapshot.files.keys == expectedKeys) { "archive selected file inventory mismatch" }
                for (file in expected) {
                    val bytes = snapshot.files.getValue(file.user to file.path.removePrefix("daily/"))
                    require(bytes.size == file.size && sha256(bytes) == file.hash) { "archive file does not match published manifest" }
                }
                for ((user, selected) in expected.groupBy { it.user }) {
                    require(utf8(snapshot.files.getValue(user to "version")).lineSequence().first().trim() == "5") { "unsupported archived database version" }
                    val mappings = parseMappings(snapshot.files.getValue(user to "mappings"))
                    val allNames = gen.files.filter { it.user == user && it.path.startsWith("daily/") }.map { it.path.removePrefix("daily/") }.sorted()
                    for (file in selected.filter { it.path.startsWith("daily/") }) {
                        val name = file.path.removePrefix("daily/")
                        val begin = bucketBegin(name)
                        require(begin in 1..observedAt) { "invalid archived bucket begin" }
                        val bucket = parseBucket(snapshot.files.getValue(user to name), begin, observedAt, mappings, user)
                        val head = gen.heads.getValue(user to name)
                        require(bucket.recordedEndTs == begin + head.endOffset + 1 && bucket.major == head.major && bucket.minor == head.minor) { "archive prefix/full protobuf conflict" }
                        totalEvents += bucket.events.size
                        require(totalEvents <= MAX_TOTAL_EVENTS) { "archive event limit" }
                        val next = allNames.getOrNull(allNames.indexOf(name) + 1)?.let(::bucketBegin)
                        val closed = next != null && next <= observedAt && next - begin <= MAX_BUCKET_MS &&
                            bucket.recordedEndTs - begin >= 23 * HOUR_MS && next >= bucket.recordedEndTs
                        out.add(HistoryBucket(HistoryCoverage(user, name, begin, if (closed) next!! else bucket.recordedEndTs,
                            closed, bucket.recordedEndTs, next, 5, bucket.major, bucket.minor), bucket.events, false))
                    }
                }
            }
            require(seen == selection.map { it.generation }.toSet()) { "missing archive payload generation" }
            return out
        }

        private fun mergeHistoryBuckets(current: HistoryReadResult, archived: List<HistoryBucket>, extraFailures: List<HistoryReadFailure>, archiveUsers: Set<Int>, archiveAbsent: Set<Int>): HistoryReadResult {
            val failures = (current.failures + extraFailures).toMutableList()
            val system = current.coverage.map { coverage -> HistoryBucket(coverage,
                current.events.filter { it.userId == coverage.userId && it.ts >= coverage.beginTs && it.ts < coverage.recordedEndTs }, true) }
            val selected = (system + archived).groupBy { it.coverage.userId to it.coverage.beginTs }.values.map { group ->
                group.maxWith(compareBy({ it.coverage.recordedEndTs }, { it.system }))
            }
            val blocked = mutableSetOf<Pair<Int, Long>>()
            for ((user, buckets) in selected.groupBy { it.coverage.userId }) {
                for ((a,b) in buckets.sortedBy { it.coverage.beginTs }.zipWithNext()) {
                    if (a.coverage.recordedEndTs > b.coverage.beginTs) {
                        blocked.add(user to a.coverage.beginTs); blocked.add(user to b.coverage.beginTs)
                        failures.add(HistoryReadFailure(user, a.coverage.file, "overlapping distinct system/archive buckets"))
                    }
                }
            }
            val good = selected.filter { (it.coverage.userId to it.coverage.beginTs) !in blocked }
            val coverage = good.groupBy { it.coverage.userId }.values.flatMap { userBuckets ->
                val ordered = userBuckets.sortedBy { it.coverage.beginTs }
                ordered.mapIndexed { index, item ->
                    val c = item.coverage
                    val next = ordered.getOrNull(index + 1)?.coverage?.beginTs
                    if (next != null && next - c.beginTs <= MAX_BUCKET_MS && c.recordedEndTs - c.beginTs >= 23 * HOUR_MS && next >= c.recordedEndTs)
                        c.copy(endTs = next, nextBeginTs = next, complete = true) else c
                }
            }
            val events = good.flatMap { it.events }.sortedWith(compareBy({ it.ts }, { it.userId }))
            require(events.size <= MAX_TOTAL_EVENTS) { "combined history event limit" }
            val users = current.users + archiveUsers + failures.mapNotNull { it.userId }
            return HistoryReadResult(events, coverage, failures, users, (current.absentUsers + archiveAbsent) - users)
        }

        private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }

        private fun utf8(data: ByteArray): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data)).toString()

        private data class Field(val number: Int, val wire: Int, val value: Long = 0, val bytes: ByteArray? = null)

        /** Every nested message is length bounded; no recursion or allocation based on unchecked lengths. */
        private class Proto(private val data: ByteArray) {
            private var pos = 0
            private var count = 0
            fun next(): Field? {
                if (pos == data.size) return null
                require(++count <= MAX_PROTO_FIELDS) { "protobuf field limit" }
                val tag = varint()
                require(tag in 1..0xffff_ffffL && tag ushr 3 <= 0x1fff_ffffL) { "invalid protobuf tag" }
                val number = (tag ushr 3).toInt()
                require(number > 0) { "zero protobuf field" }
                val wire = (tag and 7).toInt()
                return when (wire) {
                    0 -> Field(number, wire, varint())
                    1 -> { skip(8); Field(number, wire) }
                    2 -> {
                        val size = varint()
                        require(size in 0..(data.size - pos).toLong()) { "truncated protobuf bytes" }
                        val bytes = data.copyOfRange(pos, pos + size.toInt())
                        pos += size.toInt()
                        Field(number, wire, bytes = bytes)
                    }
                    5 -> { skip(4); Field(number, wire) }
                    else -> error("unsupported protobuf wire type")
                }
            }
            private fun skip(size: Int) { require(size <= data.size - pos) { "truncated fixed field" }; pos += size }
            private fun varint(): Long {
                var result = 0L
                for (shift in 0..63 step 7) {
                    require(pos < data.size) { "truncated protobuf varint" }
                    val b = data[pos++].toInt() and 255
                    if (shift == 63) require(b <= 1) { "overflow protobuf varint" }
                    result = result or ((b and 127).toLong() shl shift)
                    if (b and 128 == 0) return result
                }
                error("unterminated protobuf varint")
            }
        }

        private fun Field.integer(): Long { require(wire == 0) { "wrong protobuf scalar type" }; return value }
        private fun Field.message(): ByteArray { require(wire == 2) { "wrong protobuf message type" }; return bytes!! }

        private fun parseMappings(data: ByteArray): Map<Int, List<String>> {
            val proto = Proto(data)
            val out = linkedMapOf<Int, List<String>>()
            var totalStrings = 0
            var counter: Long? = null
            while (true) {
                val field = proto.next() ?: break
                when (field.number) {
                    1 -> { require(counter == null); counter = field.integer(); require(counter in 1..Int.MAX_VALUE.toLong()) }
                    2 -> {
                        require(out.size < MAX_MAPPINGS) { "mapping package limit" }
                        val item = Proto(field.message())
                        var token: Int? = null
                        val strings = mutableListOf<String>()
                        while (true) {
                            val f = item.next() ?: break
                            when (f.number) {
                                1 -> { require(token == null); val value = f.integer(); require(value in 1..Int.MAX_VALUE.toLong()); token = value.toInt() - 1 }
                                2 -> {
                                    val bytes = f.message()
                                    require(bytes.size <= MAX_STRING_BYTES && ++totalStrings <= MAX_MAPPING_STRINGS) { "mapping string limit" }
                                    strings.add(utf8(bytes))
                                }
                            }
                        }
                        val key = token ?: error("mapping missing package token")
                        require(key !in out && strings.isNotEmpty() && strings[0].isNotBlank() && strings[0].length <= 1024) { "invalid/duplicate package mapping" }
                        require(strings[0].none { it.isWhitespace() || it.code < 32 }) { "invalid package name" }
                        out[key] = strings
                    }
                }
            }
            require(counter != null && out.isNotEmpty()) { "incomplete mappings" }
            return out
        }

        private data class Bucket(val recordedEndTs: Long, val major: Int, val minor: Int, val events: List<RawEvent>)
        private data class EventItem(val offset: Long, val type: Int, val packageToken: Int)

        private fun parseBucket(data: ByteArray, begin: Long, observedAt: Long, mappings: Map<Int, List<String>>, user: Int): Bucket {
            val proto = Proto(data)
            var endOffset: Long? = null
            var major: Int? = null
            var minor: Int? = null
            var totalUsageMs = 0L
            val items = mutableListOf<EventItem>()
            while (true) {
                val field = proto.next() ?: break
                when (field.number) {
                    1 -> { require(endOffset == null); endOffset = field.integer(); require(endOffset in 0..MAX_BUCKET_MS) { "invalid bucket duration" } }
                    2 -> { require(major == null); val value = field.integer(); require(value == 1L) { "unsupported interval major version" }; major = value.toInt() }
                    3 -> { require(minor == null); val value = field.integer(); require(value in 0..1) { "unsupported interval minor version" }; minor = value.toInt() }
                    10, 11, 12, 13 -> {
                        val counter = Proto(field.message())
                        while (true) {
                            val f = counter.next() ?: break
                            if (f.number == 1 || f.number == 2) require(f.integer() >= 0) { "negative aggregate counter" }
                        }
                    }
                    20 -> {
                        val pkg = Proto(field.message())
                        while (true) {
                            val f = pkg.next() ?: break
                            when (f.number) {
                                1, 3, 5, 6, 8, 9, 10, 11, 12 -> f.integer()
                                4 -> { val used = f.integer(); require(used in 0..MAX_BUCKET_MS); totalUsageMs += used }
                                7 -> f.message()
                            }
                        }
                    }
                    21 -> {
                        val config = Proto(field.message())
                        while (true) {
                            val f = config.next() ?: break
                            when (f.number) {
                                1 -> f.message()
                                2, 3, 4, 5 -> f.integer()
                            }
                        }
                    }
                    22 -> {
                        require(items.size < MAX_EVENTS_PER_BUCKET) { "bucket event limit" }
                        val event = Proto(field.message())
                        var token: Int? = null
                        var offset: Long? = null
                        var type: Int? = null
                        while (true) {
                            val f = event.next() ?: break
                            when (f.number) {
                                1 -> { require(token == null); val v = f.integer(); require(v in 1..Int.MAX_VALUE.toLong()); token = v.toInt() - 1 }
                                2, 4, 7, 8, 9, 10, 11, 12, 13 -> f.integer()
                                3 -> { require(offset == null); offset = f.integer(); require(offset in 0..MAX_BUCKET_MS) { "invalid event time offset" } }
                                5 -> { require(type == null); val v = f.integer(); require(v in 0..Int.MAX_VALUE.toLong()); type = v.toInt() }
                                6, 14 -> f.message()
                            }
                        }
                        items.add(EventItem(offset ?: error("event missing time"), type ?: error("event missing type"), token ?: error("event missing package")))
                    }
                }
            }
            val end = endOffset ?: error("bucket missing end time")
            require(major != null && minor != null) { "bucket missing format version" }
            require(begin <= Long.MAX_VALUE - end - 1 && begin + end <= observedAt) { "future/overflow bucket end" }
            require(items.isNotEmpty() || totalUsageMs == 0L) { "usage without raw events" }
            var previous = -1L
            val result = ArrayList<RawEvent>()
            for (event in items) {
                require(event.offset in previous..end) { "unordered/out-of-window event" }
                previous = event.offset
                val pkg = mappings[event.packageToken]?.firstOrNull() ?: error("unmapped package token ${event.packageToken}")
                if (event.type in KEPT_TYPES && !(user != 0 && event.type in SECONDARY_SYSTEM_TYPES)) {
                    result.add(RawEvent(begin + event.offset, event.type, pkg, userId = user))
                }
            }
            return Bucket(begin + end + 1, major!!, minor!!, result)
        }
    }
}
