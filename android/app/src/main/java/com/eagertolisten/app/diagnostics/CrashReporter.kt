package com.eagertolisten.app.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.eagertolisten.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Tester crash reports, sent by email (alpha "Option A"):
 *  1. An uncaught exception is written to a file before the app dies.
 *  2. On the next launch the app asks whether to email it to the developer.
 *  3. "Send" opens the tester's email app with the report filled in; nothing is sent
 *     without the tester tapping Send there.
 * Settings > "Email last crash report" sends the most recent report on demand.
 */
object CrashReporter {

    const val DEVELOPER_EMAIL = "michael.eger@michaelegerconsulting.com"

    private const val TAG = "CrashReporter"
    private const val DIR_NAME = "crash_reports"
    private const val PENDING_FILE = "pending_crash.txt"   // not yet offered to the tester
    private const val LAST_FILE = "last_crash.txt"         // already offered; kept for Settings
    private const val MAX_TRACE_CHARS = 40_000
    private const val MAX_LOG_LINES = 300

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(appContext, thread, throwable)
            } catch (_: Throwable) {
                // Never let the reporter itself mask the original crash.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    /** A crash report the tester hasn't been asked about yet, or null. */
    fun pendingReport(context: Context): String? = readFile(File(dir(context), PENDING_FILE))

    /** The most recent crash report (pending or already offered), or null. */
    fun latestReport(context: Context): String? =
        pendingReport(context) ?: readFile(File(dir(context), LAST_FILE))

    /** Call after the next-launch prompt is answered either way, so it isn't shown again. */
    fun markPendingHandled(context: Context) {
        val pending = File(dir(context), PENDING_FILE)
        if (!pending.exists()) return
        val last = File(dir(context), LAST_FILE)
        if (last.exists()) last.delete()
        if (!pending.renameTo(last)) pending.delete()
    }

    /**
     * Opens the tester's email app with the report addressed to the developer.
     * Returns false if no email app could be opened.
     */
    fun emailReport(context: Context, report: String): Boolean {
        val subject = buildSubject(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE
        )
        val body = "Hi Michael,\n\nEager to Listen crashed. Here's the report.\n" +
            "(Optional) What was I doing when it crashed:\n\n\n" + report

        // ACTION_SEND with a mailto: selector limits the chooser to email apps but still
        // passes subject/body (some email apps ignore extras on a plain ACTION_SENDTO).
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(DEVELOPER_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(send)
            true
        } catch (e: Exception) {
            try {
                val fallback = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$DEVELOPER_EMAIL")).apply {
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                true
            } catch (e2: Exception) {
                Log.w(TAG, "No email app available", e2)
                false
            }
        }
    }

    fun buildSubject(
        versionName: String,
        versionCode: Int,
        manufacturer: String,
        model: String,
        androidRelease: String
    ): String {
        val device = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
        return "ETL crash report - $versionName (build $versionCode) - $device / Android $androidRelease"
    }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("=== Eager to Listen crash report ===")
            appendLine("App: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Crashed at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine("--- Stack trace ---")
            appendLine(trace.take(MAX_TRACE_CHARS))
            val log = recentAppLog()
            if (log.isNotBlank()) {
                appendLine()
                appendLine("--- Recent app log (last $MAX_LOG_LINES lines) ---")
                appendLine(log)
            }
        }
        File(dir(context), PENDING_FILE).writeText(text)
    }

    /** Last lines of this app's own logcat (apps can read their own process's log without permissions). */
    private fun recentAppLog(): String = try {
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "time", "-t", MAX_LOG_LINES.toString(),
            "--pid=${android.os.Process.myPid()}"
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(2, TimeUnit.SECONDS)
        output.takeLast(MAX_TRACE_CHARS)
    } catch (_: Throwable) {
        ""
    }

    private fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    private fun readFile(file: File): String? = try {
        if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
    } catch (_: Exception) {
        null
    }
}
