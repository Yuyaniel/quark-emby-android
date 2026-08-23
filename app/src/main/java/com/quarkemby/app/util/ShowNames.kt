package com.quarkemby.app.util

/**
 * Cleans a folder name before it is auto-filled as the show name.
 *   "九门(2026)"                       -> "九门"
 *   "候车室的故事 (2002) {tmdb-111436}" -> "候车室的故事"
 *   "濑户的花嫁【2024】(1080p)"         -> "濑户的花嫁"
 */
object ShowNames {

    /** full-width/half-width bracket pairs containing a 4-digit year or quality tags */
    private val yearBracket = Regex("""\s*[(（\[【][^()（）\[\]【】]*\d{4}[^()（）\[\]【】]*[)）\]】]""")
    private val qualityBracket = Regex("""\s*[(（\[【]\s*(4K|8K|2160p|1080p|720p|HDR|60fps|高清|蓝光|重制|修复)[^()（）\[\]【】]*[)）\]】]""", RegexOption.IGNORE_CASE)
    /** trailing metadata tags like {tmdb-111436} or {tvdb-123} */
    private val metaTag = Regex("""\s*\{[^{}]*}""")
    /** year standing alone at the end of the name */
    private val trailingYear = Regex("""\s*[\[（(]?\b(19|20)\d{2}\b[)）\]]?\s*$""")

    fun clean(raw: String): String {
        var out = raw.trim()
        out = metaTag.replace(out, "")
        out = yearBracket.replace(out, "")
        out = qualityBracket.replace(out, "")
        out = trailingYear.replace(out, "")
        // collapse whitespace runs and trim separator leftovers
        out = out.replace(Regex("""\s{2,}"""), " ").trim()
        out = out.trimEnd('-', '_', '·', '，', ',').trim()
        return out
    }
}