package com.eagertolisten.app.importer

/**
 * AntennaPod "Database export" (Settings > Import/Export > Database export): its SQLite database.
 * Schema per AntennaPod's PodDBAdapter / FeedItem / Feed / FeedPreferences source:
 *  - Feeds(id, title, custom_title, download_url = feed URL, author, state, feed_playback_speed,
 *    feed_skip_intro, feed_skip_ending)   state: 0 = subscribed, 1 = not subscribed, 2 = archived
 *    feed_playback_speed: -1 = use the global speed. Local-folder feeds use "antennapod_local:" URLs.
 *  - FeedItems(id, title, pubDate (ms), read, item_identifier = GUID, feed, media)
 *    read: -1 = new, 0 = unplayed, 1 = played
 *  - FeedMedia(id, duration (ms), position (ms), download_url = enclosure URL,
 *    playback_completion_date (ms), last_played_time (ms), feeditem)
 */
object AntennaPodFormat {
    const val READ_PLAYED = 1
    const val STATE_SUBSCRIBED = 0
    private const val LOCAL_FEED_PREFIX = "antennapod_local:"

    /** True when a SQLite file's tables are AntennaPod's (Feeds + FeedItems + FeedMedia). */
    fun isAntennaPodSchema(tableNames: Collection<String>): Boolean {
        val lower = tableNames.map { it.lowercase() }.toSet()
        return "feeds" in lower && "feeditems" in lower && "feedmedia" in lower
    }

    /** Followed shows only: subscribed (or no state column in older exports) and not a local folder. */
    fun isImportableFeed(state: Int?, feedUrl: String?): Boolean {
        if (feedUrl.isNullOrBlank()) return false
        if (feedUrl.startsWith(LOCAL_FEED_PREFIX, ignoreCase = true)) return false
        return state == null || state == STATE_SUBSCRIBED
    }

    /** Per-show speed, or null when AntennaPod uses the global speed (-1) or the value is out of range. */
    fun speedOrNull(raw: Float?): Float? = raw?.takeIf { it in 0.5f..3.0f }

    /** Skip-intro / skip-ending seconds, clamped to what Eager to Listen allows. */
    fun skipSeconds(raw: Int?): Int = (raw ?: 0).coerceIn(0, 300)

    /** When it was played: completion date, then last played, then publish date (all ms). */
    fun playedAtMs(completionDate: Long, lastPlayedTime: Long, pubDate: Long): Long? = when {
        completionDate > 0L -> completionDate
        lastPlayedTime > 0L -> lastPlayedTime
        pubDate > 0L -> pubDate
        else -> null
    }
}
