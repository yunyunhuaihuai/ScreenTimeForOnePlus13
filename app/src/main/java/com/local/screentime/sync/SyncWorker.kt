package com.local.screentime.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.local.screentime.data.UsageRepository
import java.util.concurrent.TimeUnit

/**
 * 系统使用明细只保留约一周，必须定期把数据搬进本地库。
 * 周期 6 小时，足够留出重试余量。
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        UsageRepository(applicationContext).syncNow()
        return Result.success()
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
