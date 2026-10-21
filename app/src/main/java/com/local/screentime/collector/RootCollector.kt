package com.local.screentime.collector

import android.content.Context
import com.local.screentime.privileged.PrivilegedCommandRunner
import com.local.screentime.privileged.RootCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.ZoneId
import java.util.Base64
import java.util.zip.GZIPInputStream

/** A complete immutable ROOT observation. observedTs is the command's actual end time. */
data class CollectorFrame(
    val formatVersion: Int,
    val frameId: String,
    val sequence: Long,
    val commandStartTs: Long,
    val commandEndTs: Long,
    val observedTs: Long,
    val zoneId: String,
    val bootId: String,
    val rawSha256: String,
    val rawByteCount: Long,
    val rawLines: List<String>,
)

data class CollectorStatus(
    val installed: Boolean = false,
    val enabled: Boolean = false,
    val bootInstalled: Boolean = false,
    val supervisorRunning: Boolean = false,
    val workerRunning: Boolean = false,
    val queuedFrames: Int = 0,
    val queuedBytes: Long = 0,
    val lastAttemptTs: Long = 0,
    val lastSuccessTs: Long = 0,
    val lastFailureTs: Long = 0,
    val workerHeartbeatTs: Long = 0,
    val supervisorHeartbeatTs: Long = 0,
    val consecutiveFailures: Int = 0,
    val workerRestarts: Long = 0,
    val droppedFrames: Long = 0,
    val droppedBytes: Long = 0,
    val lastDropTs: Long = 0,
    val firstDroppedFrameId: String? = null,
    val lastDroppedFrameId: String? = null,
    val lastDropReason: String? = null,
    val lastAckFrameId: String? = null,
    val stopReason: String? = null,
    val archiveLastAttemptTs: Long = 0,
    val archiveLastSuccessTs: Long = 0,
    val archiveLastFailureTs: Long = 0,
    val archiveFailures: Int = 0,
    val archiveLastError: String? = null,
    val archiveGenerations: Int = 0,
    val archiveBytes: Long = 0,
    val archiveDroppedGenerations: Long = 0,
    val archiveLastDropTs: Long = 0,
    val archiveLastDropReason: String? = null,
    val archiveFirstDroppedGeneration: String? = null,
    val archiveLastDroppedGeneration: String? = null,
    val notificationFailure: String? = null,
    val notificationFailureTs: Long = 0,
    val notificationLastSentTs: Long = 0,
) {
    val active: Boolean get() = installed && enabled && supervisorRunning && workerRunning
}

data class CollectorActionResult(
    val success: Boolean,
    val status: CollectorStatus? = null,
    val frameId: String? = null,
    val error: String? = null,
)

/** A corrupt/missing frame stops this batch; never acknowledge past failureFrameId. */
data class CollectorReadResult(
    val frames: List<CollectorFrame> = emptyList(),
    val failureFrameId: String? = null,
    val error: String? = null,
) {
    val success: Boolean get() = error == null
}

/**
 * Installs a KernelSU service.d collector and consumes its root-only spool. No Room writes.
 * The caller must serialize imports with the battery ledger and acknowledge only AFTER its
 * transaction commits. While this collector is enabled, app-side ROOT capture must use
 * captureNow + import, never a second direct dumpsys writer. Stop explicitly before live fallback.
 */
class RootCollector(
    private val context: Context,
    private val runner: PrivilegedCommandRunner = RootCommandRunner(),
) {
    suspend fun ensureInstalled(): CollectorActionResult = withContext(Dispatchers.IO) {
        INSTALL_MUTEX.withLock {
        guarded {
            if (!runner.isRootAvailable()) return@guarded unavailable()
            val script = context.assets.open("collector/collector.sh").use { it.readBytes() }
            val boot = context.assets.open("collector/boot.sh").use { it.readBytes() }
            val history = context.assets.open("collector/history.sh").use { it.readBytes() }
            val digest = sha256(script + boot + history)
            val installed = runner.exec("cat '$BASE/install.sha256' 2>/dev/null")
                .stdout.joinToString("").trim()
            if (installed != digest) {
                val wasDisabled = runner.exec("[ -f '$BASE/disabled' ]").success
                val stop = runner.exec("if [ -f '$SCRIPT' ]; then $SHELL '$SCRIPT' stop; fi")
                if (!stop.success) return@guarded failed("Existing collector could not stop: ${stop.exitCode}")
                val script64 = Base64.getEncoder().encodeToString(script)
                val boot64 = Base64.getEncoder().encodeToString(boot)
                val history64 = Base64.getEncoder().encodeToString(history)
                val result = runner.exec("""
                    umask 077
                    mkdir -p '$BASE' /data/adb/service.d || exit 1
                    chmod 700 '$BASE' || exit 1
                    printf '%s' '$script64' | base64 -d > '$SCRIPT.new' || exit 1
                    printf '%s' '$boot64' | base64 -d > '$BOOT.new' || exit 1
                    printf '%s' '$history64' | base64 -d > '$BASE/history.sh.new' || exit 1
                    chmod 700 '$SCRIPT.new' '$BOOT.new' '$BASE/history.sh.new' || exit 1
                    mv -f '$SCRIPT.new' '$SCRIPT' || exit 1
                    mv -f '$BOOT.new' '$BOOT' || exit 1
                    mv -f '$BASE/history.sh.new' '$BASE/history.sh' || exit 1
                    printf '%s\n' '$digest' > '$BASE/install.sha256' || exit 1
                """.trimIndent())
                if (!result.success) return@guarded failed("Collector installation failed: ${result.exitCode}")
                // Updating a running install resumes it; an explicit previous stop remains disabled.
                if (!wasDisabled) runner.exec("rm -f '$BASE/disabled'")
            }
            val start = runner.exec("[ -f '$BASE/disabled' ] || $SHELL '$SCRIPT' start")
            val state = readStatus()
            CollectorActionResult(start.success && state.installed, state,
                error = if (start.success) null else "Collector start failed: ${start.exitCode}")
        }
        }
    }

    suspend fun start(): CollectorActionResult = action("enable")
    suspend fun restart(): CollectorActionResult = action("restart")
    suspend fun stop(removeBootEntry: Boolean = false): CollectorActionResult = withContext(Dispatchers.IO) {
        val result = action("stop")
        if (result.success && removeBootEntry) {
            val removed = runner.exec("rm -f '$BOOT'")
            return@withContext result.copy(success = removed.success, status = readStatus())
        }
        result
    }

    /** Uses the producer's capture lock; returns only after a complete ready frame is published. */
    suspend fun captureNow(): CollectorActionResult = action("capture")

    suspend fun status(): CollectorActionResult = withContext(Dispatchers.IO) {
        guarded {
            if (!runner.isRootAvailable()) return@guarded unavailable()
            CollectorActionResult(true, readStatus())
        }
    }

    suspend fun uninstall(removeData: Boolean = false): CollectorActionResult = withContext(Dispatchers.IO) {
        guarded {
            val stopped = stop()
            if (!stopped.success) return@guarded stopped
            val command = if (removeData) "rm -f '$BOOT' && rm -rf '$BASE'"
                else "rm -f '$BOOT' '$SCRIPT' '$BASE/history.sh' '$BASE/install.sha256'"
            val result = runner.exec(command)
            CollectorActionResult(result.success, CollectorStatus(),
                error = if (result.success) null else "Collector uninstall failed: ${result.exitCode}")
        }
    }

    /** IDs encode logical observation order, unaffected by a wall-clock correction backwards. */
    suspend fun readPendingFrames(limit: Int = 32): CollectorReadResult = withContext(Dispatchers.IO) {
        require(limit in 1..256)
        val out = mutableListOf<CollectorFrame>()
        var current: String? = null
        try {
            if (!runner.isRootAvailable()) return@withContext CollectorReadResult(error = "ROOT unavailable")
            val listing = runner.exec("$SHELL '$SCRIPT' list")
            if (!listing.success) return@withContext CollectorReadResult(error = "Collector list failed: ${listing.exitCode}")
            val ids = listing.stdout.filter(String::isNotBlank)
            if (ids.any { !FRAME_ID.matches(it) } || ids != ids.sorted() || ids.distinct().size != ids.size) {
                return@withContext CollectorReadResult(error = "Invalid collector frame listing")
            }
            for (id in ids.take(limit)) {
                current = id
                val read = runner.exec("$SHELL '$SCRIPT' read '$id'")
                check(read.success) { "Frame read failed: ${read.exitCode}" }
                out += decodeFrame(id, read.stdout)
            }
            CollectorReadResult(out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CollectorReadResult(out, current, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    suspend fun ackThrough(frameId: String): CollectorActionResult {
        require(FRAME_ID.matches(frameId))
        return action("ack '$frameId'", includeStatus = false)
    }

    private suspend fun action(command: String, includeStatus: Boolean = true): CollectorActionResult = withContext(Dispatchers.IO) {
        guarded {
            if (!runner.isRootAvailable()) return@guarded unavailable()
            val result = runner.exec("$SHELL '$SCRIPT' $command")
            CollectorActionResult(result.success, if (includeStatus) readStatus() else null,
                frameId = result.stdout.firstOrNull { FRAME_ID.matches(it) },
                error = if (result.success) null else "Collector $command failed: ${result.exitCode} ${result.stderrFirstLine.orEmpty()}")
        }
    }

    private suspend fun readStatus(): CollectorStatus {
        val result = runner.exec("if [ -f '$SCRIPT' ]; then $SHELL '$SCRIPT' status; else printf '%s\n' '{\"installed\":false}'; fi")
        check(result.success) { "Collector status failed: ${result.exitCode}" }
        return decodeStatus(result.stdout.joinToString("\n"))
    }

    private suspend fun guarded(block: suspend () -> CollectorActionResult): CollectorActionResult =
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failed("${e.javaClass.simpleName}: ${e.message}") }

    private fun unavailable() = failed("ROOT unavailable")
    private fun failed(error: String) = CollectorActionResult(false, error = error)

    companion object {
        const val BASE = "/data/adb/screentime-collector"
        const val SCRIPT = "$BASE/collector.sh"
        const val BOOT = "/data/adb/service.d/screentime-collector.sh"
        private const val SHELL = "/data/adb/ksu/bin/busybox ash"
        const val FORMAT_VERSION = 1
        private const val MAX_RAW_BYTES = 16 * 1024 * 1024
        private val INSTALL_MUTEX = Mutex()
        private val FRAME_ID = Regex("[0-9]{16}")

        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

        internal fun decodeFrame(expectedId: String, wireLines: List<String>): CollectorFrame {
            require(wireLines.size == 2) { "Incomplete frame envelope" }
            val decoder = Base64.getDecoder()
            val meta = JSONObject(String(decoder.decode(wireLines[0]), Charsets.UTF_8))
            require(meta.getInt("formatVersion") == FORMAT_VERSION) { "Unsupported frame format" }
            require(meta.getString("frameId") == expectedId && FRAME_ID.matches(expectedId)) { "Frame ID mismatch" }
            val sequence = meta.getLong("sequence")
            require(expectedId.toLong() == sequence) { "Frame sequence mismatch" }
            require(meta.getString("source") == "ROOT" && meta.getString("command") == "batterystats --charged")
            val size = meta.getLong("rawByteCount")
            require(size in 1..MAX_RAW_BYTES.toLong()) { "Invalid raw frame size" }
            val compressed = decoder.decode(wireLines[1])
            require(compressed.size.toLong() == meta.getLong("gzipByteCount")) { "Compressed size mismatch" }
            val raw = GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
                val output = ByteArrayOutputStream(minOf(size, 256 * 1024L).toInt())
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= size) { "Decompressed frame exceeds declared size" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(raw.size.toLong() == size) { "Raw size mismatch" }
            val digest = meta.getString("rawSha256")
            require(digest.matches(Regex("[0-9a-f]{64}")) && sha256(raw) == digest) { "Raw SHA-256 mismatch" }
            require(raw.count { it == 10.toByte() } == meta.getInt("rawLineCount")) { "Raw line count mismatch" }
            val zone = meta.getString("zoneId")
            ZoneId.of(zone)
            val start = meta.getLong("commandStartTs")
            val end = meta.getLong("commandEndTs")
            val observed = meta.getLong("observedTs")
            require(start > 0 && end > 0 && observed == end) { "Invalid observation timestamps" }
            // A system clock correction during the command can make end < start; preserve it.
            return CollectorFrame(FORMAT_VERSION, expectedId, sequence, start, end, observed, zone,
                meta.getString("bootId"), digest, size, String(raw, Charsets.UTF_8).lineSequence().toList())
        }

        internal fun decodeStatus(text: String): CollectorStatus {
            val o = JSONObject(text)
            fun nonempty(key: String) = o.optString(key).takeIf(String::isNotBlank)
            return CollectorStatus(
                installed = o.optBoolean("installed"), enabled = o.optBoolean("enabled"),
                bootInstalled = o.optBoolean("bootInstalled"), supervisorRunning = o.optBoolean("supervisorRunning"),
                workerRunning = o.optBoolean("workerRunning"), queuedFrames = o.optInt("queuedFrames"),
                queuedBytes = o.optLong("queuedBytes"), lastAttemptTs = o.optLong("lastAttemptTs"),
                lastSuccessTs = o.optLong("lastSuccessTs"), lastFailureTs = o.optLong("lastFailureTs"),
                workerHeartbeatTs = o.optLong("workerHeartbeatTs"),
                supervisorHeartbeatTs = o.optLong("supervisorHeartbeatTs"),
                consecutiveFailures = o.optInt("consecutiveFailures"), workerRestarts = o.optLong("workerRestarts"),
                droppedFrames = o.optLong("droppedFrames"), droppedBytes = o.optLong("droppedBytes"),
                lastDropTs = o.optLong("lastDropTs"), firstDroppedFrameId = nonempty("firstDroppedFrameId"),
                lastDroppedFrameId = nonempty("lastDroppedFrameId"), lastDropReason = nonempty("lastDropReason"),
                lastAckFrameId = nonempty("lastAckFrameId"), stopReason = nonempty("stopReason"),
                archiveLastAttemptTs = o.optLong("archiveLastAttemptTs"),
                archiveLastSuccessTs = o.optLong("archiveLastSuccessTs"),
                archiveLastFailureTs = o.optLong("archiveLastFailureTs"),
                archiveFailures = o.optInt("archiveFailures"), archiveLastError = nonempty("archiveLastError"),
                archiveGenerations = o.optInt("archiveGenerations"), archiveBytes = o.optLong("archiveBytes"),
                archiveDroppedGenerations = o.optLong("archiveDroppedGenerations"),
                archiveLastDropTs = o.optLong("archiveLastDropTs"), archiveLastDropReason = nonempty("archiveLastDropReason"),
                archiveFirstDroppedGeneration = nonempty("archiveFirstDroppedGeneration"),
                archiveLastDroppedGeneration = nonempty("archiveLastDroppedGeneration"),
                notificationFailure = nonempty("notificationFailure"), notificationFailureTs = o.optLong("notificationFailureTs"),
                notificationLastSentTs = o.optLong("notificationLastSentTs"),
            )
        }
    }
}
