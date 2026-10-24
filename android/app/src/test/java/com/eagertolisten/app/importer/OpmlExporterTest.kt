package com.eagertolisten.app.importer

import com.eagertolisten.app.data.model.Podcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpmlExporterTest {

    @Test
    fun testEscapeXml() {
        val input = "Rock & Roll <Show> \"Best of '90s\""
        val expected = "Rock &amp; Roll &lt;Show&gt; &quot;Best of &apos;90s&quot;"
        assertEquals(expected, OpmlExporter.escapeXml(input))
    }

    @Test
    fun testGenerateOpml_containsValidOutlinesAndHeaders() {
        val podcasts = listOf(
            Podcast(
                id = "https://example.com/feed1.xml",
                feedUrl = "https://example.com/feed1.xml?a=1&b=2",
                title = "Tech & Gadgets <Daily>",
                artworkUrl = "",
                author = "",
                description = "",
                category = "Technology & Science"
            ),
            Podcast(
                id = "https://example.com/feed2.xml",
                feedUrl = "https://example.com/feed2.xml",
                title = "Simple Audio",
                artworkUrl = "",
                author = "",
                description = "",
                category = ""
            )
        )

        val xml = OpmlExporter.generateOpml(podcasts)

        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(xml.contains("<opml version=\"2.0\">"))
        assertTrue(xml.contains("<title>Eager to Listen Subscriptions</title>"))
        assertTrue(xml.contains("<dateCreated>"))

        // Escaped attributes
        assertTrue(xml.contains("xmlUrl=\"https://example.com/feed1.xml?a=1&amp;b=2\""))
        assertTrue(xml.contains("title=\"Tech &amp; Gadgets &lt;Daily&gt;\""))
        assertTrue(xml.contains("category=\"Technology &amp; Science\""))

        // Second podcast without category
        assertTrue(xml.contains("xmlUrl=\"https://example.com/feed2.xml\""))
        assertTrue(xml.contains("title=\"Simple Audio\""))
        assertTrue(xml.endsWith("</opml>\n"))
    }
}
