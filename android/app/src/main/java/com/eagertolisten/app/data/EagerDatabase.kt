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
    version = 5,
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

        fun getInstance(context: Context): EagerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EagerDatabase::class.java,
                    "eager_to_listen.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("CREATE INDEX IF NOT EXISTS idx_episodes_podcast_pub ON episodes(podcastId, isCompleted, publishedAt)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS idx_episodes_completed_updated ON episodes(isCompleted, updatedAt)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS idx_queue_order ON queue_items(orderIndex, episodeId)")
                    }
                })
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
