package com.quarkemby.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the last crash / render failure stack to filesDir/last_crash.txt
 * so it can be inspected in 任务日志 even after the process died.
 */
object CrashLog {
    private fun file(ctx: Context): File = File(ctx.filesDir, "last_crash.txt")

    fun write(ctx: Context, tag: String, t: Throwable) {
        runCatching {
            val head = "---- $tag @ " +
                    SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date()) + " ----\n"
            file(ctx).writeText(head + Log.getStackTraceString(t) + "\n")
        }
    }

    fun read(ctx: Context): String? =
        runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }
}