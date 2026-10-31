package com.eagertolisten.app.importer

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Xml
import com.eagertolisten.app.data.EagerDatabase
import androidx.room.withTransaction
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.model.EpisodeId
import com.eagertolisten.app.data.model.FeedUrl
import com.eagertolisten.app.data.model.Podcast
import com.eagertolisten.app.parser.FeedParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class ImportResult(
    val podcastsImported: Int,
    val episodesImported: Int,
    val message: String
)

class PodcastAddictImporter(private val context: Context) {

    private val db = EagerDatabase.getInstance(context)
    private val podcastDao = db.podcastDao()
    private val episodeDao = db.episodeDao()

    suspend fun importFromUri(uri: Uri): Result<ImportResult> = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return@withContext Result.failure(Exception("Cannot open selected file"))

        val tempFile = File(context.cacheDir, "import_temp_${System.currentTimeMillis()}.bin")
        try {
            FileOutputStream(tempFile).use { out ->
                inputStream.copyTo(out)
            }

            if (isZipFile(tempFile)) {
                // Podcast Addict full .backup file (ZIP with podcastAddict.db + preferences XML)
                val (dbFile, prefsXml) = extractFromZip(tempFile)
                if (dbFile == null) {
                    return@withContext Result.failure(Exception("No SQLite database found inside Podcast Addict backup"))
                }
                try {
                    val result = importFromSqliteDb(dbFile, prefsXml)
                    Result.success(result)
                } finally {
                    dbFile.delete()
                }
            } else if (isSqliteDb(tempFile)) {
                // Unzipped or direct SQLite .db file
                val result = importFromSqliteDb(tempFile)
                Result.success(result)
            } else {
                // Try parsing as OPML XML
                tempFile.inputStream().use { stream ->
                    val result = importFromOpml(stream)
                    Result.success(result)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempFile.delete()
        }
    }

    private fun isZipFile(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        val bytes = ByteArray(4)
        file.inputStream().use { it.read(bytes) }
        return bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
               bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
    }

    private fun isSqliteDb(file: File): Boolean {
        if (!file.exists() || file.length() < 16) return false
        val header = ByteArray(16)
        file.inputStream().use { it.read(header) }
        return String(header).startsWith("SQLite format 3")
    }

    /**
     * Pulls the SQLite database and the preferences XML out of a Podcast Addict backup.
     * The XML comes first in the ZIP and its name also contains "podcastaddict", so the old
     * name check grabbed the XML as the database and every full-backup import failed.
     */
    private fun extractFromZip(zipFile: File): Pair<File?, String?> {
        var dbFile: File? = null
        var prefsXml: String? = null
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && dbFile == null && PodcastAddictFormat.isDatabaseEntry(name)) {
                    val extracted = File(context.cacheDir, "extracted_${System.currentTimeMillis()}.db")
                    FileOutputStream(extracted).use { out -> zis.copyTo(out) }
                    if (isSqliteDb(extracted)) dbFile = extracted else extracted.delete()
                } else if (!entry.isDirectory && prefsXml == null && PodcastAddictFormat.isPreferencesEntry(name)) {
                    prefsXml = zis.readBytes().toString(Charsets.UTF_8)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return dbFile to prefsXml
    }

    private suspend fun importFromSqliteDb(dbFile: File, prefsXml: String? = null): ImportResult {
        val sqlite = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        var podcastsCount = 0
        var episodesCount = 0

        try {
            // Find podcast table
            val tables = mutableListOf<String>()
            sqlite.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                while (cursor.moveToNext()) {
                    tables.add(cursor.getString(0))
                }
            }

            val podcastTable = tables.firstOrNull { it.equals("podcasts", ignoreCase = true) }
                ?: tables.firstOrNull { it.contains("podcast", ignoreCase = true) }
                ?: tables.firstOrNull { it.contains("feed", ignoreCase = true) }

            val episodeTable = tables.firstOrNull { it.equals("episodes", ignoreCase = true) }
                ?: tables.firstOrNull { it.contains("episode", ignoreCase = true) }

            if (podcastTable == null) {
                return ImportResult(0, 0, "No podcast tables found in database")
            }

            // Map old Podcast Addict podcast ID -> Eager Podcast ID
            val podcastIdMap = mutableMapOf<String, String>()
            val podcastTitleMap = mutableMapOf<String, String>()
            val podcastsToInsert = mutableListOf<Podcast>()
            val perShowSpeeds = PodcastAddictFormat.parsePerShowSpeeds(prefsXml)

            sqlite.rawQuery("SELECT * FROM $podcastTable", null).use { cursor ->
                android.util.Log.i("PodcastAddictImporter", "Podcast table '$podcastTable' columns: ${cursor.columnNames.joinToString()}")
                val hasSubscribedColumn = cursor.getColumnIndex("subscribed_status") >= 0
                while (cursor.moveToNext()) {
                    // Podcast Addict keeps every show ever browsed; only bring over real subscriptions.
                    if (hasSubscribedColumn && getColumnInt(cursor, 0, "subscribed_status") != 1) continue
                    // Virtual (local-folder) shows have no feed to follow.
                    if (getColumnInt(cursor, 0, "is_virtual") == 1) continue

                    val rawId = getColumnString(cursor, "_id", "id", "feed_id") ?: ""
                    val feedUrl = getColumnString(cursor, "feed_url", "url", "rss_url", "xml_url", "link")
                    val title = getColumnString(cursor, "custom_name")?.takeIf { it.isNotBlank() }
                        ?: getColumnString(cursor, "name", "title", "podcast_name") ?: "Podcast"
                    val author = getColumnString(cursor, "author", "artist", "creator")
                    val artworkUrl = getColumnString(cursor, "image_url", "artwork_url", "thumbnail_url", "icon_url")
                    val category = getColumnString(cursor, "category", "categories", "genre") ?: ""

                    // Podcast Addict's "priority" is 1 for every show (not our 1-5 scale), which made every
                    // imported show Priority 1 / Hot List. Start everyone at the normal default instead.
                    val priority = 3

                    val speed = perShowSpeeds[rawId]
                        ?: getColumnFloat(cursor, 1.0f, "playback_speed", "speed").takeIf { it in 0.5f..3.0f }
                        ?: 1.0f
                    val skipIntro = getColumnInt(cursor, 0, "skip_first_seconds", "skip_intro", "skip_first")
                    val skipOutro = getColumnInt(cursor, 0, "skip_last_seconds", "skip_outro", "skip_last")

                    if (!feedUrl.isNullOrBlank()) {
                        val eagerId = FeedUrl.normalize(feedUrl).ifBlank { feedUrl.trim() }
                        if (rawId.isNotEmpty()) {
                            podcastIdMap[rawId] = eagerId
                        }
                        podcastIdMap[feedUrl] = eagerId
                        podcastTitleMap[eagerId] = title

                        val strategy = FeedParser.inferPlayOrderStrategy(title, category = category)

                        podcastsToInsert.add(
                            Podcast(
                                id = eagerId,
                                feedUrl = eagerId,
                                title = title,
                                author = author ?: "",
                                artworkUrl = artworkUrl ?: "",
                                description = "",
                                priority = priority,
                                skipIntroSeconds = skipIntro,
                                skipOutroSeconds = skipOutro,
                                playbackSpeed = speed,
                                autoAddToQueue = true,
                                category = com.eagertolisten.app.parser.FeedParser.effectiveCategory(title, category),
                                playOrderStrategy = strategy
                            )
                        )
                    }
                }
            }

            if (podcastsToInsert.isNotEmpty()) {
                podcastDao.insertPodcastsIgnore(podcastsToInsert)
                podcastsCount = podcastsToInsert.size
            }

            // Import episodes with played/playback position history if episode table exists
            if (episodeTable != null) {
                val episodesToInsert = mutableListOf<Episode>()
                sqlite.rawQuery("SELECT * FROM $episodeTable", null).use { cursor ->
                    // Logged so a tester's log or crash report shows exactly which columns this
                    // Podcast Addict version uses (the played-state column names aren't documented).
                    android.util.Log.i("PodcastAddictImporter", "Episode table '$episodeTable' columns: ${cursor.columnNames.joinToString()}")
                    while (cursor.moveToNext()) {
                        val rawPodcastId = getColumnString(cursor, "podcast_id", "feed_id", "podcastId") ?: ""
                        val eagerPodcastId = podcastIdMap[rawPodcastId]
                        if (eagerPodcastId.isNullOrBlank()) continue

                        val epTitle = getColumnString(cursor, "title", "name") ?: "Episode"
                        val audioUrl = getColumnString(cursor, "download_url", "stream_url", "url", "enclosure_url", "link") ?: ""
                        val guid = getColumnString(cursor, "guid", "id", "_id") ?: audioUrl
                        val epId = EpisodeId.of(podcastId = eagerPodcastId, guid = guid, enclosureUrl = audioUrl)

                        val pubDate = getColumnLong(cursor, 0L, "publication_date", "pub_date", "date", "published_at")
                        // Podcast Addict's "duration" is TEXT ("32:12"); reading it as a number gave 32 seconds,
                        // which also made nearly every started episode look finished. Prefer the ms columns.
                        val duration = PodcastAddictFormat.durationSeconds(
                            durationMs = getColumnLong(cursor, 0L, "duration_ms"),
                            rssDurationMs = getColumnLong(cursor, 0L, "rssfeed_duration_ms"),
                            durationText = getColumnString(cursor, "duration", "total_time", "length")
                        )

                        // position_to_resume is always milliseconds in Podcast Addict.
                        val posSec = if (cursor.getColumnIndex("position_to_resume") >= 0) {
                            (getColumnLong(cursor, 0L, "position_to_resume") / 1000L).coerceAtLeast(0L)
                        } else {
                            val rawPos = getColumnLong(cursor, 0L, "playback_position", "position", "progress")
                            if (rawPos > 100_000L && duration > 0L && rawPos > duration) rawPos / 1000L else rawPos
                        }

                        // Played flag: seen_status in Podcast Addict (verified). "downloaded" used to be in
                        // this list, which was wrong: a downloaded episode isn't necessarily played.
                        val playedFlag = getColumnInt(cursor, 0, "seen_status", "read", "played", "is_read")
                        val isCompleted = playedFlag == 1 || (duration > 0 && posSec >= (duration * 0.95))

                        // When it was played. Stamping every imported episode with "now" made every show
                        // look just-heard to the queue's fairness rules. Use the backup's last-played date if
                        // it has one (playbackDate, epoch ms in Podcast Addict), else the publish date.
                        val rawPlayedAt = getColumnLong(cursor, 0L, "playback_date", "playbackDate", "last_played", "last_playback_date")
                        val playedAtMs = when {
                            rawPlayedAt in 1L until 100_000_000_000L -> rawPlayedAt * 1000L  // epoch seconds
                            rawPlayedAt > 0L -> rawPlayedAt
                            pubDate > 0L -> pubDate
                            else -> null
                        }

                        episodesToInsert.add(
                            Episode(
                                id = epId,
                                podcastId = eagerPodcastId,
                                podcastTitle = podcastTitleMap[eagerPodcastId] ?: "Podcast",
                                title = epTitle,
                                audioUrl = audioUrl,
                                publishedAt = pubDate,
                                durationSeconds = duration,
                                playbackPositionSeconds = posSec,
                                isCompleted = isCompleted,
                                completedAt = if (isCompleted) playedAtMs else null
                            )
                        )
                    }
                }

                if (episodesToInsert.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    db.withTransaction {
                        // Insert in chunks so very large backups don't build one giant statement.
                        episodesToInsert.chunked(500).forEach { episodeDao.insertEpisodes(it) }
                        // insertEpisodes ignores rows that already exist (subscribed or OPML-imported
                        // before this backup), so carry the backup's played state / position onto them.
                        for (ep in episodesToInsert) {
                            if (ep.isCompleted) {
                                episodeDao.applyImportedPlayed(ep.id, ep.completedAt, now)
                            } else if (ep.playbackPositionSeconds > 0) {
                                episodeDao.applyImportedPosition(ep.id, ep.playbackPositionSeconds, now)
                            }
                        }
                    }
                    episodesCount = episodesToInsert.size
                }
            }

            return ImportResult(
                podcastsImported = podcastsCount,
                episodesImported = episodesCount,
                message = "Successfully imported $podcastsCount podcasts and $episodesCount episodes with played history from Podcast Addict"
            )
        } finally {
            sqlite.close()
        }
    }

    private suspend fun importFromOpml(inputStream: InputStream): ImportResult {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)

        var eventType = parser.eventType
        val importedPodcasts = mutableListOf<Podcast>()
        var parentCategory = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                if (parser.name.equals("outline", ignoreCase = true)) {
                    val xmlUrl = parser.getAttributeValue(null, "xmlUrl")
                        ?: parser.getAttributeValue(null, "url")
                    val title = parser.getAttributeValue(null, "text")
                        ?: parser.getAttributeValue(null, "title")
                        ?: "Podcast"
                    val category = parser.getAttributeValue(null, "category") ?: parentCategory

                    if (!xmlUrl.isNullOrBlank()) {
                        val id = FeedUrl.normalize(xmlUrl).ifBlank { xmlUrl.trim() }
                        val strategy = FeedParser.inferPlayOrderStrategy(title, category = category)
                        importedPodcasts.add(
                            Podcast(
                                id = id,
                                feedUrl = id,
                                title = title,
                                author = "",
                                artworkUrl = "",
                                description = "",
                                category = if (category.isNotBlank()) category else "General",
                                priority = 3,
                                autoAddToQueue = true,
                                playOrderStrategy = strategy
                            )
                        )
                    } else {
                        val groupText = parser.getAttributeValue(null, "text")
                            ?: parser.getAttributeValue(null, "title")
                        if (!groupText.isNullOrBlank()) {
                            parentCategory = groupText
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        if (importedPodcasts.isNotEmpty()) {
            podcastDao.insertPodcastsIgnore(importedPodcasts)
        }

        return ImportResult(
            podcastsImported = importedPodcasts.size,
            episodesImported = 0,
            message = "Successfully imported ${importedPodcasts.size} podcast feeds from OPML"
        )
    }

    private fun getColumnString(cursor: Cursor, vararg candidateNames: String): String? {
        for (name in candidateNames) {
            val idx = cursor.getColumnIndex(name)
            if (idx >= 0 && !cursor.isNull(idx)) {
                return cursor.getString(idx)
            }
        }
        return null
    }

    private fun getColumnInt(cursor: Cursor, defaultValue: Int, vararg candidateNames: String): Int {
        for (name in candidateNames) {
            val idx = cursor.getColumnIndex(name)
            if (idx >= 0 && !cursor.isNull(idx)) {
                return cursor.getInt(idx)
            }
        }
        return defaultValue
    }

    private fun getColumnFloat(cursor: Cursor, defaultValue: Float, vararg candidateNames: String): Float {
        for (name in candidateNames) {
            val idx = cursor.getColumnIndex(name)
            if (idx >= 0 && !cursor.isNull(idx)) {
                return cursor.getFloat(idx)
            }
        }
        return defaultValue
    }

    private fun getColumnLong(cursor: Cursor, defaultValue: Long, vararg candidateNames: String): Long {
        for (name in candidateNames) {
            val idx = cursor.getColumnIndex(name)
            if (idx >= 0 && !cursor.isNull(idx)) {
                return cursor.getLong(idx)
            }
        }
        return defaultValue
    }
}
