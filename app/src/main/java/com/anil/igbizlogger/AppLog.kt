package com.anil.igbizlogger

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight operational log — "service started", "export ran", "upload failed: X" —
 * never conversation content. Capped so it can't grow unbounded. This is what the
 * "App logs" settings screen reads from.
 */
object AppLog {
    private const val MAX_LINES = 500
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private fun file(context: Context) = File(context.getExternalFilesDir(null), "app_log.txt")

    @Synchronized
    fun log(context: Context, message: String) {
        try {
            val f = file(context)
            val line = "[${fmt.format(Date())}] $message\n"
            f.appendText(line)
            val lines = f.readLines()
            if (lines.size > MAX_LINES) {
                f.writeText(lines.takeLast(MAX_LINES / 2).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
            // Logging must never itself crash the app.
        }
    }

    fun readAll(context: Context): String =
        try { file(context).takeIf { it.exists() }?.readText() ?: "No log entries yet." }
        catch (_: Exception) { "Could not read log." }
}
