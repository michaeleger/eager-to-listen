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
 * Settings > "Email last crash report" sends the most recent report on demand,
 * including any recorded source errors and playback errors.
 */
object CrashReporter {

    const val DEVELOPER_EMAIL = "michael.eger@michaelegerconsulting.com"

    private const val TAG = "CrashReporter"
    private const val DIR_NAME = "crash_reports"
    private const val PENDING_FILE = "pending_crash.txt"   // not yet offered to the tester
    private const val LAST_FILE = "last_crash.txt"         // already offered; kept for Settings
    private const val PLAYBACK_ERRORS_FILE = "playback_errors.txt" // persistent source & playback error log
    private const val PLAYBACK_ENTRY_HEADER = "=== [SOURCE / PLAYBACK ERROR] ==="
    private const val MAX_PLAYBACK_ERROR_ENTRIES = 25
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

    /** Recorded source & playback error history, newest first, or null if none recorded. */
    fun latestPlaybackErrors(context: Context): String? =
        readFile(File(dir(context), PLAYBACK_ERRORS_FILE))

    /**
     * The most recent crash and/or source & playback error report, or null if neither exists.
     */
    fun latestReport(context: Context): String? {
        val crash = pendingReport(context) ?: readFile(File(dir(context), LAST_FILE))
        val playbackErrors = latestPlaybackErrors(context)
        if (crash == null && playbackErrors == null) return null

        return buildString {
            if (crash != null) {
                append(crash.trimEnd())
                if (playbackErrors != null && !crash.contains(PLAYBACK_ENTRY_HEADER)) {
                    appendLine()
                    appendLine()
                    appendLine("--- Recorded Source & Playback Errors ---")
                    appendLine(playbackErrors.trimEnd())
                }
            } else if (playbackErrors != null) {
                appendLine("=== Eager to Listen Source & Playback Error Report ===")
                appendLine("App: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Generated at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
                appendLine()
                appendLine("--- Recorded Source & Playback Errors (newest first) ---")
                appendLine(playbackErrors.trimEnd())
                val log = recentAppLog()
                if (log.isNotBlank()) {
                    appendLine()
                    appendLine("--- Recent app log (last $MAX_LOG_LINES lines) ---")
                    appendLine(log)
                }
            }
        }.trimEnd()
    }

    /**
     * Records a source error, playback error, or recovery event so it is included in
     * emailable diagnostic and crash reports.
     */
    @Synchronized
    fun recordPlaybackError(
        context: Context,
        eventType: String,
        episodeTitle: String?,
        episodeId: String?,
        podcastTitle: String?,
        podcastId: String?,
        mediaUri: String?,
        localFileStatus: String?,
        errorCode: Int?,
        errorCodeName: String?,
        httpStatus: Int? = null,
        positionSec: Long? = null,
        durationSec: Long? = null,
        recoveryAction: String,
        throwable: Throwable? = null
    ) {
        try {
            val entry = formatPlaybackErrorEntry(
                eventType = eventType,
                episodeTitle = episodeTitle,
                episodeId = episodeId,
                podcastTitle = podcastTitle,
                podcastId = podcastId,
                mediaUri = mediaUri,
                localFileStatus = localFileStatus,
                errorCode = errorCode,
                errorCodeName = errorCodeName,
                httpStatus = httpStatus,
                positionSec = positionSec,
                durationSec = durationSec,
                recoveryAction = recoveryAction,
                throwable = throwable
            )
            val file = File(dir(context.applicationContext), PLAYBACK_ERRORS_FILE)
            val existingRaw = readFile(file).orEmpty()
            val existingEntries = existingRaw
                .split(PLAYBACK_ENTRY_HEADER)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { "$PLAYBACK_ENTRY_HEADER\n$it" }
            val combined = (listOf(entry.trim()) + existingEntries)
                .take(MAX_PLAYBACK_ERROR_ENTRIES)
                .joinToString("\n\n")
            file.writeText(combined.take(MAX_TRACE_CHARS))
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to record playback error", e)
        }
    }

    fun formatPlaybackErrorEntry(
        eventType: String,
        episodeTitle: String?,
        episodeId: String?,
        podcastTitle: String?,
        podcastId: String?,
        mediaUri: String?,
        localFileStatus: String?,
        errorCode: Int?,
        errorCodeName: String?,
        httpStatus: Int? = null,
        positionSec: Long? = null,
        durationSec: Long? = null,
        recoveryAction: String,
        throwable: Throwable? = null,
        timestamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
    ): String {
        return buildString {
            appendLine(PLAYBACK_ENTRY_HEADER)
            appendLine("Time: $timestamp")
            appendLine("Event: $eventType")
            if (!episodeTitle.isNullOrBlank() || !episodeId.isNullOrBlank()) {
                appendLine("Episode: ${episodeTitle ?: "Unknown"} (id=${episodeId ?: "none"})")
            }
            if (!podcastTitle.isNullOrBlank() || !podcastId.isNullOrBlank()) {
                appendLine("Show: ${podcastTitle ?: "Unknown"} (id=${podcastId ?: "none"})")
            }
            if (!mediaUri.isNullOrBlank()) {
                appendLine("Media URI: $mediaUri")
            }
            if (!localFileStatus.isNullOrBlank()) {
                appendLine("Local File: $localFileStatus")
            }
            if (errorCode != null || !errorCodeName.isNullOrBlank() || httpStatus != null) {
                val httpPart = if (httpStatus != null) " / HTTP $httpStatus" else ""
                appendLine("Error Code: ${errorCodeName ?: "UNKNOWN"} (${errorCode ?: -1})$httpPart")
            }
            if (positionSec != null || durationSec != null) {
                appendLine("Position / Duration: ${positionSec ?: 0L}s / ${durationSec ?: 0L}s")
            }
            appendLine("Recovery Action: $recoveryAction")
            if (throwable != null) {
                val causeMsg = generateSequence(throwable) { it.cause }
                    .joinToString(" -> ") { "${it.javaClass.simpleName}: ${it.message ?: ""}" }
                appendLine("Cause: ${causeMsg.take(800)}")
            }
        }.trimEnd()
    }

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
        val body = "Hi Michael,\n\nHere is my Eager to Listen diagnostic / crash / playback error report.\n" +
            "(Optional) What was I doing when this happened:\n\n\n" + report

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
        val playbackErrors = latestPlaybackErrors(context)
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
            if (!playbackErrors.isNullOrBlank()) {
                appendLine()
                appendLine("--- Recorded Source & Playback Errors ---")
                appendLine(playbackErrors)
            }
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
        // Read on a helper thread so a stuck logcat can't hang the crashing app past ~2 seconds.
        var output = ""
        val reader = Thread {
            output = try {
                process.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Throwable) {
                ""
            }
        }.apply { isDaemon = true; start() }
        reader.join(TimeUnit.SECONDS.toMillis(2))
        if (reader.isAlive) process.destroy()
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
