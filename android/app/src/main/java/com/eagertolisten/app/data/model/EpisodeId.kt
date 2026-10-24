package com.eagertolisten.app.data.model

object EpisodeId {
    fun of(podcastId: String, guid: String?, enclosureUrl: String): String {
        val cleanFeedId = FeedUrl.normalize(podcastId).ifBlank { podcastId.trim() }
        val cleanIdentifier = guid?.trim()?.takeUnless { it.isBlank() } ?: enclosureUrl.trim()
        return "$cleanFeedId::$cleanIdentifier"
    }
}
