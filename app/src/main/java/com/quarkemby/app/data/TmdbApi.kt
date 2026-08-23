package com.quarkemby.app.data

import android.util.Log
import com.quarkemby.app.data.models.TmdbShow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Thin client for the public TMDB v3 API. Uses the user's own personal key. */
object TmdbApi {
    private const val BASE = "https://api.themoviedb.org/3"
    private const val TAG = "TmdbApi"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    class TmdbException(message: String) : Exception(message)

    /** Returns true when the configured key can reach TMDB. */
    suspend fun testKey(key: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE/configuration?api_key=$key"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "testKey failed", e); false
        }
    }

    /** Search for TV shows by name; returns candidate list for manual selection. */
    suspend fun searchTv(key: String, query: String, language: String): List<TmdbShow> =
        withContext(Dispatchers.IO) {
            val url = "$BASE/search/tv?api_key=$key&query=${java.net.URLEncoder.encode(query, "UTF-8")}&language=$language"
            val req = Request.Builder().url(url).get().build()
            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw TmdbException("TMDB 搜索失败：HTTP ${resp.code}")
                resp.body!!.string()
            }
            val json = JSONObject(body)
            val results = json.optJSONArray("results") ?: JSONArray()
            val out = mutableListOf<TmdbShow>()
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                val year = r.optString("first_air_date", "").take(4)
                out.add(
                    TmdbShow(
                        id = r.optLong("id"),
                        name = r.optString("name", "未知"),
                        firstAirYear = year,
                        mediaType = r.optString("media_type", "tv")
                    )
                )
            }
            out
        }

    /** Returns the list of seasons (season_number) for a show, using its official metadata. */
    suspend fun getSeasonNumbers(key: String, showId: Long, language: String): List<Int> =
        withContext(Dispatchers.IO) {
            val url = "$BASE/tv/$showId?api_key=$key&language=$language"
            val req = Request.Builder().url(url).get().build()
            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw TmdbException("TMDB 详情失败：HTTP ${resp.code}")
                resp.body!!.string()
            }
            val seasons = JSONObject(body).optJSONArray("seasons") ?: JSONArray()
            val out = mutableListOf<Int>()
            for (i in 0 until seasons.length()) {
                val n = seasons.getJSONObject(i).optInt("season_number", 0)
                if (n > 0) out.add(n)
            }
            out
        }
}