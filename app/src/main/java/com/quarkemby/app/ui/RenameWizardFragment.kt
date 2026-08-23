package com.quarkemby.app.ui

import android.graphics.Typeface
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.quarkemby.app.R
import com.quarkemby.app.data.Prefs
import com.quarkemby.app.data.QuarkApi
import com.quarkemby.app.data.TmdbApi
import com.quarkemby.app.data.models.FileItem
import com.quarkemby.app.data.models.JobLogEntry
import com.quarkemby.app.data.models.RenameAction
import com.quarkemby.app.data.models.TmdbShow
import com.quarkemby.app.util.EpisodeParser
import com.quarkemby.app.util.RenamePlanner
import kotlinx.coroutines.launch

/**
 * Core Emby batch-renaming wizard.
 * Steps: input show name -> TMDB match/selection -> preview (with conflict
 * detection) -> execute (create Season folders, rename, move) -> result.
 */
class RenameWizardFragment : Fragment() {

    private val folder: FileItem by lazy {
        val a = arguments!!
        FileItem(fid = a.getString(ARG_FID)!!, name = a.getString(ARG_NAME)!!, type = 0)
    }

    private var items: List<FileItem> = emptyList()
    private var plan: RenamePlanner.PlanResult? = null
    private var selectedShow: TmdbShow? = null
    private var tmdbResults: List<TmdbShow> = emptyList()

    private lateinit var root: LinearLayout
    private var scroll: android.widget.ScrollView? = null

    companion object {
        private const val ARG_FID = "fid"
        private const val ARG_NAME = "name"
        fun newInstance(folder: FileItem) = RenameWizardFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_FID, folder.fid)
                putString(ARG_NAME, folder.name)
            }
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        scroll = android.widget.ScrollView(requireContext())
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 30)
        }
        scroll!!.addView(root)
        return scroll!!
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        lifecycleScope.launch {
            try { items = QuarkApi.list(folder.fid) } catch (e: Exception) { }
        }
        renderTitle()
        renderStep1()
    }

    private fun renderTitle() {
        root.addView(TextView(requireContext()).apply {
            text = "Emby 批量重命名"
            textSize = 20f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(resources.getColor(R.color.ink, null))
        })
        root.addView(TextView(requireContext()).apply {
            text = folder.name; textSize = 13f
            setTextColor(resources.getColor(R.color.muted, null))
            setPadding(0, 2, 0, 12)
        })
    }

    // ---------- Step 1 ----------
    private fun renderStep1() {
        root.addView(stepHeader("第 1 步 · 输入剧集名称（可加年份区分同名）"))
        val nameInput = EditText(requireContext()).apply {
            hint = "剧集名称，如：濑户的花嫁"; setSingleLine(true)
        }
        val yearInput = EditText(requireContext()).apply {
            hint = "年份（可选，区分同名，如 2007）"; setSingleLine(true)
        }
        root.addView(nameInput); root.addView(spacer())
        root.addView(yearInput); root.addView(spacer())

        root.addView(button("🔍 TMDB 搜索元数据") {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) { toast("请输入剧集名称"); return@button }
            searchTmdb(name, yearInput.text.toString().trim())
        })
        root.addView(button("⚡ 不使用 TMDB，直接整理（本地解析集数）") {
            Prefs.lastSelectedShow = null
            buildAndPreview(nameInput.text.toString().trim())
        })
    }

    private fun searchTmdb(name: String, year: String) {
        lifecycleScope.launch {
            root.removeAllViews(); renderTitle()
            root.addView(stepHeader("正在搜索 TMDB …"))
            try {
                val key = Prefs.tmdbKey
                if (key.isBlank()) throw TmdbApi.TmdbException("请先在设置页填写个人 TMDB API Key")
                val query = if (year.isNotBlank()) "$name $year" else name
                tmdbResults = TmdbApi.searchTv(key, query, Prefs.tmdbLanguage)
                renderStep2(name)
            } catch (e: Exception) {
                root.addView(TextView(requireContext()).apply {
                    text = e.message ?: "TMDB 搜索失败"; textSize = 14f
                    setTextColor(resources.getColor(R.color.danger, null)); setPadding(0, 8, 0, 8)
                })
                root.addView(button("⚡ 不使用 TMDB 直接整理") { buildAndPreview(name) })
                root.addView(button("返回") { requireActivity().onBackPressed() })
            }
        }
    }

    // ---------- Step 2 ----------
    private fun renderStep2(showName: String) {
        root.removeAllViews(); renderTitle()
        root.addView(stepHeader("第 2 步 · 选择匹配结果（$showName）"))
        if (tmdbResults.isEmpty()) {
            root.addView(TextView(requireContext()).apply {
                text = "没有找到匹配结果，可跳过 TMDB 直接用本地集数整理。"
                textSize = 13f; setTextColor(resources.getColor(R.color.muted, null)); setPadding(0, 8, 0, 8)
            })
        }
        tmdbResults.forEachIndexed { idx, show ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 10, 8, 10)
                isClickable = true
                setBackgroundResource(if (selectedShow?.id == show.id) R.color.bg else 0)
                setOnClickListener {
                    selectedShow = show
                    renderStep2(showName)
                }
            }
            row.addView(TextView(requireContext()).apply {
                text = if (selectedShow?.id == show.id) "◉ " else "○ "
                textSize = 16f
                setTextColor(resources.getColor(R.color.brand_primary, null))
            })
            val col = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(requireContext()).apply {
                text = "${show.name} (${show.firstAirYear.ifEmpty { "未知年份" }})"
                textSize = 15f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(resources.getColor(R.color.ink, null))
            })
            col.addView(TextView(requireContext()).apply {
                text = "TMDB ID: ${show.id} · ${show.mediaType}"
                textSize = 11f; setTextColor(resources.getColor(R.color.muted, null))
            })
            row.addView(col)
            root.addView(row)
        }
        root.addView(spacer())
        val chosenName = selectedShow?.name ?: showName
        root.addView(button("使用所选剧集 · 继续") { buildAndPreview(chosenName) })
        root.addView(button("跳过 TMDB 直接整理") { buildAndPreview(showName) })
    }

    // ---------- Step 3 ----------
    private fun buildAndPreview(showName: String) {
        lifecycleScope.launch {
            val clean = showName.trim()
            if (clean.isEmpty()) { toast("剧集名称不能为空"); return@launch }
            root.removeAllViews(); renderTitle()
            root.addView(stepHeader("加载文件并生成变更预览 …"))
            if (items.isEmpty()) items = runCatching { QuarkApi.list(folder.fid) }.getOrDefault(emptyList())
            plan = RenamePlanner.build(items, clean, Prefs.renameTemplate, Prefs.seasonTemplate)
            renderStep3(clean)
        }
    }

    private fun renderStep3(showName: String) {
        root.removeAllViews(); renderTitle()
        val p = plan!!
        root.addView(stepHeader("第 3 步 · 预览变更（确认后再写网盘）"))
        p.actions.forEach { a ->
            val node = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL; setPadding(4, 8, 4, 8)
            }
            val block = TextView(requireContext()).apply {
                text = if (a.error.isNotEmpty()) {
                    "⚠  ${a.oldName}\n    ${a.error}"
                } else {
                    "📄  ${a.oldName}\n    ➜ ${a.newName}"
                }
                textSize = 13f
                setTextColor(
                    if (a.error.isNotEmpty()) resources.getColor(R.color.danger, null)
                    else resources.getColor(R.color.ink, null)
                )
            }
            node.addView(block)
            root.addView(node)
        }
        val conflict = p.actions.count { it.error.isNotEmpty() } > 0
        if (conflict) {
            root.addView(TextView(requireContext()).apply {
                text = "检测到 ${p.actions.count { it.error.isNotEmpty() }} 项冲突/无法识别，将跳过执行，剩余正常执行。"
                textSize = 13f; setTextColor(resources.getColor(R.color.warn, null)); setPadding(0, 8, 0, 8)
            })
        }
        root.addView(TextView(requireContext()).apply {
            text = "将创建 Season 文件夹：${p.foldersNeeded.joinToString("、")}"
            textSize = 13f; setTextColor(resources.getColor(R.color.muted, null)); setPadding(0, 4, 0, 4)
        })
        root.addView(spacer())
        if (Prefs.previewOnly) {
            root.addView(button("✅ 调试模式 · 仅预览（不会写网盘）") { finishDemo(p) })
        } else {
            root.addView(button("🚀 确认并写入网盘") { execute(p) })
        }
    }

    // ---------- Step 4: execute ----------
    private fun execute(p: RenamePlanner.PlanResult) {
        root.removeAllViews(); renderTitle()
        root.addView(stepHeader("第 4 步 · 执行中 …"))
        val bar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
        }
        val status = TextView(requireContext()).apply {
            text = "准备中…"; textSize = 13f
            setTextColor(resources.getColor(R.color.muted, null)); setPadding(0, 6, 0, 0)
        }
        root.addView(bar); root.addView(status)

        lifecycleScope.launch {
            val fidByName = items.associateBy { it.name }
            val existingFolders = runCatching { QuarkApi.list(folder.fid) }
                .getOrDefault(emptyList()).filter { it.isFolder }.associateBy { it.name }
            val seasonFids = HashMap<String, String>()
            var done = 0; val total = p.actions.count { it.error.isEmpty() } + p.foldersNeeded.size

            // 1) create Season folders
            p.foldersNeeded.forEach { fname ->
                status.text = "创建文件夹 $fname …"
                try {
                    seasonFids[fname] = existingFolders[fname]?.fid ?: QuarkApi.createFolder(folder.fid, fname)
                } catch (e: Exception) {
                    status.text = "创建 $fname 失败：${e.message}"
                }
                done++; bar.progress = done * 100 / total
            }

            // 2) rename + move each action
            val success = mutableListOf<String>()
            val failed = mutableListOf<Pair<String, String>>()
            p.actions.filter { it.error.isEmpty() }.forEach { a ->
                status.text = "处理 ${a.oldName} …"
                try {
                    val file = fidByName[a.oldName] ?: throw Exception("本地记录缺失")
                    if (a.needsRename) QuarkApi.rename(file.fid, a.newName)
                    val seasonName = Prefs.seasonTemplate.replace("{ss}", EpisodeParser.pad(a.seasonIdx + 1))
                    val sFid = seasonFids[seasonName]
                    if (a.needsMove && sFid != null) QuarkApi.move(listOf(file.fid), sFid)
                    success.add(a.newName.ifEmpty { a.oldName })
                } catch (e: Exception) {
                    failed.add(a.oldName to (e.message ?: "未知错误"))
                }
                done++; bar.progress = done * 100 / total
            }
            writeLog(p, success, failed)
            renderStep5(success, failed, p)
        }
    }

    private fun writeLog(p: RenamePlanner.PlanResult, success: List<String>, failed: List<Pair<String, String>>) {
        val detail = buildString {
            success.forEach { append("✓ ").append(it).append('\n') }
            failed.forEach { append("✗ ").append(it.first).append("：").append(it.second).append('\n') }
        }
        Prefs.addLogEntry(
            JobLogEntry(
                id = Prefs.newId(), time = Prefs.formatTime(),
                title = folder.name,
                summary = "成功 ${success.size} · 失败 ${failed.size}",
                detail = detail.ifEmpty { "无明细" }
            )
        )
    }

    private fun finishDemo(p: RenamePlanner.PlanResult) {
        writeLog(p, p.actions.filter { it.error.isEmpty() }.map { it.newName }, emptyList())
        renderStep5(
            p.actions.filter { it.error.isEmpty() }.map { it.newName },
            emptyList(), p
        )
    }

    // ---------- Step 5 ----------
    private fun renderStep5(success: List<String>, failed: List<Pair<String, String>>, p: RenamePlanner.PlanResult) {
        root.removeAllViews(); renderTitle()
        root.addView(stepHeader("第 5 步 · 完成"))
        root.addView(TextView(requireContext()).apply {
            text = if (Prefs.previewOnly) {
                "✅ 调试模式：仅预览，未写入网盘"
            } else {
                "✅ 成功 ${success.size} · 失败 ${failed.size}"
            }
            textSize = 16f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(if (failed.isEmpty()) resources.getColor(R.color.success, null) else resources.getColor(R.color.warn, null))
            setPadding(0, 8, 0, 8)
        })
        if (failed.isNotEmpty()) {
            root.addView(TextView(requireContext()).apply {
                text = failed.joinToString("\n") { "✗ ${it.first} → ${it.second}" }
                textSize = 13f; setTextColor(resources.getColor(R.color.danger, null))
            })
        }
        root.addView(spacer())
        root.addView(button("完成 · 返回文件列表") { requireActivity().onBackPressed() })
    }

    // ---------- helpers ----------
    private fun stepHeader(t: String) = TextView(requireContext()).apply {
        text = t; textSize = 16f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(resources.getColor(R.color.brand_primary, null)); setPadding(0, 14, 0, 8)
    }

    private fun spacer() = View(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(1, 6) }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(requireContext()).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24f
                colors = intArrayOf(resources.getColor(R.color.brand_primary, null), resources.getColor(R.color.brand_secondary, null))
                orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
            }
            setPadding(0, 0, 0, 0)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 10, 0, 10)
            layoutParams = lp
            setOnClickListener { onClick() }
        }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
}