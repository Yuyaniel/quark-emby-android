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

        return PlanResult(actions.filter { it.needsRename || it.needsMove }, folders)
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