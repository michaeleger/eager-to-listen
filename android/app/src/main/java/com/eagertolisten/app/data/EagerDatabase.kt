package com.eagertolisten.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.eagertolisten.app.data.dao.EpisodeDao
import com.eagertolisten.app.data.dao.InspirationDao
import com.eagertolisten.app.data.dao.PendingRemoteEpisodeDao
import com.eagertolisten.app.data.dao.PodcastDao
import com.eagertolisten.app.data.dao.QueueDao
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.model.EpisodeId
import com.eagertolisten.app.data.model.FeedUrl
import com.eagertolisten.app.data.model.InspirationClip
import com.eagertolisten.app.data.model.PendingRemoteEpisode
import com.eagertolisten.app.data.model.Podcast
import com.eagertolisten.app.data.model.QueueItem
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Podcast::class,
        Episode::class,
        QueueItem::class,
        InspirationClip::class,
        PendingRemoteEpisode::class
    ],
    version = 6,
    exportSchema = true
)
abstract class EagerDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun queueDao(): QueueDao
    abstract fun inspirationDao(): InspirationDao
    abstract fun pendingRemoteEpisodeDao(): PendingRemoteEpisodeDao

    companion object {
        @Volatile
        private var INSTANCE: EagerDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN playOrderStrategy TEXT NOT NULL DEFAULT 'EVERGREEN'")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN newsRetentionDays INTEGER NOT NULL DEFAULT 3")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN consecutivePlayLimit INTEGER NOT NULL DEFAULT -1")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN isLive INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN liveStatus TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN fundingMessage TEXT")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN customDonationUrl TEXT")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN valueJson TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN fundingUrl TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN valueJson TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN localFilePath TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN isDownloaded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS inspiration_clips (
                        id TEXT NOT NULL PRIMARY KEY,
                        episodeId TEXT NOT NULL,
                        podcastId TEXT NOT NULL,
                        podcastTitle TEXT NOT NULL,
                        episodeTitle TEXT NOT NULL,
                        timestampSeconds INTEGER NOT NULL,
                        audioUrl TEXT NOT NULL,
                        note TEXT NOT NULL,
                        quoteText TEXT NOT NULL,
                        tags TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """)
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Podcast IDs are rewritten after the child tables are rebuilt; let SQLite
                // check foreign keys at commit instead of per statement.
                db.execSQL("PRAGMA defer_foreign_keys = ON")

                // 1. Podcasts: add new columns
                db.execSQL("ALTER TABLE podcasts ADD COLUMN unsubscribedAt INTEGER")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN settingsUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN httpEtag TEXT")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN httpLastModified TEXT")

                // v5 stored the raw feed URL as the podcast ID and the bare GUID (or enclosure URL)
                // as the episode ID. FeedParser, subscribe() and sync now use the normalized feed URL
                // and EpisodeId.of(), so remap with the same code instead of approximating it in SQL.
                val podcastGroups = readCanonicalPodcastGroups(db)
                buildIdMapTables(db, podcastGroups)

                // 2. Create episodes_new
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS episodes_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        podcastId TEXT NOT NULL,
                        podcastTitle TEXT NOT NULL,
                        title TEXT NOT NULL,
                        audioUrl TEXT NOT NULL,
                        publishedAt INTEGER NOT NULL,
                        durationSeconds INTEGER NOT NULL DEFAULT 0,
                        playbackPositionSeconds INTEGER NOT NULL DEFAULT 0,
                        isCompleted INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        artworkUrl TEXT,
                        transcriptUrl TEXT,
                        transcriptType TEXT,
                        chaptersUrl TEXT,
                        personsJson TEXT,
                        soundbitesJson TEXT,
                        isLive INTEGER NOT NULL DEFAULT 0,
                        liveStatus TEXT,
                        fundingUrl TEXT,
                        valueJson TEXT,
                        localFilePath TEXT,
                        isDownloaded INTEGER NOT NULL DEFAULT 0,
                        feedOrder INTEGER NOT NULL DEFAULT 0,
                        isDismissed INTEGER NOT NULL DEFAULT 0,
                        dismissReason TEXT,
                        completedAt INTEGER,
                        skippedAt INTEGER,
                        skipCount INTEGER NOT NULL DEFAULT 0,
                        downloadBytes INTEGER,
                        FOREIGN KEY (podcastId) REFERENCES podcasts(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """)

                // Copy episodes under their remapped IDs. When two old rows collapse into one ID,
                // keep the one with the most listening progress.
                // Backfill completedAt: CASE WHEN isCompleted = 1 THEN updatedAt ELSE NULL END
                db.execSQL("""
                    INSERT OR IGNORE INTO episodes_new (
                        id, podcastId, podcastTitle, title, audioUrl, publishedAt, durationSeconds,
                        playbackPositionSeconds, isCompleted, updatedAt, description, artworkUrl,
                        transcriptUrl, transcriptType, chaptersUrl, personsJson, soundbitesJson,
                        isLive, liveStatus, fundingUrl, valueJson, localFilePath, isDownloaded,
                        feedOrder, isDismissed, dismissReason, completedAt, skippedAt, skipCount, downloadBytes
                    )
                    SELECT
                        m.newId,
                        m.newPodcastId, e.podcastTitle, e.title, e.audioUrl, e.publishedAt, e.durationSeconds,
                        e.playbackPositionSeconds, e.isCompleted, e.updatedAt, e.description, e.artworkUrl,
                        e.transcriptUrl, e.transcriptType, e.chaptersUrl, e.personsJson, e.soundbitesJson,
                        e.isLive, e.liveStatus, e.fundingUrl, e.valueJson, e.localFilePath, e.isDownloaded,
                        0, 0, NULL,
                        CASE WHEN e.isCompleted = 1 THEN e.updatedAt ELSE NULL END,
                        NULL, 0, NULL
                    FROM episodes e
                    JOIN episode_id_map m ON m.oldId = e.id
                    ORDER BY e.isCompleted DESC, e.playbackPositionSeconds DESC, e.updatedAt DESC
                """)

                // 3. Create queue_items_new
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS queue_items_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        episodeId TEXT NOT NULL,
                        podcastId TEXT NOT NULL,
                        orderIndex INTEGER NOT NULL,
                        addedAt INTEGER NOT NULL,
                        isManual INTEGER NOT NULL DEFAULT 0,
                        manualIndex INTEGER,
                        FOREIGN KEY (episodeId) REFERENCES episodes(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """)

                db.execSQL("""
                    INSERT INTO queue_items_new (
                        id, episodeId, podcastId, orderIndex, addedAt, isManual, manualIndex
                    )
                    SELECT
                        q.id,
                        m.newId,
                        m.newPodcastId, q.orderIndex, q.addedAt, 0, NULL
                    FROM queue_items q
                    JOIN episode_id_map m ON m.oldId = q.episodeId
                    WHERE q.id IN (
                        SELECT MIN(q2.id) FROM queue_items q2
                        JOIN episode_id_map m2 ON m2.oldId = q2.episodeId
                        GROUP BY m2.newId
                    )
                """)

                // 4. Create inspiration_clips_new
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS inspiration_clips_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        episodeId TEXT NOT NULL,
                        podcastId TEXT NOT NULL,
                        podcastTitle TEXT NOT NULL,
                        episodeTitle TEXT NOT NULL,
                        timestampSeconds INTEGER NOT NULL,
                        audioUrl TEXT NOT NULL,
                        note TEXT NOT NULL,
                        quoteText TEXT NOT NULL,
                        tags TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """)

                db.execSQL("""
                    INSERT INTO inspiration_clips_new (
                        id, episodeId, podcastId, podcastTitle, episodeTitle,
                        timestampSeconds, audioUrl, note, quoteText, tags, createdAt
                    )
                    SELECT
                        c.id,
                        COALESCE(m.newId, c.episodeId),
                        COALESCE(pm.newId, c.podcastId), c.podcastTitle, c.episodeTitle,
                        c.timestampSeconds, c.audioUrl, c.note, c.quoteText, c.tags, c.createdAt
                    FROM inspiration_clips c
                    LEFT JOIN episode_id_map m ON m.oldId = c.episodeId
                    LEFT JOIN podcast_id_map pm ON pm.oldId = c.podcastId
                """)

                // Swap tables
                db.execSQL("DROP TABLE episodes")
                db.execSQL("ALTER TABLE episodes_new RENAME TO episodes")

                db.execSQL("DROP TABLE queue_items")
                db.execSQL("ALTER TABLE queue_items_new RENAME TO queue_items")

                db.execSQL("DROP TABLE inspiration_clips")
                db.execSQL("ALTER TABLE inspiration_clips_new RENAME TO inspiration_clips")

                // Old child tables are gone, so podcast rows can now be merged and renamed
                applyCanonicalPodcastIds(db, podcastGroups)
                db.execSQL("DROP TABLE episode_id_map")
                db.execSQL("DROP TABLE podcast_id_map")

                // Recreate indices
                db.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_podcastId ON episodes(podcastId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_publishedAt ON episodes(publishedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_episodes_podcast_pub ON episodes(podcastId, isCompleted, publishedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_episodes_completed_updated ON episodes(isCompleted, updatedAt)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_queue_items_episodeId ON queue_items(episodeId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_queue_items_orderIndex ON queue_items(orderIndex)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_queue_order ON queue_items(orderIndex, episodeId)")

                // 5. Create pending_remote_episodes table for sync
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_remote_episodes (
                        episodeId TEXT NOT NULL PRIMARY KEY,
                        podcastId TEXT NOT NULL,
                        playbackPositionSeconds INTEGER NOT NULL,
                        isCompleted INTEGER NOT NULL,
                        completedAt INTEGER,
                        isDismissed INTEGER NOT NULL,
                        dismissReason TEXT,
                        skippedAt INTEGER,
                        skipCount INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL
                    )
                """)
            }
        }

        private class LegacyPodcastRow(val id: String, val feedUrl: String, val subscribedAt: Long)

        private class CanonicalPodcastGroup(
            val newId: String,
            val keeper: LegacyPodcastRow,
            val members: List<LegacyPodcastRow>
        )

        private fun canonicalFeedId(raw: String): String = FeedUrl.normalize(raw).ifBlank { raw.trim() }

        private fun readCanonicalPodcastGroups(db: SupportSQLiteDatabase): List<CanonicalPodcastGroup> {
            val rows = mutableListOf<LegacyPodcastRow>()
            db.query("SELECT id, feedUrl, subscribedAt FROM podcasts").use { c ->
                while (c.moveToNext()) {
                    rows.add(LegacyPodcastRow(c.getString(0), c.getString(1), c.getLong(2)))
                }
            }
            return rows.groupBy { canonicalFeedId(it.id) }.map { (newId, members) ->
                // Same feed subscribed under two spellings: keep the row already in canonical form,
                // otherwise the oldest subscription, and fold the others' episodes into it.
                val keeper = members.firstOrNull { it.id == newId } ?: members.minBy { it.subscribedAt }
                CanonicalPodcastGroup(newId, keeper, members)
            }
        }

        private fun buildIdMapTables(db: SupportSQLiteDatabase, groups: List<CanonicalPodcastGroup>) {
            db.execSQL("CREATE TEMP TABLE podcast_id_map (oldId TEXT NOT NULL PRIMARY KEY, newId TEXT NOT NULL)")
            db.execSQL("CREATE TEMP TABLE episode_id_map (oldId TEXT NOT NULL PRIMARY KEY, newId TEXT NOT NULL, newPodcastId TEXT NOT NULL)")

            val podcastIdMap = mutableMapOf<String, String>()
            db.compileStatement("INSERT INTO podcast_id_map (oldId, newId) VALUES (?, ?)").use { stmt ->
                for (group in groups) {
                    for (member in group.members) {
                        podcastIdMap[member.id] = group.newId
                        stmt.bindString(1, member.id)
                        stmt.bindString(2, group.newId)
                        stmt.executeInsert()
                        stmt.clearBindings()
                    }
                }
            }

            db.compileStatement("INSERT INTO episode_id_map (oldId, newId, newPodcastId) VALUES (?, ?, ?)").use { stmt ->
                db.query("SELECT id, podcastId, audioUrl FROM episodes").use { c ->
                    while (c.moveToNext()) {
                        val oldId = c.getString(0)
                        val oldPodcastId = c.getString(1)
                        val newPodcastId = podcastIdMap[oldPodcastId] ?: continue
                        // Tolerate rows that already carry a feed prefix
                        val identifier = when {
                            oldId.startsWith("$oldPodcastId::") -> oldId.removePrefix("$oldPodcastId::")
                            oldId.startsWith("$newPodcastId::") -> oldId.removePrefix("$newPodcastId::")
                            else -> oldId
                        }
                        stmt.bindString(1, oldId)
                        stmt.bindString(2, EpisodeId.of(podcastId = newPodcastId, guid = identifier, enclosureUrl = c.getString(2)))
                        stmt.bindString(3, newPodcastId)
                        stmt.executeInsert()
                        stmt.clearBindings()
                    }
                }
            }
        }

        private fun applyCanonicalPodcastIds(db: SupportSQLiteDatabase, groups: List<CanonicalPodcastGroup>) {
            for (group in groups) {
                for (member in group.members) {
                    if (member !== group.keeper) {
                        db.execSQL("DELETE FROM podcasts WHERE id = ?", arrayOf(member.id))
                    }
                }
                db.execSQL(
                    "UPDATE podcasts SET id = ?, feedUrl = ? WHERE id = ?",
                    arrayOf(group.newId, canonicalFeedId(group.keeper.feedUrl), group.keeper.id)
                )
            }
        }

        fun getInstance(context: Context): EagerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EagerDatabase::class.java,
                    "eager_to_listen.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
