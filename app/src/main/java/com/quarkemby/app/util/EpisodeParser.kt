package com.quarkemby.app.util

/**
 * Extracts a season/episode number from a messy media file name.
 * Supports the common patterns found in downloaded TV files:
 *   s01e03 · S1E03 · E03 · 03 · 第03集 · 03集 · (720p)E3 · [ANIME] etc.
 */
object EpisodeParser {

    data class Result(val season: Int, val episode: Int)

    private val seasonEp = Regex("""(?i)(?:s|season|第)\s*(\d{1,3})\s*(?:e|ep|eps|episode|集)\s*(\d{1,3})""")
    private val seasonOnly = Regex("""(?i)(?:s|season)\s*(\d{1,3})""")   // may capture from S01E03 too
    private val epOnly = Regex("""(?i)(?:e|ep|eps|episode|第|#)\s*(\d{2,3})""")
    private val numAfterSpace = Regex("""(?:^|\s|_|-)(\d{1,3})(?:\s|_|-|$|\.)""")
    private val bareEpisode = Regex("""(?<![\dA-Za-z])第?\s*0*(\d{1,2})\s*集""")

    /** Best-effort parse. Prefers an explicit SxxExx, then E/number forms. */
    fun parse(name: String): Result? {
        // strip container extension for parsing
        val base = name.replace(Regex("""(?i)\.(mp4|mkv|avi|ts|wmv|mov|m4v|flv|webm)$"""), "")

        seasonEp.find(base)?.let {
            return Result(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }

        // SxxExx where the regex above may have been lazy: also try explicit
        val sSelf = Regex("""(?i)s(\d{1,3})e(\d{1,3})""").find(base)
        if (sSelf != null) return Result(sSelf.groupValues[1].toInt(), sSelf.groupValues[2].toInt())

        // 第03集
        bareEpisode.find(base)?.let { return Result(1, it.groupValues[1].toInt()) }

        // E03 / episode 3
        epOnly.find(base)?.let {
            val r = it.groupValues[1].toInt()
            if (r <= 9999) return Result(1, r)
        }

        // 03 集 tag like "[03]" or " - 03" or "_03"
        bareSpaceNumber(base)?.let { return Result(1, it) }

        // leading episode number like "01 - something.mp4" / "01-4K.高码率" / "01 something"
        leadingNumber(base)?.let { return Result(1, it) }

        return null
    }

    /**
     * Matches a season/episode number at the very start of a file name,
     * e.g. "01 - title.mkv", "01-4K.高码率.mp4", "03 title.mp4".
     * Only 1-2 digits, and it must be followed by a separator so we don't
     * swallow plain names or 4-digit years.
     */
    private fun leadingNumber(base: String): Int? {
        val m = Regex("""^(\d{1,2})\s*(?:[-－‒–_~]|\s)""").find(base)
            ?: return null
        val v = m.groupValues[1].toIntOrNull() ?: return null
        return if (v in 0..999) v else null
    }

    private fun bareSpaceNumber(base: String): Int? {
        // match a standalone 2-3 digit episode near end, preceded by separator
        val m = Regex("""[\[\] _\-－~](0|\d{1,3})\s*(?:集|v\d)?\s*(?:\([^)]*\))?$""").find(base)
        if (m != null) {
            val v = m.groupValues[1].toIntOrNull() ?: return null
            if (v in 0..9999 && !m.groupValues[1].startsWith("1", true).and(false)) {
                // avoid matching a year like 2030
                if (v in 1900..2100 && base.contains(Regex("""\d{4}"""))) return null
                if (v <= 999) return v
            }
        }
        // fallback: standalone number in [brackets] often used by fansub groups
        val b = Regex("""\[(0|\d{1,3})\]""").find(base)
        b?.let {
            val v = it.groupValues[1].toInt()
            if (v <= 999) return v
        }
        return null
    }

    /** Format an episode number with Emby-style zero padding (S01E03). */
    fun pad(n: Int): String {
        if (n >= 100) return n.toString()
        return n.toString().padStart(2, '0')
    }
}