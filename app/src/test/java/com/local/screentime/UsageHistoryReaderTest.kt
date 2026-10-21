package com.local.screentime

import com.local.screentime.data.HistoryReadResult
import com.local.screentime.data.UsageHistoryReader
import com.local.screentime.privileged.CommandResult
import com.local.screentime.privileged.PrivilegedCommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64

/** Synthetic protobufs only: no device package names, timestamps, mappings or activity data. */
class UsageHistoryReaderTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val dayMs = 86_400_000L
    private fun ts(day: Int, hour: Int = 0) = LocalDateTime.of(2026, 1, day, hour, 0)
        .atZone(zone).toInstant().toEpochMilli()
    private val begin = ts(1, 17)
    private val observed = ts(4, 12)

    private class FakeRunner(var lines: List<String>, var exitCode: Int = 0, var root: Boolean = true) : PrivilegedCommandRunner {
        var command: String? = null
        val commands = mutableListOf<String>()
        var catalogue = listOf("STAC1", "END")
        var payload = emptyList<String>()
        override suspend fun isRootAvailable() = root
        override suspend fun exec(cmd: String): CommandResult {
            command = cmd
            commands.add(cmd)
            val output = when {
                cmd.contains("ST_USAGE_ARCHIVE_CATALOG_V1") -> catalogue
                cmd.contains("ST_USAGE_ARCHIVE_PAYLOAD_V1") -> payload
                else -> lines
            }
            return CommandResult(output, if (exitCode == 0) emptyList() else listOf("denied"), exitCode)
        }
    }

    private fun varint(value: Long): ByteArray {
        var v = value
        val out = ByteArrayOutputStream()
        do {
            val b = (v and 127).toInt()
            v = v ushr 7
            out.write(if (v == 0L) b else b or 128)
        } while (v != 0L)
        return out.toByteArray()
    }
    private fun scalar(field: Int, value: Long) = varint((field.toLong() shl 3)) + varint(value)
    private fun blob(field: Int, bytes: ByteArray) = varint((field.toLong() shl 3) or 2) + varint(bytes.size.toLong()) + bytes
    private fun text(field: Int, value: String) = blob(field, value.toByteArray())
    private fun mappings() = scalar(1, 4) + blob(2, scalar(1, 2) + text(2, "com.example.app") + text(2, "com.example.Activity")) +
        blob(2, scalar(1, 3) + text(2, "android"))
    private fun event(offset: Long, type: Int, storedToken: Int = 2) = blob(22,
        scalar(1, storedToken.toLong()) + scalar(2, 2) + scalar(3, offset) + scalar(5, type.toLong()))
    private fun bucket(events: ByteArray = byteArrayOf(), endOffset: Long = dayMs - 1, extra: ByteArray = byteArrayOf()) =
        scalar(1, endOffset) + scalar(2, 1) + scalar(3, 1) + extra + events

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun frame(user: Int, kind: String, name: String, bytes: ByteArray): List<String> =
        listOf("FILE $user $kind $name ${bytes.size} ${hash(bytes)}") + Base64.getEncoder().encodeToString(bytes).chunked(76) +
            listOf("END_FILE ${bytes.size} ${hash(bytes)}")
    private fun userFrames(user: Int, buckets: Map<Long, ByteArray>, version: String = "5\n35;TEST\n", map: ByteArray = mappings()): List<String> {
        val sorted = buckets.toSortedMap()
        val names = sorted.keys.joinToString(" ")
        return listOf("USER $user PRESENT $names".trimEnd()) + frame(user, "VERSION", "version", version.toByteArray()) +
            frame(user, "MAPPINGS", "mappings", map) + sorted.flatMap { (begin, bytes) -> frame(user, "DAILY", "$begin", bytes) } +
            listOf("USER_END $user HASH ${hash(map)} ${hash(version.toByteArray())} $names".trimEnd())
    }
    private fun protocol(primary: List<String>, secondary: List<String> = listOf("USER 999 ABSENT")) =
        listOf("STH1") + primary + secondary + "END"
    private fun decode(buckets: Map<Long, ByteArray>, version: String = "5\nTEST", map: ByteArray = mappings()): HistoryReadResult =
        UsageHistoryReader.decodeSnapshot(protocol(userFrames(0, buckets, version, map)), observed)

    @Test
    fun persistedMillisecondsAndSecondaryFiltering_matchDumpPolicy() = runBlocking {
        val primary = userFrames(0, linkedMapOf(
            begin to bucket(event(1234, 1) + event(4321, 2) + event(5000, 18, 3)),
            begin + dayMs + 2000 to bucket(event(1000, 1) + event(9000, 2)),
            begin + 2 * dayMs + 3000 to bucket(event(1000, 1), endOffset = 60_000),
        ))
        val secondary = userFrames(999, linkedMapOf(
            begin to bucket(event(1500, 15, 3) + event(1600, 18, 3) + event(2000, 1) + event(3000, 2) + event(3500, 16, 3) + event(6000, 17, 3)),
            begin + dayMs to bucket(),
        ))
        val runner = FakeRunner(protocol(primary, secondary))
        val result = UsageHistoryReader(runner).read(zone, observed)
        assertTrue(result.failures.toString(), result.failures.isEmpty())
        assertEquals(setOf(0, 999), result.users)
        assertTrue(result.absentUsers.isEmpty())
        assertEquals(listOf(1234L, 2000L, 3000L, 4321L, 5000L, 6000L), result.events.take(6).map { it.ts - begin })
        assertEquals(listOf(1, 1, 2, 2, 18, 17), result.events.take(6).map { it.eventType })
        assertEquals(listOf(0, 999, 999, 0, 0, 999), result.events.take(6).map { it.userId })
        val first = result.coverage.first { it.userId == 0 }
        assertTrue(first.complete)
        assertEquals(begin + dayMs + 2000, first.endTs)
        assertEquals(begin + dayMs, first.recordedEndTs)
        assertEquals(5, first.databaseVersion)
        assertEquals(1, first.majorVersion)
        assertEquals(1, first.minorVersion)
        val active = result.coverage.last { it.userId == 0 }
        assertFalse(active.complete)
        assertEquals(active.recordedEndTs, active.endTs)
        assertTrue(runner.commands.first().contains("/data/system_ce/"))
        assertTrue(runner.commands.first().contains("sha256sum"))
        assertTrue(runner.commands.all { it.startsWith("(\n") && it.endsWith("\n)") })
    }

    @Test
    fun absentSecondaryUser_isNormal_butUnavailableSecondaryIsFailure() {
        val normal = decode(mapOf(begin to bucket(), begin + dayMs to bucket()))
        assertTrue(normal.failures.toString(), normal.failures.isEmpty())
        assertEquals(setOf(999), normal.absentUsers)
        val unavailable = UsageHistoryReader.decodeSnapshot(protocol(userFrames(0, mapOf(begin to bucket())),
            listOf("ERROR 999 - CE_UNAVAILABLE")), observed)
        assertTrue(unavailable.absentUsers.isEmpty())
        assertTrue(999 in unavailable.users)
        assertTrue(unavailable.failures.any { it.userId == 999 && it.reason == "CE_UNAVAILABLE" })
    }

    @Test
    fun unsupportedDatabaseVersion_andInvalidMappings_neverAuthorizeCoverage() {
        val buckets = mapOf(begin to bucket(event(1234, 1)), begin + dayMs to bucket())
        for (result in listOf(decode(buckets, version = "6\nTEST"), decode(buckets, map = byteArrayOf(0x12, 0x7f)))) {
            assertTrue(result.events.isEmpty())
            assertTrue(result.coverage.isEmpty())
            assertTrue(result.failures.isNotEmpty())
        }
    }

    @Test
    fun corruptBucket_doesNotMasqueradeAsEmptyDay_orRemoveOtherGoodBuckets() {
        val result = decode(mapOf(begin to (bucket(event(1234, 1)) + byteArrayOf(0x80.toByte())),
            begin + dayMs to bucket(event(1234, 1))))
        assertTrue(result.failures.any { it.file == "$begin" })
        assertTrue(result.coverage.none { it.file == "$begin" })
        assertEquals(1, result.events.size)
        assertEquals(begin + dayMs + 1234, result.events.single().ts)
    }

    @Test
    fun unknownPackage_wrongWireType_missingHeader_andFutureEvents_areRejected() {
        val invalid = listOf(
            bucket(event(1234, 1, 999)),
            bucket(blob(22, scalar(1, 2) + text(3, "not a scalar") + scalar(5, 1))),
            scalar(1, dayMs - 1) + event(1234, 1),
            bucket(event(dayMs + 1, 1)),
            bucket(event(2000, 1) + event(1000, 2)),
            bucket(extra = scalar(2, 0x1_0000_0001)),
        )
        for (bytes in invalid) {
            val result = decode(mapOf(begin to bytes))
            assertTrue(result.events.isEmpty())
            assertTrue(result.coverage.isEmpty())
            assertTrue(result.failures.isNotEmpty())
        }
    }

    @Test
    fun checksumMismatch_andReadRace_rejectAffectedBucket() {
        val names = mapOf(begin to bucket(event(1234, 1)), begin + dayMs to bucket())
        val lines = protocol(userFrames(0, names)).toMutableList()
        val header = lines.indexOfFirst { it.startsWith("FILE 0 DAILY $begin ") }
        lines[header + 1] = Base64.getEncoder().encodeToString(bucket(event(1235, 1)))
        val checksum = UsageHistoryReader.decodeSnapshot(lines, observed)
        assertTrue(checksum.events.isEmpty())
        assertTrue(checksum.coverage.none { it.file == "$begin" })
        assertTrue(checksum.failures.any { it.file == "$begin" })
        val race = protocol(userFrames(0, names)).toMutableList()
        val end = race.indexOfFirst { it.startsWith("USER_END 0 ") }
        race[end] = race[end].split(' ').take(5).joinToString(" ") + " $begin"
        val changed = UsageHistoryReader.decodeSnapshot(race, observed)
        assertTrue(changed.events.isEmpty())
        assertTrue(changed.coverage.isEmpty())
        assertTrue(changed.failures.any { it.reason.contains("manifest changed") })
        val mappingRace = protocol(userFrames(0, names)).toMutableList()
        val closing = mappingRace.indexOfFirst { it.startsWith("USER_END 0 ") }
        val changedWords = mappingRace[closing].split(' ').toMutableList()
        changedWords[3] = "0".repeat(64)
        mappingRace[closing] = changedWords.joinToString(" ")
        val changedMapping = UsageHistoryReader.decodeSnapshot(mappingRace, observed)
        assertTrue(changedMapping.coverage.isEmpty())
        assertTrue(changedMapping.failures.any { it.reason.contains("mappings/version changed") })
    }

    @Test
    fun closedGapOver26Hours_andPartialClosedBucket_areNotComplete() {
        val gap = decode(mapOf(begin to bucket(event(1234, 1)), begin + dayMs * 2 to bucket()))
        assertFalse(gap.coverage.first().complete)
        assertTrue(gap.failures.any { it.reason.contains("non-contiguous") })
        val partial = decode(mapOf(begin to bucket(endOffset = 60_000), begin + dayMs to bucket()))
        assertFalse(partial.coverage.first().complete)
    }

    @Test
    fun actualEmptyBucketMayCoverHistory_butPositiveUsageWithoutEventsCannot() {
        val empty = decode(mapOf(begin to bucket(), begin + dayMs to bucket()))
        assertTrue(empty.failures.toString(), empty.failures.isEmpty())
        assertTrue(empty.coverage.first().complete)
        val missing = decode(mapOf(begin to bucket(extra = blob(20, scalar(1, 2) + scalar(4, 5000))), begin + dayMs to bucket()))
        assertTrue(missing.coverage.none { it.file == "$begin" })
        assertTrue(missing.failures.any { it.reason.contains("without raw events") })
    }

    @Test
    fun interruptedProtocol_duplicateFiles_andOversizedClaims_areRejected() {
        val good = protocol(userFrames(0, mapOf(begin to bucket())))
        assertTrue(UsageHistoryReader.decodeSnapshot(good.dropLast(1), observed).coverage.isEmpty())
        val oversize = good.toMutableList()
        val i = oversize.indexOfFirst { it.startsWith("FILE 0 DAILY") }
        val parts = oversize[i].split(' ').toMutableList()
        parts[4] = (UsageHistoryReader.MAX_DAILY_BYTES + 1).toString()
        oversize[i] = parts.joinToString(" ")
        assertTrue(UsageHistoryReader.decodeSnapshot(oversize, observed).failures.isNotEmpty())
        val duplicate = good.toMutableList()
        duplicate.addAll(i, frame(0, "DAILY", "$begin", bucket()))
        assertTrue(UsageHistoryReader.decodeSnapshot(duplicate, observed).coverage.isEmpty())
    }

    @Test
    fun noRoot_orFailedCommand_isFailure_notAnEmptySuccessfulHistory() = runBlocking {
        val denied = FakeRunner(emptyList(), root = false)
        val a = UsageHistoryReader(denied).read(zone, observed)
        assertNull(denied.command)
        assertTrue(a.failures.isNotEmpty())
        val b = UsageHistoryReader(FakeRunner(emptyList(), exitCode = 1)).read(zone, observed)
        assertTrue(b.events.isEmpty())
        assertTrue(b.coverage.isEmpty())
        assertTrue(b.failures.any { it.reason.contains("exit=1") })
    }

    @Test
    fun checkedInSuffix_preservesEpoch_andConflictingDoubleFileIsFailure() {
        val normal = protocol(userFrames(0, mapOf(begin to bucket(event(1234, 1)), begin + dayMs to bucket())))
        val checked = normal.map { it.replace("$begin", "$begin-c") }
        val result = UsageHistoryReader.decodeSnapshot(checked, observed)
        assertTrue(result.failures.toString(), result.failures.isEmpty())
        assertEquals(begin, result.coverage.first().beginTs)
        assertEquals("$begin-c", result.coverage.first().file)
        val duplicate = normal.toMutableList()
        val header = duplicate.indexOfFirst { it.startsWith("USER 0 PRESENT") }
        duplicate[header] = "USER 0 PRESENT $begin $begin-c ${begin + dayMs}"
        val ending = duplicate.indexOfFirst { it.startsWith("USER_END 0") }
        duplicate[ending] = duplicate[ending].split(' ').take(5).joinToString(" ") + " $begin $begin-c ${begin + dayMs}"
        duplicate.addAll(ending, frame(0, "DAILY", "$begin-c", bucket(event(1234, 1))))
        val conflict = UsageHistoryReader.decodeSnapshot(duplicate, observed)
        assertTrue(conflict.coverage.none { it.beginTs == begin })
        assertTrue(conflict.failures.any { it.reason.contains("checked-in/unchecked") })
    }

    private data class GenerationFixture(val id: String, val files: Map<Pair<Int, String>, ByteArray>, val manifest: ByteArray)

    private fun generation(id: String, buckets: Map<Long, ByteArray>, map: ByteArray = mappings(), version: String = "5\nTEST"): GenerationFixture {
        val files = linkedMapOf((0 to "version") to version.toByteArray(), (0 to "mappings") to map)
        buckets.toSortedMap().forEach { (time, bytes) -> files[0 to "daily/$time"] = bytes }
        val manifest = buildString {
            appendLine("STH_ARCHIVE_V1\t$id\t${observed - 1000}")
            appendLine("U\t0\tPRESENT\nU\t999\tABSENT")
            files.forEach { (key, bytes) -> appendLine("F\t${key.first}\t${key.second}\t${bytes.size}\t${hash(bytes)}") }
        }.toByteArray()
        return GenerationFixture(id, files, manifest)
    }

    private fun catalogue(vararg generations: GenerationFixture): List<String> = listOf("STAC1") + generations.flatMap { g ->
        listOf("MANIFEST ${g.id} ${g.manifest.size} ${hash(g.manifest)}") + Base64.getEncoder().encodeToString(g.manifest).chunked(76) +
            listOf("END_MANIFEST ${hash(g.manifest)}") + g.files.filterKeys { it.second.startsWith("daily/") }.map { (key, bytes) ->
                "HEADER ${g.id} ${key.first} ${key.second.removePrefix("daily/")} ${Base64.getEncoder().encodeToString(bytes.take(32).toByteArray())}"
            } + listOf("GEN_END ${g.id} ${hash(g.manifest)}")
    } + "END"

    private fun archivePayload(vararg generations: GenerationFixture): List<String> = listOf("STAP1") + generations.flatMap { g ->
        val names = g.files.keys.filter { it.first == 0 && it.second.startsWith("daily/") }.map { it.second.removePrefix("daily/") }.sorted()
        listOf("SOURCE ${g.id} ${hash(g.manifest)}", "STH1", "USER 0 PRESENT ${names.joinToString(" ")}") +
            g.files.flatMap { (key, bytes) -> frame(key.first, when(key.second) { "version" -> "VERSION"; "mappings" -> "MAPPINGS"; else -> "DAILY" }, key.second.removePrefix("daily/"), bytes) } +
            listOf("USER_END 0 HASH ${hash(g.files.getValue(0 to "mappings"))} ${hash(g.files.getValue(0 to "version"))} ${names.joinToString(" ")}",
                "USER 999 ABSENT", "END", "SOURCE_END ${g.id} ${hash(g.manifest)}")
    } + "END_ARCHIVE"

    @Test
    fun archivesExtendRetention_keepGenerationMapping_andRecomputeClosedBoundary() = runBlocking {
        val oldMap = scalar(1, 4) + blob(2, scalar(1, 2) + text(2, "com.example.old")) + blob(2, scalar(1, 3) + text(2, "android"))
        val g = generation("0000000000000001", mapOf(begin to bucket(event(1234, 1) + event(9000, 2))), oldMap)
        val runner = FakeRunner(protocol(userFrames(0, mapOf(begin + dayMs to bucket(event(1000, 1)), begin + dayMs * 2 to bucket(endOffset = 60_000)))))
        runner.catalogue = catalogue(g)
        runner.payload = archivePayload(g)
        val result = UsageHistoryReader(runner).read(zone, observed)
        assertTrue(result.failures.toString(), result.failures.isEmpty())
        assertEquals("com.example.old", result.events.first().packageName)
        assertEquals(begin + 1234, result.events.first().ts)
        assertTrue(result.coverage.first().complete)
        assertEquals(begin + dayMs, result.coverage.first().endTs)
        assertEquals(3, result.coverage.size)
        assertTrue(runner.commands.last().contains("history/0000000000000001"))
        assertEquals(3, runner.commands.size)
        assertTrue("Every script must isolate exit/locale/functions from libsu's persistent shell",
            runner.commands.all { it.startsWith("(\n") && it.endsWith("\n)") })
    }

    @Test
    fun sameHeaderSystemWins_andDuplicateArchiveGenerationsReadOnlyLatest() = runBlocking {
        val bytes = bucket(event(1000, 1) + event(9000, 2))
        val a = generation("0000000000000001", mapOf(begin to bytes))
        val b = generation("0000000000000002", mapOf(begin to bytes))
        val current = FakeRunner(protocol(userFrames(0, mapOf(begin to bytes))))
        current.catalogue = catalogue(a, b)
        val same = UsageHistoryReader(current).read(zone, observed)
        assertTrue(same.failures.toString(), same.failures.isEmpty())
        assertEquals(2, same.events.size)
        assertEquals(2, current.commands.size)
        val older = FakeRunner(protocol(userFrames(0, mapOf(begin + dayMs to bucket()))))
        older.catalogue = catalogue(a, b)
        older.payload = archivePayload(b)
        val restored = UsageHistoryReader(older).read(zone, observed)
        assertTrue(restored.failures.toString(), restored.failures.isEmpty())
        assertEquals(2, restored.events.size)
        assertFalse(older.commands.last().contains("history/0000000000000001"))
        assertTrue(older.commands.last().contains("history/0000000000000002"))
    }

    @Test
    fun archivedRollback_orConflictingSameEndCannotAuthorizeReplacement() = runBlocking {
        val good = generation("0000000000000001", mapOf(begin to bucket(event(1000, 1))))
        val rollback = generation("0000000000000002", mapOf(begin to bucket(event(1000, 1), endOffset = 20_000)))
        val conflict = generation("0000000000000002", mapOf(begin to bucket(event(2000, 1))))
        for (second in listOf(rollback, conflict)) {
            val runner = FakeRunner(protocol(userFrames(0, mapOf(begin + dayMs to bucket()))))
            runner.catalogue = catalogue(good, second)
            val result = UsageHistoryReader(runner).read(zone, observed)
            assertTrue(result.coverage.none { it.beginTs == begin })
            assertTrue(result.failures.any { it.reason.contains("rollback") })
            assertEquals(2, runner.commands.size)
        }
    }

    @Test
    fun badArchiveManifest_payloadHash_orDatabaseVersion_leaveSystemDataUntouched() = runBlocking {
        val current = protocol(userFrames(0, mapOf(begin + dayMs to bucket(event(1000, 1)))))
        val good = generation("0000000000000001", mapOf(begin to bucket(event(1000, 1))))
        val badVersion = generation("0000000000000001", mapOf(begin to bucket(event(1000, 1))), version = "6\nTEST")
        val badCatalogue = catalogue(good).toMutableList().apply { this[indexOfFirst { it.startsWith("GEN_END ") }] = "GEN_END ${good.id} ${"0".repeat(64)}" }
        val badPayload = archivePayload(good).toMutableList().apply { val idx = indexOfFirst { it.startsWith("FILE 0 DAILY") }; this[idx+1] = Base64.getEncoder().encodeToString(bucket(event(1001, 1))) }
        for ((cat,payload) in listOf(badCatalogue to archivePayload(good), catalogue(good) to badPayload, catalogue(badVersion) to archivePayload(badVersion))) {
            val runner = FakeRunner(current).apply { catalogue = cat; this.payload = payload }
            val result = UsageHistoryReader(runner).read(zone, observed)
            assertEquals(1, result.events.size)
            assertEquals(begin + dayMs + 1000, result.events.single().ts)
            assertTrue(result.coverage.none { it.beginTs == begin })
            assertTrue(result.failures.any { it.reason.startsWith("archive read:") })
        }
    }

    @Test
    fun scriptsAreSubshells_evenWhenArchiveIsAbsentOrCommandFails() = runBlocking {
        val runner = FakeRunner(protocol(userFrames(0, mapOf(begin to bucket()))))
        val result = UsageHistoryReader(runner).read(zone, observed)
        assertTrue(result.failures.toString(), result.failures.isEmpty())
        assertEquals(2, runner.commands.size)
        for (command in runner.commands) {
            assertTrue(command.startsWith("(\n"))
            assertTrue(command.endsWith("\n)"))
            assertTrue(command.contains("exit "))
        }
        val failed = FakeRunner(emptyList(), exitCode = 1)
        UsageHistoryReader(failed).read(zone, observed)
        assertTrue(failed.commands.all { it.startsWith("(\n") && it.endsWith("\n)") })
    }
}
