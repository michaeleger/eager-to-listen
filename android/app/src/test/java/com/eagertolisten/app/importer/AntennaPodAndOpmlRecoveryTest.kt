package com.eagertolisten.app.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class AntennaPodAndOpmlRecoveryTest {

    @Test
    fun detectsAntennaPodSchemaButNotPodcastAddict() {
        assertEquals(true, AntennaPodFormat.isAntennaPodSchema(listOf("android_metadata", "Feeds", "FeedItems", "FeedMedia", "Queue")))
        assertEquals(false, AntennaPodFormat.isAntennaPodSchema(listOf("podcasts", "episodes", "ordered_list")))
    }

    @Test
    fun importsOnlySubscribedRemoteFeeds() {
        assertEquals(true, AntennaPodFormat.isImportableFeed(0, "https://feeds.example.com/show"))
        assertEquals(true, AntennaPodFormat.isImportableFeed(null, "https://feeds.example.com/show")) // older exports
        assertEquals(false, AntennaPodFormat.isImportableFeed(1, "https://feeds.example.com/show"))   // not subscribed
        assertEquals(false, AntennaPodFormat.isImportableFeed(2, "https://feeds.example.com/show"))   // archived
        assertEquals(false, AntennaPodFormat.isImportableFeed(0, "antennapod_local:content://tree/x"))
        assertEquals(false, AntennaPodFormat.isImportableFeed(0, ""))
    }

    @Test
    fun speedAndSkipValues() {
        assertEquals(null, AntennaPodFormat.speedOrNull(-1f))   // use global
        assertEquals(1.5f, AntennaPodFormat.speedOrNull(1.5f))
        assertEquals(null, AntennaPodFormat.speedOrNull(9f))
        assertEquals(30, AntennaPodFormat.skipSeconds(30))
        assertEquals(300, AntennaPodFormat.skipSeconds(900))
        assertEquals(0, AntennaPodFormat.skipSeconds(null))
    }

    @Test
    fun playedAtPrefersCompletionDate() {
        assertEquals(3L, AntennaPodFormat.playedAtMs(3L, 2L, 1L))
        assertEquals(2L, AntennaPodFormat.playedAtMs(0L, 2L, 1L))
        assertEquals(1L, AntennaPodFormat.playedAtMs(0L, 0L, 1L))
        assertEquals(null, AntennaPodFormat.playedAtMs(0L, 0L, 0L))
    }

    @Test
    fun recoversPodverseOpmlWithUnescapedQuotes() {
        // Shape from podverse/podverse#333
        val broken = """<?xml version="1.0" encoding="UTF-8"?>
<opml version="1.0"><head><title>Podverse Subscriptions</title></head><body>
<outline text="Chat G&amp;T" type="rss" xmlUrl="https://www.omnycontent.com/d/playlist/abc/podcast.rss"/>
<outline text="Un podcast à soi" type="rss" xmlUrl="https://www.arteradio.com/xml_sound_emission?emissionname="Un podcast à soi""/>
<outline text='Single Quoted' type='rss' xmlUrl='https://example.com/single.xml' />
</body></opml>"""
        val outlines = OpmlRecovery.extractOutlines(broken)
        assertEquals(3, outlines.size)
        assertEquals(OpmlRecovery.Outline("Chat G&T", "https://www.omnycontent.com/d/playlist/abc/podcast.rss"), outlines[0])
        assertEquals("https://www.arteradio.com/xml_sound_emission?emissionname=\"Un podcast à soi\"", outlines[1].xmlUrl)
        assertEquals("https://example.com/single.xml", outlines[2].xmlUrl)
    }

    @Test
    fun skipsOutlinesWithoutFeeds() {
        val opml = """<opml><body><outline text="Folder"><outline text="Show" xmlUrl="https://x.com/feed"/></outline></body></opml>"""
        assertEquals(listOf(OpmlRecovery.Outline("Show", "https://x.com/feed")), OpmlRecovery.extractOutlines(opml))
    }
}
