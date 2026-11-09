package com.eagertolisten.app.data.podcastindex

import com.eagertolisten.app.data.model.FeedUrl
import com.eagertolisten.app.ui.viewmodel.SearchResult
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant

/**
 * Show search across directories. Mirrors desktop/src/core/showSearch.js; change both together.
 *
 * Podcast Index and Apple Podcasts are asked at the same time and the answers merged. Before,
 * Apple was only asked when Podcast Index returned nothing (or failed), so a new show that
 * Podcast Index hadn't picked up never appeared as long as Podcast Index found anything at all.
 */
object ShowSearch {
    private val MARKS = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    private fun norm(s: String?): String =
        Normalizer.normalize(s.orEmpty().lowercase(), Normalizer.Form.NFKD)
            .replace(MARKS, "")
            .replace(NON_ALNUM, " ")
            .trim()

    /** How well a result's title (and author) matches what was typed. Higher is better. */
    fun matchScore(query: String, result: SearchResult): Int {
        val q = norm(query)
        if (q.isEmpty()) return 0
        val t = norm(result.title)
        val words = q.split(" ")
        val titleWords = t.split(" ").toSet()
        if (t == q) return 100
        if (t.startsWith(q)) return 80
        if (" $t ".contains(" $q ")) return 60
        if (words.all { it in titleWords }) return 40
        val all = "$t ${norm(result.author)}".split(" ").toSet()
        if (words.all { it in all }) return 20
        return 0
    }

    /** Same show? Same feed address (http/https and case ignored), podcast:guid, Apple ID, or title and author. */
    private fun sameShow(a: SearchResult, b: SearchResult): Boolean {
        if (FeedUrl.matchKey(a.feedUrl) == FeedUrl.matchKey(b.feedUrl)) return true
        if (!a.podcastGuid.isNullOrBlank() && a.podcastGuid == b.podcastGuid) return true
        if (!a.appleId.isNullOrBlank() && a.appleId == b.appleId) return true
        val ta = norm(a.title)
        return ta.isNotEmpty() && ta == norm(b.title) && norm(a.author) == norm(b.author)
    }

    private class Entry(var item: SearchResult, var bestPos: Int, val firstSource: Int, val order: Int) {
        val sources = mutableSetOf(firstSource)
        var score = 0
    }

    /**
     * Merge result lists (best source first). Duplicates collapse into one entry that keeps the
     * address with the newest episode, since directories can hold a show's old address. Dead feeds
     * are dropped. Order: best title match, then how high any source ranked it, with shows both
     * directories agree on breaking ties.
     */
    fun merge(query: String, lists: List<List<SearchResult>?>): List<SearchResult> {
        val merged = mutableListOf<Entry>()
        lists.forEachIndexed { sourceIndex, list ->
            list.orEmpty().forEachIndexed { pos, r ->
                if (r.feedUrl.isBlank() || r.dead) return@forEachIndexed
                val hit = merged.firstOrNull { sameShow(it.item, r) }
                if (hit != null) {
                    hit.bestPos = minOf(hit.bestPos, pos)
                    hit.sources.add(sourceIndex)
                    if (r.newestEpisodeAt > hit.item.newestEpisodeAt) {
                        hit.item = hit.item.copy(feedUrl = r.feedUrl, newestEpisodeAt = r.newestEpisodeAt)
                    }
                    hit.item = hit.item.copy(
                        artworkUrl = hit.item.artworkUrl.ifBlank { r.artworkUrl },
                        podcastGuid = hit.item.podcastGuid ?: r.podcastGuid,
                        appleId = hit.item.appleId ?: r.appleId
                    )
                } else {
                    merged.add(Entry(r, pos, sourceIndex, merged.size))
                }
            }
        }
        merged.forEach { it.score = matchScore(query, it.item) + if (it.sources.size > 1) 5 else 0 }
        return merged
            .sortedWith(
                compareByDescending<Entry> { it.score }
                    .thenBy { it.bestPos }
                    .thenBy { it.firstSource }
                    .thenBy { it.order }
            )
            .map { it.item }
    }

    /** optString that treats JSON null as missing (Android's org.json returns the text "null"). */
    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

    /** Podcast Index search/byterm or podcasts/bycategory -> results. */
    fun fromPodcastIndex(json: String, genre: String? = null): List<SearchResult> {
        val feeds = JSONObject(json).optJSONArray("feeds") ?: return emptyList()
        val out = mutableListOf<SearchResult>()
        for (i in 0 until feeds.length()) {
            val f = feeds.optJSONObject(i) ?: continue
            val url = f.str("url")
            if (url.isBlank()) continue
            val newest = f.optLong("newestItemPubdate", 0L).takeIf { it > 0 } ?: f.optLong("lastUpdateTime", 0L)
            val itunesId = f.optLong("itunesId", 0L)
            out.add(
                SearchResult(
                    title = f.str("title").ifBlank { "Untitled" },
                    author = f.str("author").ifBlank { f.str("ownerName") },
                    feedUrl = url,
                    artworkUrl = f.str("artwork").ifBlank { f.str("image") },
                    genres = listOf(genre ?: "Podcast Index"),
                    podcastGuid = f.str("podcastGuid").ifBlank { null },
                    appleId = if (itunesId > 0) itunesId.toString() else null,
                    newestEpisodeAt = newest * 1000,
                    dead = f.optInt("dead", 0) == 1 || f.optBoolean("dead", false),
                    source = "Podcast Index"
                )
            )
        }
        return out
    }

    /** Apple Podcasts search or lookup -> results. */
    fun fromApple(json: String): List<SearchResult> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<SearchResult>()
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val feedUrl = r.str("feedUrl")
            if (feedUrl.isBlank()) continue
            val collectionId = r.optLong("collectionId", 0L)
            val released = try { Instant.parse(r.str("releaseDate")).toEpochMilli() } catch (_: Exception) { 0L }
            out.add(
                SearchResult(
                    title = r.str("collectionName").ifBlank { r.str("trackName").ifBlank { "Untitled" } },
                    author = r.str("artistName"),
                    feedUrl = feedUrl,
                    artworkUrl = r.str("artworkUrl600").ifBlank { r.str("artworkUrl100") },
                    genres = listOf(r.str("primaryGenreName").ifBlank { "Podcast" }),
                    appleId = if (collectionId > 0) collectionId.toString() else null,
                    newestEpisodeAt = released,
                    source = "Apple Podcasts"
                )
            )
        }
        return out
    }
}
