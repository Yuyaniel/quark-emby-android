package com.quarkemby.app.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
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

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentFilesBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        adapter = FileAdapter(onClick = ::enterItem, onLongClick = ::openMenu)
        b.fileList.layoutManager = LinearLayoutManager(requireContext())
        b.fileList.adapter = adapter
        b.backBtn.setOnClickListener { goUp() }
        b.refreshBtn.setOnClickListener { load() }
        updatePath()
        load()
    }

    private fun updatePath() {
        val p = if (nameStack.isEmpty()) "根目录" else nameStack.joinToString(" / ")
        b.pathText.text = p
        b.backBtn.visibility = if (navStack.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun load() = lifecycleScope.launch {
        b.loading.visibility = View.VISIBLE
        b.errorView.visibility = View.GONE
        b.emptyView.visibility = View.GONE
        try {
            val items = QuarkApi.list(currentFid)
            adapter.submit(items)
            b.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        } catch (e: Exception) {
            if (e.message?.contains("失效") == true) {
                MainActivity.INSTANCE.showLogin()
            } else {
                b.errorView.text = e.message ?: "加载失败"
                b.errorView.visibility = View.VISIBLE
            }
        } finally {
            b.loading.visibility = View.GONE
        }
    }

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

    private fun openMenu(item: FileItem) {
        val sheet = BottomSheetDialog(requireContext())
        val col = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 24)
            setBackgroundResource(R.color.surface)
            addView(menuTitle(item))
            if (item.isFolder) {
                addView(menuRow("🎬", "Emby 批量重命名", "剧集整理 · 核心功能") {
                    sheet.dismiss()
                    RenameWizardFragment.newInstance(item).show(childFragmentManager, "rename_wizard")
                })
            }
            addView(menuRow("✏️", "重命名", "手动修改名称") { sheet.dismiss(); showRename(item) })
            addView(menuRow("📂", "移动到", "选择网盘内目标目录") { sheet.dismiss(); showMove(item) })
            addView(menuRow("🗑️", "删除", "二次确认后移除") { sheet.dismiss(); confirmDelete(item) })
        }
        sheet.setContentView(col)
        sheet.show()
    }

    private fun menuTitle(item: FileItem): TextView = TextView(requireContext()).apply {
        text = item.name
        textSize = 16f
        setTextColor(resources.getColor(R.color.ink, null))
        setPadding(6, 0, 6, 14)
    }

    private fun menuRow(icon: String, title: String, sub: String, action: () -> Unit): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 12, 4, 12)
            setOnClickListener { action() }
            addView(TextView(context).apply { text = icon; textSize = 18f; setPadding(0, 0, 14, 0) })
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(resources.getColor(R.color.ink, null)); setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD) })
            col.addView(TextView(context).apply { text = sub; textSize = 12f; setTextColor(resources.getColor(R.color.muted, null)) })
            addView(col)
        }

    // ---- Rename ----
    private fun showRename(item: FileItem) {
        val input = EditText(requireContext()).apply {
            setText(item.name)
            setSingleLine(true)
            hint = "新名称"
        }
        val pad = (24 * resources.displayMetrics.density + 0.5f).toInt()
        AlertDialog.Builder(requireContext())
            .setTitle("重命名")
            .setView(input, pad, 0, pad, 0)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { toast("名称不能为空"); return@setPositiveButton }
                lifecycleScope.launch {
                    try { QuarkApi.rename(item.fid, name); load() }
                    catch (e: Exception) { toast(e.message ?: "重命名失败") }
                }
            }
            .show()
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

    // ---- Move ----
    private fun showMove(item: FileItem) {
        // Browsing stack of folder fids; "" = root. Destination = top of stack.
        val stack = mutableListOf("")
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("移动到 · ${item.name}")
            .setNegativeButton("取消", null)
            .setPositiveButton("移动到这里") { _, _ ->
                val dst = stack.last()
                lifecycleScope.launch {
                    try { QuarkApi.move(listOf(item.fid), dst); load() }
                    catch (e: Exception) { toast(e.message ?: "移动失败") }
                }
            }
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
                            text = "📁  ${f.name}"; textSize = 15f
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