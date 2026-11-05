package com.eagertolisten.app.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import com.eagertolisten.app.BuildConfig
import com.eagertolisten.app.data.EagerDatabase

/**
 * Centralized application metadata and runtime diagnostics.
 * Enables users and alpha testers to visually verify the app version,
 * build number, database schema, and device environment.
 */
object AppInfo {
    const val APP_NAME = "Eager to Listen"
    const val APP_TAGLINE = "Smart Power-User Audio & Podcasting 2.0"
    const val DEVELOPER_NAME = "Michael Eger"
    const val DEVELOPER_EMAIL = "michael.eger@michaelegerconsulting.com"
    const val GITHUB_URL = "https://github.com/michaeleger/eager-to-listen"
    const val PRIVACY_POLICY_URL = "https://eagertolisten.com/privacy-policy.html"
    const val ALPHA_TRACK = "Closed Alpha Testing"

    val versionName: String get() = BuildConfig.VERSION_NAME
    val versionCode: Int get() = BuildConfig.VERSION_CODE
    val buildType: String get() = BuildConfig.BUILD_TYPE
    val applicationId: String get() = BuildConfig.APPLICATION_ID
    val databaseVersion: Int get() = EagerDatabase.DATABASE_VERSION

    val deviceManufacturer: String get() = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
    val deviceModel: String get() = Build.MODEL.orEmpty()
    val androidRelease: String get() = Build.VERSION.RELEASE.orEmpty()
    val sdkInt: Int get() = Build.VERSION.SDK_INT

    val displayVersion: String
        get() = "v$versionName ($versionCode)"

    val fullVersionBadge: String
        get() = "v$versionName • Build $versionCode ($buildType)"

    val deviceSummary: String
        get() = "$deviceManufacturer $deviceModel (Android $androidRelease, API $sdkInt)".trim()

    fun buildDiagnosticBlock(
        vName: String = versionName,
        vCode: Int = versionCode,
        bType: String = buildType,
        appId: String = applicationId,
        dbVersion: Int = databaseVersion,
        device: String = deviceSummary
    ): String {
        return buildString {
            appendLine("$APP_NAME v$vName ($vCode)")
            appendLine("App ID: $appId")
            appendLine("Build Variant: $bType")
            appendLine("Database Schema: v$dbVersion")
            appendLine("Device: $device")
            appendLine("Track: $ALPHA_TRACK")
        }.trimEnd()
    }

    fun copyDiagnosticsToClipboard(
        context: Context,
        toastMessage: String = "App version & diagnostic info copied to clipboard"
    ): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Eager to Listen App Info", buildDiagnosticBlock())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
            true
        } catch (_: Exception) {
            Toast.makeText(context, "Could not copy to clipboard", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
