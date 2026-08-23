package com.quarkemby.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.quarkemby.app.R
import com.quarkemby.app.data.models.FileItem

class FileAdapter(
    private val onClick: (FileItem) -> Unit,
    private val onLongClick: (FileItem) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    private val items = mutableListOf<FileItem>()

    fun submit(list: List<FileItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble(); var u = 0
        while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
        return String.format("%.1f %s", v, units[u])
    }

    private fun icon(item: FileItem): String = when {
        item.isFolder -> "📁"
        item.isVideo -> "🎬"
        item.isSubtitle -> "🔤"
        else -> "📄"
    }

    override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_file, p, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.icon.text = icon(item)
        h.name.text = item.name
        h.meta.text = when {
            item.isFolder -> "文件夹 · 点击进入"
            item.isSubtitle -> "字幕 · ${item.size}B"
            else -> formatSize(item.size)
        }
        h.root.setOnClickListener { onClick(item) }
        h.root.setOnLongClickListener { onLongClick(item); true }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v
        val icon: TextView = v.findViewById(R.id.file_icon)
        val name: TextView = v.findViewById(R.id.file_name)
        val meta: TextView = v.findViewById(R.id.file_meta)
    }
}