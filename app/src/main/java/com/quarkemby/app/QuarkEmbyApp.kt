package com.quarkemby.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.quarkemby.app.data.Prefs
import com.quarkemby.app.util.CrashLog

class QuarkEmbyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material3 dark theme is the product look for this tool app.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        Prefs.init(this)

        // capture fatal crashes to a file so 任务日志 can show the exact stack
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { CrashLog.write(this, "fatal/${t.name}", e) }
            prev?.uncaughtException(t, e)
        }
    }
}