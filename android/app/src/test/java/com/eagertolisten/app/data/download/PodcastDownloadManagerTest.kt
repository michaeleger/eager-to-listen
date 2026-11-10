package com.eagertolisten.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastDownloadManagerTest {

    @Test
    fun fileKeyIsShortAndStableHoweverLongTheEpisodeIdIs() {
        val longId = "https://example.com/feeds/private/" + "a".repeat(200) + ".rss::https://example.com/episodes/" + "b".repeat(200)
        val key = PodcastDownloadManager.fileKey(longId)
        assertEquals(40, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
        assertEquals(key, PodcastDownloadManager.fileKey(longId))
        // IDs that only differ in punctuation used to collide after clean-up
        assertNotEquals(PodcastDownloadManager.fileKey("feed::a/b"), PodcastDownloadManager.fileKey("feed::a?b"))
    }

    @Test
    fun resumeOnlyWithAStrongValidator() {
        assertEquals("\"abc\"", PodcastDownloadManager.resumeValidator("\"abc\"", "Tue, 10 Nov 2026 08:00:00 GMT"))
        // A weak ETag can't prove the bytes are the same: fall back to the date
        assertEquals("Tue, 10 Nov 2026 08:00:00 GMT", PodcastDownloadManager.resumeValidator("W/\"abc\"", "Tue, 10 Nov 2026 08:00:00 GMT"))
        assertNull(PodcastDownloadManager.resumeValidator("W/\"abc\"", null))
        assertNull(PodcastDownloadManager.resumeValidator(null, " "))
    }

    @Test
    fun webPagesAreNotEpisodes() {
        assertTrue(PodcastDownloadManager.isWebPage("text/html; charset=utf-8"))
        assertTrue(PodcastDownloadManager.isWebPage("Text/HTML"))
        assertFalse(PodcastDownloadManager.isWebPage("audio/mpeg"))
        assertFalse(PodcastDownloadManager.isWebPage("application/octet-stream"))
        assertFalse(PodcastDownloadManager.isWebPage(null))
    }
}
