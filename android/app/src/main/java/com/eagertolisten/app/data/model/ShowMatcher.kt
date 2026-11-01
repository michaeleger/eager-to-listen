package com.eagertolisten.app.data.model

/**
 * Finds a show the user already has that is the same as one being subscribed or imported, so we
 * don't end up with two copies (every episode twice, played status split between them).
 *
 * Same show =
 *  1. same feed address, ignoring http vs https, letter case and a trailing slash
 *     (checked against both the show's id and its current feed URL, which changes when a feed moves), or
 *  2. same Podcasting 2.0 <podcast:guid>, the permanent show ID that survives address changes.
 */
object ShowMatcher {

    data class Candidate(val id: String, val feedUrl: String, val podcastGuid: String?)

    fun normalizeGuid(guid: String?): String? = guid?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    fun findMatch(existing: List<Candidate>, feedUrl: String, podcastGuid: String? = null): Candidate? {
        val key = FeedUrl.matchKey(feedUrl)
        if (key.isNotEmpty()) {
            existing.firstOrNull { FeedUrl.matchKey(it.id) == key || FeedUrl.matchKey(it.feedUrl) == key }
                ?.let { return it }
        }
        val guid = normalizeGuid(podcastGuid) ?: return null
        return existing.firstOrNull { normalizeGuid(it.podcastGuid) == guid }
    }

    /**
     * Assigns show ids during one import: reuses an existing show's id when it's the same show,
     * and makes two entries in the same file that are really one show share an id.
     */
    class ImportResolver(existing: List<Candidate>) {
        private val known = existing.toMutableList()

        /** The id to use for a show at [feedUrl] (with optional [podcastGuid]). */
        fun resolve(feedUrl: String, podcastGuid: String? = null): String {
            findMatch(known, feedUrl, podcastGuid)?.let { return it.id }
            val id = FeedUrl.normalize(feedUrl).ifBlank { feedUrl.trim() }
            known.add(Candidate(id, id, podcastGuid))
            return id
        }
    }
}
