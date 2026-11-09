package com.eagertolisten.app.data.model

/** What a podcast link points at, shown before subscribing (Add show sheet). */
data class FeedPreview(
    val feedUrl: String,
    val title: String,
    val author: String,
    val artworkUrl: String,
    val description: String,
    val episodeCount: Int,
    val newestEpisodeAt: Long,
    /** The same show already in the library (followed, or unfollowed with its history kept) */
    val existing: Podcast?
)
