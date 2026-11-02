package com.eagertolisten.app.data.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tombstones for pinned (manual) queue items a person removed by hand (review item R2.7).
 *
 * Without these, a pin removed on one device came back on the next sync because the other
 * device still had it. Each removal is recorded as episodeId -> removedAt (epoch ms) and
 * travels in the sync file as `manualQueueRemoved`. The rule is simply "newer wins":
 * a pin added after the removal stays; a removal at or after the pin's time takes it out.
 *
 * Pure logic lives here so it can be unit tested and mirrored exactly by the desktop client
 * (desktop/src/core/sync.js).
 */
object UnpinRules {
    /** Tombstones older than this are dropped; by then every device has long since synced. */
    const val MAX_AGE_MS: Long = 90L * 24 * 60 * 60 * 1000

    /** A remote pin is accepted only if it was added after the last removal we know about. */
    fun pinWins(pinUpdatedAt: Long, removedAt: Long?): Boolean =
        removedAt == null || pinUpdatedAt > removedAt

    /** A local pin is removed when a removal is at least as new as the pin. */
    fun removalWins(pinAddedAt: Long, removedAt: Long?): Boolean =
        removedAt != null && removedAt >= pinAddedAt

    /** Union of two tombstone sets; the newer removal time wins per episode. */
    fun merge(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> {
        val out = a.toMutableMap()
        for ((id, at) in b) {
            val cur = out[id]
            if (cur == null || at > cur) out[id] = at
        }
        return out
    }

    fun prune(unpins: Map<String, Long>, now: Long, maxAgeMs: Long = MAX_AGE_MS): Map<String, Long> =
        unpins.filterValues { now - it <= maxAgeMs }

    fun toJson(unpins: Map<String, Long>): JSONArray = JSONArray().apply {
        unpins.entries.sortedBy { it.key }.forEach { (id, at) ->
            put(JSONObject().apply {
                put("episodeId", id)
                put("removedAt", at)
            })
        }
    }

    fun fromJson(array: JSONArray?): Map<String, Long> {
        if (array == null) return emptyMap()
        val out = mutableMapOf<String, Long>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("episodeId", "")
            val at = obj.optLong("removedAt", 0L)
            if (id.isBlank() || at <= 0L) continue
            val cur = out[id]
            if (cur == null || at > cur) out[id] = at
        }
        return out
    }
}

/** This device's tombstones, kept in SharedPreferences (sync bookkeeping, not app data). */
class UnpinStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("eager_sync_unpins", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): Map<String, Long> = try {
        UnpinRules.fromJson(JSONArray(prefs.getString(KEY, "[]") ?: "[]"))
    } catch (_: Exception) {
        emptyMap()
    }

    @Synchronized
    fun save(unpins: Map<String, Long>) {
        val pruned = UnpinRules.prune(unpins, System.currentTimeMillis())
        prefs.edit().putString(KEY, UnpinRules.toJson(pruned).toString()).apply()
    }

    /** Record that these pinned episodes were removed by hand at [at]. */
    @Synchronized
    fun record(episodeIds: Collection<String>, at: Long = System.currentTimeMillis()) {
        if (episodeIds.isEmpty()) return
        save(UnpinRules.merge(load(), episodeIds.associateWith { at }))
    }

    private companion object {
        const val KEY = "unpins"
    }
}
