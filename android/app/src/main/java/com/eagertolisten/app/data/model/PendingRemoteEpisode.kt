package com.eagertolisten.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_remote_episodes")
data class PendingRemoteEpisode(
    @PrimaryKey
    val episodeId: String,
    val podcastId: String,
    val playbackPositionSeconds: Long,
    val isCompleted: Boolean,
    val completedAt: Long?,
    val isDismissed: Boolean,
    val dismissReason: String?,
    val skippedAt: Long?,
    val skipCount: Int,
    val updatedAt: Long
)
