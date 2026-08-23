package com.quarkemby.app.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.quarkemby.app.R
import com.quarkemby.app.data.Prefs
import com.quarkemby.app.data.models.JobLogEntry
import com.quarkemby.app.util.CrashLog

/** Shows the local job history written by each batch-rename run. */
class LogFragment : Fragment() {

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
        root.removeAllViews()
        root.addView(TextView(requireContext()).apply {
            text = "任务日志"; textSize = 20f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(resources.getColor(R.color.ink, null))
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
                text = "⚠ 上次崩溃记录"
                textSize = 15f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(resources.getColor(R.color.danger, null))
            })
            card.addView(TextView(requireContext()).apply {
                text = crash.take(2000)
                textSize = 11f
                setTextColor(resources.getColor(R.color.muted, null))
                setPadding(0, 6, 0, 0)
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
        })
        root.addView(spacer())
        return wrap
    }

    private fun spacer() = Ui.spacer(requireContext(), 8)
}