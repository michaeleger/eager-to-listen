package com.eagertolisten.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = Podcast::class,
            parentColumns = ["id"],
            childColumns = ["podcastId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("podcastId"),
        Index("publishedAt"),
        Index(value = ["podcastId", "isCompleted", "publishedAt"], name = "idx_episodes_podcast_pub"),
        Index(value = ["isCompleted", "updatedAt"], name = "idx_episodes_completed_updated")
    ]
)
data class Episode(
    @PrimaryKey
    val id: String, // format: EpisodeId.of(podcastId, guid, enclosureUrl)
    val podcastId: String,
    val podcastTitle: String,
    val title: String,
    val audioUrl: String,
    val publishedAt: Long,
    val durationSeconds: Long = 0,
    val playbackPositionSeconds: Long = 0,
    val isCompleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val description: String = "",
    val artworkUrl: String? = null,

    // Podcasting 2.0 Metadata
    val transcriptUrl: String? = null,
    val transcriptType: String? = null,
    val chaptersUrl: String? = null,
    val personsJson: String? = null,
    val soundbitesJson: String? = null,

    // Podcasting 2.0 Live Support
    val isLive: Boolean = false,
    val liveStatus: String? = null,

    // Podcasting 2.0 Funding & Value
    val fundingUrl: String? = null,
    val valueJson: String? = null,

    // Offline / Auto-Download Support
    val localFilePath: String? = null,
    val isDownloaded: Boolean = false,

    // Phase 1: Data integrity, dismissal, skips, ordering
    val feedOrder: Int = 0,
    val isDismissed: Boolean = false,
    val dismissReason: String? = null,
    val completedAt: Long? = null,
    val skippedAt: Long? = null,
    val skipCount: Int = 0,
    val downloadBytes: Long? = null
)
