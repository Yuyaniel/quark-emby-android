package com.quarkemby.app.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.quarkemby.app.R
import com.quarkemby.app.data.Prefs
import com.quarkemby.app.data.QuarkApi
import com.quarkemby.app.data.TmdbApi
import com.quarkemby.app.data.models.FileItem
import com.quarkemby.app.data.models.JobLogEntry
import com.quarkemby.app.util.CrashLog
import com.quarkemby.app.util.EpisodeParser
import com.quarkemby.app.util.RenamePlanner
import com.quarkemby.app.util.ShowNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Batch-renaming wizard as a PLAIN Dialog — the exact same window plumbing
 * as the verified long-press menu dialog. Every render step is wrapped by
 * [safeRender]: failures show inside the dialog and go to CrashLog, never
 * crashing the app.
 *
 * Naming: season+TMDB -> 九门.S01E01.标题.mp4; season -> 九门.S01E01.mp4;
 * empty -> 九门.01.mp4.
 */
class RenameWizard(ctx: Context, private val folder: FileItem) : Dialog(ctx) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var items: List<FileItem> = emptyList()
    private var plan: RenamePlanner.PlanResult? = null
    private var tmdbResults: List<TmdbApi.Show> = emptyList()
    private var selectedShow: TmdbApi.Show? = null
    private var seasonList: List<TmdbApi.SeasonInfo> = emptyList()
    private var tmdbSeasonSel: Int = 1

    private lateinit var root: LinearLayout

    companion object {
        private const val TPL_SEASON_TITLE = "{show_name}.S{ss}E{ee}.{ep_title}"
        private const val TPL_EP_TITLE = "{show_name}.{ee}.{ep_title}"
        private const val TPL_WITH_SEASON = "{show_name}.S{ss}E{ee}"
        private const val TPL_NO_SEASON = "{show_name}.{ee}"
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        setCanceledOnTouchOutside(false)

        val scroll = ScrollView(ctx)
        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 22, 24, 26)
            setBackgroundResource(R.drawable.bg_scrim_dialog)
        }
        scroll.addView(root)
        setContentView(scroll)
        Ui.centerWindow(this, 0.92f)
        Ui.applyScrim(this, 0.5f)

        safeRender("step1") { renderTitle(); renderStep1() }

        // prefetch folder contents; any failure is non-fatal (lazy retry later)
        scope.launch { runCatching { items = QuarkApi.list(folder.fid) } }
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }

    override fun onBackPressed() {
        dismiss()
    }

    /** Renders `block` inside the dialog, converting any failure to an in-dialog error. */
    private fun safeRender(tag: String, block: () -> Unit) {
        root.removeAllViews()
        try {
            block()
        } catch (t: Throwable) {
            CrashLog.write(context, "wizard/$tag", t)
            root.addView(Ui.title(context, "界面渲染出错", 16f))
            root.addView(TextView(context).apply {
                text = "${t.javaClass.simpleName}: ${t.message}\n\n详情已写入任务日志，可反馈给开发者。"
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.danger))
                setPadding(0, 8, 0, 8)
            })
            root.addView(Ui.secondaryTextBtn(context, "关闭") { dismiss() })
        }
    }

    private fun renderTitle() {
        root.addView(TextView(context).apply {
            text = "批量重命名"
            textSize = 20f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.ink))
        })
        root.addView(TextView(context).apply {
            text = folder.name; textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.muted))
            setPadding(0, 2, 0, 12)
        })
    }

    // ---------- Step 1 ----------
    private fun renderStep1() {
        root.addView(stepHeader("第 1 步 · 剧集名称与季号"))
        // cleaned name pre-computed defensively ("九门(2026)" -> "九门")
        val cleaned = runCatching { ShowNames.clean(folder.name) }.getOrDefault(folder.name)
        val nameInput = EditText(context).apply {
            hint = "剧集名称"; setSingleLine(true)
            setText(cleaned)
        }
        val seasonInput = EditText(context).apply {
            hint = "第几季（可选）"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(nameInput); root.addView(spacer())
        root.addView(seasonInput); root.addView(spacer())
        root.addView(TextView(context).apply {
            text = "季号留空：九门.01.mp4 ／ 填 1：九门.S01E01.mp4\n" +
                    "使用 TMDB 时季数无需填写：选剧后在下一步以 TMDB 的季为准（默认第 1 季）"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.muted))
            setPadding(4, 0, 4, 4)
        })

        root.addView(button("TMDB 搜索（封面确认 · 含剧集标题）") {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) { toast("请输入剧集名称"); return@button }
            val season = readSeason(seasonInput) ?: return@button
            searchTmdb(name, season)
        })
        root.addView(buttonSub("整理重命名(本地解析)") {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) { toast("请输入剧集名称"); return@buttonSub }
            val season = readSeason(seasonInput) ?: return@buttonSub
            buildAndPreview(name, season, null)
        })
        root.addView(buttonSub("取消") { dismiss() })
    }

    private fun readSeason(input: EditText): Int? {
        val raw = input.text.toString().trim()
        if (raw.isEmpty()) return null
        val v = raw.toIntOrNull()?.takeIf { it in 1..99 }
        if (v == null) toast("季号请填 1-99 的数字，或留空")
        return v
    }

    // ---------- Step 2: TMDB results + season chips ----------
    private fun searchTmdb(name: String, season: Int?) {
        val key = Prefs.tmdbKey
        if (key.isBlank()) {
            toast("请先在「设置」中填写 TMDB API Key，或使用本地解析")
            return
        }
        scope.launch {
            safeRender("tmdb-loading") { renderTitle(); addStep("正在搜索 TMDB …") }
            try {
                tmdbResults = TmdbApi.searchTv(key, name, Prefs.tmdbLanguage)
                selectedShow = null
                seasonList = emptyList()
                tmdbSeasonSel = season ?: 1
                safeRender("step2") { renderTitle(); renderStep2Body(name, season) }
            } catch (e: Exception) {
                safeRender("tmdb-error") {
                    renderTitle()
                    root.addView(TextView(context).apply {
                        text = e.message ?: "TMDB 搜索失败"; textSize = 14f
                        setTextColor(ContextCompat.getColor(context, R.color.danger)); setPadding(0, 8, 0, 8)
                    })
                    root.addView(buttonSub("整理重命名(本地解析)") { buildAndPreview(name, season, null) })
                    root.addView(buttonSub("返回") { safeRender("step1") { renderTitle(); renderStep1() } })
                }
            }
        }
    }

    /** Selecting a show also loads its seasons from TMDB for the chip row. */
    private fun selectShow(show: TmdbApi.Show, queryName: String, typedSeason: Int?) {
        selectedShow = show
        seasonList = emptyList()
        tmdbSeasonSel = typedSeason ?: 1
        safeRender("step2") { renderTitle(); renderStep2Body(queryName, typedSeason, loadingSeasons = true) }
        scope.launch {
            // season list straight from TMDB; failures fall back to a plain
            // "第 1 季" chip so the flow never dead-ends
            val loaded = runCatching {
                TmdbApi.tvSeasons(Prefs.tmdbKey, show.id, Prefs.tmdbLanguage)
            }.getOrDefault(emptyList())
            seasonList = loaded.ifEmpty {
                listOf(TmdbApi.SeasonInfo(tmdbSeasonSel, "第 $tmdbSeasonSel 季", 0))
            }
            // typed season wins when it exists on TMDB, otherwise keep it as-is
            if (typedSeason != null && seasonList.any { it.number == typedSeason }) {
                tmdbSeasonSel = typedSeason
            }
            safeRender("step2") { renderTitle(); renderStep2Body(queryName, typedSeason) }
        }
    }

    private fun renderStep2Body(queryName: String, typedSeason: Int?, loadingSeasons: Boolean = false) {
        root.addView(stepHeader("第 2 步 · 选择剧集与季"))
        if (tmdbResults.isEmpty()) {
            root.addView(TextView(context).apply {
                text = "没有找到匹配结果，可直接使用本地解析整理。"
                textSize = 13f; setTextColor(ContextCompat.getColor(context, R.color.muted)); setPadding(0, 8, 0, 8)
            })
        }
        tmdbResults.forEach { show ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 10, 8, 10)
                isClickable = true
                foreground = ContextCompat.getDrawable(context, R.drawable.ripple_fg)
                background = GradientDrawable().apply {
                    cornerRadius = Ui.dp(context, 12).toFloat()
                    setColor(
                        ContextCompat.getColor(
                            context,
                            if (selectedShow?.id == show.id) R.color.primary_container
                            else R.color.surface_container_high
                        )
                    )
                }
                setOnClickListener { selectShow(show, queryName, typedSeason) }
            }
            // poster thumbnail for visual confirmation
            val poster = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 54), Ui.dp(context, 81))
                    .apply { marginEnd = Ui.dp(context, 12) }
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            Img.load(show.posterUrl, poster)
            row.addView(poster)

            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(context).apply {
                text = "${show.name}（${show.firstAirYear.ifEmpty { "未知年份" }}）"
                textSize = 15f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.ink))
            })
            col.addView(TextView(context).apply {
                text = "TMDB ID: ${show.id}"
                textSize = 11f; setTextColor(ContextCompat.getColor(context, R.color.muted))
            })
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(context).apply {
                text = if (selectedShow?.id == show.id) "●" else "○"
                textSize = 14f
                setPadding(Ui.dp(context, 8), 0, 4, 0)
                setTextColor(ContextCompat.getColor(context, R.color.brand_primary))
            })
            root.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, Ui.dp(context, 6), 0, Ui.dp(context, 6)) })
        }

        // season chips of the selected show (TMDB is the authority for season)
        val sel = selectedShow
        if (sel != null) {
            root.addView(TextView(context).apply {
                text = "选择季（以 TMDB 为准" +
                        (if (loadingSeasons) " · 正在获取…" else "") + "）"
                textSize = 13f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.ink))
                setPadding(0, Ui.dp(context, 12), 0, 6)
            })
            val chipRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            seasonList.forEach { s ->
                val active = s.number == tmdbSeasonSel
                chipRow.addView(TextView(context).apply {
                    text = "第 ${s.number} 季" + (if (s.episodeCount > 0) " · ${s.episodeCount}集" else "")
                    textSize = 13f
                    isClickable = true
                    setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 8))
                    setTextColor(
                        ContextCompat.getColor(
                            context, if (active) R.color.on_primary else R.color.ink
                        )
                    )
                    background = GradientDrawable().apply {
                        cornerRadius = Ui.dp(context, 99).toFloat()
                        setColor(
                            ContextCompat.getColor(
                                context,
                                if (active) R.color.brand_primary else R.color.surface_container_high
                            )
                        )
                    }
                    setOnClickListener {
                        tmdbSeasonSel = s.number
                        safeRender("step2") { renderTitle(); renderStep2Body(queryName, typedSeason) }
                    }
                })
                chipRow.addView(spacerView(8, LinearLayout.HORIZONTAL))
            }
            root.addView(chipRow)
        }

        root.addView(spacer())
        if (sel != null) {
            root.addView(button("使用所选剧集 · 继续（第 $tmdbSeasonSel 季 · 含剧集标题）") {
                buildAndPreview(sel.name, tmdbSeasonSel, sel)
            })
        }
        root.addView(buttonSub("跳过 TMDB · 整理重命名(本地解析)") {
            buildAndPreview(queryName, typedSeason, null)
        })
        root.addView(buttonSub("返回上一步") { safeRender("step1") { renderTitle(); renderStep1() } })
    }

    // ---------- Step 3: plan + preview ----------
    private fun buildAndPreview(showName: String, userSeason: Int?, show: TmdbApi.Show?) {
        scope.launch {
            val clean = showName.trim()
            if (clean.isEmpty()) { toast("剧集名称不能为空"); return@launch }
            safeRender("plan-loading") { renderTitle(); addStep("加载文件并生成变更预览 …") }
            if (items.isEmpty()) {
                items = runCatching { QuarkApi.list(folder.fid) }.getOrDefault(emptyList())
            }
            if (items.none { it.isVideo || it.isSubtitle }) {
                safeRender("plan-empty") {
                    renderTitle()
                    root.addView(TextView(context).apply {
                        text = "读取不到视频/字幕文件，请检查网络或 Cookie 后重试。"
                        textSize = 13f; setTextColor(ContextCompat.getColor(context, R.color.danger)); setPadding(0, 8, 0, 8)
                    })
                    root.addView(buttonSub("返回") { safeRender("step1") { renderTitle(); renderStep1() } })
                }
                return@launch
            }

            // TMDB episode titles for the selected season (TMDB decides season)
            var epTitles: Map<Int, String>? = null
            if (show != null && Prefs.tmdbKey.isNotBlank()) {
                epTitles = runCatching {
                    TmdbApi.seasonEpisodes(Prefs.tmdbKey, show.id, userSeason ?: 1, Prefs.tmdbLanguage)
                }.getOrNull()
            }
            val hasTitles = !epTitles.isNullOrEmpty()
            val tpl = when {
                hasTitles && userSeason != null -> TPL_SEASON_TITLE
                hasTitles -> TPL_EP_TITLE
                userSeason != null -> TPL_WITH_SEASON
                else -> TPL_NO_SEASON
            }
            plan = runCatching {
                RenamePlanner.build(items, clean, tpl, Prefs.seasonTemplate, userSeason, epTitles)
            }.getOrNull()
            if (plan == null) {
                safeRender("plan-error") {
                    renderTitle()
                    root.addView(TextView(context).apply {
                        text = "生成重命名计划失败，请反馈任务日志。"
                        textSize = 13f; setTextColor(ContextCompat.getColor(context, R.color.danger)); setPadding(0, 8, 0, 8)
                    })
                    root.addView(buttonSub("返回") { safeRender("step1") { renderTitle(); renderStep1() } })
                }
                return@launch
            }
            safeRender("step3") { renderTitle(); renderStep3Body() }
        }
    }

    private fun renderStep3Body() {
        val p = plan ?: return
        val actionable = p.actions.indices.filter { p.actions[it].error.isEmpty() }
        val checked = linkedSetOf<Int>().apply { addAll(actionable) }

        root.addView(stepHeader("第 3 步 · 预览重命名（勾选要执行的项目）"))

        val conflictIdx = p.actions.indices.filter { p.actions[it].error.isNotEmpty() }
        root.addView(TextView(context).apply {
            text = "共 ${p.actions.size} 项，可执行 ${actionable.size} 项" +
                    (if (conflictIdx.isNotEmpty()) "，${conflictIdx.size} 项异常" else "")
            textSize = 13f; setTextColor(ContextCompat.getColor(context, R.color.muted)); setPadding(0, 0, 0, 6)
        })
        if (actionable.isEmpty()) {
            root.addView(TextView(context).apply {
                text = "没有可执行项：文件名中未能解析出集数（支持 S01E01、第01集、EP01、01-xxx 等格式），" +
                        "请先重命名文件后再整理。"
                textSize = 13f; setTextColor(ContextCompat.getColor(context, R.color.danger)); setPadding(0, 4, 0, 8)
            })
        }

        p.actions.forEachIndexed { idx, a ->
            val bg = GradientDrawable().apply {
                cornerRadius = Ui.dp(context, 12).toFloat()
                setColor(
                    ContextCompat.getColor(
                        context,
                        if (a.error.isNotEmpty()) R.color.danger_container
                        else R.color.surface_container_high
                    )
                )
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 10, 12, 10)
                background = bg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, Ui.dp(context, 6), 0, Ui.dp(context, 6)) }
            }
            val cb = CheckBox(context).apply {
                isChecked = a.error.isEmpty()
                isEnabled = a.error.isEmpty()
                setOnCheckedChangeListener { _, isCc -> if (isCc) checked.add(idx) else checked.remove(idx) }
            }
            row.addView(cb)
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(6, 0, 0, 0)
            }
            col.addView(TextView(context).apply {
                text = if (a.error.isNotEmpty()) "! ${a.oldName}" else a.oldName
                textSize = 13.5f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.ink))
            })
            col.addView(TextView(context).apply {
                text = if (a.error.isNotEmpty()) a.error else "→ ${a.newName}"
                textSize = 13.5f
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (a.error.isNotEmpty()) R.color.danger else R.color.brand_secondary
                    )
                )
            })
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(row)
        }

        if (p.foldersNeeded.isNotEmpty()) {
            root.addView(TextView(context).apply {
                text = "将创建 Season 文件夹：" + p.foldersNeeded.joinToString("、")
                textSize = 13f; setTextColor(ContextCompat.getColor(context, R.color.muted)); setPadding(0, 8, 0, 4)
            })
        }
        root.addView(spacer())

        if (Prefs.previewOnly) {
            root.addView(buttonSub("调试模式 · 仅预览（不会写网盘）") { finishDemo(p) })
        } else {
            root.addView(button("重命名勾选的项目") {
                val chosen = if (checked.isNotEmpty()) checked else actionable.toSet()
                execute(p, chosen)
            })
        }
        root.addView(buttonSub("返回上一步") { safeRender("step1") { renderTitle(); renderStep1() } })
        root.addView(buttonSub("取消") { dismiss() })
    }

    // ---------- Step 4: execute ----------
    private fun execute(p: RenamePlanner.PlanResult, chosen: Set<Int>) {
        safeRender("exec") {
            renderTitle()
            root.addView(stepHeader("第 4 步 · 执行中 …"))
        }
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
        }
        val status = TextView(context).apply {
            text = "准备中…"; textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.muted)); setPadding(0, 6, 0, 0)
        }
        root.addView(bar); root.addView(status)

        scope.launch {
            val fidByName = items.associateBy { it.name }
            val existingFolders = runCatching { QuarkApi.list(folder.fid) }
                .getOrDefault(emptyList()).filter { it.isFolder }.associateBy { it.name }
            val seasonFids = HashMap<String, String>()
            val usedActions = chosen.map { p.actions[it] }.filter { it.error.isEmpty() }
            val usedFolders = usedActions.map { it.seasonIdx }.distinct()
            val total = (usedActions.size + usedFolders.size).coerceAtLeast(1)
            var done = 0

            usedFolders.forEach { si ->
                val fname = Prefs.seasonTemplate.replace("{ss}", EpisodeParser.pad(si + 1))
                status.text = "创建文件夹 $fname …"
                try {
                    seasonFids[fname] = existingFolders[fname]?.fid ?: QuarkApi.createFolder(folder.fid, fname)
                } catch (e: Exception) {
                    status.text = "创建 $fname 失败：${e.message}"
                }
                done++; bar.progress = done * 100 / total
            }

            val success = mutableListOf<String>()
            val failed = mutableListOf<Pair<String, String>>()
            usedActions.forEach { a ->
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
            writeLog(success, failed)
            safeRender("done") { renderTitle(); renderStep4Body(success, failed) }
        }
    }

    private fun writeLog(success: List<String>, failed: List<Pair<String, String>>) {
        val detail = buildString {
            success.forEach { append("成功：").append(it).append('\n') }
            failed.forEach { append("失败：").append(it.first).append("（").append(it.second).append("）\n") }
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
        val ok = p.actions.filter { it.error.isEmpty() }.map { it.newName }
        writeLog(ok, emptyList())
        safeRender("done") { renderTitle(); renderStep4Body(ok, emptyList()) }
    }

    // ---------- Step 5 ----------
    private fun renderStep4Body(success: List<String>, failed: List<Pair<String, String>>) {
        root.addView(stepHeader("完成"))
        root.addView(TextView(context).apply {
            text = if (Prefs.previewOnly) {
                "调试模式：仅预览，未写入网盘"
            } else {
                "成功 ${success.size} · 失败 ${failed.size}"
            }
            textSize = 16f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(
                ContextCompat.getColor(
                    context, if (failed.isEmpty()) R.color.success else R.color.warn
                )
            )
            setPadding(0, 8, 0, 8)
        })
        if (failed.isNotEmpty()) {
            root.addView(TextView(context).apply {
                text = failed.joinToString("\n") { "失败：${it.first}（${it.second}）" }
                textSize = 13f; setTextColor(ContextCompat.getColor(context, R.color.danger))
            })
        }
        root.addView(spacer())
        root.addView(button("完成") { dismiss() })
    }

    // ---------- helpers ----------
    private fun addStep(t: String) {
        root.addView(stepHeader(t))
    }

    private fun stepHeader(t: String) = TextView(context).apply {
        text = t; textSize = 16f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, R.color.brand_primary)); setPadding(0, 14, 0, 8)
    }

    private fun spacer() = Ui.spacer(context, 6)

    private fun spacerView(sizeDp: Int, orientation: Int): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0).apply {
                if (orientation == LinearLayout.HORIZONTAL) {
                    width = Ui.dp(context, sizeDp); height = 1
                } else {
                    width = 1; height = Ui.dp(context, sizeDp)
                }
            }
        }

    /** Primary CTA — TextView-based for maximum device compatibility. */
    private fun button(label: String, onClick: () -> Unit) =
        Ui.primaryTextBtn(context, label, onClick)

    /** Secondary action — TextView-based for maximum device compatibility. */
    private fun buttonSub(label: String, onClick: () -> Unit) =
        Ui.secondaryTextBtn(context, label, onClick)

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
}
