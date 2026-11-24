package com.eagertolisten.app.data.podcastindex

import com.eagertolisten.app.ui.viewmodel.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPodcastCatalogTest {

    @Test
    fun stalenessFollows21DayBoundary() {
        val now = 1_760_000_000_000L
        val dayMs = 24L * 60L * 60L * 1000L

        assertTrue("Missing DB is always stale", LocalPodcastCatalog.isStale(hasDb = false, downloadedAtMs = now, nowMs = now))
        assertTrue("Zero timestamp is stale", LocalPodcastCatalog.isStale(hasDb = true, downloadedAtMs = 0L, nowMs = now))
        assertFalse("20 days old is fresh", LocalPodcastCatalog.isStale(hasDb = true, downloadedAtMs = now - 20 * dayMs, nowMs = now))
        assertFalse("Exactly 21 days old is still fresh", LocalPodcastCatalog.isStale(hasDb = true, downloadedAtMs = now - 21 * dayMs, nowMs = now))
        assertTrue("21 days + 1 ms is stale", LocalPodcastCatalog.isStale(hasDb = true, downloadedAtMs = now - 21 * dayMs - 1L, nowMs = now))
    }

    @Test
    fun ftsQuerySanitizesSpecialCharacters() {
        assertEquals("huberman* lab*", LocalPodcastCatalog.buildFtsQuery("Huberman \"Lab\"!"))
        assertEquals("huberman* s* lab*", LocalPodcastCatalog.buildFtsQuery("Huberman's Lab"))
        assertEquals("no* agenda* 2*", LocalPodcastCatalog.buildFtsQuery("No-Agenda (2)"))
        assertNull(LocalPodcastCatalog.buildFtsQuery("   ---   "))
    }

    @Test
    fun parsesTsvHeaderAndRowsCleanly() {
        val header = LocalPodcastCatalog.parseHeader(
            """# {"version":1,"generatedAt":1750000000000,"count":35000,"source":"podcastindex.org"}"""
        )
        assertNotNull(header)
        assertEquals(1, header!!.version)
        assertEquals(1750000000000L, header.generatedAt)
        assertEquals(35000, header.count)

        val row = LocalPodcastCatalog.parseRow(
            "Huberman Lab\tDr. Andrew Huberman\thttps://feeds.megaphone.fm/hubermanlab\thttps://img/h.jpg\tScience|Health & Fitness\t1545953110\tguid-h\t1750000000"
        )
        assertNotNull(row)
        assertEquals("Huberman Lab", row!!.title)
        assertEquals("Dr. Andrew Huberman", row.author)
        assertEquals("https://feeds.megaphone.fm/hubermanlab", row.url)
        assertEquals("1545953110", row.itunesId)
        assertEquals("guid-h", row.podcastGuid)
        assertEquals(1750000000L, row.newestItemPubdateSec)

        // Blank, comment, or incomplete lines are skipped
        assertNull(LocalPodcastCatalog.parseRow(""))
        assertNull(LocalPodcastCatalog.parseRow("# comment"))
        assertNull(LocalPodcastCatalog.parseRow("OnlyTitle\tOnlyAuthor"))
        assertNull(LocalPodcastCatalog.parseRow("\tAuthor\thttps://example.com/rss"))
    }

    @Test
    fun mapsCategoryChipsToFtsQueries() {
        assertEquals(listOf("health*", "fitness*"), LocalPodcastCatalog.categoryFtsQueries("Health & Fitness"))
        assertEquals(listOf("self* help*", "mental* health*", "health*"), LocalPodcastCatalog.categoryFtsQueries("Self-Help"))
        assertEquals(listOf("news*"), LocalPodcastCatalog.categoryFtsQueries("News"))
        assertEquals(listOf("technology*"), LocalPodcastCatalog.categoryFtsQueries("Technology"))
    }

    @Test
    fun strictItunesFallbackOnlyRunsWhenPodcastIndexIsEmpty() {
        val piLocalShow = SearchResult(
            title = "Huberman Lab",
            author = "Dr. Andrew Huberman",
            feedUrl = "https://feeds.megaphone.fm/hubermanlab",
            artworkUrl = "",
            genres = listOf("Science"),
            source = "Podcast Index (Local)"
        )
        val appleShow = SearchResult(
            title = "Brand New Indie Show",
            author = "Jane Doe",
            feedUrl = "https://example.com/new.rss",
            artworkUrl = "",
            genres = listOf("Health"),
            source = "Apple Podcasts"
        )

        var appleCalled = false
        val fromPi = PodcastIndexClient.resolveWithFallback(
            query = "huberman",
            podcastIndexLists = listOf(emptyList(), listOf(piLocalShow)),
            appleFallbackEnabled = true
        ) {
            appleCalled = true
            listOf(appleShow)
        }
        assertFalse("Apple fallback must NOT be called when Podcast Index found results", appleCalled)
        assertEquals(1, fromPi.size)
        assertEquals("Huberman Lab", fromPi[0].title)

        // When Podcast Index (API + local) returns nothing, Apple fallback runs
        appleCalled = false
        val fromApple = PodcastIndexClient.resolveWithFallback(
            query = "brand new indie",
            podcastIndexLists = listOf(emptyList(), emptyList()),
            appleFallbackEnabled = true
        ) {
            appleCalled = true
            listOf(appleShow)
        }
        assertTrue("Apple fallback MUST run when Podcast Index returns nothing", appleCalled)
        assertEquals(1, fromApple.size)
        assertEquals("Brand New Indie Show", fromApple[0].title)

        // When Apple fallback is disabled, never call Apple even when Podcast Index is empty
        appleCalled = false
        val none = PodcastIndexClient.resolveWithFallback(
            query = "brand new indie",
            podcastIndexLists = listOf(emptyList(), emptyList()),
            appleFallbackEnabled = false
        ) {
            appleCalled = true
            listOf(appleShow)
        }
        assertFalse("Apple fallback must NOT run when disabled in Settings", appleCalled)
        assertTrue(none.isEmpty())
    }
}
