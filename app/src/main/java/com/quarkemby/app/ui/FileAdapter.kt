package com.quarkemby.app.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.quarkemby.app.R
import com.quarkemby.app.data.models.FileItem

/**
 * Adapter for the drive file list. Supports two modes:
 *  - normal: tap opens, long-press enters selection mode (and selects the item)
 *  - selection: tap toggles the checkbox, long-press also toggles; selected items
 *    are visually highlighted so the caller can batch delete/move.
 */
class FileAdapter(
    private val onClick: (FileItem) -> Unit,
    private val onLongClick: (FileItem) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    private val items = mutableListOf<FileItem>()
    private val selected = linkedSetOf<String>() // fids
    var selectionMode = false
        private set

    /** notified whenever selection mode or the selected-count changes (count<0 = stay) */
    var onSelectionChanged: (Int) -> Unit = {}

    fun submit(list: List<FileItem>) {
        items.clear()
        items.addAll(list)
        selected.removeAll { fid -> items.none { it.fid == fid } }
        notifyDataSetChanged()
        // report active selection count (0 if cleared by new data)
        if (selected.isNotEmpty()) onSelectionChanged(selected.size)
    }

    val selectedFids: List<String> get() = selected.toList()
    val selectedCount: Int get() = selected.size

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble(); var u = 0
        while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
        return String.format("%.1f %s", v, units[u])
    }

    // ---- selection control ----
    private fun toggle(item: FileItem) {
        if (!selected.add(item.fid)) selected.remove(item.fid)
        notifyItemChanged(items.indexOfFirst { it.fid == item.fid })
        onSelectionChanged(selected.size)
    }

    fun enterSelection() {
        if (selectionMode) return
        selectionMode = true
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    fun exitSelection() {
        if (!selectionMode && selected.isEmpty()) return
        selectionMode = false
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun selectAll() {
        val before = selected.size
        items.forEach { selected.add(it.fid) }
        if (selected.size != before) { notifyDataSetChanged(); onSelectionChanged(selected.size) }
    }

    fun invertSelection() {
        items.forEach {
            if (!selected.add(it.fid)) selected.remove(it.fid)
        }
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    private fun iconRes(item: FileItem): Int = when {
        item.isFolder -> R.drawable.ic_folder_yellow
        item.isVideo -> R.drawable.ic_video
        item.isSubtitle -> R.drawable.ic_subtitle
        else -> R.drawable.ic_file
    }

    override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_file, p, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        val checked = item.fid in selected

        h.icon.setImageResource(iconRes(item))
        h.name.text = item.name
        h.meta.text = when {
            item.isFolder -> "文件夹 · 点击进入"
            item.isSubtitle -> "字幕 · ${item.size}B"
            else -> formatSize(item.size)
        }

        // selection chrome
        h.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
        h.check.isChecked = checked
        if (selectionMode) {
            h.root.background = GradientDrawable().apply {
                cornerRadius = 12f * h.root.resources.displayMetrics.density
                val c = if (checked)
                    ContextCompat.getColor(h.root.context, R.color.brand_primary)
                else ContextCompat.getColor(h.root.context, R.color.surface)
                // checked: apply translucent alpha so row tint reads as selected
                setColor(if (checked) 0x26FFFFFF and c or 0x26000000 else c)
            }
        } else {
            h.root.background = ContextCompat.getDrawable(h.root.context, R.color.surface)
        }

        h.root.setOnClickListener {
            if (selectionMode) toggle(item) else onClick(item)
        }
        h.root.setOnLongClickListener {
            if (selectionMode) toggle(item)
            else onLongClick(item)
            true
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v
        val check: CheckBox = v.findViewById(R.id.file_check)
        val icon: ImageView = v.findViewById(R.id.file_icon)
        val name: TextView = v.findViewById(R.id.file_name)
        val meta: TextView = v.findViewById(R.id.file_meta)
    }
}