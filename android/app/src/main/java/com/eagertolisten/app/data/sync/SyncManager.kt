package com.eagertolisten.app.data.sync

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.eagertolisten.app.data.EagerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

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

    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    fun getOrCreateDeviceId(): String {
        var id = prefs.getString("device_id", null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

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

    suspend fun performSync(isManual: Boolean = false): Result<SyncSummary> = withContext(Dispatchers.IO) {
        val uriStr = _state.value.folderUri
            ?: return@withContext Result.failure(Exception("No sync folder selected. Please select a Google Drive folder."))

        if (!isManual && !_state.value.autoSyncEnabled) {
            return@withContext Result.failure(Exception("Auto-sync is disabled."))
        }

        _state.value = _state.value.copy(status = SyncStatus.SYNCING)

        try {
            val treeUri = Uri.parse(uriStr)
            val dir = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext Result.failure(Exception("Unable to access Google Drive sync folder."))

            // 1. Find all sync files (and merge duplicates like eagertolisten_sync (1).json)
            val allFiles = dir.listFiles()
            val syncFiles = allFiles.filter { file ->
                val name = file.name ?: ""
                name.startsWith("eagertolisten_sync") && name.endsWith(".json") &&
                    !name.endsWith(".tmp.json") && !name.endsWith(".bak.json")
            }

            var mergedPodcastsTotal = 0
            var mergedEpisodesTotal = 0
            val duplicateFilesToDelete = mutableListOf<DocumentFile>()

            for (file in syncFiles) {
                try {
                    context.contentResolver.openInputStream(file.uri)?.use { stream ->
                        val text = stream.bufferedReader().readText()
                        if (text.isNotBlank()) {
                            val json = JSONObject(text)
                            val remoteState = SyncSerializer.parseJson(json)
                            // Only merge if from another device or newer than last sync
                            if (remoteState.deviceId != getOrCreateDeviceId() || remoteState.writtenAt > prefs.getLong("last_sync_timestamp", 0L)) {
                                val summary = SyncSerializer.mergeState(db, remoteState)
                                mergedPodcastsTotal += summary.podcastsSynced
                                mergedEpisodesTotal += summary.episodesSynced
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("SyncManager", "Failed to parse remote sync file: ${file.name}", e)
                }

                if (file.name != "eagertolisten_sync.json") {
                    duplicateFilesToDelete.add(file)
                }
            }

            // Clean up extra duplicate files in Drive
            for (dup in duplicateFilesToDelete) {
                try {
                    dup.delete()
                } catch (_: Exception) {}
            }

            // 2. Export updated consolidated state (Write Safety: .tmp.json -> validate -> .bak.json -> eagertolisten_sync.json)
            val exportJson = SyncSerializer.serializeV2(db, getOrCreateDeviceId())
            val jsonString = exportJson.toString(2)

            // Write to .tmp.json
            val tmpFile = dir.findFile("eagertolisten_sync.tmp.json")
                ?: dir.createFile("application/json", "eagertolisten_sync.tmp.json")
                ?: return@withContext Result.failure(Exception("Failed to create eagertolisten_sync.tmp.json"))

            context.contentResolver.openOutputStream(tmpFile.uri, "wt")?.use { stream ->
                stream.write(jsonString.toByteArray(Charsets.UTF_8))
                stream.flush()
            }

            // Read back and validate
            val readBackText = context.contentResolver.openInputStream(tmpFile.uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: ""
            val parsedValidation = JSONObject(readBackText)
            if (parsedValidation.optInt("format", 0) != SyncSerializer.CURRENT_FORMAT) {
                throw IllegalStateException("Validation of temporary sync file failed.")
            }

            // Backup existing eagertolisten_sync.json if present
            val existingMainFile = dir.findFile("eagertolisten_sync.json")
            if (existingMainFile != null && existingMainFile.exists()) {
                val bakFile = dir.findFile("eagertolisten_sync.bak.json")
                    ?: dir.createFile("application/json", "eagertolisten_sync.bak.json")
                if (bakFile != null) {
                    context.contentResolver.openInputStream(existingMainFile.uri)?.use { inStream ->
                        context.contentResolver.openOutputStream(bakFile.uri, "wt")?.use { outStream ->
                            inStream.copyTo(outStream)
                            outStream.flush()
                        }
                    }
                }
            }

            // Write verified content to main eagertolisten_sync.json
            val targetFile = existingMainFile ?: dir.createFile("application/json", "eagertolisten_sync.json")
                ?: return@withContext Result.failure(Exception("Failed to create eagertolisten_sync.json"))

            context.contentResolver.openOutputStream(targetFile.uri, "wt")?.use { stream ->
                stream.write(jsonString.toByteArray(Charsets.UTF_8))
                stream.flush()
            }

            // Remove tmp file
            try {
                tmpFile.delete()
            } catch (_: Exception) {}

            val now = System.currentTimeMillis()
            prefs.edit().putLong("last_sync_timestamp", now).apply()

            val summaryMsg = "Synced $mergedPodcastsTotal podcasts, $mergedEpisodesTotal episodes"
            _state.value = _state.value.copy(
                status = SyncStatus.CONNECTED,
                lastSyncTimestamp = now,
                lastSyncSummary = summaryMsg,
                errorMessage = null
            )

            Result.success(
                SyncSummary(
                    podcastsSynced = mergedPodcastsTotal,
                    episodesSynced = mergedEpisodesTotal,
                    queueCount = db.queueDao().getQueueCount(),
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

    suspend fun exportBackupToUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val exportJson = SyncSerializer.serializeV2(db, getOrCreateDeviceId())
            context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(exportJson.toString(2).toByteArray(Charsets.UTF_8))
                stream.flush()
            } ?: return@withContext Result.failure(Exception("Cannot open stream for backup export"))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreBackupFromUri(uri: Uri): Result<SyncSummary> = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: return@withContext Result.failure(Exception("Cannot open stream for backup restore"))

            val json = JSONObject(text)
            val remoteState = SyncSerializer.parseJson(json)
            val summary = SyncSerializer.mergeState(db, remoteState)
            Result.success(summary)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
