package com.quarkemby.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.quarkemby.app.R
import com.quarkemby.app.data.Prefs
import com.quarkemby.app.data.models.JobLogEntry
import com.quarkemby.app.util.CrashLog

/** Shows the local job history written by each batch-rename run. */
class LogFragment : Fragment() {

    private lateinit var root: LinearLayout

    /** Copies text to the clipboard and toasts. */
    private fun copyLog(label: String, text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(requireContext(), "已复制$label", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val scroll = ScrollView(requireContext())
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            // extra bottom padding so content clears the floating nav pill
            setPadding(20, 20, 20, Ui.dp(requireContext(), 92))
        }
        scroll.addView(root)
        return scroll
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        root.removeAllViews()

        // title row: "任务日志" on the left, clear-all action on the right
        root.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(requireContext()).apply {
                text = "任务日志"; textSize = 20f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(resources.getColor(R.color.ink, null))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(requireContext()).apply {
                text = "清理"
                textSize = 14f
                isClickable = true
                setPadding(Ui.dp(requireContext(), 14), Ui.dp(requireContext(), 8),
                    Ui.dp(requireContext(), 14), Ui.dp(requireContext(), 8))
                setTextColor(resources.getColor(R.color.danger, null))
                foreground = androidx.core.content.ContextCompat.getDrawable(
                    requireContext(), R.drawable.ripple_fg
                )
                setOnClickListener {
                    Prefs.clearLogEntries()
                    onViewCreated(view, s)
                }
            })
        })

        // last captured crash (if any) sits on top for easy feedback
        val crash = CrashLog.read(requireContext())
        if (crash != null) {
            root.addView(spacer())
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 14, 16, 14)
                setBackgroundResource(R.drawable.bg_card_m3)
            }
            card.addView(TextView(requireContext()).apply {
                text = "上次崩溃记录"
                textSize = 15f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(resources.getColor(R.color.danger, null))
            })
            card.addView(TextView(requireContext()).apply {
                text = crash.take(2000)
                textSize = 11f
                setTextColor(resources.getColor(R.color.muted, null))
                setPadding(0, 6, 0, 0)
                // long-press copies the full crash trace
                setOnLongClickListener {
                    copyLog("崩溃日志", crash)
                    true
                }
            })
            card.addView(Ui.secondaryTextBtn(requireContext(), "复制崩溃日志") {
                copyLog("崩溃日志", crash)
            })
            card.addView(Ui.secondaryTextBtn(requireContext(), "清除崩溃记录") {
                CrashLog.clear(requireContext())
                onViewCreated(view, s)
            })
            root.addView(card)
        }

        val entries = Prefs.getLogEntries()
        if (entries.isEmpty()) {
            root.addView(TextView(requireContext()).apply {
                text = "暂无任务记录。完成一次批量整理后会在这里显示结果。"
                textSize = 13f
                setTextColor(resources.getColor(R.color.muted, null))
                setPadding(0, 14, 0, 0)
            })
            return
        }

        entries.forEach { e -> root.addView(card(e)) }
    }

    private fun card(e: JobLogEntry): View {
        val wrap = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
            setBackgroundResource(R.drawable.bg_card_m3)
        }
        wrap.addView(TextView(requireContext()).apply {
            text = e.time
            textSize = 11f; setTextColor(resources.getColor(R.color.muted, null))
        })
        wrap.addView(TextView(requireContext()).apply {
            text = "${e.title} · ${e.summary}"
            textSize = 15f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(resources.getColor(R.color.ink, null))
            setPadding(0, 4, 0, 0)
        })
        wrap.addView(TextView(requireContext()).apply {
            text = e.detail
            textSize = 12f; setTextColor(resources.getColor(R.color.muted, null))
            setPadding(0, 6, 0, 0)
            // long-press copies the whole job detail for feedback reports
            setOnLongClickListener {
                copyLog("任务详情", "${e.time} ${e.title} · ${e.summary}\n${e.detail}")
                true
            }
        })
        root.addView(spacer())
        return wrap
    }

    private fun spacer() = Ui.spacer(requireContext(), 8)
}