package com.quarkemby.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.quarkemby.app.data.models.JobLogEntry
import com.quarkemby.app.data.models.TmdbShow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Key-value store for the app. Credentials (Quark cookies, device headers,
 * TMDB key) live in an EncryptedSharedPreferences instance; logs and non-secret
 * settings in a plain SharedPreferences instance.
 */
object Prefs {
    private const val SECURE = "quark_secure"
    private const val PLAIN = "quark_plain"

    private lateinit var secure: SharedPreferences
    private lateinit var plain: SharedPreferences
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        val mk = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        secure = EncryptedSharedPreferences.create(
            appContext, SECURE, mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        plain = appContext.getSharedPreferences(PLAIN, Context.MODE_PRIVATE)
    }

    // ---- Quark credentials ----
    var quarkCookies: String
        get() = secure.getString("quark_cookies", "") ?: ""
        set(v) = secure.edit().putString("quark_cookies", v).apply()
    var quarkDeviceHeaders: String
        get() = secure.getString("quark_headers", "") ?: ""
        set(v) = secure.edit().putString("quark_headers", v).apply()
    val isLoggedIn: Boolean
        get() = quarkCookies.isNotBlank()

    fun clearCredentials() {
        secure.edit().remove("quark_cookies").remove("quark_headers").apply()
    }

    // ---- TMDB ----
    var tmdbKey: String
        get() = secure.getString("tmdb_key", "") ?: ""
        set(v) = secure.edit().putString("tmdb_key", v).apply()
    var tmdbLanguage: String
        get() = plain.getString("tmdb_lang", "zh-CN") ?: "zh-CN"
        set(v) = plain.edit().putString("tmdb_lang", v).apply()

    // ---- Settings ----
    var renameTemplate: String
        get() = plain.getString("rename_tpl", "{show_name}.{ee}")!!
        set(v) = plain.edit().putString("rename_tpl", v).apply()
    var seasonTemplate: String
        get() = plain.getString("season_tpl", "Season {ss}")!!
        set(v) = plain.edit().putString("season_tpl", v).apply()
    var previewOnly: Boolean
        get() = plain.getBoolean("preview_only", true)
        set(v) = plain.edit().putBoolean("preview_only", v).apply()

    // ---- Sort preference: "name" | "size" | "time", asc true=升序 ----
    var sortKey: String
        get() = plain.getString("sort_key", "name") ?: "name"
        set(v) = plain.edit().putString("sort_key", v).apply()
    var sortAsc: Boolean
        get() = plain.getBoolean("sort_asc", true)
        set(v) = plain.edit().putBoolean("sort_asc", v).apply()

    // ---- Home (default) folder ----
    var homeFolderFid: String
        get() = plain.getString("home_folder_fid", "") ?: ""
        set(v) = plain.edit().putString("home_folder_fid", v).apply()
    var homeFolderName: String
        get() = plain.getString("home_folder_name", "") ?: ""
        set(v) = plain.edit().putString("home_folder_name", v).apply()
    val hasHomeFolder: Boolean
        get() = homeFolderFid.isNotBlank()

    // ---- Logs ----
    private const val LOG_KEY = "job_logs"

    fun addLogEntry(entry: JobLogEntry) {
        val arr = getLogEntries().toMutableList()
        arr.add(0, entry)
        if (arr.size > 200) while (arr.size > 200) arr.removeAt(arr.size - 1)
        val json = JSONArray()
        arr.forEach { json.put(entryToJson(it)) }
        plain.edit().putString(LOG_KEY, json.toString()).apply()
    }

    fun getLogEntries(): List<JobLogEntry> {
        val raw = plain.getString(LOG_KEY, "[]") ?: "[]"
        val out = mutableListOf<JobLogEntry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                out.add(entryFromJson(arr.getJSONObject(i)))
            }
        } catch (_: Exception) { }
        return out
    }

    fun formatTime(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

    fun newId(): String = UUID.randomUUID().toString().take(8)

    private fun entryToJson(e: JobLogEntry) = JSONObject().apply {
        put("id", e.id); put("time", e.time); put("title", e.title)
        put("summary", e.summary); put("detail", e.detail)
    }

    private fun entryFromJson(o: JSONObject) = JobLogEntry(
        o.optString("id"), o.optString("time"), o.optString("title"),
        o.optString("summary"), o.optString("detail")
    )

    // ---- TMDB show selection cache (runtime only possibilities, transient) ----
    var lastSelectedShow: TmdbShow? = null
}