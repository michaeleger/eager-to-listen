package com.eagertolisten.app.importer

/**
 * Pure helpers for reading a Podcast Addict full backup (.backup = ZIP with podcastAddict.db and
 * com.bambuna.podcastaddict_preferences.xml). Column meanings verified against a real backup
 * (Podcast Addict, Oct 2026):
 *  - podcasts.subscribed_status: 1 = subscribed. The table also keeps every show ever looked at
 *    (1,955 of 1,960 rows in the sample were NOT subscribed).
 *  - podcasts.priority: 1 for every show, not a 1-5 ranking, so it isn't imported.
 *  - podcasts.custom_name: the user's rename, when set.
 *  - episodes.seen_status: 1 = played.
 *  - episodes.position_to_resume: milliseconds.
 *  - episodes.duration: TEXT like "32:12" or "01:08:38"; duration_ms / rssfeed_duration_ms: ms.
 *  - episodes.playbackDate, publication_date: epoch milliseconds.
 *  - prefs: pref_speedPlaybackOn_<podcastId> + pref_speedAdjustment_<podcastId> = per-show speed.
 */
object PodcastAddictFormat {

    /** "32:12" -> 1932, "01:08:38" -> 4118, "1932" -> 1932; anything else -> 0. */
    fun parseDurationText(raw: String?): Long {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return 0L
        if (!text.contains(':')) return text.toDoubleOrNull()?.toLong()?.coerceAtLeast(0L) ?: 0L
        val parts = text.split(':').map { it.trim().toLongOrNull() ?: return 0L }
        if (parts.size > 3) return 0L
        return parts.fold(0L) { acc, n -> acc * 60L + n }
    }

    /** Picks the best duration in seconds: duration_ms, then the feed's duration, then the text field. */
    fun durationSeconds(durationMs: Long, rssDurationMs: Long, durationText: String?): Long = when {
        durationMs > 0L -> durationMs / 1000L
        rssDurationMs > 0L -> rssDurationMs / 1000L
        else -> parseDurationText(durationText)
    }

    private val speedOnRegex = Regex("""<boolean\s+name="pref_speedPlaybackOn_(\d+)"\s+value="true"\s*/>""")
    private val speedValueRegex = Regex("""<float\s+name="pref_speedAdjustment_(\d+)"\s+value="([0-9.]+)"\s*/>""")

    /** Per-show playback speeds keyed by Podcast Addict's podcast _id (only shows with custom speed on). */
    fun parsePerShowSpeeds(prefsXml: String?): Map<String, Float> {
        if (prefsXml.isNullOrBlank()) return emptyMap()
        val enabled = speedOnRegex.findAll(prefsXml).map { it.groupValues[1] }.toSet()
        return speedValueRegex.findAll(prefsXml)
            .filter { it.groupValues[1] in enabled }
            .mapNotNull { m ->
                val speed = m.groupValues[2].toFloatOrNull() ?: return@mapNotNull null
                if (speed in 0.5f..3.0f) m.groupValues[1] to speed else null
            }
            .toMap()
    }

    /** True for the SQLite database entry, false for the preferences XML that also says "podcastaddict". */
    fun isDatabaseEntry(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".db") || lower.endsWith(".sqlite") || lower.endsWith(".sqlite3")
    }

    fun isPreferencesEntry(name: String): Boolean = name.lowercase().endsWith("_preferences.xml")
}
