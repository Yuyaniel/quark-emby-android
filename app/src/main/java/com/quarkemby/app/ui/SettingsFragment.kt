package com.quarkemby.app.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.quarkemby.app.MainActivity
import com.quarkemby.app.R
import com.quarkemby.app.data.Prefs
import com.quarkemby.app.data.TmdbApi
import kotlinx.coroutines.launch

/**
 * Settings screen: TMDB key + test, rename template, season template,
 * debug (preview-only) toggle, job log, and logout.
 */
class SettingsFragment : Fragment() {

    private lateinit var root: LinearLayout

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val scroll = ScrollView(requireContext())
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 30)
        }
        scroll.addView(root)
        return scroll
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        render()
    }

    private fun render() {
        root.removeAllViews()

        val title = TextView(requireContext()).apply {
            text = "设置"; textSize = 20f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(resources.getColor(R.color.ink, null))
        }
        root.addView(title)
        root.addView(hint("TMDB 元数据与整理规则配置。密钥仅加密保存在本机。"))

        // ---- TMDB Key ----
        root.addView(section("TMDB API Key"))
        val keyInput = EditText(requireContext()).apply {
            setSingleLine(true); hint = "请输入个人 TMDB v3 API Key"
            setText(Prefs.tmdbKey)
        }
        root.addView(keyInput); root.addView(spacer())

        root.addView(button("测试 TMDB Key") {
            val key = keyInput.text.toString().trim()
            if (key.isEmpty()) { toast("请先填入 Key"); return@button }
            lifecycleScope.launch {
                val ok = TmdbApi.testKey(key)
                toast(if (ok) "✓ Key 有效（v3）" else "✗ Key 无效或网络失败")
                if (ok) Prefs.tmdbKey = key
            }
        })

        // ---- Language ----
        root.addView(spacer()); root.addView(section("TMDB 语言"))
        val langInput = EditText(requireContext()).apply {
            setSingleLine(true); hint = "zh-CN / en-US"
            setText(Prefs.tmdbLanguage)
        }
        root.addView(langInput); root.addView(spacer())

        // ---- Templates ----
        root.addView(section("文件名模板"))
        root.addView(hint("可用占位符：{ss} 季号、{ee} 集号、{show_name} 剧名"))
        val renameInput = EditText(requireContext()).apply {
            setSingleLine(true)
            setText(Prefs.renameTemplate)
        }
        root.addView(renameInput); root.addView(spacer())

        root.addView(section("Season 文件夹模板"))
        val seasonInput = EditText(requireContext()).apply {
            setSingleLine(true)
            setText(Prefs.seasonTemplate)
        }
        root.addView(seasonInput); root.addView(spacer())

        val previewBox = CheckBox(requireContext()).apply {
            text = "调试模式：仅预览，不写入网盘"
            isChecked = Prefs.previewOnly
            setTextColor(resources.getColor(R.color.ink, null))
        }
        root.addView(previewBox); root.addView(spacer())

        // ---- Actions ----
        root.addView(button("保存设置") {
            Prefs.tmdbKey = keyInput.text.toString().trim()
            Prefs.tmdbLanguage = langInput.text.toString().trim().ifEmpty { "zh-CN" }
            Prefs.renameTemplate = renameInput.text.toString().trim().ifEmpty { "{show_name}.S{ss}E{ee}" }
            Prefs.seasonTemplate = seasonInput.text.toString().trim().ifEmpty { "Season {ss}" }
            Prefs.previewOnly = previewBox.isChecked
            toast("设置已保存")
        })

        root.addView(button("查看任务日志") {
            MainActivity.INSTANCE.showLog()
        })

        root.addView(button("退出登录") {
            Prefs.clearCredentials()
            MainActivity.INSTANCE.showLogin()
        })
    }

    // ---- helpers ----
    private fun section(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 15f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(resources.getColor(R.color.brand_primary, null))
        setPadding(0, 18, 0, 6)
    }

    private fun hint(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 12f
        setTextColor(resources.getColor(R.color.muted, null))
        setPadding(0, 2, 0, 4)
    }

    private fun spacer() = View(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(1, 6) }

    private fun button(label: String, onClick: () -> Unit) =
        android.widget.Button(requireContext()).apply {
            text = label; isAllCaps = false
            setTextColor(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24f
                colors = intArrayOf(
                    resources.getColor(R.color.brand_primary, null),
                    resources.getColor(R.color.brand_secondary, null)
                )
                orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 10, 0, 10)
            layoutParams = lp
            setOnClickListener { onClick() }
        }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
}