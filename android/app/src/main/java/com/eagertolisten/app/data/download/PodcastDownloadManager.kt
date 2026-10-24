package com.eagertolisten.app.data.download

import android.content.Context
import android.os.Environment
import android.util.Log
import com.eagertolisten.app.data.EagerDatabase
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.repository.PodcastRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class PodcastDownloadManager(private val context: Context) {

    private val db = EagerDatabase.getInstance(context)
    private val episodeDao = db.episodeDao()
    private val queueDao = db.queueDao()
    private val podcastDao = db.podcastDao()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    companion object {
        private const val TAG = "PodcastDownloadMgr"
        const val PREF_KEY_AUTO_DOWNLOAD_ENABLED = "auto_download_enabled"
        const val PREF_KEY_AUTO_DOWNLOAD_HOURS = "auto_download_hours"
        const val DEFAULT_AUTO_DOWNLOAD_HOURS = 4
    }

    private fun getDownloadsDir(): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS)
            ?: File(context.filesDir, "downloads")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100)
    }

    /**
     * Auto-downloads next queue episodes up to targetDurationHours (default 4 hours).
     * Excludes podcasts with playOrderStrategy == 'NEWS'.
     * Keeps downloading until accumulated duration reaches or exceeds the target.
     */
    suspend fun performAutoDownloadQueue(targetHours: Int = DEFAULT_AUTO_DOWNLOAD_HOURS): Int = withContext(Dispatchers.IO) {
        val targetDurationSec = targetHours.toLong() * 3600L
        val queueEpisodes = queueDao.getQueueEpisodesSync()
        if (queueEpisodes.isEmpty()) {
            Log.d(TAG, "Queue is empty; nothing to auto-download.")
            return@withContext 0
        }

        var accumulatedDurationSec = 0L
        var downloadedCount = 0

        for (ep in queueEpisodes) {
            if (ep.isCompleted || ep.isLive || ep.liveStatus != null) continue

            // Exclude news podcasts: timely and will be refreshed just-in-time
            val podcast = podcastDao.getPodcastByIdSync(ep.podcastId)
            if (podcast?.playOrderStrategy == "NEWS") {
                Log.d(TAG, "Skipping '${ep.title}' - NEWS feed excluded from pre-downloading")
                continue
            }

            // Estimate duration if 0 (default to 45 min)
            val effectiveDuration = if (ep.durationSeconds > 0) ep.durationSeconds else 2700L

            // Check if already downloaded
            val isAlreadyDownloaded = ep.isDownloaded && ep.localFilePath != null && File(ep.localFilePath).exists()
            if (isAlreadyDownloaded) {
                accumulatedDurationSec += effectiveDuration
                Log.d(TAG, "Already downloaded: '${ep.title}' (${effectiveDuration}s), accumulated: ${accumulatedDurationSec}s")
            } else {
                Log.i(TAG, "Auto-downloading queue item: '${ep.title}' (${effectiveDuration}s)")
                val success = downloadEpisodeInternal(ep)
                if (success) {
                    downloadedCount++
                    accumulatedDurationSec += effectiveDuration
                }
            }

            // Rule: "You can go over just not under the setting"
            if (accumulatedDurationSec >= targetDurationSec) {
                Log.i(TAG, "Reached target programming buffer: ${accumulatedDurationSec}s >= ${targetDurationSec}s (${targetHours}h)")
                break
            }
        }

        cleanCompletedDownloads()
        downloadedCount
    }

    suspend fun downloadEpisode(episode: Episode): Boolean = withContext(Dispatchers.IO) {
        downloadEpisodeInternal(episode)
    }

    private suspend fun downloadEpisodeInternal(episode: Episode): Boolean {
        try {
            if (episode.audioUrl.isBlank()) return false
            val fileName = "ep_${episode.id.hashCode()}_${sanitizeFileName(episode.title)}.mp3"
            val targetFile = File(getDownloadsDir(), fileName)

            if (targetFile.exists() && targetFile.length() > 0) {
                episodeDao.updateDownloadStatus(episode.id, targetFile.absolutePath, true)
                return true
            }

            val tempFile = File(getDownloadsDir(), "$fileName.tmp")
            val request = Request.Builder()
                .url(episode.audioUrl)
                .header("User-Agent", PodcastRepository.USER_AGENT)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed HTTP ${response.code} downloading '${episode.title}'")
                    return false
                }
                val body = response.body ?: return false
                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(tempFile)

                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output, bufferSize = 8192)
                    }
                }
            }

            if (tempFile.exists() && tempFile.length() > 1024) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
                episodeDao.updateDownloadStatus(episode.id, targetFile.absolutePath, true)
                Log.i(TAG, "Successfully downloaded: '${episode.title}' to ${targetFile.absolutePath} (${targetFile.length() / 1024 / 1024} MB)")
                return true
            } else {
                tempFile.delete()
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception downloading '${episode.title}': ${e.message}", e)
            return false
        }
    }

    suspend fun deleteDownloadedEpisode(episodeId: String) = withContext(Dispatchers.IO) {
        val ep = episodeDao.getEpisodeByIdSync(episodeId) ?: return@withContext
        if (ep.localFilePath != null) {
            val file = File(ep.localFilePath)
            if (file.exists()) {
                file.delete()
            }
        }
        episodeDao.updateDownloadStatus(episodeId, null, false)
    }

    suspend fun cleanCompletedDownloads() = withContext(Dispatchers.IO) {
        val downloaded = episodeDao.getDownloadedEpisodesSync()
        for (ep in downloaded) {
            if (ep.isCompleted) {
                if (ep.localFilePath != null) {
                    val file = File(ep.localFilePath)
                    if (file.exists()) {
                        file.delete()
                    }
                }
                episodeDao.updateDownloadStatus(ep.id, null, false)
                Log.d(TAG, "Cleaned completed download for '${ep.title}'")
            }
        }
    }
}
