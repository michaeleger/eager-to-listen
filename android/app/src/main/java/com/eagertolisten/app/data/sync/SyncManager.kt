package com.eagertolisten.app.data.sync

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.eagertolisten.app.data.EagerDatabase
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.model.Podcast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class SyncStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    SYNCING,
    ERROR
}

data class SyncSummary(
    val podcastsSynced: Int,
    val episodesSynced: Int,
    val queueCount: Int,
    val message: String
)

data class SyncState(
    val status: SyncStatus = SyncStatus.DISCONNECTED,
    val folderUri: String? = null,
    val folderDisplayName: String? = null,
    val lastSyncTimestamp: Long = 0,
    val autoSyncEnabled: Boolean = true,
    val errorMessage: String? = null,
    val lastSyncSummary: String? = null
)

class SyncManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("eager_sync_prefs", Context.MODE_PRIVATE)
    private val db = EagerDatabase.getInstance(context)
    private val podcastDao = db.podcastDao()
    private val episodeDao = db.episodeDao()
    private val queueDao = db.queueDao()

    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private fun loadInitialState(): SyncState {
        val uriStr = prefs.getString("sync_folder_uri", null)
        val name = prefs.getString("sync_folder_name", null)
        val lastSync = prefs.getLong("last_sync_timestamp", 0)
        val autoSync = prefs.getBoolean("auto_sync_enabled", true)
        val isConnected = !uriStr.isNullOrBlank()

        return SyncState(
            status = if (isConnected) SyncStatus.CONNECTED else SyncStatus.DISCONNECTED,
            folderUri = uriStr,
            folderDisplayName = name,
            lastSyncTimestamp = lastSync,
            autoSyncEnabled = autoSync
        )
    }

    fun setSyncFolder(uri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)

            val dir = DocumentFile.fromTreeUri(context, uri)
            val displayName = dir?.name ?: "Google Drive Folder"

            prefs.edit()
                .putString("sync_folder_uri", uri.toString())
                .putString("sync_folder_name", displayName)
                .apply()

            _state.value = _state.value.copy(
                status = SyncStatus.CONNECTED,
                folderUri = uri.toString(),
                folderDisplayName = displayName,
                errorMessage = null
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                status = SyncStatus.ERROR,
                errorMessage = e.localizedMessage ?: "Failed to set sync folder"
            )
        }
    }

    fun setAutoSync(enabled: Boolean) {
        prefs.edit().putBoolean("auto_sync_enabled", enabled).apply()
        _state.value = _state.value.copy(autoSyncEnabled = enabled)
    }

    fun disconnect() {
        val uriStr = _state.value.folderUri
        if (uriStr != null) {
            try {
                val uri = Uri.parse(uriStr)
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.releasePersistableUriPermission(uri, flags)
            } catch (_: Exception) {}
        }

        prefs.edit()
            .remove("sync_folder_uri")
            .remove("sync_folder_name")
            .remove("last_sync_timestamp")
            .apply()

        _state.value = SyncState(
            status = SyncStatus.DISCONNECTED,
            folderUri = null,
            folderDisplayName = null,
            lastSyncTimestamp = 0
        )
    }

    suspend fun performSync(): Result<SyncSummary> = withContext(Dispatchers.IO) {
        val uriStr = _state.value.folderUri
            ?: return@withContext Result.failure(Exception("No sync folder selected. Please select a Google Drive folder."))

        _state.value = _state.value.copy(status = SyncStatus.SYNCING)

        try {
            val treeUri = Uri.parse(uriStr)
            val dir = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext Result.failure(Exception("Unable to access Google Drive sync folder."))

            val fileName = "eagertolisten_sync.json"
            var syncFile = dir.findFile(fileName)

            // 1. Read remote state if exists
            var remoteJson: JSONObject? = null
            if (syncFile != null && syncFile.exists() && syncFile.length() > 0) {
                try {
                    context.contentResolver.openInputStream(syncFile.uri)?.use { stream ->
                        val text = stream.bufferedReader().readText()
                        if (text.isNotBlank()) {
                            remoteJson = JSONObject(text)
                        }
                    }
                } catch (_: Exception) {
                    // Proceed with local export if remote is corrupted/empty
                }
            }

            // 2. Merge remote changes into local database
            var mergedPodcasts = 0
            var mergedEpisodes = 0
            val rJson = remoteJson
            if (rJson != null) {
                val remotePodcasts = rJson.optJSONArray("podcasts")
                if (remotePodcasts != null) {
                    for (i in 0 until remotePodcasts.length()) {
                        val pObj = remotePodcasts.getJSONObject(i)
                        val id = pObj.getString("id")
                        val existing = podcastDao.getPodcastByIdSync(id)
                        if (existing == null) {
                            val newPodcast = Podcast(
                                id = id,
                                title = pObj.optString("title", "Podcast"),
                                feedUrl = pObj.optString("feedUrl", id),
                                artworkUrl = pObj.optString("artworkUrl", ""),
                                author = pObj.optString("author", ""),
                                description = pObj.optString("description", ""),
                                priority = pObj.optInt("priority", 3),
                                skipIntroSeconds = pObj.optInt("skipIntroSeconds", 0),
                                skipOutroSeconds = pObj.optInt("skipOutroSeconds", 0),
                                playbackSpeed = pObj.optDouble("playbackSpeed", 1.0).toFloat(),
                                autoAddToQueue = pObj.optBoolean("autoAddToQueue", true),
                                maxEpisodesInQueue = pObj.optInt("maxEpisodesInQueue", 5),
                                category = pObj.optString("category", "General"),
                                playOrderStrategy = pObj.optString("playOrderStrategy", "EVERGREEN"),
                                newsRetentionDays = pObj.optInt("newsRetentionDays", 3),
                                consecutivePlayLimit = pObj.optInt("consecutivePlayLimit", -1)
                            )
                            podcastDao.insertPodcast(newPodcast)
                            mergedPodcasts++
                        }
                    }
                }

                val remoteEpisodes = rJson.optJSONArray("episodes")
                if (remoteEpisodes != null) {
                    for (i in 0 until remoteEpisodes.length()) {
                        val eObj = remoteEpisodes.getJSONObject(i)
                        val epId = eObj.getString("id")
                        val remotePos = eObj.optLong("playbackPositionSeconds", 0L)
                        val remoteCompleted = eObj.optBoolean("isCompleted", false)
                        val remoteUpdatedAt = eObj.optLong("updatedAt", 0L)

                        val localEp = episodeDao.getEpisodeByIdSync(epId)
                        if (localEp != null) {
                            val shouldUpdate = (remoteCompleted && !localEp.isCompleted) ||
                                (!localEp.isCompleted && remotePos > localEp.playbackPositionSeconds && remoteUpdatedAt >= localEp.updatedAt)
                            if (shouldUpdate) {
                                episodeDao.updatePlaybackProgress(
                                    episodeId = epId,
                                    positionSec = if (remoteCompleted) localEp.durationSeconds else remotePos,
                                    isCompleted = remoteCompleted,
                                    updatedAt = remoteUpdatedAt
                                )
                                mergedEpisodes++
                            }
                        }
                    }
                }
            }

            // 3. Export latest consolidated local state to Drive
            val localPodcasts = podcastDao.getAllPodcastsSync()
            val localEpisodes = episodeDao.getRecentUnplayedEpisodesSync(200)
            val localQueue = queueDao.getQueueEpisodesSync()

            val exportJson = JSONObject().apply {
                put("version", 1)
                put("client", "Eager to Listen (Android)")
                put("timestamp", System.currentTimeMillis())

                val podcastsArray = JSONArray()
                localPodcasts.forEach { p ->
                    podcastsArray.put(JSONObject().apply {
                        put("id", p.id)
                        put("title", p.title)
                        put("feedUrl", p.feedUrl)
                        put("artworkUrl", p.artworkUrl)
                        put("author", p.author)
                        put("description", p.description)
                        put("priority", p.priority)
                        put("skipIntroSeconds", p.skipIntroSeconds)
                        put("skipOutroSeconds", p.skipOutroSeconds)
                        put("playbackSpeed", p.playbackSpeed.toDouble())
                        put("autoAddToQueue", p.autoAddToQueue)
                        put("maxEpisodesInQueue", p.maxEpisodesInQueue)
                        put("category", p.category)
                        put("playOrderStrategy", p.playOrderStrategy)
                        put("newsRetentionDays", p.newsRetentionDays)
                        put("consecutivePlayLimit", p.consecutivePlayLimit)
                    })
                }
                put("podcasts", podcastsArray)

                val episodesArray = JSONArray()
                localEpisodes.forEach { ep ->
                    episodesArray.put(JSONObject().apply {
                        put("id", ep.id)
                        put("podcastId", ep.podcastId)
                        put("podcastTitle", ep.podcastTitle)
                        put("title", ep.title)
                        put("audioUrl", ep.audioUrl)
                        put("publishedAt", ep.publishedAt)
                        put("durationSeconds", ep.durationSeconds)
                        put("playbackPositionSeconds", ep.playbackPositionSeconds)
                        put("isCompleted", ep.isCompleted)
                        put("updatedAt", ep.updatedAt)
                    })
                }
                put("episodes", episodesArray)

                val queueArray = JSONArray()
                localQueue.forEach { q ->
                    queueArray.put(q.id)
                }
                put("queue", queueArray)
            }

            if (syncFile == null || !syncFile.exists()) {
                syncFile = dir.createFile("application/json", fileName)
                    ?: return@withContext Result.failure(Exception("Failed to create eagertolisten_sync.json in Drive folder"))
            }

            context.contentResolver.openOutputStream(syncFile.uri, "wt")?.use { stream ->
                stream.write(exportJson.toString(2).toByteArray(Charsets.UTF_8))
                stream.flush()
            }

            val now = System.currentTimeMillis()
            prefs.edit().putLong("last_sync_timestamp", now).apply()

            val summaryMsg = "Successfully synced ${localPodcasts.size} podcasts and ${localEpisodes.size} episodes"
            _state.value = _state.value.copy(
                status = SyncStatus.CONNECTED,
                lastSyncTimestamp = now,
                lastSyncSummary = summaryMsg,
                errorMessage = null
            )

            Result.success(
                SyncSummary(
                    podcastsSynced = localPodcasts.size,
                    episodesSynced = localEpisodes.size,
                    queueCount = localQueue.size,
                    message = summaryMsg
                )
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                status = SyncStatus.ERROR,
                errorMessage = e.localizedMessage ?: "Sync error occurred"
            )
            Result.failure(e)
        }
    }
}
