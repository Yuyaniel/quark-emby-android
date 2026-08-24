package com.quarkemby.app.util

import com.quarkemby.app.data.models.FileItem
import com.quarkemby.app.data.models.RenameAction

/**
 * Builds the Emby-standard rename/move plan for a set of files inside a show
 * folder. Renders the display template and detects duplicate-episode conflicts
 * before anything is written to the drive.
 */
object RenamePlanner {

    class PlanResult(val actions: List<RenameAction>, val foldersNeeded: List<String>)

    /**
     * @param userSeason season number typed by the user; when set it overrides
     *        whatever season the parser inferred from each file name.
     * @param epTitles optional TMDB episode titles (episode -> title) merged
     *        into the name via the {ep_title} placeholder.
     */
    fun build(
        items: List<FileItem>,
        showName: String,
        renameTemplate: String,
        seasonTemplate: String,
        userSeason: Int? = null,
        epTitles: Map<Int, String>? = null
    ): PlanResult {
        val videos = items.filter { it.isVideo }
        val subs = items.filter { it.isSubtitle }

        // Parse episodes for each video: pair (parsedSeason, ep)
        val parsedVideos = videos.mapNotNull { v ->
            val p = EpisodeParser.parse(v.name) ?: return@mapNotNull null
            v to p
        }

        val actions = mutableListOf<RenameAction>()
        val usedTargets = HashMap<String, FileItem>() // base+season -> source
        val seasonsUsed = HashSet<Int>()

        // Map videos
        for ((video, parsed) in parsedVideos) {
            val season = userSeason ?: parsed.season.coerceAtLeast(1)
            seasonsUsed.add(season)
            val baseNoExt = video.name.substringBeforeLast('.')
            val title = epTitles?.get(parsed.episode)?.let { sanitizeTitle(it) }
            val newBase = applyTemplate(
                renameTemplate, showName,
                EpisodeParser.pad(season), EpisodeParser.pad(parsed.episode),
                title
            )
            val newName = newBase + video.ext
            val key = "${season}|${newName.lowercase()}"
            val conflict = usedTargets.containsKey(key)
            if (conflict) {
                // keep first, mark this one as conflict (skip from plan)
            } else {
                usedTargets[key] = video
            }
            actions.add(
                RenameAction(
                    oldName = video.name,
                    newName = newName,
                    seasonIdx = season - 1,
                    isSubtitle = false,
                    needsRename = newName != video.name,
                    needsMove = true,
                    conflict = conflict,
                    error = if (conflict) "与已有条目集号冲突，请手动修正" else ""
                )
            )
            // attach matching subtitle
            val sub = subs.firstOrNull {
                it.name.startsWith(baseNoExt, ignoreCase = true) || baseNoExt.startsWith(it.name.substringBeforeLast('.'), ignoreCase = true)
            }
            if (sub != null) {
                actions.add(
                    RenameAction(
                        oldName = sub.name,
                        newName = newBase + sub.ext,
                        seasonIdx = season - 1,
                        isSubtitle = true,
                        needsRename = true,
                        needsMove = true,
                        error = ""
                    )
                )
            }
        }

        // Videos we could not parse: leave them listed but excluded from plan
        val parsedNames = parsedVideos.map { it.first.fid }.toSet()
        videos.filter { it.fid !in parsedNames }.forEach {
            actions.add(
                RenameAction(
                    oldName = it.name, newName = "", seasonIdx = 0,
                    isSubtitle = false, needsRename = false, needsMove = false,
                    error = "未能识别集数，已跳过"
                )
            )
        }

        val folders = seasonsUsed.sorted().map { s ->
            seasonTemplate.replace("{ss}", EpisodeParser.pad(s))
        }

        // KEEP unparseable/conflicting rows: the preview must show why a file
        // was skipped, otherwise the dialog looks empty ("识别不到剧集").
        return PlanResult(actions, folders)
    }

    /**
     * Movie mode: no episodes, no season folders.
     *  - single video  -> "Movie Name.ext"
     *  - N videos      -> "Movie Name.CD1.ext", "Movie Name.CD2.ext" … (by name)
     *  - subtitles follow their matching video base name, as in TV mode.
     * Files are renamed IN PLACE (needsMove = false), so no Season folder
     * is ever created for a movie.
     */
    fun buildMovie(items: List<FileItem>, movieName: String): PlanResult {
        val videos = items.filter { it.isVideo }.sortedBy { it.name.lowercase() }
        val subs = items.filter { it.isSubtitle }

        val actions = mutableListOf<RenameAction>()
        val usedTargets = HashSet<String>()

        videos.forEachIndexed { i, v ->
            val base = if (videos.size == 1) movieName else "$movieName.CD${i + 1}"
            val newName = base + v.ext
            val conflict = !usedTargets.add(newName.lowercase())
            actions.add(
                RenameAction(
                    oldName = v.name,
                    newName = newName,
                    seasonIdx = 0,
                    isSubtitle = false,
                    needsRename = newName != v.name,
                    needsMove = false,
                    conflict = conflict,
                    error = if (conflict) "目标名称冲突，请手动修正" else ""
                )
            )
            // attach matching subtitle (same prefix rule as TV mode)
            val baseNoExt = v.name.substringBeforeLast('.')
            val sub = subs.firstOrNull {
                it.name.startsWith(baseNoExt, ignoreCase = true) ||
                    baseNoExt.startsWith(it.name.substringBeforeLast('.'), ignoreCase = true)
            }
            if (sub != null) {
                actions.add(
                    RenameAction(
                        oldName = sub.name,
                        newName = base + sub.ext,
                        seasonIdx = 0,
                        isSubtitle = true,
                        needsRename = true,
                        needsMove = false,
                        error = ""
                    )
                )
            }
        }

        // subtitles that matched no video: keep them visible with a reason
        val matchedSubs = actions.filter { it.isSubtitle }.map { it.oldName }.toSet()
        subs.filter { it.name !in matchedSubs }.forEach {
            actions.add(
                RenameAction(
                    oldName = it.name, newName = "", seasonIdx = 0,
                    isSubtitle = true, needsRename = false, needsMove = false,
                    error = "未能匹配到视频，已跳过"
                )
            )
        }

        return PlanResult(actions, emptyList())
    }

    /** Formats a single Emby-style file base name using the user template. */
    private fun applyTemplate(tpl: String, show: String, ss: String, ee: String, epTitle: String? = null): String {
        val mapped = HashMap<String, String>()
        mapped["show_name"] = show
        mapped["ss"] = ss
        mapped["ee"] = ee
        mapped["ep_title"] = epTitle ?: ""
        var out = tpl
        mapped.forEach { (k, v) -> out = out.replace("{$k}", v) }
        // drop separator leftovers when the title placeholder was empty
        out = out.replace(Regex("""[.\s_-]{2,}"""), ".")
        out = out.trim('.', ' ', '-', '_')
        // sanitize illegal filename chars
        return out.replace(Regex("""[<>:"/\\|?*]"""), "_").trim()
    }

    /** Makes a TMDB episode title safe (and reasonably short) for a filename. */
    private fun sanitizeTitle(t: String): String =
        t.replace(Regex("""[<>:"/\\|?*？：]"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .take(80)
}
