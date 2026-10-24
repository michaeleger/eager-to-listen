package com.eagertolisten.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedUrlAndEpisodeIdTest {

    @Test
    fun testFeedUrlNormalization() {
        val url1 = "https://example.com/podcast/rss/"
        val url2 = "HTTPS://Example.COM/podcast/rss"
        val url3 = "  https://example.com/podcast/rss  "

        assertEquals("https://example.com/podcast/rss", FeedUrl.normalize(url1))
        assertEquals("https://example.com/podcast/rss", FeedUrl.normalize(url2))
        assertEquals("https://example.com/podcast/rss", FeedUrl.normalize(url3))
    }

    @Test
    fun testEpisodeIdConsistency() {
        val podcastUrl = "https://example.com/feed.xml/"
        val guid = "episode-12345"
        val enclosure = "https://example.com/media/ep1.mp3"

        val idWithGuid = EpisodeId.of(podcastUrl, guid, enclosure)
        assertEquals("https://example.com/feed.xml::episode-12345", idWithGuid)

        val idWithoutGuid = EpisodeId.of(podcastUrl, null, enclosure)
        assertEquals("https://example.com/feed.xml::https://example.com/media/ep1.mp3", idWithoutGuid)

        val idBlankGuid = EpisodeId.of(podcastUrl, "   ", enclosure)
        assertEquals("https://example.com/feed.xml::https://example.com/media/ep1.mp3", idWithoutGuid)
    }

    @Test
    fun testDeduplicationAcrossFeedsWithSameGuid() {
        val guid = "1"
        val idFeedA = EpisodeId.of("https://feed-a.com/rss", guid, "https://feed-a.com/1.mp3")
        val idFeedB = EpisodeId.of("https://feed-b.com/rss", guid, "https://feed-b.com/1.mp3")

        // Guaranteed not to collide globally because ID is prefixed with normalized feed URL
        org.junit.Assert.assertNotEquals(idFeedA, idFeedB)
    }
}
