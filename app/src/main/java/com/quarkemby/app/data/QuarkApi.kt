package com.quarkemby.app.data

import android.util.Log
import com.quarkemby.app.data.models.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Unofficial Quark drive adapter.
 *
 * Quark provides NO official public API. All endpoints below come from
 * reverse-engineering the web/mobile client (the common `drive.quark.cn`
 * clouddrive API used by open-source Quark drivers). They are bound to the
 * Cookie + device headers captured at login and may change at any time. If a
 * call fails, QuarkApi throws a QuarkException whose message is shown to the
 * user so the API layer can be re-captured and fixed in one place.
 *
 * NOTE: Quark may require a request signature depending on the client flavor.
 * When a fresh capture shows such a header, add it inside `commonHeaders()`.
 */
object QuarkApi {
    private const val BASE = "https://drive.quark.cn/1/clouddrive"
    private const val TAG = "QuarkApi"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    class QuarkException(message: String) : Exception(message)

    private fun commonHeaders(h: kotlin.collections.Map<String, String>): kotlin.collections.Map<String, String> {
        val m = HashMap<String, String>()
        m["User-Agent"] = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
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

    private fun post(path: String, body: JSONObject, extra: Map<String, String> = emptyMap()): JSONObject {
        val rb = Request.Builder().url(BASE + path)
        commonHeaders(extra).forEach { (k, v) -> rb.addHeader(k, v) }
        val req = rb.post(body.toString().toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) {
                throw QuarkException("接口失败 HTTP ${resp.code}\n$text")
            }
            val json = JSONObject(text)
            if (json.optInt("status", 0) != 200) {
                val code = json.optString("code", "")
                if (code in listOf("40200001", "loginCheck", "401", "40000005", "noLogin")) {
                    throw QuarkException("登录凭证已失效，请重新登录")
                }
                throw QuarkException(json.optString("message", "请求失败 code=$code"))
            }
            return json
        }
    }

    /** List children of `parentFid` (empty = root). Returns folders first. */
    suspend fun list(parentFid: String): List<FileItem> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("pid", parentFid)
            put("pr", 1)
            put("pwd_fid", "")
            put("dir", JSONObject().put("desc", 0).put("name", 0).put("size", 0))
            put("fri", 0)
            put("sort", JSONObject().put("sort_type", 3).put("order", 1))
            put("size", 200)
        }
        val resp = post("/file/sort", body)
        val data = resp.optJSONObject("data") ?: JSONObject()
        val list = data.optJSONArray("list") ?: org.json.JSONArray()
        val out = mutableListOf<FileItem>()
        for (i in 0 until list.length()) {
            val it = list.getJSONObject(i)
            val isDir = it.optInt("dir", 0) == 1
            val name = it.optString("file_name", "")
            val fid = it.optString("fid", it.optString("fids", "").let { f ->
                try {
                    org.json.JSONArray(f).optString(0, "")
                } catch (_: Exception) { f }
                })
            if (fid.isEmpty() || name.isEmpty()) continue
            out.add(
                FileItem(
                    fid = fid,
                    name = name,
                    type = if (isDir) 0 else 1,
                    size = it.optLong("size", 0L),
                    ext = name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it".lowercase() }
                )
            )
        }
        out.sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
    }

    /** Create a folder under `parentFid` returning its fid. */
    suspend fun createFolder(parentFid: String, name: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("pdir_fid", parentFid)
            put("file_name", name)
            put("dir_path", "")
            put("dir_init_lock", false)
        }
        val resp = post("/file/create", body)
        val data = resp.optJSONObject("data") ?: JSONObject()
        data.optString("fid", "").ifEmpty {
            // some flavors reply with an array of created fid
            val arr = data.optJSONArray("fid_list")
            if (arr != null && arr.length() > 0) arr.optString(0, "") else ""
        }
    }

    /** Rename a single file/folder. */
    suspend fun rename(fid: String, newName: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("fid_list", org.json.JSONArray().put(fid))
            put("file_name", newName)
        }
        post("/file/rename", body)
    }

    /** Move files into a destination folder by fid. */
    suspend fun move(fids: List<String>, dstFid: String) = withContext(Dispatchers.IO) {
        val arr = org.json.JSONArray()
        fids.forEach { arr.put(it) }
        val body = JSONObject().apply {
            put("fid_list", arr)
            put("to_pdir_fid", dstFid)
        }
        post("/file/move", body)
    }

    /** Delete files/folders (batch). */
    suspend fun delete(fids: List<String>) = withContext(Dispatchers.IO) {
        val arr = org.json.JSONArray()
        fids.forEach { arr.put(it) }
        val body = JSONObject().apply {
            put("fid_list", arr)
        }
        post("/file/delete", body)
    }

    /** Lightweight reachability check for the drive endpoint. */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            post("/file/config", JSONObject())
            true
        } catch (e: Exception) {
            Log.w(TAG, "ping failed", e); false
        }
    }
}