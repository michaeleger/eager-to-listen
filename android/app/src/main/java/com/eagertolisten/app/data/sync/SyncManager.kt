package com.eagertolisten.app.data.sync

import com.eagertolisten.app.data.model.editedAt
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
import kotlinx.coroutines.sync.withLock
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

    companion object {
        private val syncMutex = kotlinx.coroutines.sync.Mutex()
        const val SYNC_FILE = "eagertolisten_sync.json"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("eager_sync_prefs", Context.MODE_PRIVATE)
    private val db = EagerDatabase.getInstance(context)
    private val unpinStore = UnpinStore(context)
    private val bookmarkStore = BookmarkStore(context)

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

    /**
     * One sync at a time across the app. Leaving the app, the 12-hour job and the Sync button each
     * make their own SyncManager, and two passes at once would write the same temp file.
     */
    suspend fun performSync(isManual: Boolean = false): Result<SyncSummary> =
        syncMutex.withLock { performSyncLocked(isManual) }

    private suspend fun performSyncLocked(isManual: Boolean): Result<SyncSummary> = withContext(Dispatchers.IO) {
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
            var mainUnreadable: Exception? = null

            for (file in syncFiles) {
                var readOk = false
                try {
                    context.contentResolver.openInputStream(file.uri)?.use { stream ->
                        val text = stream.bufferedReader().readText()
                        if (text.isNotBlank()) {
                            val json = JSONObject(text)
                            val remoteState = SyncSerializer.parseJson(json)
                            // Only merge if from another device or newer than last sync
                            if (remoteState.deviceId != getOrCreateDeviceId() || remoteState.writtenAt > prefs.getLong("last_sync_timestamp", 0L)) {
                                val summary = SyncSerializer.mergeState(db, remoteState, unpinStore = unpinStore, bookmarkStore = bookmarkStore)
                                mergedPodcastsTotal += summary.podcastsSynced
                                mergedEpisodesTotal += summary.episodesSynced
                            }
                        }
                        readOk = true
                    } ?: throw java.io.IOException("Couldn't open ${file.name}")
                } catch (e: Exception) {
                    Log.w("SyncManager", "Failed to parse remote sync file: ${file.name}", e)
                    if (file.name == SYNC_FILE) mainUnreadable = e
                }

                // A copy we couldn't read is left alone rather than deleted with its contents unmerged
                if (file.name != SYNC_FILE && readOk) {
                    duplicateFilesToDelete.add(file)
                }
            }

            // The main file exists but couldn't be read (half uploaded, Drive not caught up, damaged).
            // Stop here: writing now would replace it, and the backup copy, with this phone's state only.
            mainUnreadable?.let {
                throw IllegalStateException(
                    "The sync file in Drive couldn't be read, so nothing was changed. Try again in a few minutes. (${it.localizedMessage ?: it.javaClass.simpleName})"
                )
            }

            // Clean up extra duplicate files in Drive
            for (dup in duplicateFilesToDelete) {
                try {
                    dup.delete()
                } catch (_: Exception) {}
            }

            // 2. Export updated consolidated state (Write Safety: .tmp.json -> validate -> .bak.json -> eagertolisten_sync.json)
            val exportJson = SyncSerializer.serializeV2(db, getOrCreateDeviceId(), unpinStore = unpinStore, bookmarkStore = bookmarkStore)
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

            // Bookmark archive in the same folder. It never shrinks; a problem here doesn't fail the sync.
            val archiveNote = try {
                "bookmark archive: ${runBookmarkArchive(dir)} saved"
            } catch (e: Exception) {
                Log.w("SyncManager", "Bookmark archive failed", e)
                "bookmark archive failed: ${e.localizedMessage ?: "unknown error"}"
            }

            val now = System.currentTimeMillis()
            prefs.edit().putLong("last_sync_timestamp", now).apply()

            val summaryMsg = "Synced $mergedPodcastsTotal podcasts, $mergedEpisodesTotal episodes · $archiveNote"
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

    private val httpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun readDoc(file: DocumentFile): String =
        context.contentResolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() } ?: ""

    private fun writeDoc(file: DocumentFile, text: String) {
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.flush()
        } ?: throw IllegalStateException("Can't write ${file.name}")
    }

    /** Find a file by exact name, or create it. Some providers add an extension; undo that. */
    private fun findOrCreate(dir: DocumentFile, name: String): DocumentFile {
        dir.findFile(name)?.let { return it }
        val created = dir.createFile("application/octet-stream", name) ?: throw IllegalStateException("Can't create $name")
        if (created.name != name) created.renameTo(name)
        return dir.findFile(name) ?: created
    }

    private fun fetchTranscript(url: String, type: String?, durationSeconds: Long): com.eagertolisten.app.parser.ParsedTranscript? = try {
        val req = okhttp3.Request.Builder().url(url)
            .header("User-Agent", com.eagertolisten.app.data.repository.PodcastRepository.USER_AGENT).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null
            else com.eagertolisten.app.parser.TranscriptParser().parseWithTiming(resp.body?.string() ?: "", type, durationSeconds)
        }
    } catch (_: Exception) {
        null
    }

    /**
     * One archive pass (same steps as the desktop's runArchive): merge the archive file(s) in the
     * sync folder with this device's bookmarks and removals, write one file back safely, and record
     * which bookmarks are confirmed in the archive (only those can be cleared). Returns the total.
     */
    private suspend fun runBookmarkArchive(dir: DocumentFile): Int {
        val files = dir.listFiles().filter { BookmarkArchive.isArchiveFileName(it.name) }
        val texts = mutableListOf<String>()
        val unreadable = mutableSetOf<String?>()
        for (f in files) {
            try { texts.add(readDoc(f)) } catch (_: Exception) { unreadable.add(f.name) }
        }
        val archive = BookmarkArchive.parse(*texts.toTypedArray())
        val before = BookmarkArchive.serialize(archive)

        val isLocal = { id: String -> com.eagertolisten.app.data.local.LocalFolderFormat.isLocalFeed(id) }
        val clips = db.inspirationDao().getAllInspirationsSync().filterNot { isLocal(it.podcastId) }
        val held = bookmarkStore.held().filterNot { isLocal(it.podcastId) }
        val deviceId = getOrCreateDeviceId()
        val transcripts = mutableMapOf<String, com.eagertolisten.app.parser.ParsedTranscript?>()
        val newExcerpts = mutableMapOf<String, BookmarkArchive.Excerpt>()
        for (clip in clips + held) {
            val ep = db.episodeDao().getEpisodeByIdSync(clip.episodeId)
            val podcast = db.podcastDao().getPodcastByIdSync(clip.podcastId)
            val existing = archive.records[clip.id]
            var excerpt = bookmarkStore.excerpt(clip.id)
                ?: existing?.optJSONObject("transcript")?.let { t ->
                    if (t.isNull("excerpt")) null else BookmarkArchive.Excerpt(t.optString("excerpt"), t.optString("timing", "exact"))
                }
            val tUrl = ep?.transcriptUrl
            if (excerpt == null && !tUrl.isNullOrBlank()) {
                val parsed = transcripts.getOrPut(tUrl) { fetchTranscript(tUrl, ep?.transcriptType, ep?.durationSeconds ?: 0L) }
                excerpt = parsed?.let { BookmarkArchive.excerptAt(it, clip.timestampSeconds) }
                excerpt?.let { newExcerpts[clip.id] = it }
            }
            archive.records[clip.id] = BookmarkArchive.mergeRecord(
                existing,
                BookmarkArchive.record(clip, ep, podcast?.title, podcast?.feedUrl, excerpt, deviceId)
            )
        }
        for ((id, at) in bookmarkStore.removals()) {
            val r = archive.records[id] ?: continue
            if (r.isNull("removedAt") || r.optLong("removedAt") < at) r.put("removedAt", at)
        }

        val after = BookmarkArchive.serialize(archive)
        val main = files.firstOrNull { it.name == BookmarkArchive.ARCHIVE_FILE }
        val copies = files.filter { it.name != BookmarkArchive.ARCHIVE_FILE }
        if (after != before || copies.isNotEmpty() || main == null) {
            if (main != null) {
                val current = try { readDoc(main) } catch (_: Exception) { "" }
                check(BookmarkArchive.entryCount(after) >= BookmarkArchive.entryCount(current)) { "Refusing to shrink the bookmark archive." }
            }
            val tmp = findOrCreate(dir, BookmarkArchive.ARCHIVE_TMP)
            writeDoc(tmp, after)
            check(readDoc(tmp) == after) { "The bookmark archive didn't read back cleanly." }
            writeDoc(main ?: findOrCreate(dir, BookmarkArchive.ARCHIVE_FILE), after)
            try { tmp.delete() } catch (_: Exception) {}
            for (c in copies) if (c.name !in unreadable) try { c.delete() } catch (_: Exception) {}
        }
        bookmarkStore.saveExcerpts(newExcerpts)
        bookmarkStore.clearHeld()
        bookmarkStore.saveArchived(clips.associate { it.id to it.editedAt })
        return archive.records.size
    }

    suspend fun exportBackupToUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val exportJson = SyncSerializer.serializeV2(db, getOrCreateDeviceId(), unpinStore = unpinStore, bookmarkStore = bookmarkStore)
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
            val summary = SyncSerializer.mergeState(db, remoteState, unpinStore = unpinStore, bookmarkStore = bookmarkStore)
            Result.success(summary)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
