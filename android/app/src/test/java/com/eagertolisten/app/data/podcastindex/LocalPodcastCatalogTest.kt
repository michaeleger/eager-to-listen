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
    fun needsUnpackDetectsMissingOrUpdatedBundledSnapshot() {
        val v1 = 1_750_000_000_000L
        val v2 = 1_760_000_000_000L

        assertTrue("Missing DB must unpack", LocalPodcastCatalog.needsUnpack(hasDb = false, installedGeneratedAtMs = v1, bundledGeneratedAtMs = v1))
        assertTrue("Zero installed timestamp must unpack", LocalPodcastCatalog.needsUnpack(hasDb = true, installedGeneratedAtMs = 0L, bundledGeneratedAtMs = v1))
        assertFalse("Same bundled timestamp skips unpack", LocalPodcastCatalog.needsUnpack(hasDb = true, installedGeneratedAtMs = v1, bundledGeneratedAtMs = v1))
        assertTrue("Newer bundled snapshot triggers unpack", LocalPodcastCatalog.needsUnpack(hasDb = true, installedGeneratedAtMs = v1, bundledGeneratedAtMs = v2))
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
            """# {"version":1,"generatedAt":1750000000000,"count":25000,"source":"podcastindex.org"}"""
        )
        assertNotNull(header)
        assertEquals(1, header!!.version)
        assertEquals(1750000000000L, header.generatedAt)
        assertEquals(25000, header.count)

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
    fun tieredWaterfallShieldsPodcastIndexApiAndFallsBackToApple() {
        val piLocalShow = SearchResult(
            title = "Huberman Lab",
            author = "Dr. Andrew Huberman",
            feedUrl = "https://feeds.megaphone.fm/hubermanlab",
            artworkUrl = "",
            genres = listOf("Science"),
            source = "Podcast Index (Local)"
        )
        val piApiShow = SearchResult(
            title = "Niche Podcasting 2.0 Show",
            author = "Indie Host",
            feedUrl = "https://example.com/niche.rss",
            artworkUrl = "",
            genres = listOf("Technology"),
            source = "Podcast Index"
        )
        val appleShow = SearchResult(
            title = "Brand New Indie Show",
            author = "Jane Doe",
            feedUrl = "https://example.com/new.rss",
            artworkUrl = "",
            genres = listOf("Health"),
            source = "Apple Podcasts"
        )

        // Tier 1 hit: neither Podcast Index API nor Apple is queried
        var apiCalled = false
        var appleCalled = false
        val fromLocal = PodcastIndexClient.resolveTiered(
            query = "huberman",
            localResults = listOf(piLocalShow),
            hasPodcastIndexApi = true,
            queryPodcastIndexApi = {
                apiCalled = true
                listOf(piApiShow)
            },
            appleFallbackEnabled = true,
            queryAppleFallback = {
                appleCalled = true
                listOf(appleShow)
            }
        )
        assertFalse("Live Podcast Index API must NOT be called when local copy matches", apiCalled)
        assertFalse("Apple fallback must NOT be called when local copy matches", appleCalled)
        assertEquals(listOf("Huberman Lab"), fromLocal.map { it.title })

        // Tier 1 miss -> Tier 2 hit: live Podcast Index API is queried, Apple is NOT
        apiCalled = false
        appleCalled = false
        val fromApi = PodcastIndexClient.resolveTiered(
            query = "niche",
            localResults = emptyList(),
            hasPodcastIndexApi = true,
            queryPodcastIndexApi = {
                apiCalled = true
                listOf(piApiShow)
            },
            appleFallbackEnabled = true,
            queryAppleFallback = {
                appleCalled = true
                listOf(appleShow)
            }
        )
        assertTrue("Live Podcast Index API MUST be called when local copy misses", apiCalled)
        assertFalse("Apple fallback must NOT be called when live Podcast Index API matches", appleCalled)
        assertEquals(listOf("Niche Podcasting 2.0 Show"), fromApi.map { it.title })

        // Tier 1 miss + Tier 2 miss -> Tier 3 Apple fallback runs
        apiCalled = false
        appleCalled = false
        val fromApple = PodcastIndexClient.resolveTiered(
            query = "brand new indie",
            localResults = emptyList(),
            hasPodcastIndexApi = true,
            queryPodcastIndexApi = {
                apiCalled = true
                emptyList()
            },
            appleFallbackEnabled = true,
            queryAppleFallback = {
                appleCalled = true
                listOf(appleShow)
            }
        )
        assertTrue(apiCalled)
        assertTrue("Apple fallback MUST run when both Podcast Index tiers return nothing", appleCalled)
        assertEquals(listOf("Brand New Indie Show"), fromApple.map { it.title })

        // When Apple fallback is disabled, never call Apple even when both Podcast Index tiers miss
        appleCalled = false
        val none = PodcastIndexClient.resolveTiered(
            query = "brand new indie",
            localResults = emptyList(),
            hasPodcastIndexApi = false,
            queryPodcastIndexApi = { emptyList() },
            appleFallbackEnabled = false,
            queryAppleFallback = {
                appleCalled = true
                listOf(appleShow)
            }
        )
        assertFalse("Apple fallback must NOT run when disabled in Settings", appleCalled)
        assertTrue(none.isEmpty())
    }
}
