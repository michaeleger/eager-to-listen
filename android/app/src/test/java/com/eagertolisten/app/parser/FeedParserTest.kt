package com.eagertolisten.app.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

class FeedParserTest {

    private fun loadFixture(fileName: String): InputStream {
        return javaClass.classLoader?.getResourceAsStream(fileName)
            ?: throw IllegalArgumentException("Fixture not found: $fileName")
    }

    @Test
    fun parseRiversideSample() {
        val stream = loadFixture("riverside_sample.xml")
        val parser = FeedParser()
        val parsed = parser.parse(stream, "https://api.riverside.com/hosting/drlxDc7x.rss")

        assertNotNull(parsed.podcast)
        assertTrue(parsed.podcast.title.isNotBlank())
        assertTrue(parsed.episodes.isNotEmpty())

        val firstEp = parsed.episodes.first()
        assertTrue(firstEp.title.isNotBlank())
        assertTrue(firstEp.audioUrl.isNotBlank())
    }

    @Test
    fun parseLiveItem() {
        val stream = loadFixture("live_item.xml")
        val parser = FeedParser()
        val parsed = parser.parse(stream, "https://example.com/live.xml")

        assertEquals(2, parsed.episodes.size)
        val liveEp = parsed.episodes.find { it.isLive }
        assertNotNull(liveEp)
        assertEquals("Live Broadcast Today", liveEp?.title)
        assertEquals("live", liveEp?.liveStatus)
    }

    @Test
    fun parseMissingPubDate() {
        val stream = loadFixture("missing_pubdate.xml")
        val parser = FeedParser()
        val parsed = parser.parse(stream, "https://example.com/missing.xml")

        assertEquals(2, parsed.episodes.size)
        // Missing or unparseable pubDate must be 0L, not System.currentTimeMillis()
        assertEquals(0L, parsed.episodes[0].publishedAt)
        assertEquals(0L, parsed.episodes[1].publishedAt)
        assertEquals(0, parsed.episodes[0].feedOrder)
        assertEquals(1, parsed.episodes[1].feedOrder)
    }

    @Test
    fun parseMultipleTranscripts() {
        val stream = loadFixture("multiple_transcripts.xml")
        val parser = FeedParser()
        val parsed = parser.parse(stream, "https://example.com/multi.xml")

        assertEquals(1, parsed.episodes.size)
        val ep = parsed.episodes[0]
        assertNotNull(ep.transcriptUrl)
        // Prefer text/vtt over text/plain and text/html
        assertEquals("https://example.com/trans.vtt", ep.transcriptUrl)
        assertEquals("text/vtt", ep.transcriptType)
    }

    @Test
    fun testStrategyInference() {
        // "Classic" should not trigger "class" rule for chronological
        assertEquals("EVERGREEN", FeedParser.inferPlayOrderStrategy("Classic Rock Music", "Discussion about vintage rock"))
        // "Daily News" should trigger NEWS
        assertEquals("NEWS", FeedParser.inferPlayOrderStrategy("Daily News Roundup", "Headlines and politics"))
        // "History of Rome" should trigger CHRONOLOGICAL
        assertEquals("CHRONOLOGICAL", FeedParser.inferPlayOrderStrategy("The History of Rome", "Chronological narrative"))
        // itunes:type serial should trigger CHRONOLOGICAL
        assertEquals("CHRONOLOGICAL", FeedParser.inferPlayOrderStrategy("Serial Audio Drama", "", itunesType = "serial"))
    }
}
