package com.local.screentime

import com.local.screentime.data.SyncResult
import com.local.screentime.sync.SyncWorkDecision
import com.local.screentime.sync.syncWorkDecision
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWorkerDecisionTest {
    private fun result(usageObserved: Boolean = true, batteryOk: Boolean = true, retryable: Boolean = false) =
        SyncResult.Ok(0, 1, 0, 0, batteryOk = batteryOk, usageObserved = usageObserved, retryableFailure = retryable)

    @Test
    fun missingPermissionAndUnobservedUsageAreNotSuccessful() {
        assertEquals(SyncWorkDecision.FAILURE, syncWorkDecision(SyncResult.NoPermission, 0))
        assertEquals(SyncWorkDecision.FAILURE, syncWorkDecision(result(usageObserved = false), 0))
    }

    @Test
    fun temporaryFailuresRetryEvenWhenUsageWasObservedAndStopAfterThreeRetries() {
        val partial = result(batteryOk = false, retryable = true)
        assertEquals(SyncWorkDecision.RETRY, syncWorkDecision(partial, 0))
        assertEquals(SyncWorkDecision.RETRY, syncWorkDecision(partial, 2))
        assertEquals(SyncWorkDecision.FAILURE, syncWorkDecision(partial, 3))
    }

    @Test
    fun permanentBatteryFailureDoesNotCauseFutileRetries() {
        assertEquals(SyncWorkDecision.SUCCESS, syncWorkDecision(result(batteryOk = false), 0))
        assertEquals(SyncWorkDecision.SUCCESS, syncWorkDecision(result(), 0))
    }
}
