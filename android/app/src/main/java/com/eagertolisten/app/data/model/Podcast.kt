package com.eagertolisten.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "podcasts")
data class Podcast(
    @PrimaryKey
    val id: String, // Feed URL
    val title: String,
    val feedUrl: String,
    val artworkUrl: String,
    val author: String,
    val description: String,
    val subscribedAt: Long = System.currentTimeMillis(),
    
    // Radio U Customization Rules
    val priority: Int = 3, // 1 (Highest) to 5 (Lowest)
    val skipIntroSeconds: Int = 0, // 0 to 300 seconds
    val skipOutroSeconds: Int = 0, // 0 to 300 seconds
    val playbackSpeed: Float = 1.0f, // 0.8f to 2.5f
    val autoAddToQueue: Boolean = true,
    val maxEpisodesInQueue: Int = 5, // -1 for unlimited
    val category: String = "General",
    val fundingUrl: String? = null,
    val fundingMessage: String? = null,
    val customDonationUrl: String? = null,
    val valueJson: String? = null,

    // 4 Play Orders: CHRONOLOGICAL, REVERSE_CHRONOLOGICAL, NEWS, EVERGREEN
    val playOrderStrategy: String = PlayOrderStrategy.EVERGREEN.name,
    // News Retention Window in Days (for NEWS strategy)
    val newsRetentionDays: Int = 3,
    // Consecutive Episodes Limit (-1 = inherit global setting)
    val consecutivePlayLimit: Int = -1,

    // Soft-unsubscribe and sync metadata
    val unsubscribedAt: Long? = null,
    val settingsUpdatedAt: Long = 0L,
    val httpEtag: String? = null,
    val httpLastModified: String? = null
)

enum class PlayOrderStrategy(val displayName: String, val description: String) {
    CHRONOLOGICAL("Chronological", "Serial stories & courses (oldest unplayed first)"),
    REVERSE_CHRONOLOGICAL("Reverse Chronological", "Always the newest unplayed episode"),
    NEWS("News", "Updates first, last X unplayed days in chronological order"),
    EVERGREEN("Evergreen / Knowledge", "Newest unplayed episode first, then next newest unplayed")
}

