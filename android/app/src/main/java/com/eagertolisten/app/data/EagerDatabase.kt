package com.eagertolisten.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.eagertolisten.app.data.dao.EpisodeDao
import com.eagertolisten.app.data.dao.PodcastDao
import com.eagertolisten.app.data.dao.QueueDao
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.model.Podcast
import com.eagertolisten.app.data.model.QueueItem

import androidx.room.migration.Migration
import com.eagertolisten.app.data.dao.InspirationDao
import com.eagertolisten.app.data.model.InspirationClip
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Podcast::class,
        Episode::class,
        QueueItem::class,
        InspirationClip::class
    ],
    version = 6,
    exportSchema = true
)
abstract class EagerDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun queueDao(): QueueDao
    abstract fun inspirationDao(): InspirationDao

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
                // 1. Podcasts: add new columns
                db.execSQL("ALTER TABLE podcasts ADD COLUMN unsubscribedAt INTEGER")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN settingsUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN httpEtag TEXT")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN httpLastModified TEXT")

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

                // Copy and remap episode IDs to: CASE WHEN id LIKE '%::%' THEN id ELSE podcastId || '::' || id END
                // Backfill completedAt: CASE WHEN isCompleted = 1 THEN updatedAt ELSE NULL END
                db.execSQL("""
                    INSERT OR REPLACE INTO episodes_new (
                        id, podcastId, podcastTitle, title, audioUrl, publishedAt, durationSeconds,
                        playbackPositionSeconds, isCompleted, updatedAt, description, artworkUrl,
                        transcriptUrl, transcriptType, chaptersUrl, personsJson, soundbitesJson,
                        isLive, liveStatus, fundingUrl, valueJson, localFilePath, isDownloaded,
                        feedOrder, isDismissed, dismissReason, completedAt, skippedAt, skipCount, downloadBytes
                    )
                    SELECT 
                        CASE WHEN id LIKE '%::%' THEN id ELSE podcastId || '::' || id END,
                        podcastId, podcastTitle, title, audioUrl, publishedAt, durationSeconds,
                        playbackPositionSeconds, isCompleted, updatedAt, description, artworkUrl,
                        transcriptUrl, transcriptType, chaptersUrl, personsJson, soundbitesJson,
                        isLive, liveStatus, fundingUrl, valueJson, localFilePath, isDownloaded,
                        0, 0, NULL,
                        CASE WHEN isCompleted = 1 THEN updatedAt ELSE NULL END,
                        NULL, 0, NULL
                    FROM episodes
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
                        id,
                        CASE WHEN episodeId LIKE '%::%' THEN episodeId ELSE podcastId || '::' || episodeId END,
                        podcastId, orderIndex, addedAt, 0, NULL
                    FROM queue_items
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
                        id,
                        CASE WHEN episodeId LIKE '%::%' THEN episodeId ELSE podcastId || '::' || episodeId END,
                        podcastId, podcastTitle, episodeTitle,
                        timestampSeconds, audioUrl, note, quoteText, tags, createdAt
                    FROM inspiration_clips
                """)

                // Swap tables
                db.execSQL("DROP TABLE episodes")
                db.execSQL("ALTER TABLE episodes_new RENAME TO episodes")

                db.execSQL("DROP TABLE queue_items")
                db.execSQL("ALTER TABLE queue_items_new RENAME TO queue_items")

                db.execSQL("DROP TABLE inspiration_clips")
                db.execSQL("ALTER TABLE inspiration_clips_new RENAME TO inspiration_clips")

                // Recreate indices
                db.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_podcastId ON episodes(podcastId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_publishedAt ON episodes(publishedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_episodes_podcast_pub ON episodes(podcastId, isCompleted, publishedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_episodes_completed_updated ON episodes(isCompleted, updatedAt)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_queue_items_episodeId ON queue_items(episodeId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_queue_items_orderIndex ON queue_items(orderIndex)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_queue_order ON queue_items(orderIndex, episodeId)")
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
