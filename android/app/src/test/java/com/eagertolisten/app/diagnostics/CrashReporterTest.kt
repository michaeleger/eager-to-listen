package com.eagertolisten.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReporterTest {

    @Test
    fun subjectIncludesVersionBuildDeviceAndAndroid() {
        val subject = CrashReporter.buildSubject(
            versionName = "0.9.2-alpha",
            versionCode = 11,
            manufacturer = "Google",
            model = "Pixel 11 Pro X",
            androidRelease = "17"
        )
        assertEquals("ETL crash report - 0.9.2-alpha (build 11) - Google Pixel 11 Pro X / Android 17", subject)
    }

    @Test
    fun subjectDoesNotRepeatManufacturerAlreadyInModel() {
        val subject = CrashReporter.buildSubject("0.9.2-alpha", 11, "samsung", "Samsung Galaxy S26", "16")
        assertEquals("ETL crash report - 0.9.2-alpha (build 11) - Samsung Galaxy S26 / Android 16", subject)
    }

    @Test
    fun reportsGoToTheDeveloperAddress() {
        assertEquals("michael.eger@michaelegerconsulting.com", CrashReporter.DEVELOPER_EMAIL)
    }

    @Test
    fun formatPlaybackErrorEntryIncludesSourceErrorAndRecoveryDetails() {
        val entry = CrashReporter.formatPlaybackErrorEntry(
            eventType = "Local Download Source Error -> Streaming Fallback",
            episodeTitle = "Ep 104: Sleep & Recovery",
            episodeId = "ep-104",
            podcastTitle = "Eager to be Healthy",
            podcastId = "pod-1",
            mediaUri = "file:///data/user/0/com.eagertolisten.app/files/podcast_downloads/ep-104.mp3",
            localFileStatus = "Deleted failed local file",
            errorCode = 2000,
            errorCodeName = "ERROR_CODE_IO_UNSPECIFIED",
            httpStatus = null,
            positionSec = 1420L,
            durationSec = 3600L,
            recoveryAction = "Deleted bad local download and switched to streaming at 1420s",
            throwable = RuntimeException("Source error", IllegalStateException("FileNotFoundException")),
            timestamp = "2026-11-04 23:55:00 -0500"
        )

        assertTrue(entry.contains("=== [SOURCE / PLAYBACK ERROR] ==="))
        assertTrue(entry.contains("Event: Local Download Source Error -> Streaming Fallback"))
        assertTrue(entry.contains("Episode: Ep 104: Sleep & Recovery (id=ep-104)"))
        assertTrue(entry.contains("Show: Eager to be Healthy (id=pod-1)"))
        assertTrue(entry.contains("Error Code: ERROR_CODE_IO_UNSPECIFIED (2000)"))
        assertTrue(entry.contains("Position / Duration: 1420s / 3600s"))
        assertTrue(entry.contains("Recovery Action: Deleted bad local download and switched to streaming at 1420s"))
        assertTrue(entry.contains("Cause: RuntimeException: Source error -> IllegalStateException: FileNotFoundException"))
    }

    @Test
    fun privateFeedKeysAreRemovedFromEmailedReports() {
        val entry = CrashReporter.formatPlaybackErrorEntry(
            eventType = "Source / Playback Error (RETRY_NETWORK)",
            episodeTitle = "Bonus episode",
            episodeId = "ep-9",
            podcastTitle = "Members Feed",
            podcastId = "https://www.patreon.com/rss/someshow?auth=SECRETKEY123",
            mediaUri = "https://user:pa55@cdn.example.com/audio/ep9.mp3?token=SECRETKEY123#t=10",
            localFileStatus = null,
            errorCode = 2001,
            errorCodeName = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            recoveryAction = "Retry 1/2",
            throwable = RuntimeException("Unable to connect to https://cdn.example.com/audio/ep9.mp3?token=SECRETKEY123")
        )
        assertFalse(entry.contains("SECRETKEY123"))
        assertFalse(entry.contains("pa55"))
        assertTrue(entry.contains("Media URI: https://cdn.example.com/audio/ep9.mp3?[removed]"))
        assertTrue(entry.contains("https://www.patreon.com/rss/someshow?[removed]"))
        assertEquals("https://example.com/feed.xml", CrashReporter.redactUrls("https://example.com/feed.xml"))
    }
}
