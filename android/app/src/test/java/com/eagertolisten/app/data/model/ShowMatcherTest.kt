package com.eagertolisten.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ShowMatcherTest {

    private val noAgenda = ShowMatcher.Candidate(
        id = "https://feeds.noagendaassets.com/noagenda.xml",
        feedUrl = "https://feeds.noagendaassets.com/noagenda.xml",
        podcastGuid = "856cd618-7f34-57ea-9b84-3600f1f65e7f"
    )
    private val moved = ShowMatcher.Candidate(
        id = "https://old.example.com/feed",          // id keeps the original address
        feedUrl = "https://new.example.com/feed.xml", // feedUrl follows the move
        podcastGuid = null
    )
    private val existing = listOf(noAgenda, moved)

    @Test
    fun matchesAddressVariants() {
        assertEquals(noAgenda, ShowMatcher.findMatch(existing, "http://feeds.noagendaassets.com/noagenda.xml"))
        assertEquals(noAgenda, ShowMatcher.findMatch(existing, "HTTPS://Feeds.NoAgendaAssets.com/noagenda.xml/"))
    }

    @Test
    fun matchesBothOriginalAndMovedAddress() {
        assertEquals(moved, ShowMatcher.findMatch(existing, "http://old.example.com/feed"))
        assertEquals(moved, ShowMatcher.findMatch(existing, "https://new.example.com/feed.xml"))
    }

    @Test
    fun matchesSameShowAtDifferentAddressByGuid() {
        assertEquals(noAgenda, ShowMatcher.findMatch(existing, "https://mirror.example.net/na.rss", "856CD618-7F34-57EA-9B84-3600F1F65E7F"))
        assertEquals(noAgenda, ShowMatcher.findMatch(existing, "", "856cd618-7f34-57ea-9b84-3600f1f65e7f"))
    }

    @Test
    fun differentShowsDoNotMatch() {
        assertEquals(null, ShowMatcher.findMatch(existing, "https://feeds.example.com/other"))
        assertEquals(null, ShowMatcher.findMatch(existing, "https://feeds.example.com/other", "some-other-guid"))
        assertEquals(null, ShowMatcher.findMatch(existing, "", null))
        assertEquals(null, ShowMatcher.findMatch(existing, "https://x.com/a", "  "))
    }

    @Test
    fun importResolverReusesExistingIdsAndDedupesWithinOneFile() {
        val r = ShowMatcher.ImportResolver(existing)
        assertEquals(noAgenda.id, r.resolve("http://feeds.noagendaassets.com/noagenda.xml"))
        assertEquals(moved.id, r.resolve("https://new.example.com/feed.xml"))
        // New show, listed twice in the same file under http and https
        val first = r.resolve("https://brand.new/show")
        assertEquals("https://brand.new/show", first)
        assertEquals(first, r.resolve("http://brand.new/show/"))
        // New show listed twice with the same guid at different addresses
        val a = r.resolve("https://a.example/feed", "guid-xyz")
        assertEquals(a, r.resolve("https://b.example/feed", "GUID-XYZ"))
    }
}
