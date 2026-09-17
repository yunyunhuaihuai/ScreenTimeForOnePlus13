package com.local.screentime

import android.app.Application
import com.local.screentime.sync.SyncWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        SyncWorker.ensureScheduled(this)
    }
}
