package com.eagertolisten.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.eagertolisten.app.data.model.Podcast
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcasts WHERE unsubscribedAt IS NULL ORDER BY title ASC")
    fun getAllPodcasts(): Flow<List<Podcast>>

    @Query("SELECT * FROM podcasts WHERE id = :id AND unsubscribedAt IS NULL LIMIT 1")
    fun getPodcastById(id: String): Flow<Podcast?>

    @Query("SELECT * FROM podcasts WHERE id = :id LIMIT 1")
    suspend fun getPodcastByIdSync(id: String): Podcast?

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl LIMIT 1")
    suspend fun getPodcastByFeedUrlSync(feedUrl: String): Podcast?

    @Query("SELECT * FROM podcasts WHERE unsubscribedAt IS NULL ORDER BY priority ASC, title ASC")
    suspend fun getAllPodcastsSync(): List<Podcast>

    @Query("SELECT * FROM podcasts ORDER BY priority ASC, title ASC")
    suspend fun getAllPodcastsIncludingUnsubscribedSync(): List<Podcast>

    @Query("""
        SELECT * FROM podcasts 
        WHERE unsubscribedAt IS NULL
          AND (title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%')
        ORDER BY priority ASC, title ASC 
        LIMIT :limit
    """)
    suspend fun searchPodcastsSync(query: String, limit: Int = 10): List<Podcast>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPodcastIgnore(podcast: Podcast): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPodcastsIgnore(podcasts: List<Podcast>): List<Long>

    @Upsert
    suspend fun insertPodcast(podcast: Podcast)

    @Upsert
    suspend fun insertPodcasts(podcasts: List<Podcast>)

    @Update
    suspend fun updatePodcast(podcast: Podcast)

    @Query("""
        UPDATE podcasts 
        SET title = CASE WHEN :title != '' THEN :title ELSE title END,
            artworkUrl = CASE WHEN :artworkUrl != '' THEN :artworkUrl ELSE artworkUrl END,
            author = CASE WHEN :author != '' THEN :author ELSE author END,
            description = CASE WHEN :description != '' THEN :description ELSE description END,
            fundingUrl = :fundingUrl,
            fundingMessage = :fundingMessage,
            valueJson = :valueJson,
            httpEtag = :httpEtag,
            httpLastModified = :httpLastModified
        WHERE id = :id
    """)
    suspend fun updateFeedMetadata(
        id: String,
        title: String,
        artworkUrl: String,
        author: String,
        description: String,
        fundingUrl: String?,
        fundingMessage: String?,
        valueJson: String?,
        httpEtag: String? = null,
        httpLastModified: String? = null
    ): Int

    @Query("UPDATE podcasts SET unsubscribedAt = :timestamp WHERE id = :id")
    suspend fun softUnsubscribe(id: String, timestamp: Long = System.currentTimeMillis()): Int

    @Query("UPDATE podcasts SET unsubscribedAt = NULL WHERE id = :id")
    suspend fun clearUnsubscribed(id: String): Int

    @Query("SELECT * FROM podcasts WHERE unsubscribedAt IS NOT NULL")
    suspend fun getUnsubscribedPodcasts(): List<Podcast>

    @Query("DELETE FROM podcasts WHERE unsubscribedAt IS NOT NULL")
    suspend fun deletePermanentlyRemovedPodcasts(): Int

    @Query("UPDATE podcasts SET playbackSpeed = :speed, settingsUpdatedAt = :updatedAt WHERE id = :podcastId")
    suspend fun updatePodcastSpeed(podcastId: String, speed: Float, updatedAt: Long = System.currentTimeMillis()): Int

    @Delete
    suspend fun deletePodcast(podcast: Podcast)

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun deletePodcastById(id: String): Int

    @Query("UPDATE podcasts SET feedUrl = :feedUrl, settingsUpdatedAt = :updatedAt WHERE id = :id")
    suspend fun updateFeedUrl(id: String, feedUrl: String, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE podcasts 
        SET priority = :priority,
            skipIntroSeconds = :skipIntro,
            skipOutroSeconds = :skipOutro,
            playbackSpeed = :speed,
            autoAddToQueue = :autoAdd,
            maxEpisodesInQueue = :maxEpisodes,
            category = :category,
            customDonationUrl = :customDonationUrl,
            playOrderStrategy = :playOrderStrategy,
            newsRetentionDays = :newsRetentionDays,
            consecutivePlayLimit = :consecutivePlayLimit,
            settingsUpdatedAt = :settingsUpdatedAt
        WHERE id = :podcastId
    """)
    suspend fun updateFeedRules(
        podcastId: String,
        priority: Int,
        skipIntro: Int,
        skipOutro: Int,
        speed: Float,
        autoAdd: Boolean,
        maxEpisodes: Int,
        category: String,
        customDonationUrl: String?,
        playOrderStrategy: String,
        newsRetentionDays: Int,
        consecutivePlayLimit: Int,
        settingsUpdatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE podcasts SET customDonationUrl = :url, settingsUpdatedAt = :updatedAt WHERE id = :podcastId")
    suspend fun updateCustomDonationUrl(podcastId: String, url: String?, updatedAt: Long = System.currentTimeMillis()): Int
}
