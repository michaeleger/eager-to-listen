package com.eagertolisten.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.eagertolisten.app.data.model.InspirationClip
import kotlinx.coroutines.flow.Flow

@Dao
interface InspirationDao {

    @Query("SELECT * FROM inspiration_clips ORDER BY createdAt DESC")
    fun getAllInspirations(): Flow<List<InspirationClip>>

    @Query("SELECT * FROM inspiration_clips ORDER BY createdAt DESC")
    suspend fun getAllInspirationsSync(): List<InspirationClip>

    @Query("SELECT * FROM inspiration_clips WHERE episodeId = :episodeId ORDER BY timestampSeconds ASC")
    fun getInspirationsForEpisode(episodeId: String): Flow<List<InspirationClip>>

    @Upsert
    suspend fun insertInspiration(clip: InspirationClip)

    @Upsert
    suspend fun upsertInspirations(clips: List<InspirationClip>)

    @Update
    suspend fun updateInspiration(clip: InspirationClip)

    @Delete
    suspend fun deleteInspiration(clip: InspirationClip)

    @Query("DELETE FROM inspiration_clips WHERE id = :id")
    suspend fun deleteInspirationById(id: String)
}
