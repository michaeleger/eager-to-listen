package com.eagertolisten.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.eagertolisten.app.data.model.PendingRemoteEpisode

@Dao
interface PendingRemoteEpisodeDao {
    @Query("SELECT * FROM pending_remote_episodes WHERE podcastId = :podcastId")
    suspend fun getPendingForPodcast(podcastId: String): List<PendingRemoteEpisode>

    @Upsert
    suspend fun insertOrUpdate(episodes: List<PendingRemoteEpisode>)

    @Query("DELETE FROM pending_remote_episodes WHERE episodeId IN (:episodeIds)")
    suspend fun deleteByIds(episodeIds: List<String>): Int

    @Query("DELETE FROM pending_remote_episodes WHERE podcastId = :podcastId")
    suspend fun deleteForPodcast(podcastId: String): Int
}
