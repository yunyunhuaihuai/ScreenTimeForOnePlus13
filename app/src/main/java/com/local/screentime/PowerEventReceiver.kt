package com.local.screentime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.local.screentime.data.UsageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 插电瞬间抢拍耗电累计值（v0.16.8）。
 *
 * 背景：`dumpsys batterystats --charged` 是「自上次充电」窗口，**充电开始时系统即重置
 * 所有累计计数**。此前只有手动刷新 / 6h 同步会采集，凡「上次同步之后～插电重置之前」
 * 的使用（恰好是睡前高使用时段）估算耗电都会被系统重置永久丢弃（实测 bilibili
 * 17:09–17:18 的 8.8 分钟因此丢失）。
 *
 * 本接收器在 POWER_CONNECTED 时与系统抢跑：尽力在重置生效前读出最终累计值并差分入库。
 * 即使抢跑失败（读到的是重置后的小值），差分逻辑也能正确识别重置、不产生脏数据，
 * 损失由 15 分钟兜底采样（BatteryCaptureWorker）兜住。
 */
class PowerEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = UsageRepository(context.applicationContext)
                val ok = repo.captureBatteryNow()
                Log.i(TAG, "power-capture: captured=$ok")
            } catch (e: Exception) {
                Log.w(TAG, "power-capture failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "STLog"
    }
}
