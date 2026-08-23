package com.quarkemby.app.util

/**
 * Cleans a folder name before it is auto-filled as the show name.
 *   "九门(2026)"                       -> "九门"
 *   "候车室的故事 (2002) {tmdb-111436}" -> "候车室的故事"
 *   "濑户的花嫁【2024】(1080p)"         -> "濑户的花嫁"
 *   "毛骗２０１０"                      -> "毛骗"   (full-width digits)
 */
object ShowNames {

    /** full-width/half-width bracket pairs containing a 4-digit year or quality tags */
    private val yearBracket = Regex("""\s*[(（\[【][^()（）\[\]【】]*\d{4}[^()（）\[\]【】]*[)）\]】]""")
    private val qualityBracket = Regex("""\s*[(（\[【]\s*(4K|8K|2160p|1080p|720p|HDR|60fps|高清|蓝光|重制|修复)[^()（）\[\]【】]*[)）\]】]""", RegexOption.IGNORE_CASE)
    /** trailing metadata tags like {tmdb-111436} or {tvdb-123} */
    private val metaTag = Regex("""\s*\{[^{}]*}""")
    /** year standing alone at the end of the name */
    private val trailingYear = Regex("""\s*[\[（(]?\b(19|20)\d{2}\b[)）\]]?\s*$""")
    /** any standalone 4-digit year elsewhere in the name (not glued to letters/digits) */
    private val looseYear = Regex("""(?<![0-9A-Za-z])(19|20)\d{2}(?![0-9A-Za-z])""")
    /** bracket pairs left EMPTY by the removals above — never keep stray "()" */
    private val emptyBracket = Regex("""\s*[(（\[【]\s*[)）\]】]""")
    private val fullWidthDigit = Regex("""[０-９]""")

    /** full-width digits ０-９ -> 0-9 so \d patterns always match */
    private fun normalizeDigits(s: String): String =
        fullWidthDigit.replace(s) { (it.value[0] - '０' + '0'.code).toChar().toString() }

    fun clean(raw: String): String {
        var out = normalizeDigits(raw.trim())
        out = metaTag.replace(out, "")
        out = yearBracket.replace(out, "")
        out = qualityBracket.replace(out, "")
        out = looseYear.replace(out, "")
        out = trailingYear.replace(out, "")
        out = emptyBracket.replace(out, "")
        // collapse whitespace runs and trim separator leftovers
        out = out.replace(Regex("""\s{2,}"""), " ").trim()
        out = out.trimEnd('-', '_', '·', '，', ',').trim()
        // a folder literally named after a year must not be wiped out
        return out.ifBlank { raw.trim() }
    }
}
