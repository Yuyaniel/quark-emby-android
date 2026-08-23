package com.quarkemby.app.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.quarkemby.app.MainActivity
import com.quarkemby.app.data.Prefs
import com.quarkemby.app.databinding.FragmentLoginBinding

/**
 * A WebView used ONLY as an authorization channel. After the user signs in we
 * capture the session cookies + device headers, store them encrypted, close
 * this view and move to the self-built file browser.
 */
class LoginFragment : Fragment() {

    private var _b: FragmentLoginBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentLoginBinding.inflate(inflater, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        val webView = b.quarkWebview

        b.loginProgress.visibility = View.VISIBLE
        b.loginManualToggle.setOnClickListener {
            b.loginCookieInput.visibility =
                if (b.loginCookieInput.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        b.loginCookieInput.setOnEditorActionListener { _, _, _ ->
            if (captureManualCookie()) { b.loginCookieInput.visibility = View.GONE }
            true
        }
        b.loginDone.isEnabled = false
        b.loginDone.setOnClickListener {
            if (Prefs.quarkCookies.isNotBlank()) {
                MainActivity.INSTANCE.onLoggedIn()
            }
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(v, url, favicon)
                b.loginProgress.visibility = View.VISIBLE
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                super.onPageFinished(v, url)
                b.loginProgress.visibility = View.GONE
                tryCapture()
            }
        }

        webView.loadUrl(MainActivity.LOGIN_URL)
    }

    private fun captureManualCookie(): Boolean {
        val raw = b.loginCookieInput.text.toString().trim()
        if (raw.isEmpty()) return false
        Prefs.quarkCookies = raw
        Prefs.quarkDeviceHeaders = ""
        b.loginStatus.text = "✓ 已保存手动粘贴的 Cookie，点击下方按钮进入应用"
        b.loginDone.isEnabled = true
        return true
    }

    private fun tryCapture() {
        val cookies = CookieManager.getInstance().getCookie(MainActivity.LOGIN_URL)
        if (cookies != null && cookies.contains("=")) {
            Prefs.quarkCookies = cookies
            // device fingerprint headers captured at web layer (best-effort);
            // keep them minimal here — full device fingerprint is gathered by the API layer.
            Prefs.quarkDeviceHeaders = ""
            b.loginStatus.text = "✓ 已捕获登录凭据，点击下方按钮进入应用"
            b.loginDone.isEnabled = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}