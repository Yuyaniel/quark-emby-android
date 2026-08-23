package com.quarkemby.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.quarkemby.app.data.Prefs

class QuarkEmbyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material3 dark theme is the product look for this tool app.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        Prefs.init(this)
    }
}