package com.eagertolisten.app.data.sync

import com.eagertolisten.app.data.model.editedAt
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.model.InspirationClip
import com.eagertolisten.app.parser.ParsedTranscript
import org.json.JSONObject

/**
 * Creator Inspiration Studio rules: the Drive archive, the share list and the transcript excerpt.
 * Pure logic, mirrored rule for rule by the desktop app (desktop/src/core/bookmarks.js and
 * transcript.js). Change both together.
 *
 * Workflow: capture while listening -> archive to Drive on every sync (never cleared) ->
 * add notes in a batch -> share the whole list -> clear what was shared from the app.
 */
object BookmarkArchive {
    const val ARCHIVE_FILE = "eagertolisten_bookmarks.jsonl"
    const val ARCHIVE_TMP = "eagertolisten_bookmarks.tmp.jsonl"
    const val ARCHIVE_VERSION = 1

    /** Save Idea marks the spot this many seconds before the tap, so it catches what you just heard. */
    const val LEAD_IN_SECONDS = 15L

    /** The main archive file plus Drive conflict copies like "eagertolisten_bookmarks (1).jsonl". */
    fun isArchiveFileName(name: String?): Boolean =
        name != null && name.startsWith("eagertolisten_bookmarks") && name.endsWith(".jsonl") && !name.endsWith(".tmp.jsonl")

    fun formatTs(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }

    /** Audio link that starts at the moment (media fragment; browsers and many players honor it). */
    fun momentUrl(audioUrl: String?, seconds: Long): String {
        if (audioUrl.isNullOrBlank()) return ""
        return "${audioUrl.substringBefore('#')}#t=${seconds.coerceAtLeast(0)}"
    }

    // ---------- transcript excerpt ----------

    data class Excerpt(val text: String, val timing: String)

    /** Riverside paragraphs start with the speaker's name on its own line: show it as "Name: words". */
    fun speakerLabel(paragraph: String): String {
        val nl = paragraph.indexOf('\n')
        if (nl <= 0) return paragraph
        val first = paragraph.substring(0, nl).trim()
        val rest = paragraph.substring(nl + 1).trim()
        val looksLikeName = rest.isNotEmpty() && first.length <= 40 && !Regex("[.!?,;]$").containsMatchIn(first) &&
            first.split(Regex("\\s+")).size <= 5
        return if (looksLikeName) "$first: ${rest.replace(Regex("\\s*\\n\\s*"), " ")}" else paragraph.replace(Regex("\\s*\\n\\s*"), " ")
    }

    /**
     * The words around a moment. Exact transcripts: from 5s before to 60s after. Estimated timing
     * (plain text with no timestamps) is only roughly right, so the window is wider.
     */
    fun excerptAt(parsed: ParsedTranscript, atSeconds: Long, maxChars: Int = 1500): Excerpt? {
        val cues = parsed.cues
        if (cues.isEmpty()) return null
        val at = atSeconds * 1000
        val from = at - if (parsed.estimated) 60_000 else 5_000
        val to = at + if (parsed.estimated) 90_000 else 60_000
        var picked = cues.filter { it.endMs >= from && it.startMs <= to }
        if (picked.isEmpty()) picked = listOf(cues.minByOrNull { kotlin.math.abs(it.startMs - at) }!!)
        var text = picked.joinToString(" ") { if (parsed.estimated) speakerLabel(it.text) else it.text }
            .replace(Regex("\\s+"), " ").trim()
        if (text.length > maxChars) text = text.substring(0, maxChars).replace(Regex("\\s+\\S*$"), "") + "…"
        return Excerpt(text, if (parsed.estimated) "estimated" else "exact")
    }

    // ---------- archive records (JSON Lines) ----------

    /** One archive line: everything needed to find and use the moment later, standing on its own. */
    fun record(
        clip: InspirationClip,
        episode: Episode?,
        showTitle: String?,
        feedUrl: String?,
        excerpt: Excerpt?,
        device: String,
        removedAt: Long? = null
    ): JSONObject = JSONObject().apply {
        val audio = clip.audioUrl.ifBlank { episode?.audioUrl ?: "" }
        put("type", "bookmark")
        put("v", ARCHIVE_VERSION)
        put("id", clip.id)
        put("createdAt", clip.createdAt)
        put("updatedAt", clip.editedAt)
        put("removedAt", removedAt ?: JSONObject.NULL)
        put("capturedOn", device)
        put("show", JSONObject().apply {
            put("title", showTitle?.ifBlank { null } ?: clip.podcastTitle)
            put("feedUrl", feedUrl?.ifBlank { null } ?: clip.podcastId)
        })
        put("episode", JSONObject().apply {
            put("id", clip.episodeId)
            put("title", episode?.title ?: clip.episodeTitle)
            put("link", episode?.link ?: JSONObject.NULL)
            put("publishedAt", episode?.publishedAt?.takeIf { it > 0 } ?: JSONObject.NULL)
            put("audioUrl", audio)
        })
        put("timestampSeconds", clip.timestampSeconds)
        put("timestamp", formatTs(clip.timestampSeconds))
        put("audioAtMoment", momentUrl(audio, clip.timestampSeconds))
        put("transcript", JSONObject().apply {
            put("url", episode?.transcriptUrl ?: JSONObject.NULL)
            put("type", episode?.transcriptType ?: JSONObject.NULL)
            put("excerpt", excerpt?.text ?: JSONObject.NULL)
            put("timing", excerpt?.timing ?: JSONObject.NULL)
        })
        put("note", clip.note)
        put("quote", clip.quoteText)
        put("tags", clip.tags)
    }

    data class Archive(val records: LinkedHashMap<String, JSONObject>, val bad: List<String>)

    /** Read one or more archive files; the newest version of each bookmark wins. Bad lines are kept aside. */
    fun parse(vararg texts: String): Archive {
        val records = LinkedHashMap<String, JSONObject>()
        val bad = mutableListOf<String>()
        for (text in texts) {
            for (line in text.split('\n')) {
                if (line.isBlank()) continue
                val r = try { JSONObject(line) } catch (_: Exception) { bad.add(line); continue }
                val id = r.optString("id", "")
                if (id.isEmpty()) continue
                records[id] = mergeRecord(records[id], r)
            }
        }
        return Archive(records, bad)
    }

    private fun longOrNull(o: JSONObject, key: String): Long? =
        if (o.has(key) && !o.isNull(key)) o.optLong(key) else null

    private fun mergeObj(older: JSONObject?, newer: JSONObject?): JSONObject {
        val out = JSONObject(older?.toString() ?: "{}")
        if (newer != null) {
            for (k in newer.keys()) {
                val v = newer.opt(k)
                if (v != null && v != JSONObject.NULL && v != "") out.put(k, v)
            }
        }
        return out
    }

    /** Combine two versions of one bookmark's archive record without ever losing information. */
    fun mergeRecord(a: JSONObject?, b: JSONObject?): JSONObject {
        if (a == null) return b!!
        if (b == null) return a
        val (older, newer) = if (b.optLong("updatedAt", 0) >= a.optLong("updatedAt", 0)) a to b else b to a
        val out = JSONObject(older.toString())
        for (k in newer.keys()) out.put(k, newer.opt(k))
        out.put("show", mergeObj(older.optJSONObject("show"), newer.optJSONObject("show")))
        out.put("episode", mergeObj(older.optJSONObject("episode"), newer.optJSONObject("episode")))
        out.put("transcript", mergeObj(older.optJSONObject("transcript"), newer.optJSONObject("transcript")))
        val ra = longOrNull(a, "removedAt")
        val rb = longOrNull(b, "removedAt")
        val removed = listOfNotNull(ra, rb).maxOrNull()
        out.put("removedAt", removed ?: JSONObject.NULL)
        val ca = a.optLong("createdAt", 0).takeIf { it > 0 }
        val cb = b.optLong("createdAt", 0).takeIf { it > 0 }
        listOfNotNull(ca, cb).minOrNull()?.let { out.put("createdAt", it) }
        return out
    }

    fun serialize(archive: Archive): String {
        val lines = archive.records.values
            .sortedWith(compareBy<JSONObject> { it.optLong("createdAt", 0) }.thenBy { it.optString("id") })
            .map { it.toString() }
        // Lines we couldn't read are carried along untouched rather than dropped
        return (lines + archive.bad).joinToString("\n") + "\n"
    }

    /** Distinct bookmarks (by id) plus unreadable lines: an archive write must never lower this. */
    fun entryCount(text: String): Int {
        val ids = mutableSetOf<String>()
        var other = 0
        for (line in text.split('\n')) {
            if (line.isBlank()) continue
            val id = try { JSONObject(line).optString("id", "") } catch (_: Exception) { "" }
            if (id.isEmpty()) other++ else ids.add(id)
        }
        return ids.size + other
    }

    // ---------- share list (Markdown) ----------

    data class ShareItem(
        val clip: InspirationClip,
        val showTitle: String,
        val episodeTitle: String,
        val episodeLink: String?,
        val transcriptUrl: String?,
        val excerpt: Excerpt?
    )

    /** The whole list as Markdown, grouped by show then episode, in timestamp order. */
    fun shareMarkdown(items: List<ShareItem>, sharedOn: String): String {
        val sb = StringBuilder()
        sb.append("# Podcast bookmarks (${items.size}), shared $sharedOn\n\n")
        val byShow = LinkedHashMap<String, LinkedHashMap<String, MutableList<ShareItem>>>()
        for (it in items) byShow.getOrPut(it.showTitle.ifBlank { "Unknown show" }) { LinkedHashMap() }
            .getOrPut(it.clip.episodeId) { mutableListOf() }.add(it)
        for ((show, eps) in byShow) {
            sb.append("## $show\n\n")
            for (list in eps.values) {
                val first = list.first()
                sb.append("### ${first.episodeTitle}\n")
                val links = mutableListOf<String>()
                first.episodeLink?.takeIf { it.isNotBlank() }?.let { links.add("[Episode page]($it)") }
                first.clip.audioUrl.takeIf { it.isNotBlank() }?.let { links.add("[Audio]($it)") }
                first.transcriptUrl?.takeIf { it.isNotBlank() }?.let { links.add("[Transcript]($it)") }
                if (links.isNotEmpty()) sb.append(links.joinToString(" · ")).append('\n')
                sb.append('\n')
                for (item in list.sortedBy { it.clip.timestampSeconds }) {
                    val c = item.clip
                    sb.append("- **[${formatTs(c.timestampSeconds)}](${momentUrl(c.audioUrl, c.timestampSeconds)})**")
                    if (c.note.isNotBlank()) sb.append(' ').append(c.note)
                    sb.append('\n')
                    if (c.quoteText.isNotBlank()) sb.append("  > ${c.quoteText.replace(Regex("\\n+"), " ")}\n")
                    item.excerpt?.let { ex ->
                        sb.append("  > ${ex.text}")
                        if (ex.timing == "estimated") sb.append(" *(transcript position estimated)*")
                        sb.append('\n')
                    }
                }
                sb.append('\n')
            }
        }
        return sb.toString().trimEnd() + "\n"
    }
}
