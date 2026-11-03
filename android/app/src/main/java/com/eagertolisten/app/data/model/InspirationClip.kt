package com.eagertolisten.app.data.model

import androidx.room.ColumnInfo
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
    val createdAt: Long = System.currentTimeMillis(),
    // Last edit (note), so sync keeps the newer edit. 0 = never edited; createdAt stands in. DB v8.
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0L
)

/** When this bookmark last changed: its last edit, or its creation. (An extension, so Room ignores it.) */
val InspirationClip.editedAt: Long get() = if (updatedAt > 0L) updatedAt else createdAt
