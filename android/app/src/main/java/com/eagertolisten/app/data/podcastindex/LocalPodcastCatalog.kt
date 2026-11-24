package com.eagertolisten.app.data.podcastindex

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.eagertolisten.app.data.repository.PodcastRepository
import com.eagertolisten.app.ui.viewmodel.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.zip.GZIPInputStream

/**
 * Local partial copy of the PodcastIndex.org directory (5,000 to 100,000 top active podcasts)
 * stored in a compact read-only SQLite + FTS4 database inside app storage.
 *
 * Refreshed automatically when missing or older than [MAX_AGE_DAYS] (21 days), using conditional
 * HTTP headers (ETag / If-Modified-Since) and unmetered Wi-Fi constraints in background workers
 * to respect normal device storage, memory, and mobile data limitations.
 */
class LocalPodcastCatalog(private val context: Context) {

    data class CatalogHeader(
        val version: Int,
        val generatedAt: Long,
        val count: Int
    )

    data class CatalogRow(
        val title: String,
        val author: String,
        val url: String,
        val artwork: String,
        val categories: String,
        val itunesId: String?,
        val podcastGuid: String?,
        val newestItemPubdateSec: Long
    )

    data class CatalogStatus(
        val available: Boolean,
        val count: Int,
        val downloadedAtMs: Long,
        val generatedAtMs: Long,
        val isStale: Boolean
    )

    enum class RefreshOutcome {
        UP_TO_DATE,
        UPDATED,
        NOT_MODIFIED,
        SKIPPED_METERED,
        FAILED
    }

    data class RefreshResult(
        val outcome: RefreshOutcome,
        val count: Int = 0,
        val message: String = ""
    )

    companion object {
        private const val TAG = "LocalPodcastCatalog"

        const val DEFAULT_SNAPSHOT_URL =
            "https://github.com/michaeleger/eager-to-listen/releases/download/podcastindex-catalog/podcastindex-catalog.tsv.gz"

        const val MAX_AGE_DAYS = 21L
        const val MAX_AGE_MS = MAX_AGE_DAYS * 24L * 60L * 60L * 1000L

        /** Hard limits protecting normal phone storage and RAM against oversized/corrupt snapshots. */
        const val MAX_IMPORT_ROWS = 100_000
        const val MAX_UNCOMPRESSED_BYTES = 64L * 1024L * 1024L

        const val PREF_KEY_DOWNLOADED_AT = "pi_catalog_downloaded_at"
        const val PREF_KEY_GENERATED_AT = "pi_catalog_generated_at"
        const val PREF_KEY_COUNT = "pi_catalog_count"
        const val PREF_KEY_ETAG = "pi_catalog_etag"
        const val PREF_KEY_LAST_MODIFIED = "pi_catalog_last_modified"
        const val PREF_KEY_SNAPSHOT_URL = "pi_catalog_snapshot_url"

        private val MARKS = Regex("\\p{M}+")
        private val NON_ALNUM = Regex("[^a-z0-9]+")
        private val dbLock = Any()

        /** Returns true when no local DB is present or the last update/check is older than 21 days. */
        fun isStale(
            hasDb: Boolean,
            downloadedAtMs: Long,
            nowMs: Long = System.currentTimeMillis()
        ): Boolean {
            if (!hasDb || downloadedAtMs <= 0L) return true
            return (nowMs - downloadedAtMs) > MAX_AGE_MS
        }

        fun normalizeForIndex(text: String?): String =
            Normalizer.normalize(text.orEmpty().lowercase(), Normalizer.Form.NFKD)
                .replace(MARKS, "")
                .replace(NON_ALNUM, " ")
                .trim()

        /** Builds a safe FTS4 prefix query (e.g. "huberman* lab*") from arbitrary user text. */
        fun buildFtsQuery(query: String): String? {
            val tokens = normalizeForIndex(query)
                .split(" ")
                .filter { it.isNotBlank() }
                .take(8)
            if (tokens.isEmpty()) return null
            return tokens.joinToString(" ") { "$it*" }
        }

        fun parseHeader(line: String): CatalogHeader? {
            if (!line.startsWith("#")) return null
            val raw = line.removePrefix("#").trim()
            if (!raw.startsWith("{")) return null
            return try {
                val obj = JSONObject(raw)
                CatalogHeader(
                    version = obj.optInt("version", 1),
                    generatedAt = obj.optLong("generatedAt", 0L),
                    count = obj.optInt("count", 0)
                )
            } catch (_: Exception) {
                null
            }
        }

        fun parseRow(line: String): CatalogRow? {
            if (line.isBlank() || line.startsWith("#")) return null
            val parts = line.split('\t')
            if (parts.size < 3) return null
            val title = parts[0].trim()
            val author = parts.getOrElse(1) { "" }.trim()
            val url = parts.getOrElse(2) { "" }.trim()
            if (title.isBlank() || url.isBlank()) return null
            val artwork = parts.getOrElse(3) { "" }.trim()
            val categories = parts.getOrElse(4) { "" }.trim()
            val itunesId = parts.getOrElse(5) { "" }.trim().ifBlank { null }
            val podcastGuid = parts.getOrElse(6) { "" }.trim().ifBlank { null }
            val newestSec = parts.getOrElse(7) { "0" }.trim().toLongOrNull() ?: 0L
            return CatalogRow(
                title = title,
                author = author,
                url = url,
                artwork = artwork,
                categories = categories,
                itunesId = itunesId,
                podcastGuid = podcastGuid,
                newestItemPubdateSec = newestSec
            )
        }

        /** Maps app category labels to normalized FTS4 search phrases for category browsing. */
        fun categoryFtsQueries(category: String): List<String> {
            val c = normalizeForIndex(category)
            return when {
                c.contains("health") || c.contains("fitness") || c.contains("wellness") ->
                    listOf("health*", "fitness*")
                c.contains("self help") || c.contains("mindset") ->
                    listOf("self* help*", "mental* health*", "health*")
                c.contains("true crime") -> listOf("true* crime*")
                c.contains("society") || c.contains("culture") -> listOf("society*", "culture*")
                c.contains("news") -> listOf("news*")
                c.contains("technology") -> listOf("technology*")
                c.contains("science") -> listOf("science*")
                c.contains("business") -> listOf("business*")
                c.contains("history") -> listOf("history*")
                c.contains("comedy") -> listOf("comedy*")
                c.contains("education") -> listOf("education*")
                else -> buildFtsQuery(category)?.let { listOf(it) } ?: emptyList()
            }
        }
    }

    private val prefs get() = context.getSharedPreferences("eager_settings", Context.MODE_PRIVATE)

    internal fun catalogDir(): File = File(context.filesDir, "catalog").apply { if (!exists()) mkdirs() }
    internal fun dbFile(): File = File(catalogDir(), "podcastindex_catalog.db")

    fun snapshotUrl(): String =
        prefs.getString(PREF_KEY_SNAPSHOT_URL, null)?.trim()?.ifBlank { null } ?: DEFAULT_SNAPSHOT_URL

    fun hasCatalog(): Boolean {
        val f = dbFile()
        return f.exists() && f.length() > 0L && prefs.getInt(PREF_KEY_COUNT, 0) > 0
    }

    fun getStatus(nowMs: Long = System.currentTimeMillis()): CatalogStatus {
        val available = hasCatalog()
        val downloadedAt = prefs.getLong(PREF_KEY_DOWNLOADED_AT, 0L)
        val generatedAt = prefs.getLong(PREF_KEY_GENERATED_AT, 0L)
        val count = if (available) prefs.getInt(PREF_KEY_COUNT, 0) else 0
        return CatalogStatus(
            available = available,
            count = count,
            downloadedAtMs = downloadedAt,
            generatedAtMs = generatedAt,
            isStale = isStale(available, downloadedAt, nowMs)
        )
    }

    fun isStale(nowMs: Long = System.currentTimeMillis()): Boolean =
        isStale(hasCatalog(), prefs.getLong(PREF_KEY_DOWNLOADED_AT, 0L), nowMs)

    fun isOnUnmeteredNetwork(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Refreshes the local partial Podcast Index DB if missing or > 21 days old (or if [force] is true).
     * When [requireUnmetered] is true (background auto-update), skips if the device is on metered mobile data.
     */
    suspend fun refreshIfStale(
        force: Boolean = false,
        requireUnmetered: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): RefreshResult = withContext(Dispatchers.IO) {
        if (!force && !isStale(nowMs)) {
            return@withContext RefreshResult(
                outcome = RefreshOutcome.UP_TO_DATE,
                count = prefs.getInt(PREF_KEY_COUNT, 0),
                message = "Local catalog is up to date"
            )
        }
        if (requireUnmetered && !isOnUnmeteredNetwork()) {
            Log.i(TAG, "Skipping background catalog refresh on metered network")
            return@withContext RefreshResult(
                outcome = RefreshOutcome.SKIPPED_METERED,
                count = prefs.getInt(PREF_KEY_COUNT, 0),
                message = "Waiting for Wi-Fi to update local catalog"
            )
        }

        val url = snapshotUrl()
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", PodcastRepository.USER_AGENT)

        if (!force && hasCatalog()) {
            prefs.getString(PREF_KEY_ETAG, null)?.takeIf { it.isNotBlank() }?.let {
                reqBuilder.header("If-None-Match", it)
            }
            prefs.getString(PREF_KEY_LAST_MODIFIED, null)?.takeIf { it.isNotBlank() }?.let {
                reqBuilder.header("If-Modified-Since", it)
            }
        }

        try {
            val client = PodcastRepository.getOkHttpClient()
            client.newCall(reqBuilder.build()).execute().use { response ->
                if (response.code == 304 && hasCatalog()) {
                    prefs.edit().putLong(PREF_KEY_DOWNLOADED_AT, nowMs).apply()
                    val count = prefs.getInt(PREF_KEY_COUNT, 0)
                    Log.i(TAG, "Local catalog unchanged (304 Not Modified); reset 21-day timer ($count shows)")
                    return@withContext RefreshResult(
                        outcome = RefreshOutcome.NOT_MODIFIED,
                        count = count,
                        message = "Local catalog is already the latest snapshot"
                    )
                }
                if (!response.isSuccessful) {
                    val msg = "Catalog download failed: HTTP ${response.code}"
                    Log.w(TAG, msg)
                    return@withContext RefreshResult(outcome = RefreshOutcome.FAILED, message = msg)
                }
                val body = response.body ?: return@withContext RefreshResult(
                    outcome = RefreshOutcome.FAILED,
                    message = "Empty catalog response"
                )
                val imported = importFromStream(
                    rawStream = body.byteStream(),
                    etag = response.header("ETag"),
                    lastModified = response.header("Last-Modified"),
                    nowMs = nowMs
                )
                Log.i(TAG, "Imported local Podcast Index catalog: $imported shows")
                RefreshResult(
                    outcome = RefreshOutcome.UPDATED,
                    count = imported,
                    message = "Updated local catalog ($imported shows)"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh local catalog: ${e.message}", e)
            RefreshResult(
                outcome = RefreshOutcome.FAILED,
                count = prefs.getInt(PREF_KEY_COUNT, 0),
                message = e.message ?: "Catalog download failed"
            )
        }
    }

    /**
     * Streams a plain or gzipped TSV snapshot into a temporary SQLite + FTS4 database and
     * atomically swaps it into place. Safe for up to [MAX_IMPORT_ROWS] (100,000) shows on mobile devices.
     */
    fun importFromStream(
        rawStream: InputStream,
        etag: String? = null,
        lastModified: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val buffered = BufferedInputStream(rawStream, 16 * 1024)
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        val isGzip = (b0 == 0x1f && b1 == 0x8b)
        val decodedStream: InputStream = if (isGzip) GZIPInputStream(buffered, 16 * 1024) else buffered

        val tmpFile = File(catalogDir(), "podcastindex_catalog.db.tmp")
        cleanupSqliteArtifacts(tmpFile)

        var header: CatalogHeader? = null
        var count = 0
        var totalBytesRead = 0L

        try {
            SQLiteDatabase.openOrCreateDatabase(tmpFile, null).use { db ->
                db.rawQuery("PRAGMA synchronous = OFF", null).use { it.moveToFirst() }
                db.rawQuery("PRAGMA journal_mode = MEMORY", null).use { it.moveToFirst() }
                db.execSQL(
                    """
                    CREATE TABLE podcasts (
                        rank INTEGER PRIMARY KEY,
                        title TEXT NOT NULL,
                        author TEXT NOT NULL,
                        url TEXT NOT NULL,
                        artwork TEXT NOT NULL,
                        categories TEXT NOT NULL,
                        itunes_id TEXT,
                        podcast_guid TEXT,
                        newest_item_pubdate INTEGER NOT NULL,
                        title_norm TEXT NOT NULL,
                        author_norm TEXT NOT NULL,
                        categories_norm TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE podcasts_fts USING fts4(
                        content='podcasts',
                        title_norm,
                        author_norm,
                        categories_norm
                    )
                    """.trimIndent()
                )

                val insertSql =
                    "INSERT INTO podcasts(rank, title, author, url, artwork, categories, itunes_id, podcast_guid, newest_item_pubdate, title_norm, author_norm, categories_norm) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                val stmt = db.compileStatement(insertSql)

                db.beginTransaction()
                try {
                    BufferedReader(InputStreamReader(decodedStream, Charsets.UTF_8), 16 * 1024).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            totalBytesRead += line.length + 1
                            if (totalBytesRead > MAX_UNCOMPRESSED_BYTES) {
                                Log.w(TAG, "Reached catalog uncompressed size cap (${MAX_UNCOMPRESSED_BYTES} bytes); stopping import at $count rows")
                                break
                            }
                            if (header == null && line.startsWith("#")) {
                                header = parseHeader(line)
                                continue
                            }
                            val row = parseRow(line) ?: continue
                            stmt.clearBindings()
                            stmt.bindLong(1, count.toLong())
                            stmt.bindString(2, row.title)
                            stmt.bindString(3, row.author)
                            stmt.bindString(4, row.url)
                            stmt.bindString(5, row.artwork)
                            stmt.bindString(6, row.categories)
                            if (row.itunesId != null) stmt.bindString(7, row.itunesId) else stmt.bindNull(7)
                            if (row.podcastGuid != null) stmt.bindString(8, row.podcastGuid) else stmt.bindNull(8)
                            stmt.bindLong(9, row.newestItemPubdateSec)
                            stmt.bindString(10, normalizeForIndex(row.title))
                            stmt.bindString(11, normalizeForIndex(row.author))
                            stmt.bindString(12, normalizeForIndex(row.categories.replace('|', ' ')))
                            stmt.executeInsert()
                            count++
                            if (count >= MAX_IMPORT_ROWS) break
                        }
                    }

                    if (count == 0) {
                        throw IllegalStateException("Catalog snapshot contained 0 valid podcasts")
                    }

                    // Populate external-content FTS4 index in one fast pass
                    db.execSQL("INSERT INTO podcasts_fts(podcasts_fts) VALUES('rebuild')")
                    db.setTransactionSuccessful()
                } finally {
                    stmt.close()
                    db.endTransaction()
                }
            }

            synchronized(dbLock) {
                val target = dbFile()
                cleanupSqliteArtifacts(target)
                if (!tmpFile.renameTo(target)) {
                    tmpFile.copyTo(target, overwrite = true)
                    tmpFile.delete()
                }
                prefs.edit()
                    .putLong(PREF_KEY_DOWNLOADED_AT, nowMs)
                    .putLong(PREF_KEY_GENERATED_AT, header?.generatedAt ?: nowMs)
                    .putInt(PREF_KEY_COUNT, count)
                    .apply {
                        if (etag != null) putString(PREF_KEY_ETAG, etag)
                        if (lastModified != null) putString(PREF_KEY_LAST_MODIFIED, lastModified)
                    }
                    .apply()
            }
            return count
        } catch (e: Exception) {
            cleanupSqliteArtifacts(tmpFile)
            throw e
        }
    }

    /** Full-text search against the local partial Podcast Index SQLite DB. */
    fun search(query: String, limit: Int = 40): List<SearchResult> {
        val ftsQuery = buildFtsQuery(query) ?: return emptyList()
        return queryWithDb { db ->
            val sql = """
                SELECT p.title, p.author, p.url, p.artwork, p.categories, p.itunes_id, p.podcast_guid, p.newest_item_pubdate
                FROM podcasts p
                JOIN podcasts_fts f ON p.rank = f.docid
                WHERE podcasts_fts MATCH ?
                ORDER BY p.rank ASC
                LIMIT ?
            """.trimIndent()
            db.rawQuery(sql, arrayOf(ftsQuery, (limit * 2).toString())).use { cursor ->
                val out = mutableListOf<SearchResult>()
                while (cursor.moveToNext()) {
                    out.add(cursorToSearchResult(cursor))
                }
                out
            }
        }
    }

    /** Browse a category from the local partial Podcast Index SQLite DB. */
    fun browseCategory(category: String, limit: Int = 40): List<SearchResult> {
        val queries = categoryFtsQueries(category)
        if (queries.isEmpty()) return emptyList()
        return queryWithDb { db ->
            val sql = """
                SELECT p.title, p.author, p.url, p.artwork, p.categories, p.itunes_id, p.podcast_guid, p.newest_item_pubdate
                FROM podcasts p
                JOIN podcasts_fts f ON p.rank = f.docid
                WHERE f.categories_norm MATCH ?
                ORDER BY p.rank ASC
                LIMIT ?
            """.trimIndent()
            val out = mutableListOf<SearchResult>()
            val seen = HashSet<String>()
            for (q in queries) {
                if (out.size >= limit) break
                db.rawQuery(sql, arrayOf(q, limit.toString())).use { cursor ->
                    while (cursor.moveToNext() && out.size < limit) {
                        val item = cursorToSearchResult(cursor, overrideGenre = category)
                        if (seen.add(item.feedUrl.lowercase())) {
                            out.add(item)
                        }
                    }
                }
            }
            out
        }
    }

    private fun <T> queryWithDb(block: (SQLiteDatabase) -> List<T>): List<T> {
        synchronized(dbLock) {
            val f = dbFile()
            if (!f.exists() || f.length() == 0L) return emptyList()
            return try {
                SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use(block)
            } catch (e: Exception) {
                Log.w(TAG, "Local catalog query failed: ${e.message}")
                emptyList()
            }
        }
    }

    private fun cursorToSearchResult(cursor: android.database.Cursor, overrideGenre: String? = null): SearchResult {
        val title = cursor.getString(0).orEmpty()
        val author = cursor.getString(1).orEmpty()
        val url = cursor.getString(2).orEmpty()
        val artwork = cursor.getString(3).orEmpty()
        val rawCats = cursor.getString(4).orEmpty()
        val itunesId = if (cursor.isNull(5)) null else cursor.getString(5)?.ifBlank { null }
        val podcastGuid = if (cursor.isNull(6)) null else cursor.getString(6)?.ifBlank { null }
        val newestSec = cursor.getLong(7)
        val genres = when {
            overrideGenre != null -> listOf(overrideGenre)
            rawCats.isNotBlank() -> rawCats.split('|').map { it.trim() }.filter { it.isNotEmpty() }.take(3)
            else -> listOf("Podcast Index")
        }
        return SearchResult(
            title = title,
            author = author,
            feedUrl = url,
            artworkUrl = artwork,
            genres = genres,
            podcastGuid = podcastGuid,
            appleId = itunesId,
            newestEpisodeAt = newestSec * 1000L,
            dead = false,
            source = "Podcast Index (Local)"
        )
    }

    private fun cleanupSqliteArtifacts(base: File) {
        if (base.exists()) base.delete()
        File("${base.absolutePath}-journal").delete()
        File("${base.absolutePath}-wal").delete()
        File("${base.absolutePath}-shm").delete()
    }
}
