package com.local.screentime.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

data class RawEvent(val ts: Long, val eventType: Int, val packageName: String)

class UsageStatsReader(private val context: Context) {

    private val usm = context.getSystemService(UsageStatsManager::class.java)

    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** [start, end) 窗口内的原始事件，时间升序 */
    fun queryEvents(start: Long, end: Long): List<RawEvent> {
        if (end <= start) return emptyList()
        val out = ArrayList<RawEvent>()
        val events = try {
            usm.queryEvents(start, end)
        } catch (e: SecurityException) {
            return emptyList()
        }
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            out.add(RawEvent(e.timeStamp, e.eventType, pkg))
        }
        return out
    }
}
