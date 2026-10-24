package com.eagertolisten.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.eagertolisten.app.data.model.Episode
import kotlinx.coroutines.flow.Flow

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateEpisode(episode: Episode)

    @Update
    suspend fun updateEpisode(episode: Episode)

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

    @Query("UPDATE episodes SET podcastTitle = :title WHERE podcastId = :podcastId")
    suspend fun updatePodcastTitleForEpisodes(podcastId: String, title: String): Int

    @Query("SELECT * FROM episodes WHERE isCompleted = 0 AND isLive = 0 AND liveStatus IS NULL ORDER BY publishedAt DESC LIMIT :limit")
    fun getUnplayedEpisodes(limit: Int = 200): Flow<List<Episode>>

    @Query("SELECT * FROM episodes WHERE isCompleted = 1 AND isLive = 0 AND liveStatus IS NULL ORDER BY updatedAt DESC LIMIT :limit")
    fun getPlayedEpisodes(limit: Int = 100): Flow<List<Episode>>

    @Query("SELECT * FROM episodes WHERE isCompleted = 1 AND isLive = 0 AND liveStatus IS NULL ORDER BY updatedAt DESC LIMIT :limit")
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
