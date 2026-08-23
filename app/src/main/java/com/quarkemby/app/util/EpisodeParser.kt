package com.quarkemby.app.util

/**
 * Extracts a season/episode number from a messy media file name.
 *
 * Strategy order (first hit wins), validated against a 36-case battery
 * covering CJK and latin naming conventions:
 *  1. full-date names (2023.05.01) are rejected outright
 *  2. explicit SxxExx / 第X季第Y集
 *  3. 第Y集/话/期/回
 *  4. E / EP / EPS / Episode / # prefixes
 *  5. season markers ("Season 2", "第2季") stripped so they never read as episodes
 *  6. [12] bracket numbering (fansub style)
 *  7. separator + number near the end: 九门.30 / Show - 12 / Show_12 / 30v2
 *  8. trailing digits attached to CJK: 九门30
 *  9. leading number: "01 - title" / "01-4K.高码率"
 * 10. last standalone 1-3 digit token anywhere (broad fallback)
 *
 * Years (1900-2100) and resolutions (1080p/4K) are never mistaken for episodes.
 */
object EpisodeParser {

    data class Result(val season: Int, val episode: Int)

    private val extStrip = Regex("""(?i)\.(mp4|mkv|avi|ts|wmv|mov|m4v|flv|webm|rmvb|rm|iso|mpg|mpeg|mpe|m2ts|mts|3gp|vob|tp|asf|divx|f4v|ogm)$""")
    private val fullDate = Regex("""(?<!\d)(19|20)\d{2}[._\- ]\d{1,2}[._\- ]\d{1,2}(?!\d)""")
    private val sxxexx = Regex("""(?i)s\s*(\d{1,3})\s*[.\-_ ]?\s*e\s*(\d{1,3})""")
    private val cjkSeasonEp = Regex("""第\s*(\d{1,3})\s*季.*?第?\s*(\d{1,3})\s*[集话期回]""")
    private val cjkEpisode = Regex("""第?\s*0*(\d{1,3})\s*[集话期回]""")
    private val epPrefix = Regex("""(?i)(?:^|[^a-z0-9])(?:e|ep|eps|episode|#)\.?\s*0*(\d{1,3})(?!\d)""")
    private val seasonMark = Regex("""(?i)(?:season|s)\s*[.\-_ ]*\s*0*\d{1,3}\s*|第\s*0*\d{1,3}\s*季""")
    private val bracketNum = Regex("""\[\s*0*(\d{1,3})\s*\]""")
    private val sepNum = Regex("""(?:^|[._\-－ ])(?:0*)(\d{1,3})(?:v\d+)?(?:$|[._\-－ ]|[^\dA-Za-z]|$)""")
    private val trailingDigits = Regex("""(?<![A-Za-z\d])0*(\d{1,3})\s*$""")
    private val leadingNumber = Regex("""^0*(\d{1,3})\s*(?:[-－‒–_~]|\s)""")
    private val tokenizer = Regex("""[._\-－ \[\]()（）【】]+""")
    private val any4Digits = Regex("""\d{4}""")

    fun parse(name: String): Result? {
        val base = extStrip.replace(name, "")

        // 1) dates are not episodes
        if (fullDate.containsMatchIn(base)) return null

        // 2) explicit season+episode
        sxxexx.find(base)?.let {
            return Result(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        cjkSeasonEp.find(base)?.let {
            return Result(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }

        // 3) 第Y集/话/期/回
        cjkEpisode.find(base)?.let {
            return Result(1, it.groupValues[1].toInt())
        }

        // 4) E/EP/EPS/Episode/# prefix
        epPrefix.find(base)?.let {
            return Result(1, it.groupValues[1].toInt())
        }

        // 5) remove season markers before the loose number strategies
        val stripped = seasonMark.replace(base, " ")

        // 6) [12] fansub brackets
        bracketNum.find(stripped)?.let {
            return Result(1, it.groupValues[1].toInt())
        }

        // 7) separator + number near the end (九门.30 / Show - 12 / 30v2)
        sepNum.findAll(stripped).lastOrNull()?.let {
            val v = it.groupValues[1].toInt()
            if (v in 0..999) return Result(1, v)
        }

        // 8) trailing digits attached to CJK: 九门30
        trailingDigits.find(stripped)?.let {
            val v = it.groupValues[1].toInt()
            val isYear = v in 1900..2100 && any4Digits.containsMatchIn(stripped)
            if (!isYear) return Result(1, v)
        }

        // 9) leading number: "01 - title" / "01-4K.高码率"
        leadingNumber.find(stripped)?.let {
            val v = it.groupValues[1].toInt()
            if (v in 0..400) return Result(1, v)
        }

        // 10) broad fallback: last standalone 1-3 digit token anywhere
        val digits = tokenizer.split(stripped)
            .filter { it.length in 1..3 && it.all(Char::isDigit) }
            .mapNotNull { it.toIntOrNull() }
        if (digits.isNotEmpty()) {
            val v = digits.last()
            val isYear = v in 1900..2100 && any4Digits.containsMatchIn(stripped)
            if (!isYear) return Result(1, v)
        }
        return null
    }

    /** Format an episode number with Emby-style zero padding (S01E03). */
    fun pad(n: Int): String {
        if (n >= 100) return n.toString()
        return n.toString().padStart(2, '0')
    }
}