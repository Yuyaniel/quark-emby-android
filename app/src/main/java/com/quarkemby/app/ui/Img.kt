package com.quarkemby.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.widget.ImageView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Tiny dependency-free image loader for TMDB posters: OkHttp fetch +
 * LruCache + main-thread apply. Enough for a handful of small thumbnails.
 */
object Img {

    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Loads `url` into `into`; shows a soft placeholder until decoded. */
    fun load(url: String, into: ImageView) {
        if (url.isBlank()) { into.setImageDrawable(placeholder(into)); return }
        cache.get(url)?.let { into.setImageBitmap(it); return }
        into.setImageDrawable(placeholder(into))
        http.newCall(Request.Builder().url(url).get().build()).enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {}
            override fun onResponse(c: Call, r: Response) {
                val bmp = r.use { it.body?.byteStream()?.use { BitmapFactory.decodeStream(it) } }
                    ?: return
                cache.put(url, bmp)
                into.post { if (into.tag == url) into.setImageBitmap(bmp) }
            }
        })
        into.tag = url
    }

    private fun placeholder(v: ImageView): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = Ui.dp(v.context, 8).toFloat()
        setColor(0xFF232833.toInt())
    }
}