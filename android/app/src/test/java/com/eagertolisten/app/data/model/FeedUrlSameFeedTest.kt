package com.eagertolisten.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedUrlSameFeedTest {

    @Test
    fun matchesAcrossSchemeCaseAndTrailingSlash() {
        assertEquals(true, FeedUrl.sameFeed("https://feeds.example.com/show.xml", "http://feeds.example.com/show.xml"))
        assertEquals(true, FeedUrl.sameFeed("https://Feeds.Example.com/show/", "https://feeds.example.com/show"))
        assertEquals(true, FeedUrl.sameFeed("  https://feeds.example.com/show  ", "https://feeds.example.com/show"))
    }

    @Test
    fun differentFeedsDoNotMatch() {
        assertEquals(false, FeedUrl.sameFeed("https://feeds.example.com/show-a", "https://feeds.example.com/show-b"))
        assertEquals(false, FeedUrl.sameFeed("", ""))
    }
}
