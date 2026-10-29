package com.eagertolisten.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.model.QueueItem
import kotlinx.coroutines.flow.Flow

data class QueueEpisode(
    val episode: Episode,
    val podcastPriority: Int,
    val skipIntroSeconds: Int,
    val skipOutroSeconds: Int,
    val playbackSpeed: Float,
    val orderIndex: Int
)

@Dao
abstract class QueueDao {
    @Query("""
        SELECT e.* FROM episodes e
        INNER JOIN queue_items q ON e.id = q.episodeId
        WHERE e.isLive = 0 AND e.liveStatus IS NULL
        ORDER BY q.orderIndex ASC
    """)
    abstract fun getQueueEpisodes(): Flow<List<Episode>>

    @Query("""
        SELECT e.* FROM episodes e
        INNER JOIN queue_items q ON e.id = q.episodeId
        WHERE e.isLive = 0 AND e.liveStatus IS NULL
        ORDER BY q.orderIndex ASC
    """)
    abstract suspend fun getQueueEpisodesSync(): List<Episode>

    @Query("SELECT * FROM queue_items ORDER BY orderIndex ASC")
    abstract fun getAllQueueItems(): Flow<List<QueueItem>>

    @Upsert
    abstract suspend fun insertQueueItem(item: QueueItem)

    @Upsert
    abstract suspend fun insertQueueItems(items: List<QueueItem>)

    @Query("DELETE FROM queue_items WHERE episodeId = :episodeId")
    abstract suspend fun removeEpisodeFromQueue(episodeId: String): Int

    @Query("DELETE FROM queue_items WHERE isManual = 0")
    abstract suspend fun clearProgrammedQueue(): Int

    @Query("DELETE FROM queue_items")
    abstract suspend fun clearQueue(): Int

    @Query("SELECT COUNT(*) FROM queue_items")
    abstract suspend fun getQueueCount(): Int

    @Query("SELECT MAX(orderIndex) FROM queue_items")
    abstract suspend fun getMaxOrderIndex(): Int?

    @Query("SELECT MIN(manualIndex) FROM queue_items WHERE isManual = 1")
    abstract suspend fun getMinManualIndex(): Int?

    @Query("SELECT MAX(manualIndex) FROM queue_items WHERE isManual = 1")
    abstract suspend fun getMaxManualIndex(): Int?

    @Query("SELECT * FROM queue_items WHERE isManual = 1 ORDER BY manualIndex ASC")
    abstract suspend fun getPinnedItemsSync(): List<QueueItem>

    @Query("SELECT COUNT(*) FROM queue_items WHERE podcastId = :podcastId")
    abstract suspend fun countEpisodesInQueueForPodcast(podcastId: String): Int

    @Query("DELETE FROM queue_items WHERE podcastId = :podcastId")
    abstract suspend fun removeEpisodesForPodcast(podcastId: String): Int

    @Query("DELETE FROM queue_items WHERE episodeId IN (SELECT id FROM episodes WHERE isCompleted = 1)")
    abstract suspend fun purgeCompletedFromQueue(): Int

    @Query("DELETE FROM queue_items WHERE episodeId IN (SELECT id FROM episodes WHERE isLive = 1 OR liveStatus IS NOT NULL)")
    abstract suspend fun purgeLiveItemsFromQueue(): Int

    @Query("""
        DELETE FROM queue_items 
        WHERE episodeId IN (
            SELECT id FROM episodes 
            WHERE podcastId = :podcastId AND publishedAt < :cutoffTimestamp
        )
    """)
    abstract suspend fun removeExpiredEpisodesForPodcast(podcastId: String, cutoffTimestamp: Long): Int

    @Query("SELECT * FROM queue_items ORDER BY orderIndex ASC")
    abstract suspend fun getAllQueueItemsSync(): List<QueueItem>

    @Transaction
    open suspend fun enqueueEpisodeTop(episodeId: String, podcastId: String) {
        removeEpisodeFromQueue(episodeId)
        val minManual = getMinManualIndex() ?: 0
        insertQueueItem(
            QueueItem(
                episodeId = episodeId,
                podcastId = podcastId,
                orderIndex = 0,
                isManual = true,
                manualIndex = minManual - 1
            )
        )
    }

    @Transaction
    open suspend fun appendManualEpisode(episodeId: String, podcastId: String) {
        removeEpisodeFromQueue(episodeId)
        val maxManual = getMaxManualIndex() ?: 0
        val maxOrder = getMaxOrderIndex() ?: 0
        insertQueueItem(
            QueueItem(
                episodeId = episodeId,
                podcastId = podcastId,
                orderIndex = maxOrder + 1,
                isManual = true,
                manualIndex = maxManual + 1
            )
        )
    }

    @Transaction
    open suspend fun reorderQueue(orderedEpisodeIds: List<String>) {
        orderedEpisodeIds.forEachIndexed { index, episodeId ->
            updateOrderIndex(episodeId, index)
        }
    }

    @Query("UPDATE queue_items SET orderIndex = :newIndex WHERE episodeId = :episodeId")
    abstract suspend fun updateOrderIndex(episodeId: String, newIndex: Int): Int
}
