package com.local.screentime

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.local.screentime.collector.RootCollector
import com.local.screentime.privileged.CommandResult
import com.local.screentime.privileged.PrivilegedCommandRunner
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RootCollectorTest {
    private val id1 = "0001792555200000"
    private val id2 = "0001792555800000"
    private val id3 = "0001792556400000"
    private val raw = "Start clock time: 2026-10-21-08-00-00\nEstimated power use (mAh):\n  UID u0a330: 2.0\n"

    private fun envelope(id: String, mutate: (JSONObject) -> Unit = {}): List<String> {
        val bytes = raw.toByteArray()
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(bytes) }
        }.toByteArray()
        val meta = JSONObject().apply {
            put("formatVersion", 1); put("frameId", id); put("sequence", id.toLong())
            put("commandStartTs", id.toLong() - 2000); put("commandEndTs", id.toLong())
            put("observedTs", id.toLong()); put("zoneId", "Asia/Shanghai"); put("bootId", "test-boot")
            put("source", "ROOT"); put("command", "batterystats --charged")
            put("rawSha256", RootCollector.sha256(bytes)); put("rawByteCount", bytes.size)
            put("rawLineCount", bytes.count { it == 10.toByte() }); put("gzipByteCount", compressed.size)
            mutate(this)
        }
        return listOf(Base64.getEncoder().encodeToString(meta.toString().toByteArray()),
            Base64.getEncoder().encodeToString(compressed))
    }

    @Test fun completeGzipFramePreservesCommandObservationAndTimezone() {
        val frame = RootCollector.decodeFrame(id1, envelope(id1))
        assertEquals(id1, frame.frameId)
        assertEquals(id1.toLong(), frame.observedTs)
        assertEquals("Asia/Shanghai", frame.zoneId)
        assertEquals(raw, frame.rawLines.joinToString("\n"))
    }

    @Test fun alteredPayloadMetadataAndOversizedInflationAreRejected() {
        listOf<(JSONObject) -> Unit>(
            { it.put("rawSha256", "0".repeat(64)) },
            { it.put("rawByteCount", 10) },
            { it.put("gzipByteCount", 1) },
            { it.put("frameId", id2) },
            { it.put("formatVersion", 2) },
            { it.put("rawLineCount", 0) },
        ).forEach { mutate ->
            assertThrows(IllegalArgumentException::class.java) {
                RootCollector.decodeFrame(id1, envelope(id1, mutate))
            }
        }
    }

    @Test fun earlierCorruptFrameBlocksLaterFrameAndNeverAcknowledgesAutomatically() = runBlocking {
        val commands = mutableListOf<String>()
        val runner = object : PrivilegedCommandRunner {
            override suspend fun isRootAvailable() = true
            override suspend fun exec(cmd: String): CommandResult {
                commands += cmd
                val lines = when {
                    cmd.endsWith(" list") -> listOf(id1, id2, id3)
                    cmd.contains(" read '$id1'") -> envelope(id1)
                    cmd.contains(" read '$id2'") -> envelope(id2) { it.put("rawSha256", "0".repeat(64)) }
                    else -> error("Unexpected shell command: $cmd")
                }
                return CommandResult(lines, emptyList(), 0)
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = RootCollector(context, runner).readPendingFrames()
        assertFalse(result.success)
        assertEquals(listOf(id1), result.frames.map { it.frameId })
        assertEquals(id2, result.failureFrameId)
        assertTrue(commands.none { it.contains("ack ") || it.contains("read '$id3'") })
    }

    @Test fun statusKeepsQueueLossVisibleWhenLatestCaptureSucceeded() {
        val status = RootCollector.decodeStatus("""{
            "installed":true,"enabled":true,"supervisorRunning":true,"workerRunning":true,
            "lastSuccessTs":1792556400000,"droppedFrames":4,"droppedBytes":12345,
            "firstDroppedFrameId":"$id1","lastDroppedFrameId":"$id2","lastDropReason":"byte_limit",
            "workerHeartbeatTs":1792556399999
        }""")
        assertTrue(status.active)
        assertEquals(4, status.droppedFrames)
        assertEquals(id1, status.firstDroppedFrameId)
        assertEquals("byte_limit", status.lastDropReason)
        assertEquals(1792556399999, status.workerHeartbeatTs)
    }

    @Test fun cancellationPropagatesThroughReadAndGuardedActions() = runBlocking {
        val runner = object : PrivilegedCommandRunner {
            override suspend fun isRootAvailable(): Boolean = throw CancellationException("cancelled")
            override suspend fun exec(cmd: String): CommandResult = error("must not execute")
        }
        val collector = RootCollector(ApplicationProvider.getApplicationContext<Context>(), runner)
        assertThrows(CancellationException::class.java) { runBlocking { collector.readPendingFrames() } }
        assertThrows(CancellationException::class.java) { runBlocking { collector.status() } }
        assertThrows(CancellationException::class.java) { runBlocking { collector.ensureInstalled() } }
        Unit
    }
}
