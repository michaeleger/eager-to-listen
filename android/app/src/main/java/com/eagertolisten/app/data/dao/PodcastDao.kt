package com.eagertolisten.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.eagertolisten.app.data.model.Podcast
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcasts ORDER BY title ASC")
    fun getAllPodcasts(): Flow<List<Podcast>>

    @Query("SELECT * FROM podcasts WHERE id = :id LIMIT 1")
    fun getPodcastById(id: String): Flow<Podcast?>

    @Query("SELECT * FROM podcasts WHERE id = :id LIMIT 1")
    suspend fun getPodcastByIdSync(id: String): Podcast?

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl LIMIT 1")
    suspend fun getPodcastByFeedUrlSync(feedUrl: String): Podcast?

    @Query("SELECT * FROM podcasts ORDER BY priority ASC, title ASC")
    suspend fun getAllPodcastsSync(): List<Podcast>

    @Query("""
        SELECT * FROM podcasts 
        WHERE title LIKE '%' || :query || '%' 
           OR author LIKE '%' || :query || '%' 
        ORDER BY priority ASC, title ASC 
        LIMIT :limit
    """)
    suspend fun searchPodcastsSync(query: String, limit: Int = 10): List<Podcast>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPodcast(podcast: Podcast)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPodcasts(podcasts: List<Podcast>)

    @Update
    suspend fun updatePodcast(podcast: Podcast)

    @Delete
    suspend fun deletePodcast(podcast: Podcast)

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun deletePodcastById(id: String): Int

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
            consecutivePlayLimit = :consecutivePlayLimit
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
        consecutivePlayLimit: Int
    ): Int

    @Query("UPDATE podcasts SET customDonationUrl = :url WHERE id = :podcastId")
    suspend fun updateCustomDonationUrl(podcastId: String, url: String?): Int

    @Query("""
        UPDATE podcasts 
        SET playOrderStrategy = 'NEWS', 
            category = 'News, Current Events' 
        WHERE (title LIKE '%No Agenda%' OR feedUrl LIKE '%nashownotes%') 
          AND (playOrderStrategy != 'NEWS' OR category NOT LIKE '%News%')
    """)
    suspend fun enforceNoAgendaNewsStrategy(): Int

    @Query("""
        UPDATE podcasts 
        SET category = 'Health & Wellness, Self-Help' 
        WHERE (title LIKE '%Eager to be Healthy%' OR feedUrl LIKE '%eagertobehealthy%') 
          AND category NOT LIKE '%Health & Wellness, Self-Help%'
    """)
    suspend fun enforceEagerToBeHealthyCategory(): Int
}
