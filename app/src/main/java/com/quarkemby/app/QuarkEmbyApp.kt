package com.quarkemby.app

import android.app.Application
import com.quarkemby.app.data.Prefs

class QuarkEmbyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}