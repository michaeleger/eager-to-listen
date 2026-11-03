package com.eagertolisten.app.data.sync

import com.eagertolisten.app.data.model.editedAt
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.model.InspirationClip
import com.eagertolisten.app.data.model.TranscriptCue
import com.eagertolisten.app.parser.ParsedTranscript
import com.eagertolisten.app.parser.TranscriptParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors desktop/test/bookmarks.test.js so both apps write the same archive and share list. */
class BookmarkArchiveTest {

    private val episode = Episode(
        id = "F::1", podcastId = "F", podcastTitle = "Eager to Be Healthy", title = "JR Rennie",
        audioUrl = "https://x/1.mp3", publishedAt = 1000L, transcriptUrl = "https://x/1.vtt",
        transcriptType = "text/vtt", link = "https://x/ep1"
    )

    private fun clip(id: String, ts: Long, note: String = "", created: Long = 1000L, updated: Long = 0L) = InspirationClip(
        id = id, episodeId = "F::1", podcastId = "F", podcastTitle = "Eager to Be Healthy", episodeTitle = "JR Rennie",
        timestampSeconds = ts, audioUrl = "https://x/1.mp3", note = note, createdAt = created, updatedAt = updated
    )

    @Test
    fun recordHasEveryField() {
        val r = BookmarkArchive.record(
            clip("b1", 85, "blog idea", updated = 1500L), episode, "Eager to Be Healthy", "https://x/feed",
            BookmarkArchive.Excerpt("Good stuff here.", "exact"), "pixel"
        )
        assertEquals("bookmark", r.getString("type"))
        assertEquals(1500L, r.getLong("updatedAt"))
        assertTrue(r.isNull("removedAt"))
        assertEquals("Eager to Be Healthy", r.getJSONObject("show").getString("title"))
        assertEquals("https://x/ep1", r.getJSONObject("episode").getString("link"))
        assertEquals("https://x/1.mp3#t=85", r.getString("audioAtMoment"))
        assertEquals("01:25", r.getString("timestamp"))
        assertEquals("https://x/1.vtt", r.getJSONObject("transcript").getString("url"))
        assertEquals("Good stuff here.", r.getJSONObject("transcript").getString("excerpt"))
        assertEquals("blog idea", r.getString("note"))
    }

    @Test
    fun parseMergesCopiesKeepsBadLinesAndNewestWins() {
        val old = JSONObject().put("id", "b1").put("createdAt", 1000).put("updatedAt", 1000).put("note", "old")
            .put("transcript", JSONObject().put("excerpt", "kept excerpt"))
        val new = JSONObject().put("id", "b1").put("createdAt", 1000).put("updatedAt", 2000).put("note", "new")
            .put("transcript", JSONObject().put("excerpt", JSONObject.NULL)).put("removedAt", JSONObject.NULL)
        val removed = JSONObject().put("id", "b1").put("updatedAt", 500).put("removedAt", 3000)
        val a = BookmarkArchive.parse("$old\nnot json\n", "$new\n", "$removed\n")
        val r = a.records.getValue("b1")
        assertEquals("new", r.getString("note"))
        assertEquals("kept excerpt", r.getJSONObject("transcript").getString("excerpt"))
        assertEquals(3000L, r.getLong("removedAt"))
        assertEquals(listOf("not json"), a.bad)
        val text = BookmarkArchive.serialize(a)
        assertTrue(text.endsWith("not json\n"))
        assertEquals(2, BookmarkArchive.entryCount(text))
    }

    @Test
    fun shareListGroupedInTimestampOrderWithLinks() {
        val items = listOf(
            BookmarkArchive.ShareItem(clip("b2", 285), "Eager to Be Healthy", "JR Rennie", "https://x/ep1", "https://x/1.vtt", null),
            BookmarkArchive.ShareItem(clip("b1", 85, "open with this"), "Eager to Be Healthy", "JR Rennie", "https://x/ep1", "https://x/1.vtt",
                BookmarkArchive.Excerpt("So I quit my job.", "estimated"))
        )
        val md = BookmarkArchive.shareMarkdown(items, "Nov 3, 2026")
        assertTrue(md.startsWith("# Podcast bookmarks (2), shared Nov 3, 2026\n\n## Eager to Be Healthy\n\n### JR Rennie\n"))
        assertTrue(md.contains("[Episode page](https://x/ep1) · [Audio](https://x/1.mp3) · [Transcript](https://x/1.vtt)"))
        assertTrue(md.indexOf("[01:25]") < md.indexOf("[04:45]"))
        assertTrue(md.contains("- **[01:25](https://x/1.mp3#t=85)** open with this\n  > So I quit my job. *(transcript position estimated)*"))
    }

    @Test
    fun excerptExactAndEstimated() {
        val vtt = "WEBVTT\n\n00:01:50.000 --> 00:01:58.000\nBefore the moment.\n\n00:02:00.000 --> 00:02:10.000\nThe <b>moment</b> itself.\n\n00:05:00.000 --> 00:05:05.000\nMuch later."
        val ex = BookmarkArchive.excerptAt(TranscriptParser().parseWithTiming(vtt, "text/vtt"), 115)!!
        assertEquals("exact", ex.timing)
        assertEquals("Before the moment. The moment itself.", ex.text)

        val plain = (0 until 10).joinToString("\n\n") { i -> "Speaker ${if (i % 2 == 1) "B" else "A"}\nParagraph $i words words words words" }
        val parsed = TranscriptParser().parseWithTiming(plain, "text/plain", 1000)
        assertTrue(parsed.estimated)
        val ex2 = BookmarkArchive.excerptAt(parsed, 500)!!
        assertEquals("estimated", ex2.timing)
        assertTrue(ex2.text.contains("Speaker B: Paragraph 5"))
    }

    @Test
    fun helpers() {
        assertEquals("1:02:05", BookmarkArchive.formatTs(3725))
        assertEquals("https://x/a.mp3#t=10", BookmarkArchive.momentUrl("https://x/a.mp3#t=4", 10))
        assertTrue(BookmarkArchive.isArchiveFileName("eagertolisten_bookmarks (2).jsonl"))
        assertFalse(BookmarkArchive.isArchiveFileName("eagertolisten_sync.json"))
        assertFalse(BookmarkArchive.isArchiveFileName(BookmarkArchive.ARCHIVE_TMP))
        assertEquals(1000L, clip("x", 1).editedAt)
        assertEquals(ParsedTranscript(emptyList<TranscriptCue>(), false), TranscriptParser().parseWithTiming("  "))
    }
}
