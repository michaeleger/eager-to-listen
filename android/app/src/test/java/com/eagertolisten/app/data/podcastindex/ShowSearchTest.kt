package com.eagertolisten.app.data.podcastindex

import com.eagertolisten.app.ui.viewmodel.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as desktop/test/feedLink.test.js (search part): both apps rank and merge alike. */
class ShowSearchTest {
    private fun r(title: String, author: String = "", feedUrl: String = "https://x.example/$title") =
        SearchResult(title = title, author = author, feedUrl = feedUrl, artworkUrl = "", genres = emptyList())

    @Test
    fun titleMatchesRankFirst() {
        assertEquals(100, ShowSearch.matchScore("Huberman Lab", r("Huberman Lab")))
        assertEquals(80, ShowSearch.matchScore("huberman", r("Huberman Lab Clips")))
        assertEquals(60, ShowSearch.matchScore("lab", r("The Huberman Lab Show")))
        assertEquals(40, ShowSearch.matchScore("lab huberman", r("The Huberman Lab Show")))
        assertEquals(20, ShowSearch.matchScore("eger healthy", r("Eager to Be Healthy", "Michael Eger")))
        assertEquals(0, ShowSearch.matchScore("cooking", r("Eager to Be Healthy")))
    }

    @Test
    fun bothDirectoriesMerge_newShowsFromAppleShowUp() {
        val pi = ShowSearch.fromPodcastIndex(
            """{"feeds":[
              {"title":"Healthy Living Daily","author":"A","url":"https://a.example/rss","newestItemPubdate":1700000000},
              {"title":"Old Dead Show","author":"B","url":"https://dead.example/rss","dead":1},
              {"title":"Eager to Be Healthy","author":"Michael Scott Eger","url":"http://old-host.example/eager.rss","itunesId":555,"newestItemPubdate":1600000000,"podcastGuid":null}
            ]}"""
        )
        val apple = ShowSearch.fromApple(
            """{"results":[
              {"collectionName":"Brand New Health Show","artistName":"C","feedUrl":"https://new.example/rss","collectionId":777,"releaseDate":"2026-11-08T10:00:00Z"},
              {"collectionName":"Eager to Be Healthy","artistName":"Michael Scott Eger","feedUrl":"https://api.riverside.com/hosting/drlxDc7x.rss","collectionId":555,"releaseDate":"2026-11-01T10:00:00Z"},
              {"collectionName":"No feed here","artistName":"D"}
            ]}"""
        )
        // JSON null must not turn into the text "null"
        assertEquals(null, pi[2].podcastGuid)

        val out = ShowSearch.merge("eager to be healthy", listOf(pi, apple))
        assertEquals("Eager to Be Healthy", out[0].title)
        // The duplicate kept the address with the newest episode (Apple's current one)
        assertEquals("https://api.riverside.com/hosting/drlxDc7x.rss", out[0].feedUrl)
        assertEquals(1, out.count { it.title == "Eager to Be Healthy" })
        assertTrue(out.any { it.title == "Brand New Health Show" })
        assertFalse(out.any { it.title == "Old Dead Show" })
        assertFalse(out.any { it.title == "No feed here" })
    }

    @Test
    fun oneDirectoryDown_theOtherStillAnswers() {
        val apple = listOf(r("Only Apple", "X", "https://x.example/rss"))
        assertEquals(listOf("Only Apple"), ShowSearch.merge("only", listOf(emptyList(), apple)).map { it.title })
        assertEquals(listOf("Only Apple"), ShowSearch.merge("only", listOf(null, apple)).map { it.title })
    }

    @Test
    fun sameAddressOverHttpAndHttpsIsOneShow() {
        val out = ShowSearch.merge(
            "x",
            listOf(listOf(r("X One", "", "http://Example.com/feed/")), listOf(r("X Show", "someone", "https://example.com/feed")))
        )
        assertEquals(1, out.size)
    }
}
