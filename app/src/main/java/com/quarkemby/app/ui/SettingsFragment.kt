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
        val keyInput = field().apply {
            hint = "请输入个人 TMDB v3 API Key"
            setText(Prefs.tmdbKey)
        }
        root.addView(keyInput); root.addView(spacer())

        root.addView(secondary("测试 TMDB Key") {
            val key = keyInput.text.toString().trim()
            if (key.isEmpty()) { toast("请先填入 Key"); return@secondary }
            lifecycleScope.launch {
                val ok = TmdbApi.testKey(key)
                toast(if (ok) "✓ Key 有效（v3）" else "✗ Key 无效或网络失败")
                if (ok) Prefs.tmdbKey = key
            }
        })

        // ---- Language ----
        root.addView(spacer()); root.addView(section("TMDB 语言"))
        val langInput = field().apply {
            hint = "zh-CN / en-US"
            setText(Prefs.tmdbLanguage)
        }
        root.addView(langInput); root.addView(spacer())

        // ---- Templates ----
        root.addView(section("文件名模板"))
        root.addView(hint("可用占位符：{ss} 季号、{ee} 集号、{show_name} 剧名"))
        val renameInput = field().apply { setText(Prefs.renameTemplate) }
        root.addView(renameInput); root.addView(spacer())

        root.addView(section("Season 文件夹模板"))
        val seasonInput = field().apply { setText(Prefs.seasonTemplate) }
        root.addView(seasonInput); root.addView(spacer())

        val previewBox = CheckBox(requireContext()).apply {
            text = "调试模式：仅预览，不写入网盘"
            isChecked = Prefs.previewOnly
            setTextColor(resources.getColor(R.color.ink, null))
        }
        root.addView(previewBox); root.addView(spacer())

        // ---- Home folder ----
        root.addView(section("首页目录"))
        root.addView(hint(
            if (Prefs.hasHomeFolder) "当前默认目录：${Prefs.homeFolderName}"
            else "暂无默认目录（启动进入根目录）——可在文件列表长按文件夹选择“设为首页目录”。"
        ))
        if (Prefs.hasHomeFolder) {
            root.addView(secondary("清除首页目录") {
                Prefs.homeFolderFid = ""; Prefs.homeFolderName = ""
                render()
                toast("已清除首页目录")
            })
            root.addView(spacer())
        }

        // ---- Actions ----
        root.addView(primary("保存设置") {
            Prefs.tmdbKey = keyInput.text.toString().trim()
            Prefs.tmdbLanguage = langInput.text.toString().trim().ifEmpty { "zh-CN" }
            Prefs.renameTemplate = renameInput.text.toString().trim().ifEmpty { "{show_name}.{ee}" }
            Prefs.seasonTemplate = seasonInput.text.toString().trim().ifEmpty { "Season {ss}" }
            Prefs.previewOnly = previewBox.isChecked
            toast("设置已保存")
        })

        root.addView(secondary("查看任务日志") {
            MainActivity.INSTANCE.showLog()
        })

        root.addView(danger("退出登录") {
            Prefs.clearCredentials()
            MainActivity.INSTANCE.showLogin()
        })
    }

    // ---- helpers ----
    private fun section(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 14f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(resources.getColor(R.color.brand_primary, null))
        setPadding(0, 18, 0, 6)
    }

    private fun hint(t: String) = Ui.helper(requireContext(), t).apply { setPadding(0, 2, 0, 4) }

    private fun spacer() = Ui.spacer(requireContext(), 6)

    private fun field(): EditText = EditText(requireContext()).apply {
        setSingleLine(true)
        textSize = 14f
        setTextColor(resources.getColor(R.color.ink, null))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Ui.dp(requireContext(), 12).toFloat()
            setColor(resources.getColor(R.color.surface_container, null))
            setStroke(
                Ui.dp(requireContext(), 1), resources.getColor(R.color.outline_variant, null)
            )
        }
        setPadding(Ui.dp(requireContext(), 12), Ui.dp(requireContext(), 12), Ui.dp(requireContext(), 12), Ui.dp(requireContext(), 12))
    }

    private fun primary(label: String, onClick: () -> Unit) =
        Ui.primaryBtn(requireContext(), label, onClick)

    private fun secondary(label: String, onClick: () -> Unit) =
        Ui.secondaryBtn(requireContext(), label, onClick)

    private fun danger(label: String, onClick: () -> Unit) =
        Ui.dangerBtn(requireContext(), label, onClick)

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
}