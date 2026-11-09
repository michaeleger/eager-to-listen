package com.eagertolisten.app.data.model

import com.eagertolisten.app.data.model.FeedLink.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as desktop/test/feedLink.test.js: both apps must read links the same way. */
class FeedLinkTest {
    private fun feed(url: String, guessed: Boolean = false) = Target.Feed(url, guessed)

    @Test
    fun linksDefaultToPriority2() {
        assertEquals(2, FeedLink.DEFAULT_PRIORITY)
    }

    @Test
    fun plainFeedAddressesPassThrough() {
        assertEquals(feed("https://api.riverside.com/hosting/drlxDc7x.rss"), FeedLink.resolve("https://api.riverside.com/hosting/drlxDc7x.rss"))
        assertEquals(feed("http://example.com/feed.xml"), FeedLink.resolve("  http://example.com/feed.xml  "))
    }

    @Test
    fun podcastAppSchemesBecomeHttpsFeeds() {
        assertEquals(feed("https://example.com/rss", true), FeedLink.resolve("feed://example.com/rss"))
        assertEquals(feed("https://feeds.example.com/show.xml", true), FeedLink.resolve("podcast://feeds.example.com/show.xml"))
        assertEquals(feed("https://example.com/podcast.xml", true), FeedLink.resolve("itpc://example.com/podcast.xml"))
        assertEquals(feed("https://example.com/podcast.xml", true), FeedLink.resolve("pcast://example.com/podcast.xml"))
        assertEquals(feed("https://example.com/rss"), FeedLink.resolve("feed:https://example.com/rss"))
        assertEquals(feed("http://example.com/rss"), FeedLink.resolve("feed:http://example.com/rss"))
        assertEquals("http://example.com/rss", FeedLink.httpFallback("https://example.com/rss"))
        assertNull(FeedLink.httpFallback("http://example.com/rss"))
    }

    @Test
    fun subscribeOnAndroidUnwraps() {
        assertEquals(feed("https://feeds.example.com/show.xml", true), FeedLink.resolve("https://subscribeonandroid.com/feeds.example.com/show.xml"))
        assertEquals(feed("https://feeds.example.com/show.xml"), FeedLink.resolve("https://www.subscribeonandroid.com/https://feeds.example.com/show.xml"))
    }

    @Test
    fun appleAndOvercastResolveByAppleId() {
        assertEquals(Target.Apple("1781234567"), FeedLink.resolve("https://podcasts.apple.com/us/podcast/eager-to-be-healthy/id1781234567"))
        assertEquals(Target.Apple("42"), FeedLink.resolve("https://podcasts.apple.com/us/podcast/x/id42?i=1000600000000"))
        assertEquals(Target.Apple("99"), FeedLink.resolve("https://itunes.apple.com/podcast/id99"))
        assertEquals(Target.Apple("1781234567"), FeedLink.resolve("https://overcast.fm/itunes1781234567/eager-to-be-healthy"))
    }

    @Test
    fun linkInsideSharedTextIsFound() {
        assertEquals(feed("https://example.com/feed.xml"), FeedLink.resolve("Great show! https://example.com/feed.xml)."))
        assertEquals(Target.Apple("7"), FeedLink.resolve("Eager to Be Healthy\nhttps://podcasts.apple.com/us/podcast/x/id7"))
        assertEquals(Target.Search("huberman lab"), FeedLink.resolve("huberman lab"))
        assertEquals(Target.None, FeedLink.resolve("   "))
        assertEquals(Target.None, FeedLink.resolve("feed://"))
        assertEquals(Target.Search("Progress: the podcast"), FeedLink.resolve("Progress: the podcast"))
        assertEquals(feed("https://example.com/rss"), FeedLink.resolve("Address: https://example.com/rss"))
    }

    @Test
    fun webPagesAreToldApartFromFeeds() {
        assertTrue(FeedLink.looksLikeHtml("<!DOCTYPE html><html>", ""))
        assertTrue(FeedLink.looksLikeHtml("<?xml version=\"1.0\"?><rss>", "text/html"))
        assertFalse(FeedLink.looksLikeHtml("<?xml version=\"1.0\"?><rss>", "application/rss+xml"))
        assertFalse(FeedLink.looksLikeHtml("<?xml version=\"1.0\"?><rss>", "text/xml"))
        assertTrue(FeedLink.looksLikeHtml("  <html lang=\"en\">", null))
    }

    @Test
    fun feedLinksAreFoundOnAWebPage() {
        val html = """<html><head>
            <link rel="stylesheet" href="/a.css">
            <link rel="alternate" type="application/rss+xml" title="Podcast" href="/podcast/feed.xml">
            <link type='application/atom+xml' rel='alternate' href='https://cdn.example.com/atom?x=1&amp;y=2'>
            <link rel="alternate" type="application/rss+xml" href="https://example.com/comments/feed/">
            <link rel="alternate" type="application/rss+xml" href="/podcast/feed.xml">
            <link rel="alternate" hreflang="es" href="/es/">
        </head></html>"""
        assertEquals(
            listOf("https://example.com/podcast/feed.xml", "https://cdn.example.com/atom?x=1&y=2"),
            FeedLink.discoverFeedLinks(html, "https://example.com/show/")
        )
        assertEquals(emptyList<String>(), FeedLink.discoverFeedLinks("<html></html>", "https://example.com/"))
    }

    @Test
    fun feedFileSaysWhereItLives() {
        val selfOnly = """<?xml version="1.0"?><rss xmlns:atom="http://www.w3.org/2005/Atom"><channel>
            <title>Show</title><link>https://show.example.com/</link>
            <atom:link href="https://feeds.example.com/show.xml?a=1&amp;b=2" rel="self" type="application/rss+xml"/>
            </channel></rss>"""
        assertEquals("https://feeds.example.com/show.xml?a=1&b=2", FeedLink.feedUrlFromXml(selfOnly))
        // A moved show: the new address wins over the old self link
        val moved = selfOnly.replace("<title>", "<itunes:new-feed-url>https://new.example.com/feed</itunes:new-feed-url><title>")
        assertEquals("https://new.example.com/feed", FeedLink.feedUrlFromXml(moved))
        // rel="hub" and other links aren't the feed
        assertEquals(null, FeedLink.feedUrlFromXml("""<rss><channel><atom:link rel="hub" href="https://pubsub.example.com/"/></channel></rss>"""))
        assertEquals(null, FeedLink.feedUrlFromXml(null))
    }
}
