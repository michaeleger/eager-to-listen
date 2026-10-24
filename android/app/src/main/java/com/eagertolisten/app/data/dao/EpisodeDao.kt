package com.eagertolisten.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.eagertolisten.app.data.model.Episode
import kotlinx.coroutines.flow.Flow

data class EpisodeQueueProjection(
    val id: String,
    val podcastId: String,
    val publishedAt: Long,
    val feedOrder: Int,
    val playbackPositionSeconds: Long,
    val isCompleted: Boolean,
    val isDismissed: Boolean,
    val skippedAt: Long?,
    val skipCount: Int
)

data class PodcastHighWaterMark(
    val podcastId: String,
    val maxPub: Long
)

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId AND isLive = 0 AND liveStatus IS NULL ORDER BY publishedAt DESC")
    fun getEpisodesForPodcast(podcastId: String): Flow<List<Episode>>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId AND isLive = 0 AND liveStatus IS NULL ORDER BY publishedAt DESC")
    suspend fun getEpisodesForPodcastSync(podcastId: String): List<Episode>

    @Query("SELECT * FROM episodes WHERE id = :id LIMIT 1")
    fun getEpisodeById(id: String): Flow<Episode?>

    @Query("SELECT * FROM episodes WHERE id = :id LIMIT 1")
    suspend fun getEpisodeByIdSync(id: String): Episode?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEpisodes(episodes: List<Episode>)

    @Upsert
    suspend fun insertOrUpdateEpisode(episode: Episode)

    @Upsert
    suspend fun upsertEpisodes(episodes: List<Episode>)

    @Update
    suspend fun updateEpisode(episode: Episode)

    @Query("""
        UPDATE episodes 
        SET title = :title,
            audioUrl = :audioUrl,
            durationSeconds = CASE WHEN :durationSeconds > 0 THEN :durationSeconds ELSE durationSeconds END,
            description = :description,
            artworkUrl = :artworkUrl,
            feedOrder = :feedOrder
        WHERE id = :id
    """)
    suspend fun updateEpisodeFeedFields(
        id: String,
        title: String,
        audioUrl: String,
        durationSeconds: Long,
        description: String,
        artworkUrl: String?,
        feedOrder: Int
    ): Int

    @Query("""
        UPDATE episodes 
        SET playbackPositionSeconds = :positionSec, 
            isCompleted = :isCompleted, 
            updatedAt = :updatedAt 
        WHERE id = :episodeId
    """)
    suspend fun updatePlaybackProgress(
        episodeId: String,
        positionSec: Long,
        isCompleted: Boolean,
        updatedAt: Long
    ): Int

    @Query("""
        UPDATE episodes 
        SET isCompleted = 1,
            completedAt = :completedAt,
            playbackPositionSeconds = durationSeconds,
            updatedAt = :completedAt
        WHERE id = :episodeId
    """)
    suspend fun markEpisodeFinished(episodeId: String, completedAt: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE episodes 
        SET skippedAt = :skippedAt,
            skipCount = skipCount + 1,
            playbackPositionSeconds = :positionSec,
            updatedAt = :skippedAt
        WHERE id = :episodeId
    """)
    suspend fun markEpisodeSkipped(
        episodeId: String,
        positionSec: Long,
        skippedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("""
        UPDATE episodes 
        SET isDismissed = 1,
            dismissReason = :reason,
            updatedAt = :timestamp
        WHERE id = :episodeId
    """)
    suspend fun markEpisodeDismissed(
        episodeId: String,
        reason: String,
        timestamp: Long = System.currentTimeMillis()
    ): Int

    @Query("""
        UPDATE episodes 
        SET isDismissed = 1,
            dismissReason = 'EXPIRED_NEWS',
            updatedAt = :timestamp
        WHERE podcastId = :podcastId AND isCompleted = 0 AND publishedAt > 0 AND publishedAt < :cutoffTimestamp
    """)
    suspend fun dismissExpiredNews(podcastId: String, cutoffTimestamp: Long, timestamp: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE episodes 
        SET isCompleted = 0,
            completedAt = NULL,
            playbackPositionSeconds = 0,
            skippedAt = NULL,
            skipCount = 0,
            isDismissed = 0,
            dismissReason = NULL,
            updatedAt = :timestamp
        WHERE id = :episodeId
    """)
    suspend fun markEpisodeUnplayed(episodeId: String, timestamp: Long = System.currentTimeMillis()): Int

    @Query("""
        SELECT id, podcastId, publishedAt, feedOrder, playbackPositionSeconds, isCompleted, isDismissed, skippedAt, skipCount
        FROM episodes 
        WHERE podcastId IN (:podcastIds) 
          AND isCompleted = 0 
          AND isDismissed = 0 
          AND isLive = 0
    """)
    suspend fun getEligibleEpisodesForQueueSync(podcastIds: List<String>): List<EpisodeQueueProjection>

    @Query("""
        SELECT podcastId, MAX(publishedAt) AS maxPub 
        FROM episodes 
        WHERE isCompleted = 1 AND publishedAt > 0 
        GROUP BY podcastId
    """)
    suspend fun getAllHeardHighWaterMarks(): List<PodcastHighWaterMark>

    @Query("""
        SELECT MAX(publishedAt) 
        FROM episodes 
        WHERE podcastId = :podcastId AND isCompleted = 1 AND publishedAt > 0
    """)
    suspend fun getHeardHighWaterMark(podcastId: String): Long?

    @Query("UPDATE episodes SET podcastTitle = :title WHERE podcastId = :podcastId")
    suspend fun updatePodcastTitleForEpisodes(podcastId: String, title: String): Int

    @Query("SELECT * FROM episodes WHERE isCompleted = 0 AND isDismissed = 0 AND isLive = 0 AND liveStatus IS NULL ORDER BY publishedAt DESC LIMIT :limit")
    fun getUnplayedEpisodes(limit: Int = 200): Flow<List<Episode>>

    @Query("SELECT * FROM episodes WHERE isCompleted = 1 AND isLive = 0 AND liveStatus IS NULL ORDER BY CASE WHEN completedAt IS NOT NULL THEN completedAt ELSE updatedAt END DESC LIMIT :limit")
    fun getPlayedEpisodes(limit: Int = 100): Flow<List<Episode>>

    @Query("SELECT * FROM episodes WHERE isCompleted = 1 AND isLive = 0 AND liveStatus IS NULL ORDER BY CASE WHEN completedAt IS NOT NULL THEN completedAt ELSE updatedAt END DESC LIMIT :limit")
    suspend fun getRecentPlayedEpisodesSync(limit: Int = 100): List<Episode>

    // Hot List: Unplayed episodes belonging to Priority 1 podcasts (excluding expired news)
    @Query("""
        SELECT e.* FROM episodes e
        INNER JOIN podcasts p ON e.podcastId = p.id
        WHERE e.isCompleted = 0 
          AND e.isLive = 0 
          AND e.liveStatus IS NULL 
          AND p.priority = 1
          AND (
              p.playOrderStrategy != 'NEWS' 
              OR e.publishedAt >= ((strftime('%s', 'now') * 1000) - ((CASE WHEN p.newsRetentionDays > 0 THEN p.newsRetentionDays ELSE 3 END) * 86400000))
          )
        ORDER BY e.publishedAt DESC
        LIMIT 100
    """)
    fun getHotListEpisodes(): Flow<List<Episode>>

    @Query("""
        SELECT e.* FROM episodes e
        INNER JOIN podcasts p ON e.podcastId = p.id
        WHERE e.isCompleted = 0 
          AND e.isLive = 0 
          AND e.liveStatus IS NULL 
          AND p.priority = 1
          AND (
              p.playOrderStrategy != 'NEWS' 
              OR e.publishedAt >= ((strftime('%s', 'now') * 1000) - ((CASE WHEN p.newsRetentionDays > 0 THEN p.newsRetentionDays ELSE 3 END) * 86400000))
          )
        ORDER BY e.publishedAt DESC
        LIMIT :limit
    """)
    suspend fun getHotListEpisodesSync(limit: Int = 50): List<Episode>

    @Query("""
        SELECT e.* FROM episodes e
        INNER JOIN podcasts p ON e.podcastId = p.id
        WHERE e.isCompleted = 0 AND e.isLive = 0 AND e.liveStatus IS NULL AND p.playOrderStrategy = 'NEWS' AND e.publishedAt >= :cutoffTimestamp
        ORDER BY e.publishedAt ASC
        LIMIT :limit
    """)
    suspend fun getRecentNewsEpisodesSync(cutoffTimestamp: Long, limit: Int = 50): List<Episode>

    @Query("""
        SELECT * FROM episodes 
        WHERE isCompleted = 0 AND isLive = 0 AND liveStatus IS NULL
        ORDER BY publishedAt DESC 
        LIMIT :limit
    """)
    suspend fun getRecentUnplayedEpisodesSync(limit: Int = 50): List<Episode>

    @Query("""
        SELECT * FROM episodes 
        WHERE title LIKE '%' || :query || '%' 
           OR podcastTitle LIKE '%' || :query || '%' 
        ORDER BY publishedAt DESC 
        LIMIT :limit
    """)
    suspend fun searchEpisodesSync(query: String, limit: Int = 30): List<Episode>

    @Query("SELECT * FROM episodes WHERE isLive = 0 AND liveStatus IS NULL ORDER BY publishedAt DESC LIMIT :limit")
    fun getRecentEpisodes(limit: Int): Flow<List<Episode>>

    @Query("UPDATE episodes SET isCompleted = :isCompleted, updatedAt = :updatedAt WHERE id = :episodeId")
    suspend fun setEpisodeCompleted(
        episodeId: String,
        isCompleted: Boolean,
        updatedAt: Long
    ): Int

    @Query("UPDATE episodes SET isCompleted = 1, updatedAt = :updatedAt WHERE podcastId = :podcastId")
    suspend fun markAllEpisodesPlayedForPodcast(
        podcastId: String,
        updatedAt: Long
    ): Int

    @Query("SELECT * FROM episodes WHERE isLive = 1 AND liveStatus = 'live' ORDER BY publishedAt DESC")
    fun getLiveEpisodes(): Flow<List<Episode>>

    @Query("SELECT * FROM episodes WHERE isLive = 1 AND liveStatus = 'live' ORDER BY publishedAt DESC")
    suspend fun getLiveEpisodesSync(): List<Episode>

    @Query("UPDATE episodes SET isLive = 0, liveStatus = 'ended', isCompleted = 1 WHERE podcastId = :podcastId AND (isLive = 1 OR liveStatus = 'live')")
    suspend fun deactivateLiveEpisodesForPodcast(podcastId: String): Int

    @Query("UPDATE episodes SET isLive = 0, liveStatus = 'ended', isCompleted = 1 WHERE isLive = 1 OR liveStatus = 'live'")
    suspend fun deactivateAllLiveEpisodes(): Int

    @Query("UPDATE episodes SET isCompleted = 1 WHERE liveStatus IS NOT NULL AND isCompleted = 0")
    suspend fun markEndedLiveEpisodesCompleted(): Int

    @Query("""
        UPDATE episodes 
        SET transcriptUrl = COALESCE(:transcriptUrl, transcriptUrl),
            transcriptType = COALESCE(:transcriptType, transcriptType),
            chaptersUrl = COALESCE(:chaptersUrl, chaptersUrl),
            personsJson = COALESCE(:personsJson, personsJson),
            soundbitesJson = COALESCE(:soundbitesJson, soundbitesJson),
            fundingUrl = COALESCE(:fundingUrl, fundingUrl),
            valueJson = COALESCE(:valueJson, valueJson),
            isLive = :isLive,
            liveStatus = :liveStatus
        WHERE id = :id
    """)
    suspend fun updateEpisodeMetadata(
        id: String,
        transcriptUrl: String?,
        transcriptType: String?,
        chaptersUrl: String?,
        personsJson: String?,
        soundbitesJson: String?,
        fundingUrl: String?,
        valueJson: String?,
        isLive: Boolean,
        liveStatus: String?
    ): Int

    @Query("""
        UPDATE episodes 
        SET isCompleted = 1, updatedAt = :updatedAt 
        WHERE podcastId = :podcastId 
          AND isCompleted = 0 
          AND publishedAt < :cutoffTimestamp
    """)
    suspend fun purgeExpiredNewsEpisodesForPodcast(
        podcastId: String,
        cutoffTimestamp: Long,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("""
        UPDATE episodes 
        SET isCompleted = 1, updatedAt = :updatedAt 
        WHERE isCompleted = 0 
          AND publishedAt < :cutoffTimestamp 
          AND podcastId IN (SELECT id FROM podcasts WHERE playOrderStrategy = 'NEWS')
    """)
    suspend fun purgeAllExpiredNewsEpisodes(
        cutoffTimestamp: Long,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE episodes SET localFilePath = :localPath, isDownloaded = :isDownloaded WHERE id = :episodeId")
    suspend fun updateDownloadStatus(episodeId: String, localPath: String?, isDownloaded: Boolean): Int

    @Query("SELECT * FROM episodes WHERE isDownloaded = 1 ORDER BY publishedAt DESC")
    fun getDownloadedEpisodes(): Flow<List<Episode>>

    @Query("SELECT * FROM episodes WHERE isDownloaded = 1 ORDER BY publishedAt DESC")
    suspend fun getDownloadedEpisodesSync(): List<Episode>
}
