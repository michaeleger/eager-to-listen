package com.eagertolisten.app.data.model

import java.net.URI
import java.net.URLDecoder

/**
 * Turning whatever someone opens, shares or pastes into a podcast feed. Mirrors
 * desktop/src/core/feedLink.js rule for rule; change both together.
 *
 * Handles plain feed URLs, feed:/podcast:/pcast:/itpc: links, feed:https://..., the
 * subscribeonandroid.com redirector, Apple Podcasts and Overcast show links (by Apple ID), and
 * links buried in shared text. A web page works too: the feed is found from its
 * <link rel="alternate"> tags.
 */
object FeedLink {
    /** Default priority for a show added from a link. */
    const val DEFAULT_PRIORITY = 2

    sealed class Target {
        data class Feed(val url: String, val schemeGuessed: Boolean) : Target()
        data class Apple(val id: String) : Target()
        data class Search(val term: String) : Target()
        object None : Target()
    }

    private val SCHEME_ALIASES = listOf("feed", "podcast", "podcasts", "pcast", "itpc", "rss", "antennapod-subscribe")
    private val URL_IN_TEXT = Regex(
        "\\b(?:https?|${SCHEME_ALIASES.joinToString("|")}):[^\\s<>\"']+",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_PUNCTUATION = Regex("[),.;!?\\]]+$")
    private val NESTED = Regex("^([a-z-]+):(https?://.+)$", RegexOption.IGNORE_CASE)
    private val SCHEME = Regex("^([a-z][a-z0-9+.-]*):(?://)?(.*)$", RegexOption.IGNORE_CASE)
    private val HTTP_PREFIX = Regex("^https?://", RegexOption.IGNORE_CASE)

    fun resolve(input: String?): Target {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) return Target.None
        val m = URL_IN_TEXT.find(text) ?: return Target.Search(text)
        // Trailing punctuation from prose ("...feed.xml).") isn't part of the link
        val link = m.value.replace(TRAILING_PUNCTUATION, "")

        // feed:https://example.com/rss  ->  https://example.com/rss
        NESTED.find(link)?.let { n ->
            if (n.groupValues[1].lowercase() in SCHEME_ALIASES) return resolveHttp(n.groupValues[2], false)
        }
        val scheme = SCHEME.find(link) ?: return Target.Search(text)
        val s = scheme.groupValues[1].lowercase()
        if (s == "http" || s == "https") return resolveHttp(link, false)
        if (s in SCHEME_ALIASES) {
            val rest = scheme.groupValues[2]
            if (rest.isEmpty()) return Target.None
            // These links don't say http or https: try https first (the app falls back to http)
            return resolveHttp("https://$rest", true)
        }
        return Target.Search(text)
    }

    private fun resolveHttp(url: String, schemeGuessed: Boolean): Target {
        val uri = try { URI(url.trim()) } catch (_: Exception) { return Target.None }
        val rawHost = uri.host ?: return Target.Feed(url.trim(), schemeGuessed)
        val host = rawHost.lowercase().removePrefix("www.")
        val path = uri.rawPath ?: ""
        // https://subscribeonandroid.com/feeds.example.com/show.xml
        if (host == "subscribeonandroid.com" && path.length > 1) {
            val rest = URLDecoder.decode(path.substring(1), "UTF-8") + (uri.rawQuery?.let { "?$it" } ?: "")
            return if (HTTP_PREFIX.containsMatchIn(rest)) resolveHttp(rest, false) else resolveHttp("https://$rest", true)
        }
        // https://podcasts.apple.com/us/podcast/some-show/id1234567890
        if (host == "podcasts.apple.com" || host == "itunes.apple.com") {
            val id = Regex("/id(\\d+)").find(path)?.groupValues?.get(1)
                ?: Regex("(?:^|&)id=(\\d+)").find(uri.rawQuery ?: "")?.groupValues?.get(1)
            if (id != null) return Target.Apple(id)
        }
        // https://overcast.fm/itunes1234567890/some-show
        if (host == "overcast.fm") {
            Regex("^/itunes(\\d+)").find(path)?.let { return Target.Apple(it.groupValues[1]) }
        }
        return Target.Feed(url.trim(), schemeGuessed)
    }

    /** The http fallback for a link that didn't say which scheme it uses. */
    fun httpFallback(url: String): String? =
        if (url.startsWith("https://", ignoreCase = true)) "http://${url.substring(8)}" else null

    /** Does this fetched body look like a web page rather than a feed? */
    fun looksLikeHtml(body: String?, contentType: String? = ""): Boolean {
        val ct = contentType.orEmpty().lowercase()
        if (ct.contains("html") && !ct.contains("xml")) return true
        val head = body.orEmpty().take(1024).trimStart().lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }

    private val NEW_FEED_URL = Regex("<itunes:new-feed-url>\\s*(?:<!\\[CDATA\\[)?\\s*(https?://[^<\\]\\s]+)", RegexOption.IGNORE_CASE)
    private val ATOM_LINK = Regex("<(?:[a-z0-9]+:)?link\\b[^>]*>", RegexOption.IGNORE_CASE)

    /**
     * Where a feed file says it lives online, for a feed opened as a file (Downloads, Files):
     * <itunes:new-feed-url> when the show has moved, else its <atom:link rel="self">.
     */
    fun feedUrlFromXml(xml: String?): String? {
        val text = xml.orEmpty().take(256 * 1024)
        NEW_FEED_URL.find(text)?.let { return it.groupValues[1].replace("&amp;", "&") }
        for (m in ATOM_LINK.findAll(text)) {
            val tag = m.value
            if (attr(tag, "rel").lowercase() != "self") continue
            val href = attr(tag, "href").replace("&amp;", "&")
            if (HTTP_PREFIX.containsMatchIn(href)) return href
        }
        return null
    }

    private val LINK_TAG = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val FEED_TYPE = Regex("(rss|atom)\\+xml|application/xml|text/xml")
    private val COMMENTS_FEED = Regex("/comments/feed/?$", RegexOption.IGNORE_CASE)

    private fun attr(tag: String, name: String): String {
        val m = Regex("\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(tag) ?: return ""
        return (m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: "").trim()
    }

    /**
     * Feed addresses a web page advertises with <link rel="alternate" type="application/rss+xml">
     * (or Atom), resolved against the page address, in page order, without duplicates.
     */
    fun discoverFeedLinks(html: String?, pageUrl: String): List<String> {
        val out = mutableListOf<String>()
        val base = try { URI(pageUrl) } catch (_: Exception) { null }
        for (match in LINK_TAG.findAll(html.orEmpty())) {
            val tag = match.value
            val rel = attr(tag, "rel").lowercase().split(Regex("\\s+"))
            val type = attr(tag, "type").lowercase()
            val href = attr(tag, "href").replace("&amp;", "&")
            if ("alternate" !in rel || href.isEmpty()) continue
            if (!FEED_TYPE.containsMatchIn(type)) continue
            val abs = try { base?.resolve(href)?.toString() ?: URI(href).toString() } catch (_: Exception) { continue }
            if (!abs.startsWith("http", ignoreCase = true)) continue
            // Skip per-post comment feeds that blogs advertise next to the main feed
            if (COMMENTS_FEED.containsMatchIn(abs)) continue
            if (abs !in out) out.add(abs)
        }
        return out
    }
}
