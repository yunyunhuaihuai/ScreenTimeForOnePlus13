package com.local.screentime.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.local.screentime.data.BatteryCaptureOutcome
import com.local.screentime.data.UsageRepository
import java.util.concurrent.TimeUnit

/**
 * 耗电快照兜底采样（v0.16.8 引入）：名义 15 分钟一次，只做 `dumpsys batterystats
 * --charged` 快照差分（毫秒级、轻量），不做全量同步。
 *
 * 为什么需要：插电瞬间接收器（PowerEventReceiver）与系统重置存在竞态，抢跑失败时
 * 「上次采集～插电」段的估算耗电仍会丢。周期采样把典型丢失窗口从 6h 同步间隔压缩到
 * 名义 15 分钟；高频差分同时让日归属（dayWeights 按时间比例拆分）更精确。
 *
 * 注意（v0.17.0，P1-1）：PeriodicWorkRequest(15 minutes) **不保证**每 15 分钟准时执行——
 * 实际间隔受 Doze、系统调度与厂商后台管控影响，可能明显更长。实际损失窗口以
 * sync_state 持久化的采样健康数据（cap_max_gap_ms 等，见 diagnosticJson）为准，
 * 不要依赖「≤15 分钟」这类绝对表述。
 *
 * 结果语义：仅对真正可能恢复的临时错误（shell/差分事务异常）Result.retry()（最多
 * 3 次，退避后仍失败则放弃本轮，等下一个周期）；权限不可用与解析为空不 retry——
 * 重试无意义或由下一周期自然覆盖。
 */
class BatteryCaptureWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = UsageRepository(applicationContext)
        val outcome = repo.captureBatteryNow()
        if (outcome !is BatteryCaptureOutcome.Success) {
            android.util.Log.i("STLog", "battery-capture: $outcome (attempt=$runAttemptCount)")
        }
        return when (outcome) {
            is BatteryCaptureOutcome.Success -> Result.success()
            // 系统状态而非错误（batterystats 未就绪/输出为空）：等下一周期
            is BatteryCaptureOutcome.NoData -> Result.success()
            // 权限需用户侧动作（root 授权/adb 授权 DUMP）：retry 无意义
            is BatteryCaptureOutcome.SourceUnavailable -> Result.success()
            // 临时错误：有限次退避重试，避免无限无意义重试
            is BatteryCaptureOutcome.Failed ->
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val MAX_RETRIES = 3

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
