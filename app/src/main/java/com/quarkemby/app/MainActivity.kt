package com.quarkemby.app

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.quarkemby.app.data.Prefs
import com.quarkemby.app.ui.LoginFragment
import com.quarkemby.app.ui.LogFragment
import com.quarkemby.app.ui.FilesFragment
import com.quarkemby.app.ui.SettingsFragment

class MainActivity : AppCompatActivity() {

    companion object {
        const val LOGIN_URL = "https://pan.quark.cn/"
        lateinit var INSTANCE: MainActivity
    }

    private lateinit var container: FrameLayout
    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        INSTANCE = this
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.fragment_container)
        bottomNav = findViewById(R.id.bottom_nav)

        if (savedInstanceState == null) {
            if (Prefs.isLoggedIn) showFiles() else showLogin()
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_files -> { showFiles(); true }
                R.id.nav_settings -> showSettings()
                else -> false
            }
        }
    }

    private fun addFragment(fragment: Fragment, toBackStack: Boolean, tag: String = fragment.javaClass.simpleName) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment, tag)
            .apply { if (toBackStack) addToBackStack(tag) }
            .commit()
    }

    fun showLogin() {
        showBottomNav(false)
        addFragment(LoginFragment(), false, "login")
    }

    fun onLoggedIn() {
        Prefs.lastSelectedShow = null
        showBottomNav(true)
        addFragment(FilesFragment(), false, "files")
    }

    fun showFiles() {
        showBottomNav(true)
        addFragment(FilesFragment(), false, "files")
    }

    fun showSettings(): Boolean {
        showBottomNav(true)
        addFragment(SettingsFragment(), true, "settings")
        return true
    }

    fun showLog() {
        addFragment(LogFragment(), true, "log")
    }

    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount == 0) {
            super.onBackPressed()
        } else {
            supportFragmentManager.popBackStack()
        }
    }

    private fun showBottomNav(show: Boolean) {
        bottomNav.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }
}