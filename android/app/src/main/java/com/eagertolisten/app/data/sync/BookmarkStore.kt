package com.eagertolisten.app.data.sync

import com.eagertolisten.app.data.model.editedAt
import android.content.Context
import com.eagertolisten.app.data.model.InspirationClip
import org.json.JSONObject

/**
 * Bookkeeping for bookmarks (Creator Inspiration Studio), kept in SharedPreferences:
 * - removals: bookmarks deleted or cleared (id -> removedAt), synced so other devices drop them
 * - archived: what's confirmed in the Drive archive (id -> editedAt), so "Clear" is only offered
 *   once a bookmark is safe
 * - held: copies of bookmarks removed before they reached the archive, written on the next pass
 * - excerpts: transcript excerpt cache (id -> text + timing)
 */
class BookmarkStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("eager_bookmarks", Context.MODE_PRIVATE)

    @Synchronized fun removals(): Map<String, Long> = readLongMap(KEY_REMOVED)

    @Synchronized fun saveRemovals(map: Map<String, Long>) {
        val now = System.currentTimeMillis()
        writeLongMap(KEY_REMOVED, map.filterValues { now - it <= REMOVED_MAX_AGE_MS })
    }

    @Synchronized fun archived(): Map<String, Long> = readLongMap(KEY_ARCHIVED)
    @Synchronized fun saveArchived(map: Map<String, Long>) = writeLongMap(KEY_ARCHIVED, map)

    fun isArchived(clip: InspirationClip): Boolean = (archived()[clip.id] ?: -1L) >= clip.editedAt

    /** Record a delete or clear. Bookmarks not yet in the archive are held until the next archive pass. */
    @Synchronized fun recordRemoval(clips: Collection<InspirationClip>, now: Long = System.currentTimeMillis()) {
        if (clips.isEmpty()) return
        val removed = removals().toMutableMap()
        val archivedNow = archived()
        val held = heldJson()
        for (c in clips) {
            val at = maxOf(now, c.editedAt)
            if ((removed[c.id] ?: -1L) < at) removed[c.id] = at
            if ((archivedNow[c.id] ?: -1L) < c.editedAt) held.put(c.id, clipToJson(c))
        }
        saveRemovals(removed)
        prefs.edit().putString(KEY_HELD, held.toString()).apply()
    }

    @Synchronized fun held(): List<InspirationClip> {
        val o = heldJson()
        return o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.let { clipFromJson(it) } }.toList()
    }

    @Synchronized fun clearHeld() {
        prefs.edit().remove(KEY_HELD).apply()
    }

    @Synchronized fun excerpt(id: String): BookmarkArchive.Excerpt? {
        val o = try { JSONObject(prefs.getString(KEY_EXCERPTS, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val e = o.optJSONObject(id) ?: return null
        return BookmarkArchive.Excerpt(e.optString("text"), e.optString("timing", "exact"))
    }

    @Synchronized fun saveExcerpts(map: Map<String, BookmarkArchive.Excerpt>) {
        if (map.isEmpty()) return
        val o = try { JSONObject(prefs.getString(KEY_EXCERPTS, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        for ((id, e) in map) o.put(id, JSONObject().put("text", e.text).put("timing", e.timing))
        prefs.edit().putString(KEY_EXCERPTS, o.toString()).apply()
    }

    private fun heldJson(): JSONObject = try { JSONObject(prefs.getString(KEY_HELD, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    private fun readLongMap(key: String): Map<String, Long> = try {
        val o = JSONObject(prefs.getString(key, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.optLong(it) }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun writeLongMap(key: String, map: Map<String, Long>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString(key, o.toString()).apply()
    }

    companion object {
        private const val KEY_REMOVED = "removed"
        private const val KEY_ARCHIVED = "archived"
        private const val KEY_HELD = "held"
        private const val KEY_EXCERPTS = "excerpts"
        private const val REMOVED_MAX_AGE_MS = 365L * 24 * 60 * 60 * 1000

        fun clipToJson(c: InspirationClip): JSONObject = JSONObject().apply {
            put("id", c.id); put("episodeId", c.episodeId); put("podcastId", c.podcastId)
            put("podcastTitle", c.podcastTitle); put("episodeTitle", c.episodeTitle)
            put("timestampSeconds", c.timestampSeconds); put("audioUrl", c.audioUrl)
            put("note", c.note); put("quoteText", c.quoteText); put("tags", c.tags)
            put("createdAt", c.createdAt); put("updatedAt", c.updatedAt)
        }

        fun clipFromJson(o: JSONObject): InspirationClip = InspirationClip(
            id = o.getString("id"),
            episodeId = o.optString("episodeId", ""),
            podcastId = o.optString("podcastId", ""),
            podcastTitle = o.optString("podcastTitle", ""),
            episodeTitle = o.optString("episodeTitle", ""),
            timestampSeconds = o.optLong("timestampSeconds", 0L),
            audioUrl = o.optString("audioUrl", ""),
            note = o.optString("note", ""),
            quoteText = o.optString("quoteText", ""),
            tags = o.optString("tags", ""),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L)
        )
    }
}
