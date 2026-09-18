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
 * 耗电快照兜底采样（v0.16.8）：每 15 分钟只做一次 `dumpsys batterystats --charged`
 * 快照差分（毫秒级、轻量），不做全量同步。
 *
 * 为什么需要：插电瞬间接收器（PowerEventReceiver）与系统重置存在竞态，抢跑失败时
 * 「上次采集～插电」段的估算耗电仍会丢。15 分钟兜底把最大损失窗口压到 ≤15 分钟
 * （对比此前 6h 同步间隔）。高频差分同时让日归属（dayWeights 按时间比例拆分）更精确。
 */
class BatteryCaptureWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        UsageRepository(applicationContext).captureBatteryNow()
        return Result.success()
    }

    companion object {
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<BatteryCaptureWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "battery-capture",
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
