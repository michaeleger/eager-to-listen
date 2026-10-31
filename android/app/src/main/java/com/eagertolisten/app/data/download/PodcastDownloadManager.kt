package com.eagertolisten.app.data.download

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.eagertolisten.app.data.EagerDatabase
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.repository.PodcastRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class PodcastDownloadManager(private val context: Context) {

    private val db = EagerDatabase.getInstance(context)
    private val episodeDao = db.episodeDao()
    private val queueDao = db.queueDao()
    private val podcastDao = db.podcastDao()

    private val httpClient = PodcastRepository.getOkHttpClient().newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    companion object {
        private const val TAG = "PodcastDownloadMgr"
        const val PREF_KEY_AUTO_DOWNLOAD_ENABLED = "auto_download_enabled"
        const val PREF_KEY_ALLOW_CELLULAR = "allow_cellular_downloads"
        const val PREF_KEY_AUTO_DOWNLOAD_HOURS = "auto_download_hours"
        const val PREF_KEY_LAST_ORPHAN_SWEEP = "last_orphan_sweep_timestamp"
        const val DEFAULT_AUTO_DOWNLOAD_HOURS = 4

        fun enqueueAutoDownload(context: Context) {
            val prefs = context.getSharedPreferences("eager_settings", Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean(PREF_KEY_AUTO_DOWNLOAD_ENABLED, true)
            if (!enabled) {
                WorkManager.getInstance(context).cancelUniqueWork("eager-downloads")
                return
            }

            val allowCellular = prefs.getBoolean(PREF_KEY_ALLOW_CELLULAR, false)
            val networkType = if (allowCellular) NetworkType.CONNECTED else NetworkType.UNMETERED

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .setRequiresStorageNotLow(true)
                .build()

            val request = OneTimeWorkRequestBuilder<DownloadQueueWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "eager-downloads",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    private fun getDownloadsDir(): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS)
            ?: File(context.filesDir, "downloads")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    suspend fun performAutoDownloadQueue(
        targetHours: Int = DEFAULT_AUTO_DOWNLOAD_HOURS,
        onProgress: ((title: String, current: Int, total: Int, indeterminate: Boolean) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        val targetDurationSec = targetHours.toLong() * 3600L
        val queueEpisodes = queueDao.getQueueEpisodesSync()
        if (queueEpisodes.isEmpty()) {
            Log.d(TAG, "Queue is empty; nothing to auto-download.")
            cleanUnqueuedDownloads()
            checkOrphanSweep()
            return@withContext 0
        }

        var accumulatedDurationSec = 0L
        var downloadedCount = 0
        val targetEpisodes = mutableListOf<Episode>()

        for (ep in queueEpisodes) {
            if (ep.isCompleted || ep.isDismissed || ep.isLive || ep.liveStatus != null) continue
            // Local folder files are already on the device
            if (com.eagertolisten.app.data.local.LocalFolderFormat.isLocalFeed(ep.podcastId)) continue

            val podcast = podcastDao.getPodcastByIdSync(ep.podcastId)
            if (podcast?.playOrderStrategy == "NEWS") {
                Log.d(TAG, "Skipping '${ep.title}' - NEWS feed excluded from pre-downloading")
                continue
            }

            val effectiveDuration = if (ep.durationSeconds > 0) ep.durationSeconds else 2700L
            targetEpisodes.add(ep)
            accumulatedDurationSec += effectiveDuration

            if (accumulatedDurationSec >= targetDurationSec) {
                break
            }
        }

        cleanUnqueuedDownloads()
        checkOrphanSweep()

        val totalToDownload = targetEpisodes.count { !it.isDownloaded || it.localFilePath == null || !File(it.localFilePath).exists() }
        var currentProgress = 0

        for (ep in targetEpisodes) {
            val isAlreadyDownloaded = ep.isDownloaded && ep.localFilePath != null && File(ep.localFilePath).exists()
            if (isAlreadyDownloaded) {
                continue
            }

            currentProgress++
            onProgress?.invoke(ep.title, currentProgress, totalToDownload, false)

            val success = downloadEpisodeInternal(ep)
            if (success) {
                downloadedCount++
            }
        }

        downloadedCount
    }

    suspend fun downloadEpisode(episode: Episode): Boolean = withContext(Dispatchers.IO) {
        downloadEpisodeInternal(episode)
    }

    private suspend fun downloadEpisodeInternal(episode: Episode): Boolean {
        try {
            if (episode.audioUrl.isBlank()) return false
            // Local folder files are already on the device; nothing to download.
            if (com.eagertolisten.app.data.local.LocalFolderFormat.isLocalFeed(episode.podcastId)) return true
            val safeId = episode.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val downloadsDir = getDownloadsDir()

            val existingMatching = downloadsDir.listFiles()?.firstOrNull { it.name.startsWith("ep_${safeId}.") && !it.name.endsWith(".part") }
            if (existingMatching != null && existingMatching.length() > 0) {
                episodeDao.updateDownloadStatus(episode.id, existingMatching.absolutePath, true, existingMatching.length())
                return true
            }

            val partFile = File(downloadsDir, "ep_${safeId}.part")
            val existingBytes = if (partFile.exists()) partFile.length() else 0L

            val requestBuilder = Request.Builder()
                .url(episode.audioUrl)
                .header("User-Agent", PodcastRepository.USER_AGENT)

            if (existingBytes > 0) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
                Log.d(TAG, "Resuming partial download for '${episode.title}' from $existingBytes bytes")
            }

            val request = requestBuilder.build()
            var ext = "mp3"

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 416) {
                    Log.w(TAG, "Failed HTTP ${response.code} downloading '${episode.title}'")
                    return false
                }

                if (response.code == 416) {
                    partFile.delete()
                    return false
                }

                val contentType = response.header("Content-Type")?.lowercase() ?: ""
                val urlLower = episode.audioUrl.lowercase()
                ext = when {
                    contentType.contains("audio/mp4") || contentType.contains("audio/x-m4a") || contentType.contains("video/mp4") -> "m4a"
                    contentType.contains("audio/aac") -> "aac"
                    contentType.contains("audio/ogg") || contentType.contains("audio/opus") -> "ogg"
                    urlLower.contains(".m4a") -> "m4a"
                    urlLower.contains(".aac") -> "aac"
                    urlLower.contains(".ogg") -> "ogg"
                    else -> "mp3"
                }

                val body = response.body ?: return false
                val isAppend = response.code == 206 && existingBytes > 0
                val outputStream = FileOutputStream(partFile, isAppend)

                body.byteStream().use { input ->
                    outputStream.use { output ->
                        input.copyTo(output, bufferSize = 16384)
                        output.flush()
                    }
                }
            }

            if (partFile.exists() && partFile.length() > 1024) {
                val targetFile = File(downloadsDir, "ep_${safeId}.$ext")
                if (targetFile.exists()) targetFile.delete()
                val renamed = partFile.renameTo(targetFile)
                if (renamed) {
                    episodeDao.updateDownloadStatus(episode.id, targetFile.absolutePath, true, targetFile.length())
                    Log.i(TAG, "Successfully downloaded: '${episode.title}' (${targetFile.length() / 1024 / 1024} MB) to ${targetFile.name}")
                    return true
                }
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Exception downloading '${episode.title}': ${e.message}", e)
            return false
        }
    }

    suspend fun deleteDownloadedEpisode(episodeId: String) = withContext(Dispatchers.IO) {
        val ep = episodeDao.getEpisodeByIdSync(episodeId) ?: return@withContext
        // Never delete the user's own files from a local folder show.
        if (com.eagertolisten.app.data.local.LocalFolderFormat.isLocalFeed(ep.podcastId)) return@withContext
        if (ep.localFilePath != null) {
            val file = File(ep.localFilePath)
            if (file.exists()) {
                file.delete()
            }
        }
        val safeId = episodeId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val partFile = File(getDownloadsDir(), "ep_${safeId}.part")
        if (partFile.exists()) partFile.delete()

        episodeDao.updateDownloadStatus(episodeId, null, false, null)
    }

    suspend fun cleanUnqueuedDownloads() = withContext(Dispatchers.IO) {
        val downloaded = episodeDao.getDownloadedEpisodesSync()
        val queueItems = queueDao.getAllQueueItemsSync()
        val queueEpisodeIds = queueItems.map { it.episodeId }.toSet()
        val pinnedEpisodeIds = queueItems.filter { it.isManual }.map { it.episodeId }.toSet()

        for (ep in downloaded) {
            if (com.eagertolisten.app.data.local.LocalFolderFormat.isLocalFeed(ep.podcastId)) continue
            if (ep.isCompleted || ep.isDismissed || (!queueEpisodeIds.contains(ep.id) && !pinnedEpisodeIds.contains(ep.id))) {
                if (ep.localFilePath != null) {
                    val file = File(ep.localFilePath)
                    if (file.exists()) {
                        file.delete()
                    }
                }
                episodeDao.updateDownloadStatus(ep.id, null, false, null)
                Log.d(TAG, "Cleaned unqueued/completed download for '${ep.title}'")
            }
        }
    }

    private suspend fun checkOrphanSweep() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("eager_settings", Context.MODE_PRIVATE)
        val lastSweep = prefs.getLong(PREF_KEY_LAST_ORPHAN_SWEEP, 0L)
        val now = System.currentTimeMillis()
        if (now - lastSweep > 7L * 24L * 3600L * 1000L) {
            performOrphanSweep()
            prefs.edit().putLong(PREF_KEY_LAST_ORPHAN_SWEEP, now).apply()
        }
    }

    suspend fun performOrphanSweep() = withContext(Dispatchers.IO) {
        val downloadsDir = getDownloadsDir()
        val files = downloadsDir.listFiles() ?: return@withContext
        val downloaded = episodeDao.getDownloadedEpisodesSync()
        val knownPaths = downloaded.mapNotNull { it.localFilePath }.toSet()

        var deletedCount = 0
        for (file in files) {
            if (file.name.endsWith(".part")) {
                if (System.currentTimeMillis() - file.lastModified() > 48L * 3600L * 1000L) {
                    file.delete()
                    deletedCount++
                }
            } else if (!knownPaths.contains(file.absolutePath)) {
                file.delete()
                deletedCount++
            }
        }
        if (deletedCount > 0) {
            Log.i(TAG, "Orphan sweep removed $deletedCount untracked audio file(s)")
        }
    }
}
