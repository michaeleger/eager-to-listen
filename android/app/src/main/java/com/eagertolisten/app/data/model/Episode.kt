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
        Index("publishedAt")
    ]
)
data class Episode(
    @PrimaryKey
    val id: String, // Enclosure URL or GUID
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
    val isDownloaded: Boolean = false
)
