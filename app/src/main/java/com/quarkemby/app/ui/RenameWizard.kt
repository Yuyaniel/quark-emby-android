package com.quarkemby.app.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
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
    private val scroll: ScrollView = ScrollView(ctx)

    /** Query name of the last TMDB search — needed to re-render step 2 on back. */
    private var lastQuery = ""
    /** LIFO re-render closures: hardware back unwinds the wizard ONE step. */
    private val stepHistory = mutableListOf<() -> Unit>()
    /** Bumped on every back navigation; async renders from a stale epoch
     *  (e.g. a search finishing AFTER the user backed out) are dropped. */
    private var epoch = 0
    /** True while rename jobs are running — back is suppressed so the run
     *  can't be half-aborted by a stray back press. */
    private var executing = false

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

        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 22), Ui.dp(ctx, 24), Ui.dp(ctx, 22), Ui.dp(ctx, 24))
            setBackgroundResource(R.drawable.bg_center_dialog)
        }
        scroll.addView(root)
        setContentView(scroll)
        dialogWindow()
        Ui.applyScrim(this, 0.5f)

        safeRender("step1") { renderTitle(); renderStep1() }

        // prefetch folder contents; any failure is non-fatal (lazy retry later)
        scope.launch { runCatching { items = QuarkApi.list(folder.fid) } }
    }

    /**
     * Centered dialog window: wrap-content height (no dialog scrolling).
     * [widthRatio] allows the denser step-2 layout to render slightly
     * narrower than the default 0.92.
     */
    private fun dialogWindow(widthRatio: Float = 0.92f) {
        val w = window ?: return
        scroll.isFillViewport = false
        root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        val lp = w.attributes
        lp.gravity = Gravity.CENTER
        lp.width = (w.context.resources.displayMetrics.widthPixels * widthRatio).toInt()
        lp.horizontalMargin = 0f
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        w.attributes = lp
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }

    override fun onBackPressed() = goBackStep()

    /** Hardware back = previous wizard step (LIFO); at step 1 it closes. */
    private fun goBackStep() {
        if (executing) { toast("正在执行整理，请稍候…"); return }
        epoch++
        val re = if (stepHistory.isEmpty()) null else stepHistory.removeAt(stepHistory.size - 1)
        if (re == null) { dismiss(); return }
        re()
    }

    /** Push the page we are LEAVING so back can re-render it. */
    private fun pushHistory(re: () -> Unit) { stepHistory.add(re) }

    /** Back targets (wrapped in safeRender so failures degrade gracefully). */
    private fun renderStep1Again() = safeRender("step1") { renderTitle(); renderStep1() }
    private fun renderStep2Again() = safeRender("step2") { renderTitle(); renderStep2Body(lastQuery) }

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
        // step-2 swaps the content view; every other step restores the
        // original scroll container before rendering
        setContentView(scroll)
        dialogWindow()
        root.addView(TextView(context).apply {
            text = "批量重命名"
            textSize = 20f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.ink))
        })
        val cleanedName = runCatching { ShowNames.clean(folder.name) }.getOrDefault("")
        root.addView(TextView(context).apply {
            text = cleanedName.ifBlank { folder.name }
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.muted))
            setPadding(0, 2, 0, 12)
        })
    }

    // ---------- Step 1 ----------
    private fun renderStep1() {
        root.addView(stepHeader("第 1 步 · 剧集名称"))
        // cleaned name pre-computed defensively: any failure leaves the field
        // empty rather than leaking the raw folder name (which may contain years).
        val cleaned = runCatching { ShowNames.clean(folder.name) }.getOrDefault("")
        val nameInput = EditText(context).apply {
            hint = "剧集名称"; setSingleLine(true)
            setText(cleaned)
        }
        root.addView(nameInput); root.addView(spacer())
        root.addView(TextView(context).apply {
            text = "已自动去除年份等杂项（毛骗(2010) → 毛骗），可手动修改"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.muted))
            setPadding(4, 0, 4, 10)
        })

        // TMDB path: season NOT required — season chips come from TMDB in step 2
        root.addView(button("TMDB 搜索（电影 / 剧集）") {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) { toast("请输入剧集名称"); return@button }
            searchTmdb(name)
        })
        root.addView(spacer())

        val seasonInput = EditText(context).apply {
            hint = "第几季（可选）"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(seasonInput); root.addView(spacer())
        // same primary filled style as the TMDB button (identical radius/height)
        root.addView(button("整理重命名(本地解析)") {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) { toast("请输入剧集名称"); return@button }
            val raw = seasonInput.text.toString().trim()
            val season = raw.toIntOrNull()?.takeIf { it in 1..99 }
            if (raw.isNotEmpty() && season == null) {
                toast("季号请填 1-99 的数字，或留空"); return@button
            }
            pushHistory(::renderStep1Again)
            buildAndPreview(name, season, null)
        })
        root.addView(TextView(context).apply {
            text = "本地解析：季号留空 → 毛骗.01.mp4，填 1 → 毛骗.S01E01.mp4"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.muted))
            setPadding(4, 2, 4, 4)
        })
        root.addView(buttonOutline("取消") { dismiss() })
    }

    // ---------- Step 2: TMDB results + season chips ----------
    private fun searchTmdb(name: String) {
        val key = Prefs.tmdbKey
        if (key.isBlank()) {
            toast("请先在「设置」中填写 TMDB API Key，或使用本地解析")
            return
        }
        scope.launch {
            val myEpoch = epoch
            safeRender("tmdb-loading") { renderTitle(); addStep("正在搜索 TMDB …") }
            try {
                tmdbResults = TmdbApi.searchAll(key, name, Prefs.tmdbLanguage)
                selectedShow = null
                seasonList = emptyList()
                tmdbSeasonSel = 1
                lastQuery = name
                if (epoch != myEpoch) return@launch
                pushHistory(::renderStep1Again)
                safeRender("step2") { renderTitle(); renderStep2Body(name) }
            } catch (e: Exception) {
                if (epoch != myEpoch) return@launch
                stepHistory.clear(); pushHistory(::renderStep1Again)
                safeRender("tmdb-error") {
                    renderTitle()
                    root.addView(TextView(context).apply {
                        text = e.message ?: "TMDB 搜索失败"; textSize = 14f
                        setTextColor(ContextCompat.getColor(context, R.color.danger)); setPadding(0, 8, 0, 8)
                    })
                    root.addView(buttonSub("整理重命名(本地解析)") { buildAndPreview(name, null, null) })
                    root.addView(buttonSub("返回") { goBackStep() })
                }
            }
        }
    }

    /** Selecting a show loads its seasons from TMDB and jumps STRAIGHT to the
     *  rename preview (season defaults to 1). The old intermediate step-2
     *  re-render with season chips and a "使用所选剧集 · 继续" button is
     *  removed — one confirm dialog, then preview. */
    private fun confirmShowAndPreview(show: TmdbApi.Show) {
        scope.launch {
            val myEpoch = epoch
            selectedShow = show
            if (show.isMovie) {
                // movies have no seasons/episodes: straight to the preview,
                // files are renamed in place (no Season folder)
                seasonList = emptyList()
                tmdbSeasonSel = 1
                if (epoch != myEpoch) return@launch
                pushHistory(::renderStep2Again)
                buildAndPreview(show.name, null, show)
                return@launch
            }
            safeRender("tmdb-loading") { renderTitle(); addStep("正在获取 TMDB 季信息 …") }
            seasonList = runCatching {
                TmdbApi.tvSeasons(Prefs.tmdbKey, show.id, Prefs.tmdbLanguage)
            }.getOrDefault(emptyList()).ifEmpty { listOf(TmdbApi.SeasonInfo(1, "第 1 季", 0)) }
            tmdbSeasonSel = if (seasonList.any { it.number == 1 }) 1 else seasonList.first().number
            if (epoch != myEpoch) return@launch
            pushHistory(::renderStep2Again)
            buildAndPreview(show.name, tmdbSeasonSel, show)
        }
    }

    /**
     * Step 2 — Material3 dark centered dialog (pure View system): scrollable
     * result list. Header / fixed-height scrollable list (≈4 rows visible) /
     * actions. Tapping a row opens the confirm dialog whose 开始整理 jumps
     * straight to the preview.
     */
    private fun renderStep2Body(queryName: String) {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 22), Ui.dp(context, 24), Ui.dp(context, 22), Ui.dp(context, 24))
            setBackgroundResource(R.drawable.bg_center_dialog)
        }
        dialogWindow(0.85f)
        setContentView(page)

        // ---- header: three left-aligned text blocks ----
        page.addView(TextView(context).apply {
            text = "批量重命名"
            textSize = 20f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.ink))
        })
        val cleanedName2 = runCatching { ShowNames.clean(folder.name) }.getOrDefault("")
        page.addView(TextView(context).apply {
            text = cleanedName2.ifBlank { folder.name }; textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.muted))
            setPadding(0, Ui.dp(context, 2), 0, Ui.dp(context, 12))
        })
        page.addView(stepHeader("第 2 步 · 选择匹配结果"))

        // ---- scrollable list: first 4 rows visible, swipe for more ----
        val listColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (tmdbResults.isEmpty()) {
            listColumn.addView(TextView(context).apply {
                text = "没有找到匹配结果，可直接使用本地解析整理。"
                textSize = 13f; setTextColor(ContextCompat.getColor(context, R.color.muted))
                setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 10))
            })
        } else {
            tmdbResults.forEachIndexed { i, show ->
                val row = showRow(show)
                (row.layoutParams as LinearLayout.LayoutParams).topMargin =
                    if (i == 0) 0 else Ui.dp(context, 16)
                listColumn.addView(row)
            }
        }
        val scrollResults = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(context, 12) }
            addView(listColumn)
        }
        page.addView(scrollResults)

        // Adaptive list height: expand to fit rows, but cap at ~4 rows (420dp).
        // This eliminates blank space when there is only 1-2 results.
        scrollResults.post {
            val maxH = Ui.dp(context, 420)
            listColumn.measure(
                View.MeasureSpec.makeMeasureSpec(scrollResults.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val desiredH = listColumn.measuredHeight.coerceAtMost(maxH)
            if (scrollResults.layoutParams.height != desiredH) {
                scrollResults.layoutParams.height = desiredH
                scrollResults.requestLayout()
            }
        }

        // ---- two TextButtons bottom-right ----
        page.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(textBtn("跳过TMDB本地解析") {
                pushHistory(::renderStep2Again)
                buildAndPreview(queryName, null, null)
            })
            addView(textBtn("返回上一步") { goBackStep() })
        })
    }

    /** Borderless text action (Material3 TextButton style). */
    private fun textBtn(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        isClickable = true
        setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
        setTextColor(ContextCompat.getColor(context, R.color.brand_primary))
        setOnClickListener { onClick() }
    }

    /**
     * One TMDB result as a plain row (no card / border / radio), strictly
     * center-aligned vertically: 64×90 poster (2:3, 8dp rounded) + 2-line
     * title + "类型 · 年份" secondary text. 8dp vertical padding. Tapping
     * the row opens a confirmation dialog instead of selecting directly.
     */
    private fun showRow(show: TmdbApi.Show): LinearLayout =
        LinearLayout(context).apply {
            val selected = selectedShow?.id == show.id
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 2), Ui.dp(context, 8), Ui.dp(context, 2), Ui.dp(context, 8))
            isClickable = true
            foreground = ContextCompat.getDrawable(context, R.drawable.ripple_fg)
            setOnClickListener { showConfirmDialog(show) }

            val poster = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 64), Ui.dp(context, 90))
                    .apply { marginEnd = Ui.dp(context, 12) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                // 8dp rounded corners via outline clipping
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(
                            0, 0, view.width, view.height, Ui.dp(context, 8).toFloat()
                        )
                    }
                }
            }
            Img.load(show.posterUrl, poster)
            addView(poster)

            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            col.addView(TextView(context).apply {
                text = show.name
                textSize = 15f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (selected) R.color.brand_primary else R.color.ink
                    )
                )
            })
            col.addView(TextView(context).apply {
                text = "${if (show.isMovie) "电影" else "剧集"} · ${show.firstAirYear.ifEmpty { "未知年份" }}"
                textSize = 12f; setPadding(0, Ui.dp(context, 3), 0, 0)
                setTextColor(ContextCompat.getColor(context, R.color.muted))
            })
            addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    /**
     * Second-step confirmation: centered dialog showing the show's info
     * before committing (same structure as the scrape-confirm dialog):
     * left-aligned headline / poster+info row / explanation paragraph /
     * right-aligned TextButtons. 开始整理 loads seasons and jumps STRAIGHT
     * to the rename preview (default season 1); 返回 just closes it.
     */
    private fun showConfirmDialog(show: TmdbApi.Show) {
        val dlg = Dialog(context)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 22), Ui.dp(context, 24), Ui.dp(context, 22), Ui.dp(context, 18))
            setBackgroundResource(R.drawable.bg_center_dialog)

            // ---- headline, left-aligned ----
            addView(TextView(context).apply {
                text = "确认使用TMDB信息"
                textSize = 22f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.ink))
                setPadding(0, 0, 0, Ui.dp(context, 16))
            })

            // ---- info row: poster + column, vertically centered ----
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                val poster = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 80), Ui.dp(context, 112))
                        .apply { marginEnd = Ui.dp(context, 16) }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(
                                0, 0, view.width, view.height, Ui.dp(context, 8).toFloat()
                            )
                        }
                    }
                }
                Img.load(show.posterUrl, poster)
                addView(poster)

                val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                info.addView(TextView(context).apply {
                    text = show.name
                    textSize = 17f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(ContextCompat.getColor(context, R.color.ink))
                })
                info.addView(TextView(context).apply {
                    text = "类型：${if (show.isMovie) "电影" else "剧集"}"
                    textSize = 13f; setPadding(0, Ui.dp(context, 6), 0, 0)
                    setTextColor(ContextCompat.getColor(context, R.color.muted))
                })
                info.addView(TextView(context).apply {
                    text = "年份：${show.firstAirYear.ifEmpty { "未知" }}"
                    textSize = 13f; setPadding(0, Ui.dp(context, 2), 0, 0)
                    setTextColor(ContextCompat.getColor(context, R.color.muted))
                })
                info.addView(TextView(context).apply {
                    text = "TMDB-ID：${show.id}"
                    textSize = 13f; setPadding(0, Ui.dp(context, 2), 0, 0)
                    setTextColor(ContextCompat.getColor(context, R.color.muted))
                })
                addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })

            // ---- explanation paragraph, secondary color ----
            addView(TextView(context).apply {
                text = if (show.isMovie) {
                    "确认后将以 TMDB 电影名称直接生成重命名预览（原地重命名，不创建季文件夹）。"
                } else {
                    "确认后将以 TMDB 信息（默认第 1 季，含剧集标题）直接生成重命名预览。"
                }
                textSize = 14f
                setPadding(0, Ui.dp(context, 16), 0, Ui.dp(context, 8))
                setTextColor(ContextCompat.getColor(context, R.color.muted))
            })

            // ---- actions, right-aligned TextButtons ----
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(textBtn("返回") { dlg.dismiss() })
                addView(textBtn("开始整理") {
                    dlg.dismiss()
                    confirmShowAndPreview(show)
                })
            })
        }
        dlg.setContentView(col)
        Ui.centerWindow(dlg, 0.8f)
        Ui.applyScrim(dlg)
        dlg.show()
    }

    // ---------- Step 3: plan + preview ----------
    private fun buildAndPreview(showName: String, userSeason: Int?, show: TmdbApi.Show?) {
        scope.launch {
            val myEpoch = epoch
            val clean = showName.trim()
            if (clean.isEmpty()) { toast("剧集名称不能为空"); return@launch }
            safeRender("plan-loading") { renderTitle(); addStep("加载文件并生成变更预览 …") }
            if (items.isEmpty()) {
                items = runCatching { QuarkApi.list(folder.fid) }.getOrDefault(emptyList())
            }
            if (items.none { it.isVideo || it.isSubtitle }) {
                if (epoch != myEpoch) return@launch
                stepHistory.clear(); pushHistory(::renderStep1Again)
                safeRender("plan-empty") {
                    renderTitle()
                    root.addView(TextView(context).apply {
                        text = "读取不到视频/字幕文件，请检查网络或 Cookie 后重试。"
                        textSize = 13f; setTextColor(ContextCompat.getColor(context, R.color.danger)); setPadding(0, 8, 0, 8)
                    })
                    root.addView(buttonSub("返回") { goBackStep() })
                }
                return@launch
            }

            // TMDB episode titles for the selected season (TMDB decides season);
            // movies have none and take the dedicated in-place movie plan
            val isMovie = show?.isMovie == true
            var epTitles: Map<Int, String>? = null
            if (!isMovie && show != null && Prefs.tmdbKey.isNotBlank()) {
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
                if (isMovie) RenamePlanner.buildMovie(items, clean)
                else RenamePlanner.build(items, clean, tpl, Prefs.seasonTemplate, userSeason, epTitles)
            }.getOrNull()
            if (plan == null) {
                if (epoch != myEpoch) return@launch
                stepHistory.clear(); pushHistory(::renderStep1Again)
                safeRender("plan-error") {
                    renderTitle()
                    root.addView(TextView(context).apply {
                        text = "生成重命名计划失败，请反馈任务日志。"
                        textSize = 13f; setTextColor(ContextCompat.getColor(context, R.color.danger)); setPadding(0, 8, 0, 8)
                    })
                    root.addView(buttonSub("返回") { goBackStep() })
                }
                return@launch
            }
            if (epoch != myEpoch) return@launch
            safeRender("step3") { renderTitle(); renderStep3Body() }
        }
    }

    private fun renderStep3Body() {
        val p = plan ?: return
        val actionable = p.actions.indices.filter { p.actions[it].error.isEmpty() }
        val checked = linkedSetOf<Int>().apply { addAll(actionable) }

        root.addView(stepHeader("第 3 步 · 预览重命名（勾选要执行的项目）"))

        // ---- non-modal TMDB season switcher ----
        // TMDB search itself never returns seasons (show-level results only),
        // and the old season-picker window was removed; these chips restore
        // season control right where the titles are visible. Tapping one
        // re-runs buildAndPreview for that season (folder items are cached).
        if (selectedShow != null && seasonList.isNotEmpty()) {
            root.addView(TextView(context).apply {
                text = "使用 TMDB 季（点击切换，自动重刷该季剧集标题）"
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.muted))
                setPadding(0, 0, 0, Ui.dp(context, 6))
            })
            val chipScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val chipRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            seasonList.forEach { s ->
                val active = s.number == tmdbSeasonSel
                val chip = TextView(context).apply {
                    text = "${s.name}·${s.episodeCount}集"
                    textSize = 13f
                    setPadding(Ui.dp(context, 14), Ui.dp(context, 7), Ui.dp(context, 14), Ui.dp(context, 7))
                    background = GradientDrawable().apply {
                        cornerRadius = Ui.dp(context, 20).toFloat()
                        setColor(
                            ContextCompat.getColor(
                                context,
                                if (active) R.color.brand_primary else R.color.surface_container_high
                            )
                        )
                    }
                    setTextColor(
                        ContextCompat.getColor(
                            context,
                            if (active) R.color.on_primary else R.color.muted
                        )
                    )
                }
                chip.setOnClickListener {
                    if (s.number != tmdbSeasonSel && selectedShow != null) {
                        tmdbSeasonSel = s.number
                        buildAndPreview(selectedShow!!.name, s.number, selectedShow)
                    }
                }
                chipRow.addView(chip, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = Ui.dp(context, 8) })
            }
            chipScroll.addView(chipRow)
            root.addView(chipScroll)
            root.addView(TextView(context).apply {
                text = ""  // breathing room below chips
                textSize = 4f
            })
        }

        val conflictIdx = p.actions.indices.filter { p.actions[it].error.isNotEmpty() }
        root.addView(TextView(context).apply {
            text = "共 ${p.actions.size} 项，可执行 ${actionable.size} 项" +
                    (if (conflictIdx.isNotEmpty()) "，${conflictIdx.size} 项异常" else "")
            textSize = 13f; setTextColor(ContextCompat.getColor(context, R.color.muted)); setPadding(0, 0, 0, 6)
        })
        if (actionable.isEmpty()) {
            root.addView(TextView(context).apply {
                text = "没有可执行项：文件名中未能解析出集数。支持格式：S01E01、第01集、EP01、" +
                        "剧名.30、剧名-30、[30]、30话、01-xxx 等，请先重命名文件后再整理。"
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
        root.addView(buttonSub("返回上一步") { goBackStep() })
        root.addView(buttonSub("取消") { dismiss() })
    }

    // ---------- Step 4: execute ----------
    private fun execute(p: RenamePlanner.PlanResult, chosen: Set<Int>) {
        executing = true
        stepHistory.clear()   // no step-back while jobs are running
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
            val myEpoch = epoch
            val fidByName = items.associateBy { it.name }
            val existingFolders = runCatching { QuarkApi.list(folder.fid) }
                .getOrDefault(emptyList()).filter { it.isFolder }.associateBy { it.name }
            val seasonFids = HashMap<String, String>()
            val usedActions = chosen.map { p.actions[it] }.filter { it.error.isEmpty() }
            // only actions that actually move (TV mode) need a Season folder;
            // movie-mode actions rename in place and never create one
            val usedFolders = usedActions.filter { it.needsMove }.map { it.seasonIdx }.distinct()
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
            executing = false
            if (epoch == myEpoch) {
                safeRender("done") { renderTitle(); renderStep4Body(success, failed) }
            }
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
        stepHistory.clear()
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

    /** Primary CTA — TextView-based for maximum device compatibility. */
    private fun button(label: String, onClick: () -> Unit) =
        Ui.primaryTextBtn(context, label, onClick)

    /** Outlined action — transparent fill + hairline border, same shape as primary. */
    private fun buttonOutline(label: String, onClick: () -> Unit) =
        Ui.outlineTextBtn(context, label, onClick)

    /** Secondary action — TextView-based for maximum device compatibility. */
    private fun buttonSub(label: String, onClick: () -> Unit) =
        Ui.secondaryTextBtn(context, label, onClick)

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
}
