package com.quarkemby.app.ui

import android.graphics.Typeface
import android.os.Bundle
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
import com.quarkemby.app.MainActivity
import com.quarkemby.app.R
import com.quarkemby.app.data.Prefs

/**
 * Settings screen: season-folder template, debug (preview-only) toggle,
 * home folder, job log, and logout.
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
        root.addView(hint("整理规则与应用行为配置，全部保存在本机。"))

        // ---- Templates ----
        root.addView(section("Season 文件夹模板"))
        root.addView(hint("可用占位符：{ss} 季号（两位补零）"))
        val seasonInput = field().apply { setText(Prefs.seasonTemplate) }
        root.addView(seasonInput); root.addView(spacer())

        root.addView(section("文件命名规则"))
        root.addView(hint("批量重命名时输入季号：剧名.S01E01.mp4"))
        root.addView(hint("季号留空：剧名.01.mp4"))

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
