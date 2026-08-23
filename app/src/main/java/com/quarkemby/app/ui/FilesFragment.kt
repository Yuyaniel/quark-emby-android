package com.quarkemby.app.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.quarkemby.app.MainActivity
import com.quarkemby.app.R
import com.quarkemby.app.data.Prefs
import com.quarkemby.app.data.QuarkApi
import com.quarkemby.app.data.models.FileItem
import com.quarkemby.app.databinding.FragmentFilesBinding
import kotlinx.coroutines.launch

class FilesFragment : Fragment() {

    private var _b: FragmentFilesBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: FileAdapter

    private val navStack = mutableListOf<String>()      // parent fids
    private val nameStack = mutableListOf<String>()     // names
    private var currentFid = ""                          // "" = root
    private var loadedItems: List<FileItem> = emptyList()
    /** Home-folder jump must happen ONCE per fragment instance. Without this
     *  flag every view recreation (returning from Settings/Log) re-applied
     *  the jump and the user was yanked off the root directory. */
    private var initialNavDone = false

    private lateinit var backCallback: OnBackPressedCallback

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentFilesBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        adapter = FileAdapter(onClick = ::enterItem, onLongClick = ::openMenu)
        b.fileList.layoutManager = LinearLayoutManager(requireContext())
        b.fileList.adapter = adapter
        // let items scroll under the floating bottom nav pill
        b.fileList.clipToPadding = false
        b.fileList.setPadding(
            b.fileList.paddingLeft, b.fileList.paddingTop,
            b.fileList.paddingRight, Ui.dp(requireContext(), 92)
        )
        // soft vertical gaps between list cards
        b.fileList.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: android.graphics.Rect, view: View,
                parent: RecyclerView, state: RecyclerView.State
            ) {
                val pos = parent.getChildAdapterPosition(view)
                outRect.top = if (pos == 0) Ui.dp(requireContext(), 10) else Ui.dp(requireContext(), 5)
                outRect.bottom = Ui.dp(requireContext(), 5)
            }
        })

        b.backBtn.setOnClickListener { goUp() }
        b.refreshBtn.setOnClickListener { load() }
        b.sortBtn.setOnClickListener { showSortDialog() }

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = goUp()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        if (!initialNavDone && navStack.isEmpty() && Prefs.hasHomeFolder) {
            initialNavDone = true
            applyHomeFolder()
        } else {
            updatePath()
            load()
        }
    }

    /** Jump into the saved home folder, remembering the way back to root. */
    private fun applyHomeFolder() {
        navStack.clear(); nameStack.clear()
        navStack.add("0")
        nameStack.add(Prefs.homeFolderName.ifBlank { "首页目录" })
        currentFid = Prefs.homeFolderFid
        updatePath()
        load()
    }

    // ---------- breadcrumb ----------
    /**
     * Renders "根目录 > 影视 > 濑户的花嫁" as clickable segments.
     * Long paths collapse the middle into an ellipsis. Clicking a segment
     * jumps directly to that folder.
     */
    private fun updatePath() {
        b.crumbRow.removeAllViews()
        val segments = mutableListOf<String>()
        segments.add("根目录")
        segments.addAll(nameStack)
        // collapse middle levels when the path gets long
        val display: List<Pair<String, Int>> =
            if (segments.size > 4) {
                val keep = listOf(0, 1, segments.size - 2, segments.size - 1)
                val out = mutableListOf<Pair<String, Int>>()
                var last = -2
                keep.forEach { i ->
                    if (i - last > 1) out.add("…" to -1)
                    out.add(segments[i] to i)
                    last = i
                }
                out
            } else {
                segments.mapIndexed { i, t -> t to i }
            }

        display.forEachIndexed { di, (label, segIdx) ->
            if (di > 0) b.crumbRow.addView(separator())
            val isLast = di == display.size - 1
            b.crumbRow.addView(crumbLabel(label, segIdx, isLast))
        }
        // auto-scroll to the newest (right-most) segment
        b.crumbScroll.post { b.crumbScroll.fullScroll(View.FOCUS_RIGHT) }

        val canGoUp = navStack.isNotEmpty()
        b.backBtn.visibility = if (canGoUp) View.VISIBLE else View.GONE
        if (::backCallback.isInitialized) backCallback.isEnabled = canGoUp
    }

    private fun separator(): TextView = TextView(requireContext()).apply {
        text = "›"
        textSize = 15f
        // lowered opacity per spec
        setTextColor(
            ColorUtils.setAlphaComponent(
                ContextCompat.getColor(requireContext(), R.color.ink), 0x88
            )
        )
        setPadding(Ui.dp(requireContext(), 2), 0, Ui.dp(requireContext(), 2), 0)
    }

    private fun crumbLabel(label: String, segIdx: Int, isLast: Boolean): TextView =
        TextView(requireContext()).apply {
            text = label
            textSize = if (isLast) 17f else 15f
            typeface = if (isLast) android.graphics.Typeface.DEFAULT_BOLD else null
            maxLines = 1
            ellipsize = if (isLast) android.text.TextUtils.TruncateAt.MIDDLE else null
            maxWidth = Ui.dp(requireContext(), 180)
            setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (isLast) R.color.ink else R.color.muted
                )
            )
            setPadding(Ui.dp(requireContext(), 6), Ui.dp(requireContext(), 10), Ui.dp(requireContext(), 6), Ui.dp(requireContext(), 10))
            background = ContextCompat.getDrawable(requireContext(), R.drawable.ripple_fg)
            isClickable = true
            setOnClickListener {
                if (segIdx == -1) return@setOnClickListener
                jumpToSegment(segIdx)
            }
        }

    /**
     * Jump to the folder represented by breadcrumb segment index.
     * 0 = root; k>=1 maps to nameStack[k-1].
     */
    private fun jumpToSegment(segIdx: Int) {
        if (segIdx == 0) {
            if (currentFid.isBlank() && navStack.isEmpty()) return
            navStack.clear(); nameStack.clear(); currentFid = ""
        } else {
            val j = segIdx - 1
            if (j >= nameStack.size - 1) return // already there
            val targetFid = if (j == nameStack.size - 1) currentFid else navStack[j + 1]
            while (nameStack.size > j + 1) {
                nameStack.removeAt(nameStack.size - 1)
                navStack.removeAt(navStack.size - 1)
            }
            currentFid = targetFid
        }
        updatePath()
        load()
    }

    // ---------- loading + sorting ----------
    private fun load() = lifecycleScope.launch {
        // Rapid tab taps may destroy the view while this coroutine runs;
        // capture the binding (or bail out) so we never hit _b!! NPE.
        val vb = _b ?: return@launch
        vb.loading.visibility = View.VISIBLE
        vb.errorView.visibility = View.GONE
        vb.emptyView.visibility = View.GONE
        try {
            val items = QuarkApi.list(currentFid)
            loadedItems = sort(items)
            adapter.submit(loadedItems)
            if (_b != null) {
                b.emptyView.visibility = if (loadedItems.isEmpty()) View.VISIBLE else View.GONE
            }
        } catch (e: Exception) {
            if (e.message?.contains("失效") == true) {
                MainActivity.INSTANCE.showLogin()
            } else if (_b != null) {
                b.errorView.text = e.message ?: "加载失败"
                b.errorView.visibility = View.VISIBLE
            }
        } finally {
            // view may be gone by now (fragment replaced mid-flight)
            _b?.loading?.visibility = View.GONE
        }
    }

    /** Apply the saved sort preference while keeping folders grouped on top. */
    private fun sort(items: List<FileItem>): List<FileItem> {
        val (folders, files) = items.partition { it.isFolder }
        val key = Prefs.sortKey
        val asc = Prefs.sortAsc
        fun cmp(a: FileItem, b: FileItem): Int = when (key) {
            "size" -> a.size.compareTo(b.size)
            "time" -> a.updatedAt.compareTo(b.updatedAt)
            else -> a.name.lowercase().compareTo(b.name.lowercase())
        }
        val dir = if (asc) 1 else -1
        fun sortGroup(g: List<FileItem>) = g.sortedWith(Comparator { a, b -> cmp(a, b) * dir })
        return sortGroup(folders) + sortGroup(files)
    }

    private fun showSortDialog() {
        val opts = listOf(
            Triple("name", true, "名称 升序 (A→Z)"),
            Triple("name", false, "名称 降序 (Z→A)"),
            Triple("size", false, "大小 降序 (大→小)"),
            Triple("size", true, "大小 升序 (小→大)"),
            Triple("time", false, "修改时间 降序 (新→旧)"),
            Triple("time", true, "修改时间 升序 (旧→新)")
        )
        val choice = arrayOf(opts.indices.firstOrNull {
            opts[it].first == Prefs.sortKey && opts[it].second == Prefs.sortAsc
        } ?: 0)
        AlertDialog.Builder(requireContext())
            .setTitle("排序方式")
            .setSingleChoiceItems(opts.map { it.third }.toTypedArray(), choice[0]) { d, which ->
                val (k, asc, _) = opts[which]
                Prefs.sortKey = k; Prefs.sortAsc = asc
                loadedItems = sort(loadedItems)
                adapter.submit(loadedItems)
                d.dismiss()
                toast("已排序")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- navigation ----------
    private fun enterItem(item: FileItem) {
        if (!item.isFolder) { toast("文件：${item.name}"); return }
        navStack.add(currentFid)
        nameStack.add(item.name)
        currentFid = item.fid
        updatePath()
        load()
    }

    private fun goUp() {
        if (navStack.isEmpty()) return
        currentFid = navStack.removeAt(navStack.size - 1)
        nameStack.removeAt(nameStack.size - 1)
        updatePath()
        load()
    }

    // ---------- item menu (scrim + blur dialog) ----------
    private fun openMenu(item: FileItem) {
        val dlg = android.app.Dialog(requireContext())
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val col = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            // unified dialog padding (all dp) so title and rows share one grid
            setPadding(
                Ui.dp(requireContext(), 20), Ui.dp(requireContext(), 18),
                Ui.dp(requireContext(), 20), Ui.dp(requireContext(), 16)
            )
            setBackgroundResource(R.drawable.bg_scrim_dialog)
            addView(menuTitle(item))
            // ---- common actions ----
            if (item.isFolder) {
                addView(menuRow("批量重命名", "剧集整理 · 核心功能") {
                    dlg.dismiss()
                    // plain Dialog, same verified plumbing as this menu
                    RenameWizard(requireActivity(), item).show()
                })
            }
            addView(menuRow("重命名", "手动修改名称") { dlg.dismiss(); showRename(item) })
            addView(menuRow("移动到", "选择网盘内目标目录") { dlg.dismiss(); showMove(item) })
            if (item.isFolder) {
                addView(menuRow("设为首页目录", "打开应用后默认进入此文件夹") {
                    dlg.dismiss(); setAsHome(item)
                })
            }
            // ---- destructive group, visually separated ----
            addView(menuDivider())
            addView(menuRow("删除", "二次确认后移除", danger = true) { dlg.dismiss(); confirmDelete(item) })
        }
        dlg.setContentView(col)
        Ui.centerWindow(dlg, 0.88f)
        Ui.applyScrim(dlg)
        dlg.show()
    }

    /**
     * Dialog header (folder name). Left/right padding matches the inner
     * padding of the menu cards, so title text aligns with row text.
     */
    private fun menuTitle(item: FileItem): TextView = Ui.title(requireContext(), item.name, 17f).apply {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        setPadding(
            Ui.dp(requireContext(), 14), 0,
            Ui.dp(requireContext(), 14), Ui.dp(requireContext(), 4)
        )
    }

    /** Thin hairline separating the destructive group from common actions. */
    private fun menuDivider(): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(requireContext(), 1)
        ).apply { setMargins(0, Ui.dp(requireContext(), 6), 0, Ui.dp(requireContext(), 6)) }
        setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.outline_variant))
    }

    /**
     * Text-only menu row rendered as a subtle card: faint background +
     * 1dp hairline border + rounded corners. Every row gets the same card
     * treatment (danger rows tinted red) so click boundaries are obvious,
     * and uniform dp(4) vertical margins visually isolate the options.
     */
    private fun menuRow(
        title: String, sub: String,
        danger: Boolean = false, action: () -> Unit
    ): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(
            Ui.dp(requireContext(), 14), Ui.dp(requireContext(), 11),
            Ui.dp(requireContext(), 14), Ui.dp(requireContext(), 11)
        )
        foreground = ContextCompat.getDrawable(requireContext(), R.drawable.ripple_fg)
        isClickable = true
        background = GradientDrawable().apply {
            cornerRadius = Ui.dp(requireContext(), 12).toFloat()
            setColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (danger) R.color.danger_container else R.color.surface_container_high
                )
            )
            setStroke(
                Ui.dp(requireContext(), 1),
                ContextCompat.getColor(requireContext(), R.color.outline_variant)
            )
        }
        setOnClickListener { action() }
        val col = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.title(requireContext(), title, 15f).apply {
            if (danger) setTextColor(ContextCompat.getColor(requireContext(), R.color.danger))
        })
        col.addView(Ui.helper(requireContext(), sub).apply {
            if (danger) setTextColor(ContextCompat.getColor(requireContext(), R.color.danger))
        })
        addView(col)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, Ui.dp(requireContext(), 4), 0, Ui.dp(requireContext(), 4)) }
    }

    private fun setAsHome(item: FileItem) {
        Prefs.homeFolderFid = item.fid
        Prefs.homeFolderName = item.name
        toast("已将“${item.name}”设为首页目录")
    }

    // ---- Rename (themed dark dialog) ----
    // Fixed dark palette: the panel is always dark (like the scrim menu),
    // independent of the system day/night theme.

    // headline: brighter than body text for emphasis
    private val renameTitleColor = 0xFFEEF1F8.toInt()
    // input body text / hint
    private val renameInputColor = 0xFFE3E6EC.toInt()
    private val renameHintColor = 0xFFA6ADBB.toInt()
    // hairline divider inside the panel
    private val renameDividerColor = 0xFF2E3440.toInt()
    // cancel label (secondary) / confirm label (dark-on-accent)
    private val renameCancelColor = 0xFFA6ADBB.toInt()
    private val renameConfirmColor = 0xFF1B2842.toInt()

    private fun showRename(item: FileItem) {
        val dlg = android.app.Dialog(requireContext())
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val col = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            // generous padding on all sides so controls never feel cramped
            setPadding(
                Ui.dp(requireContext(), 28), Ui.dp(requireContext(), 32),
                Ui.dp(requireContext(), 28), Ui.dp(requireContext(), 24)
            )
            setBackgroundResource(R.drawable.bg_rename_dialog)
        }

        // headline: enlarged, highlighted light tone
        col.addView(TextView(requireContext()).apply {
            text = "重命名"
            textSize = 22f
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(renameTitleColor)
        })

        // dark input: dark surface + light text + low-saturation accent border,
        // taller vertical padding for a comfortable touch target
        val input = EditText(requireContext()).apply {
            setText(item.name)
            setSingleLine(true)
            hint = "新名称"
            textSize = 16f
            setTextColor(renameInputColor)
            setHintTextColor(renameHintColor)
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_rename_input)
            setPadding(
                Ui.dp(requireContext(), 16), Ui.dp(requireContext(), 18),
                Ui.dp(requireContext(), 16), Ui.dp(requireContext(), 18)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(requireContext(), 20) }
        }
        col.addView(input)

        // divider above the button row
        col.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(requireContext(), 1)
            ).apply { setMargins(0, Ui.dp(requireContext(), 24), 0, Ui.dp(requireContext(), 12)) }
            setBackgroundColor(renameDividerColor)
        })

        // horizontal button row at the bottom
        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnRow.addView(renameBtn("取消", renameCancelColor, R.drawable.bg_rename_btn_secondary) { dlg.dismiss() })
        btnRow.addView(renameBtn("确定", renameConfirmColor, R.drawable.bg_rename_btn_primary) {
            val name = input.text.toString().trim()
            if (name.isEmpty()) { toast("名称不能为空"); return@renameBtn }
            lifecycleScope.launch {
                try { QuarkApi.rename(item.fid, name); load() }
                catch (e: Exception) { toast(e.message ?: "重命名失败") }
            }
            dlg.dismiss()
        })
        col.addView(btnRow)

        dlg.setContentView(col)
        Ui.centerWindow(dlg, 0.88f)
        Ui.applyScrim(dlg)
        dlg.show()
    }

    /** Pill-shaped text button. [labelColor] picks secondary vs confirm tone;
     *  [bgRes] pairs it with the outline (cancel) or filled accent (confirm)
     *  background. min 48dp tall for a comfortable touch target. */
    private fun renameBtn(label: String, labelColor: Int, bgRes: Int, onClick: () -> Unit): TextView =
        TextView(requireContext()).apply {
            text = label
            textSize = 15f
            isClickable = true
            isFocusable = true
            gravity = Gravity.CENTER
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(labelColor)
            background = ContextCompat.getDrawable(requireContext(), bgRes)
            minHeight = Ui.dp(requireContext(), 48)
            minWidth = Ui.dp(requireContext(), 96)
            setPadding(
                Ui.dp(requireContext(), 20), Ui.dp(requireContext(), 12),
                Ui.dp(requireContext(), 20), Ui.dp(requireContext(), 12)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Ui.dp(requireContext(), 12) }
            setOnClickListener { onClick() }
        }

    // ---- Delete ----
    private fun confirmDelete(item: FileItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除")
            .setMessage("确定删除「${item.name}」？删除不可恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try { QuarkApi.delete(listOf(item.fid)); load() }
                    catch (e: Exception) { toast(e.message ?: "删除失败") }
                }
            }
            .show()
    }

    // ---- Move (single) ----
    private fun showMove(item: FileItem) {
        showMovePicker("移动到 · ${item.name}") { dst ->
            lifecycleScope.launch {
                try { QuarkApi.move(listOf(item.fid), dst); load() }
                catch (e: Exception) { toast(e.message ?: "移动失败") }
            }
        }
    }

    /** Generic folder picker that runs `commit(dstFid)` for the chosen target. */
    private fun showMovePicker(title: String, commit: (String) -> Unit) {
        val stack = mutableListOf("")
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setNegativeButton("取消", null)
            .setPositiveButton("移动到这里") { _, _ -> commit(stack.last()) }
            .create()

        val body = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val pathTxt = TextView(requireContext()).apply {
            textSize = 13f; setTextColor(resources.getColor(R.color.muted, null)); setPadding(16, 4, 16, 4)
        }
        val upBtn = TextView(requireContext()).apply {
            text = "‹ 上级目录"; textSize = 14f
            setTextColor(resources.getColor(R.color.brand_primary, null)); setPadding(16, 12, 16, 4)
        }
        val listWrap = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16, 4, 16, 4)
        }

        fun refresh() {
            lifecycleScope.launch {
                try {
                    val fid = stack.last()
                    pathTxt.text = buildPath(stack)
                    val kids = QuarkApi.list(fid).filter { it.isFolder }
                    listWrap.removeAllViews()
                    if (kids.isEmpty()) {
                        listWrap.addView(TextView(requireContext()).apply {
                            text = "此目录下无子文件夹 · 可直接移动到这里"
                            textSize = 12f; setTextColor(resources.getColor(R.color.muted, null))
                        })
                    }
                    kids.forEach { f ->
                        val row = TextView(requireContext()).apply {
                            text = "  ${f.name}"; textSize = 15f
                            setTextColor(resources.getColor(R.color.ink, null)); setPadding(8, 12, 8, 12)
                        }
                        row.setOnClickListener { stack.add(f.fid); refresh() }
                        listWrap.addView(row)
                    }
                } catch (e: Exception) { toast(e.message ?: "加载失败") }
            }
        }

        upBtn.setOnClickListener {
            if (stack.size > 1) { stack.removeAt(stack.size - 1); refresh() }
        }
        body.addView(upBtn)
        body.addView(pathTxt)
        body.addView(listWrap)
        dialog.setView(body)

        refresh()
        dialog.show()
    }

    private fun buildPath(stack: List<String>): String =
        if (stack.size <= 1) "目标：根目录" else "目标：根目录 / 子目录（${stack.size - 1} 层）"

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}