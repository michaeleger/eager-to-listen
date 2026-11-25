package com.eagertolisten.app.data.podcastindex

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.eagertolisten.app.ui.viewmodel.SearchResult
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.zip.GZIPInputStream

/**
 * Local partial copy of the PodcastIndex.org directory (5,000 to 100,000 top active podcasts)
 * bundled into the APK at build time (`assets/podcastindex-catalog.tsv.gz`) and indexed into a
 * compact read-only SQLite + FTS4 database inside app storage.
 *
 * End-user devices NEVER download the Podcast Index catalog over the network. The build-time
 * pipeline refreshes the bundled snapshot whenever it is older than 21 days.
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
        val generatedAtMs: Long
    )

    companion object {
        private const val TAG = "LocalPodcastCatalog"
        // Android's aapt2 automatically decompresses .gz files in assets/ and strips the ".gz" suffix,
        // so check the stripped name first and fall back to the .gz name.
        val BUNDLED_ASSET_CANDIDATES = listOf("podcastindex-catalog.tsv", "podcastindex-catalog.tsv.gz")

        /** Hard limits protecting normal phone storage and RAM against oversized/corrupt snapshots. */
        const val MAX_IMPORT_ROWS = 100_000
        const val MAX_UNCOMPRESSED_BYTES = 64L * 1024L * 1024L

        const val PREF_KEY_GENERATED_AT = "pi_catalog_generated_at"
        const val PREF_KEY_COUNT = "pi_catalog_count"

        private val MARKS = Regex("\\p{M}+")
        private val NON_ALNUM = Regex("[^a-z0-9]+")
        private val dbLock = Any()

        /** True when the on-device SQLite index is missing or older than the APK's bundled snapshot. */
        fun needsUnpack(
            hasDb: Boolean,
            installedGeneratedAtMs: Long,
            bundledGeneratedAtMs: Long
        ): Boolean {
            if (!hasDb || installedGeneratedAtMs <= 0L) return true
            if (bundledGeneratedAtMs > 0L && bundledGeneratedAtMs != installedGeneratedAtMs) return true
            return false
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

    private fun openBundledAsset(): InputStream? {
        for (name in BUNDLED_ASSET_CANDIDATES) {
            try {
                return context.assets.open(name)
            } catch (_: Exception) {
                // try next candidate
            }
        }
        return null
    }

    fun hasBundledAsset(): Boolean = openBundledAsset()?.use { true } ?: false

    fun hasCatalog(): Boolean {
        val f = dbFile()
        if (f.exists() && f.length() > 0L && prefs.getInt(PREF_KEY_COUNT, 0) > 0) return true
        return hasBundledAsset()
    }

    fun getStatus(): CatalogStatus {
        val f = dbFile()
        val unpacked = f.exists() && f.length() > 0L && prefs.getInt(PREF_KEY_COUNT, 0) > 0
        if (unpacked) {
            return CatalogStatus(
                available = true,
                count = prefs.getInt(PREF_KEY_COUNT, 0),
                generatedAtMs = prefs.getLong(PREF_KEY_GENERATED_AT, 0L)
            )
        }
        val bundled = readBundledHeader()
        return if (bundled != null && bundled.count > 0) {
            CatalogStatus(available = true, count = bundled.count, generatedAtMs = bundled.generatedAt)
        } else {
            CatalogStatus(available = false, count = 0, generatedAtMs = 0L)
        }
    }

    private fun readBundledHeader(): CatalogHeader? {
        return try {
            openBundledAsset()?.use { raw ->
                val buffered = BufferedInputStream(raw, 8 * 1024)
                buffered.mark(2)
                val b0 = buffered.read()
                val b1 = buffered.read()
                buffered.reset()
                val decoded: InputStream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered, 8 * 1024) else buffered
                BufferedReader(InputStreamReader(decoded, Charsets.UTF_8)).use { reader ->
                    parseHeader(reader.readLine().orEmpty())
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Unpacks and indexes the build-time bundled APK asset (`assets/podcastindex-catalog.tsv` / `.tsv.gz`)
     * into local SQLite + FTS4 storage if missing or updated by a newer app build. Zero network I/O.
     */
    fun ensureInstalled(): Boolean {
        synchronized(dbLock) {
            val f = dbFile()
            val hasDb = f.exists() && f.length() > 0L && prefs.getInt(PREF_KEY_COUNT, 0) > 0
            val installedGeneratedAt = prefs.getLong(PREF_KEY_GENERATED_AT, 0L)
            val bundledHeader = readBundledHeader()
            if (bundledHeader == null && !hasBundledAsset()) {
                return hasDb
            }
            if (!needsUnpack(hasDb, installedGeneratedAt, bundledHeader?.generatedAt ?: 0L)) {
                return true
            }
            return try {
                openBundledAsset()?.use { stream ->
                    val count = importFromStream(stream)
                    Log.i(TAG, "Indexed bundled Podcast Index catalog: $count shows")
                    count > 0
                } ?: hasDb
            } catch (_: FileNotFoundException) {
                hasDb
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unpack bundled Podcast Index catalog: ${e.message}", e)
                hasDb
            }
        }
    }

    /**
     * Streams a plain or gzipped TSV snapshot into a temporary SQLite + FTS4 database and
     * atomically swaps it into place. Safe for up to [MAX_IMPORT_ROWS] (100,000) shows on mobile devices.
     */
    fun importFromStream(
        rawStream: InputStream,
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
                    .putLong(PREF_KEY_GENERATED_AT, header?.generatedAt ?: nowMs)
                    .putInt(PREF_KEY_COUNT, count)
                    .apply()
            }
            return count
        } catch (e: Exception) {
            cleanupSqliteArtifacts(tmpFile)
            throw e
        }
    }

    /** Full-text search against the bundled local partial Podcast Index SQLite DB. */
    fun search(query: String, limit: Int = 40): List<SearchResult> {
        val ftsQuery = buildFtsQuery(query) ?: return emptyList()
        ensureInstalled()
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

    /** Browse a category from the bundled local partial Podcast Index SQLite DB. */
    fun browseCategory(category: String, limit: Int = 40): List<SearchResult> {
        val queries = categoryFtsQueries(category)
        if (queries.isEmpty()) return emptyList()
        ensureInstalled()
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
