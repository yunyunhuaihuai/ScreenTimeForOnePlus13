package com.local.screentime

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import com.local.screentime.sync.BatteryCaptureWorker
import com.local.screentime.sync.SyncWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        applyAppNightMode(this)
        Notifications.ensureChannel(this)
        SyncWorker.ensureScheduled(this)
        BatteryCaptureWorker.ensureScheduled(this)
    }

    companion object {
        const val PREFS = "settings"
        const val KEY_THEME_MODE = "theme_mode"

        fun themeModeOf(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_THEME_MODE, "system") ?: "system"

        /**
         * 把 app 内选的主题告诉系统。
         *
         * 为什么必须做：Android 12+ 的启动闪屏与窗口底色，是系统在进程启动之前就按
         * 资源限定符 `-night` 解析好的；而 `-night` 默认只跟随“**系统**”明暗，与 app 内
         * 的主题设定无关。于是「系统暗 + app 设白天」时会先闪一层 #1C1B1F 黑底
         * （v0.16.5 修复，v0.16.4 只修好了「系统亮 + app 设白天却闪黑」那一半）。
         *
         * `UiModeManager.setApplicationNightMode()` 能让系统按 app 自己的夜间模式解析
         * 资源，官方文档明确推荐用它来「让系统在启动画面期间匹配主题」。
         * 该 API 需要 API 31+；本项目 minSdk = 35，无需降级分支。
         */
        fun applyAppNightMode(ctx: Context) {
            val manager = ctx.getSystemService(UiModeManager::class.java) ?: return
            manager.setApplicationNightMode(
                when (themeModeOf(ctx)) {
                    "dark" -> UiModeManager.MODE_NIGHT_YES
                    "light" -> UiModeManager.MODE_NIGHT_NO
                    // “跟随系统”：交还给系统默认，由系统明暗决定
                    else -> UiModeManager.MODE_NIGHT_AUTO
                }
            )
        }
    }
}
