package com.quarkemby.app

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
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
    private lateinit var bottomNav: LinearLayout
    // 0 = nothing highlighted yet, so the first highlightTab() call always applies
    private var selectedTabId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        INSTANCE = this
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.fragment_container)
        bottomNav = findViewById(R.id.bottom_nav)

        // compact custom pill tabs (View system)
        findViewById<LinearLayout>(R.id.nav_files).setOnClickListener { showFiles() }
        findViewById<LinearLayout>(R.id.nav_settings).setOnClickListener { showSettings() }

        if (savedInstanceState == null) {
            if (Prefs.isLoggedIn) showFiles() else showLogin()
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
        showBottomNav(true)
        addFragment(FilesFragment(), false, "files")
    }

    fun showFiles() {
        showBottomNav(true)
        highlightTab(R.id.nav_files)
        // repeated tab taps: skip if files is already the visible top page
        val top = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (top is com.quarkemby.app.ui.FilesFragment && top.isVisible) return
        addFragment(FilesFragment(), false, "files")
    }

    fun showSettings(): Boolean {
        showBottomNav(true)
        highlightTab(R.id.nav_settings)
        // avoid stacking duplicate settings pages on rapid taps
        val top = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (top is SettingsFragment && top.isVisible) return true
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

    /** Photo-style tab state: active tab gets a darker pill container with
     *  white content; idle tabs are transparent with grey content. Skips
     *  re-applying drawables when the selection is unchanged — swapping the
     *  background resource restarts the ripple layer and visibly flashes. */
    private fun highlightTab(id: Int) {
        if (id == selectedTabId) return
        selectedTabId = id
        val white = ContextCompat.getColor(this, android.R.color.white)
        val grey = ContextCompat.getColor(this, R.color.muted)
        listOf(
            R.id.nav_files to listOf(R.id.nav_files_icon, R.id.nav_files_label),
            R.id.nav_settings to listOf(R.id.nav_settings_icon, R.id.nav_settings_label)
        ).forEach { (tab, views) ->
            val on = tab == selectedTabId
            findViewById<LinearLayout>(tab)
                .setBackgroundResource(if (on) R.drawable.bg_nav_pill_active else R.drawable.bg_nav_pill_idle)
            findViewById<ImageView>(views[0]).setColorFilter(if (on) white else grey)
            findViewById<TextView>(views[1]).setTextColor(if (on) white else grey)
        }
    }

    private fun showBottomNav(show: Boolean) {
        bottomNav.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }
}
