package com.eagertolisten.app.data.model

import java.net.URI

object FeedUrl {
    /**
     * True when two feed URLs point at the same feed for "already subscribed?" checks:
     * normalized, ignoring http vs https and letter case.
     */
    fun sameFeed(a: String, b: String): Boolean {
        fun key(url: String): String = normalize(url).ifBlank { url.trim() }
            .removePrefix("https://").removePrefix("http://")
            .lowercase()
        val ka = key(a)
        return ka.isNotEmpty() && ka == key(b)
    }

    fun normalize(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return ""
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase() ?: "https"
            val host = uri.host?.lowercase() ?: ""
            val port = if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            var path = uri.rawPath ?: ""
            // Strip every trailing slash so normalize(normalize(x)) == normalize(x)
            if (path.length > 1) {
                path = path.trimEnd('/').ifEmpty { "/" }
            }
            val query = if (!uri.rawQuery.isNullOrBlank()) "?${uri.rawQuery}" else ""
            val fragment = if (!uri.rawFragment.isNullOrBlank()) "#${uri.rawFragment}" else ""
            if (host.isNotBlank()) {
                "$scheme://$host$port$path$query$fragment"
            } else {
                trimmed.trimEnd('/')
            }
        } catch (_: Exception) {
            trimmed.trimEnd('/')
        }
    }
}
