package com.eagertolisten.app.importer

import com.eagertolisten.app.data.model.Podcast
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object OpmlExporter {

    fun generateOpml(podcasts: List<Podcast>): String {
        val now = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().atZone(ZoneOffset.UTC))
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<opml version=\"2.0\">\n")
        sb.append("  <head>\n")
        sb.append("    <title>Eager to Listen Subscriptions</title>\n")
        sb.append("    <dateCreated>").append(now).append("</dateCreated>\n")
        sb.append("  </head>\n")
        sb.append("  <body>\n")
        for (podcast in podcasts) {
            val title = if (podcast.title.isNotBlank()) podcast.title else "Untitled Podcast"
            val titleEsc = escapeXml(title)
            val feedUrlEsc = escapeXml(podcast.feedUrl)
            sb.append("    <outline text=\"").append(titleEsc).append("\" title=\"").append(titleEsc)
                .append("\" type=\"rss\" xmlUrl=\"").append(feedUrlEsc).append("\"")
            if (podcast.category.isNotBlank()) {
                sb.append(" category=\"").append(escapeXml(podcast.category)).append("\"")
            }
            sb.append(" />\n")
        }
        sb.append("  </body>\n")
        sb.append("</opml>\n")
        return sb.toString()
    }

    fun writeToStream(podcasts: List<Podcast>, outputStream: OutputStream) {
        val content = generateOpml(podcasts)
        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(content)
        }
    }

    fun escapeXml(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
