package com.eagertolisten.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "inspiration_clips")
data class InspirationClip(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val episodeId: String,
    val podcastId: String,
    val podcastTitle: String,
    val episodeTitle: String,
    val timestampSeconds: Long,
    val audioUrl: String,
    val note: String = "",
    val quoteText: String = "",
    val tags: String = "Inspiration",
    val createdAt: Long = System.currentTimeMillis()
)
