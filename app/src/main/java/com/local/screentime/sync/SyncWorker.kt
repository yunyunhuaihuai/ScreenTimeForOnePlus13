package com.local.screentime.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.local.screentime.data.SyncResult
import com.local.screentime.data.UsageRepository
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

internal enum class SyncWorkDecision { SUCCESS, RETRY, FAILURE }

/** 最多补偿重试 3 次；周期任务下次仍会正常对账。权限/未观测不伪装成完整成功。 */
internal fun syncWorkDecision(result: SyncResult, runAttemptCount: Int): SyncWorkDecision = when (result) {
    SyncResult.NoPermission -> SyncWorkDecision.FAILURE
    is SyncResult.Ok -> when {
        result.retryableFailure -> if (runAttemptCount < 3) SyncWorkDecision.RETRY else SyncWorkDecision.FAILURE
        !result.usageObserved -> SyncWorkDecision.FAILURE
        else -> SyncWorkDecision.SUCCESS
    }
}

/**
 * 后台对账与历史回补的备用入口。ROOT 独立采集器负责持续留存；
 * WorkManager 的名义 6 小时周期不作为准点运行或数据覆盖的保证。
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val syncResult = try {
            UsageRepository(applicationContext).syncNow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Repository 保留原始诊断；最终 WorkInfo 也留下原因，不能无限重试。
            return if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf(
                "status" to "exception",
                "error" to "${error.javaClass.simpleName}: ${error.message}".take(1_000),
            ))
        }
        val output = when (syncResult) {
            SyncResult.NoPermission -> workDataOf(
                "status" to "no_permission",
                "usage_observed" to false,
                "battery_observed" to false,
                "detail" to "缺少使用记录访问权限",
            )
            is SyncResult.Ok -> workDataOf(
                "status" to when {
                    syncResult.retryableFailure -> "retryable_failure"
                    !syncResult.usageObserved -> "usage_unobserved"
                    !syncResult.batteryOk -> "partial"
                    else -> "observed"
                },
                "usage_observed" to syncResult.usageObserved,
                "battery_observed" to syncResult.batteryOk,
                "detail" to syncResult.detail?.take(1_000),
            )
        }
        return when (syncWorkDecision(syncResult, runAttemptCount)) {
            SyncWorkDecision.SUCCESS -> Result.success(output)
            SyncWorkDecision.RETRY -> Result.retry()
            SyncWorkDecision.FAILURE -> Result.failure(output)
        }
    }

    companion object {
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "usage-sync",
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
