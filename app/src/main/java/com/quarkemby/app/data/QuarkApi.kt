package com.quarkemby.app.data

import android.util.Log
import com.quarkemby.app.data.models.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Quark drive adapter (v2 — current web API).
 *
 * Quark provides no official public API. Endpoints below are reverse-engineered
 * from the current web/mobile client (the `drive-pc.quark.cn` clouddrive API used
 * by Quark's own web app and by open-source Quark drivers). Key facts confirmed as
 * of this version:
 *
 *  - Base host is now `drive-pc.quark.cn` (the old `drive.quark.cn` set of endpoints
 *    returns 405 Method Not Allowed for list sorting).
 *  - Directory listing (`/1/clouddrive/file/sort`) is a GET with query params,
 *    root folder == `pdir_fid=0` (NOT an empty string).
 *  - Delete takes `filelist` + `action_type=2` (no longer `fid_list`).
 *  - A credible desktop UA + `Referer: https://pan.quark.cn/` is required.
 *
 * Requests are bound to the Cookie captured at login and may change at any time.
 * On failure we throw a QuarkException whose message surfaces to the user so the
 * API layer can be re-captured and fixed in one place.
 */
object QuarkApi {
    private const val BASE_PC = "https://drive-pc.quark.cn/1/clouddrive"
    private const val TAG = "QuarkApi"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "quark-cloud-drive/3.14.2 Chrome/120.0.0.0 Electron/24.1.3.8 Safari/537.36 Channel/pckk_other_ch"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    class QuarkException(message: String) : Exception(message)

    private fun commonHeaders(h: kotlin.collections.Map<String, String>): kotlin.collections.Map<String, String> {
        val m = HashMap<String, String>()
        m["User-Agent"] = UA
        m["Referer"] = "https://pan.quark.cn/"
        m["Content-Type"] = "application/json"
        m["Cookie"] = Prefs.quarkCookies
        // apply captured custom device headers (parsed as "k=v,k2=v2")
        parseHeaders(Prefs.quarkDeviceHeaders).forEach { (k, v) -> m[k] = v }
        m.putAll(h)
        return m
    }

    private fun parseHeaders(raw: String): Map<String, String> {
        val out = HashMap<String, String>()
        raw.split(",").forEach { seg ->
            val idx = seg.indexOf('=')
            if (idx > 0) out[seg.substring(0, idx).trim()] = seg.substring(idx + 1).trim()
        }
        return out
    }

    /** GET the given path with query params; returns the parsed root JSON. */
    private fun get(path: String, params: Map<String, String>, extra: Map<String, String> = emptyMap()): JSONObject {
        val url = buildString {
            append(BASE_PC).append(path)
            var first = true
            params.forEach { (k, v) ->
                append(if (first) '?' else '&'); first = false
                append(k).append('=').append(java.net.URLEncoder.encode(v, "UTF-8"))
            }
        }
        val rb = Request.Builder().url(url).get()
        commonHeaders(extra).forEach { (k, v) -> rb.addHeader(k, v) }
        client.newCall(rb.build()).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw QuarkException("接口失败 HTTP ${resp.code}\n$text")
            return checkRoot(JSONObject(text))
        }
    }

    /** POST JSON to the given path; returns the parsed root JSON. */
    private fun post(path: String, body: JSONObject, extra: Map<String, String> = emptyMap()): JSONObject {
        val rb = Request.Builder().url(BASE_PC + path)
        commonHeaders(extra).forEach { (k, v) -> rb.addHeader(k, v) }
        val req = rb.post(body.toString().toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw QuarkException("接口失败 HTTP ${resp.code}\n$text")
            return checkRoot(JSONObject(text))
        }
    }

    /**
     * Uniform error handling. The Quark PC API reports success with
     * `{code:0}` and, on many endpoints, also sets `status:200`. Official
     * clients check `code != 0` for failure, so we do the same here.
     */
    private fun checkRoot(json: JSONObject): JSONObject {
        val code = json.optString("code", "0")   // default 0 = success when field absent
        if (code != "0") {
            val lower = code.lowercase()
            if (lower in setOf("nologin", "logincheck", "401", "40000005", "40200001", "999999")) {
                throw QuarkException("登录凭证已失效，请重新登录")
            }
            val msg = json.optString("message", "请求失败 code=$code")
            if (msg.isNotBlank()) throw QuarkException(msg)
            throw QuarkException("请求失败 code=$code")
        }
        return json
    }

    /** List children of `parentFid` (empty/"0" = root). Returns folders first. */
    suspend fun list(parentFid: String): List<FileItem> = withContext(Dispatchers.IO) {
        val pid = if (parentFid.isBlank() || parentFid == "0") "0" else parentFid
        val json = get("/file/sort", linkedMapOf(
            "pr" to "ucpro", "fr" to "pc", "uc_param_str" to "",
            "pdir_fid" to pid,
            "_page" to "1", "_size" to "200",
            "_fetch_total" to "1", "_fetch_sub_dirs" to "0",
            "_sort" to "file_type:asc,updated_at:desc",
            "_fetch_full_path" to "0",
            "fetch_all_file" to "1",
            "fetch_risk_file_name" to "1"
        ))
        val data = json.optJSONObject("data") ?: JSONObject()
        // list may be either an array directly or an array of {files: [...]}
        val list = data.optJSONArray("list") ?: JSONArray()
        val out = mutableListOf<FileItem>()
        for (i in 0 until list.length()) {
            val it = list.optJSONObject(i) ?: continue
            // when wrapped: {file_type, files:[...]}; unwrap if files present
            val filesArr = it.optJSONArray("files")
            if (filesArr != null && filesArr.length() > 0) {
                for (j in 0 until filesArr.length()) parseItem(filesArr.optJSONObject(j) ?: continue, out)
            } else {
                parseItem(it, out)
            }
        }
        out.sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
        if (out.isEmpty()) Log.w(TAG, "list($pid) empty. raw=${json.toString().take(500)}")
        out
    }

    private fun parseItem(it: JSONObject, out: MutableList<FileItem>) {
        // dir is reported as boolean true/1 on the PC API; be tolerant of all forms.
        val isDir = when (val d = it.opt("dir")) {
            is Boolean -> d
            is Number -> d.toInt() == 1
            is String -> d == "1" || d.equals("true", true)
            else -> false
        }
        val name = it.optString("file_name", "")
        val fid = it.optString("fid", "")
        if (fid.isEmpty() || name.isEmpty()) return
        out.add(
            FileItem(
                fid = fid,
                name = name,
                type = if (isDir) 0 else 1,
                size = it.optLong("size", 0L),
                ext = name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it".lowercase() },
                updatedAt = parseTime(it)
            )
        )
    }

    /** Parse update time; API often returns seconds, sometimes millis. */
    private fun parseTime(it: JSONObject): Long {
        val raw = it.optString("updated_at", it.optString("update_time", "0")).trim()
        val t = raw.toLongOrNull() ?: return 0L
        return if (t > 10_000_000_000L) t else t * 1000L
    }

    /** Create a folder under `parentFid` returning its fid. */
    suspend fun createFolder(parentFid: String, name: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("pdir_fid", if (parentFid.isBlank()) "0" else parentFid)
            put("file_name", name)
            put("dir_path", "")
            put("dir_init_lock", false)
        }
        val resp = post("/file/create?pr=ucpro&fr=pc&uc_param_str=", body)
        val data = resp.optJSONObject("data") ?: JSONObject()
        data.optString("fid", "").ifEmpty {
            val arr = data.optJSONArray("fid_list")
            if (arr != null && arr.length() > 0) arr.optString(0, "") else ""
        }
    }

    /** Rename a single file/folder. */
    suspend fun rename(fid: String, newName: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("fid", fid)
            put("file_name", newName)
        }
        post("/file/rename?pr=ucpro&fr=pc&uc_param_str=", body)
    }

    /** Move files into a destination folder by fid. */
    suspend fun move(fids: List<String>, dstFid: String) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        fids.forEach { arr.put(it) }
        val body = JSONObject().apply {
            put("filelist", arr)
            put("to_pdir_fid", if (dstFid.isBlank()) "0" else dstFid)
            put("exclude_fids", JSONArray())
            put("action_type", 1)
        }
        post("/file/move?pr=ucpro&fr=pc&uc_param_str=", body)
    }

    /** Delete files/folders (batch). */
    suspend fun delete(fids: List<String>) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        fids.forEach { arr.put(it) }
        val body = JSONObject().apply {
            put("action_type", 2)
            put("filelist", arr)
            put("exclude_fids", JSONArray())
        }
        post("/file/delete?pr=ucpro&fr=pc&uc_param_str=", body)
    }

    /** Lightweight reachability check for the drive endpoint. */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            list("0")
            true
        } catch (e: Exception) {
            Log.w(TAG, "ping failed", e); false
        }
    }
}