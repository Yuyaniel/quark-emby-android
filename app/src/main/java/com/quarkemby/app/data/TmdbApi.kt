package com.quarkemby.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal TMDB v3 client used to enrich renames with episode titles:
 *  - multi search (TV shows AND movies, no type restriction)
 *  - list episode names of one season
 * Requests are small GETs; the personal API key comes from Settings.
 */
object TmdbApi {

    class TmdbException(message: String) : Exception(message)

    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p/w154"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Show(
        val id: Long,
        val name: String,
        val firstAirYear: String,
        val posterUrl: String,
        /** true = TMDB movie, false = TV show */
        val isMovie: Boolean = false
    )

    /** One season of a show, used to let the user pick the TMDB season. */
    data class SeasonInfo(
        val number: Int,
        val name: String,
        val episodeCount: Int
    )

    /** Season list of a show (from /tv/{id} details). Season 0 = 特别篇. */
    suspend fun tvSeasons(key: String, tvId: Long, lang: String): List<SeasonInfo> =
        withContext(Dispatchers.IO) {
            val root = get("$BASE/tv/$tvId?api_key=$key&language=$lang")
            val arr = root.optJSONArray("seasons") ?: return@withContext emptyList()
            val all = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SeasonInfo(
                    number = o.optInt("season_number"),
                    name = o.optString("name").ifEmpty { "第 ${o.optInt("season_number")} 季" },
                    episodeCount = o.optInt("episode_count")
                )
            }
            // prefer regular seasons; keep specials only when nothing else exists
            all.filter { it.number >= 1 }.ifEmpty { all }
        }

    /** POSTER base for building image urls. */
    fun posterUrl(path: String?): String =
        if (path.isNullOrBlank()) "" else IMG + path

    suspend fun testKey(key: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { get("$BASE/configuration?api_key=$key") }.isSuccess
    }

    /** Multi search: TV shows AND movies in one query, no type restriction.
     *  Person results are dropped; each result carries a small poster url. */
    suspend fun searchAll(key: String, query: String, lang: String): List<Show> =
        withContext(Dispatchers.IO) {
            val url = "$BASE/search/multi?api_key=$key&language=$lang&query=$query&page=1"
            val root = get(url)
            val arr = root.optJSONArray("results") ?: return@withContext emptyList()
            val out = mutableListOf<Show>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = o.optString("media_type")
                if (type != "tv" && type != "movie") continue  // skip person etc.
                val isMovie = type == "movie"
                out.add(
                    Show(
                        id = o.optLong("id"),
                        name = if (isMovie) {
                            o.optString("title").ifEmpty { o.optString("original_title") }
                        } else {
                            o.optString("name").ifEmpty { o.optString("original_name") }
                        },
                        firstAirYear = (if (isMovie) o.optString("release_date")
                                        else o.optString("first_air_date")).take(4),
                        posterUrl = posterUrl(o.optString("poster_path")),
                        isMovie = isMovie
                    )
                )
            }
            out
        }

    /** Episode number -> localized episode title for one season. */
    suspend fun seasonEpisodes(
        key: String, tvId: Long, season: Int, lang: String
    ): Map<Int, String> = withContext(Dispatchers.IO) {
        val url = "$BASE/tv/$tvId/season/$season?api_key=$key&language=$lang"
        val root = get(url)
        val arr = root.optJSONArray("episodes") ?: return@withContext emptyMap()
        val out = HashMap<Int, String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name")
            if (name.isNotBlank()) out[o.optInt("episode_number")] = name
        }
        out
    }

    private fun get(url: String): JSONObject {
        val resp = client.newCall(Request.Builder().url(url).get().build()).execute()
        resp.use {
            val body = it.body?.string() ?: throw TmdbException("TMDB 响应为空")
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: throw TmdbException("TMDB 响应解析失败")
            if (json.has("status_code") && json.optInt("status_code") != 1) {
                throw TmdbException("TMDB：${json.optString("status_message")}")
            }
            return json
        }
    }
}
