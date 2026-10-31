package com.eagertolisten.app.data.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.eagertolisten.app.data.EagerDatabase
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.model.PlayOrderStrategy
import com.eagertolisten.app.data.model.Podcast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a user-picked folder of audio files into a show, and rescans it on refresh.
 * Read-only: it never writes to, moves or deletes the user's files.
 */
class LocalFolderScanner(context: Context) {

    private val appContext = context.applicationContext
    private val db = EagerDatabase.getInstance(appContext)
    private val podcastDao = db.podcastDao()
    private val episodeDao = db.episodeDao()

    /** Adds [treeUri] (from OpenDocumentTree, permission already persisted) as a show and scans it. */
    suspend fun addFolder(treeUri: Uri): Result<Podcast> = withContext(Dispatchers.IO) {
        try {
            val dir = DocumentFile.fromTreeUri(appContext, treeUri)
                ?: return@withContext Result.failure(Exception("Couldn't open that folder"))
            val id = LocalFolderFormat.feedIdFor(treeUri.toString())
            val existing = podcastDao.getPodcastByIdSync(id)
            val podcast = existing ?: Podcast(
                id = id,
                feedUrl = id,
                title = LocalFolderFormat.showTitle(dir.name, treeUri.toString()),
                artworkUrl = "",
                author = "On this device",
                description = "Audio files from a folder on this device.",
                category = LocalFolderFormat.CATEGORY,
                priority = 3,
                autoAddToQueue = true,
                // Archives play in order: oldest (first file by name) first.
                playOrderStrategy = PlayOrderStrategy.CHRONOLOGICAL.name
            )
            if (existing == null) podcastDao.insertPodcast(podcast)
            rescan(podcast).getOrThrow()
            Result.success(podcast)
        } catch (e: Exception) {
            Log.e(TAG, "addFolder failed for $treeUri", e)
            Result.failure(e)
        }
    }

    /** Picks up files added to the folder and hides episodes whose files are gone. Returns new-file count. */
    suspend fun rescan(podcast: Podcast): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val treeUri = Uri.parse(LocalFolderFormat.treeUriOf(podcast.feedUrl))
            val dir = DocumentFile.fromTreeUri(appContext, treeUri)
            if (dir == null || !dir.exists() || !dir.canRead()) {
                return@withContext Result.failure(
                    Exception("Lost access to the folder for '${podcast.title}'. It may have been moved or renamed; add it again from Settings.")
                )
            }

            val files = dir.listFiles().filter { it.isFile && LocalFolderFormat.isAudioFile(it.name, it.type) }
            val byName = files.associateBy { it.name ?: it.uri.toString() }
            val ordered = LocalFolderFormat.publishTimesInNameOrder(byName.map { (name, f) -> name to f.lastModified() })

            val existing = episodeDao.getEpisodesForPodcastSync(podcast.id).associateBy { it.id }
            val presentIds = mutableSetOf<String>()
            val newEpisodes = mutableListOf<Episode>()

            ordered.forEachIndexed { index, (name, publishedAt) ->
                val file = byName[name] ?: return@forEachIndexed
                val fileUri = file.uri.toString()
                val episodeId = "${podcast.id}::$fileUri"
                presentIds.add(episodeId)
                if (episodeId in existing) return@forEachIndexed

                val (durationSec, tagTitle) = readMetadata(file.uri)
                newEpisodes.add(
                    Episode(
                        id = episodeId,
                        podcastId = podcast.id,
                        podcastTitle = podcast.title,
                        title = tagTitle ?: LocalFolderFormat.titleFromFileName(name),
                        audioUrl = fileUri,
                        publishedAt = publishedAt,
                        durationSeconds = durationSec,
                        feedOrder = ordered.size - 1 - index
                    )
                )
            }
            if (newEpisodes.isNotEmpty()) episodeDao.insertEpisodes(newEpisodes)

            val now = System.currentTimeMillis()
            for ((id, ep) in existing) {
                if (id !in presentIds && !ep.isDismissed) {
                    episodeDao.markEpisodeDismissed(id, LocalFolderFormat.MISSING_REASON, now)
                } else if (id in presentIds && ep.isDismissed && ep.dismissReason == LocalFolderFormat.MISSING_REASON) {
                    episodeDao.restoreMissingLocalEpisode(id, now)
                }
            }
            Log.d(TAG, "Rescanned '${podcast.title}': ${files.size} files, ${newEpisodes.size} new")
            Result.success(newEpisodes.size)
        } catch (e: SecurityException) {
            Result.failure(Exception("Lost access to the folder for '${podcast.title}'. Add it again from Settings."))
        } catch (e: Exception) {
            Log.e(TAG, "rescan failed for '${podcast.title}'", e)
            Result.failure(e)
        }
    }

    /** Length (seconds) and the file's own title tag, if any. */
    private fun readMetadata(uri: Uri): Pair<Long, String?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
            (ms / 1000L) to title
        } catch (e: Exception) {
            0L to null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "LocalFolderScanner"
    }
}
