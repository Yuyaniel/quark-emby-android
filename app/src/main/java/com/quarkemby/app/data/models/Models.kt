package com.quarkemby.app.data.models

/** A single item (folder or file) inside Quark drive. */
data class FileItem(
    val fid: String,
    val name: String,
    val type: Int,          // 0=folder, 1=file
    val size: Long = 0L,
    val path: String = "",  // full drive path
    val ext: String = "",   // lowercased extension incl dot, e.g. ".mp4"
    val updatedAt: Long = 0L // last modified unix ms
) {
    val isFolder: Boolean get() = type == 0
    val isVideo: Boolean
        get() = !isFolder && VIDEO_EXTS.contains(ext)
    val isSubtitle: Boolean
        get() = !isFolder && SUB_EXTS.contains(ext)

    companion object {
        // broad video extension set so uncommon containers (.rmvb/.iso/.ts
        // variants) are still recognized as videos by the rename planner
        val VIDEO_EXTS = setOf(
            ".mp4", ".mkv", ".avi", ".ts", ".wmv", ".mov", ".m4v", ".flv",
            ".webm", ".rmvb", ".rm", ".iso", ".mpg", ".mpeg", ".mpe", ".m2ts",
            ".mts", ".3gp", ".vob", ".tp", ".asf", ".divx", ".f4v", ".ogm"
        )
        val SUB_EXTS = setOf(".ass", ".srt", ".ssa", ".vtt", ".sub")
    }
}

/** One planned file change, shown in the preview before writing to drive. */
data class RenameAction(
    val oldName: String,
    val newName: String,     // may be empty if only moved
    val seasonIdx: Int,      // 0-based internal index -> "Season 01"
    val isSubtitle: Boolean,
    val needsRename: Boolean,
    val needsMove: Boolean,
    val conflict: Boolean = false,
    val error: String = ""
)

/** Log entry written after a batch job. */
data class JobLogEntry(
    val id: String,
    val time: String,
    val title: String,
    val summary: String,   // e.g. 成功 3 · 失败 1
    val detail: String
)