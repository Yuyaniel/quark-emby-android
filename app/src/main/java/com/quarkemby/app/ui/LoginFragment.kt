package com.quarkemby.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.quarkemby.app.MainActivity
import com.quarkemby.app.data.Prefs
import com.quarkemby.app.databinding.FragmentLoginBinding

/**
 * Login is Cookie-only. Paste the Quark drive Cookie and tap "enter".
 * The cookie is stored encrypted. No WebView is used here.
 */
class LoginFragment : Fragment() {

    private var _b: FragmentLoginBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentLoginBinding.inflate(inflater, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        // Pre-fill from a previous session so users can just re-enter.
        b.loginCookieInput.setText(Prefs.quarkCookies)
        updateState()

        b.loginCookieInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) = updateState()
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
        })

        b.loginDone.setOnClickListener {
            val raw = b.loginCookieInput.text.toString().trim()
            if (raw.isBlank()) {
                toast("请先粘贴 Cookie")
                return@setOnClickListener
            }
            Prefs.quarkCookies = raw
            MainActivity.INSTANCE.onLoggedIn()
        }
    }

    private fun updateState() {
        val raw = b.loginCookieInput.text.toString().trim()
        val has = raw.isNotBlank()
        b.loginDone.isEnabled = has
        b.loginDone.alpha = if (has) 1f else 0.45f
        b.loginStatus.text =
            if (has) "✓ 已填入 Cookie（${raw.split(';').size} 段），可直接进入" else "尚未登录 · 请粘贴 Cookie"
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}