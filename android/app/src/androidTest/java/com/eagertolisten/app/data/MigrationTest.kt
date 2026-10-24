package com.eagertolisten.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EagerDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate5To6() {
        // Create DB at version 5 with seeded rows
        var db = helper.createDatabase(TEST_DB, 5).apply {
            execSQL("""
                INSERT INTO podcasts (
                    id, title, feedUrl, artworkUrl, author, description,
                    subscribedAt, priority, skipIntroSeconds, skipOutroSeconds,
                    playbackSpeed, autoAddToQueue, maxEpisodesInQueue, category,
                    playOrderStrategy, newsRetentionDays, consecutivePlayLimit
                ) VALUES (
                    'feed1', 'Test Show', 'https://example.com/rss', 'https://example.com/art.jpg', 'Author', 'Desc',
                    1000, 1, 15, 30,
                    1.25, 1, 5, 'Health',
                    'EVERGREEN', 3, 2
                )
            """)
            execSQL("""
                INSERT INTO episodes (
                    id, podcastId, podcastTitle, title, audioUrl,
                    durationSeconds, publishedAt, isCompleted, playbackPositionSeconds, updatedAt
                ) VALUES (
                    'raw-guid-1', 'feed1', 'Test Show', 'Ep 1', 'https://example.com/1.mp3',
                    300, 1000, 1, 300, 1500
                )
            """)
            execSQL("""
                INSERT INTO queue_items (
                    id, episodeId, podcastId, orderIndex, addedAt
                ) VALUES (
                    1, 'raw-guid-1', 'feed1', 0, 1200
                )
            """)
            execSQL("""
                INSERT INTO inspiration_clips (
                    id, episodeId, podcastId, podcastTitle, episodeTitle,
                    timestampSeconds, audioUrl, note, quoteText, tags, createdAt
                ) VALUES (
                    'clip-1', 'raw-guid-1', 'feed1', 'Test Show', 'Ep 1',
                    45, 'https://example.com/1.mp3', 'Great note', 'Quote', 'Health', 1400
                )
            """)
            close()
        }

        // Run migration 5 to 6 and validate schema
        db = helper.runMigrationsAndValidate(TEST_DB, 6, true, EagerDatabase.MIGRATION_5_6)

        // Verify podcast survived with all rules intact (I3)
        val pCursor = db.query("SELECT id, title, priority, playbackSpeed, unsubscribedAt FROM podcasts WHERE id = 'feed1'")
        assert(pCursor.moveToFirst())
        assertEquals("Test Show", pCursor.getString(1))
        assertEquals(1, pCursor.getInt(2))
        assertEquals(1.25f, pCursor.getFloat(3), 0.001f)
        pCursor.close()

        // Verify episode ID remapped to feed1::raw-guid-1 and completedAt backfilled from updatedAt
        val epCursor = db.query("SELECT id, isCompleted, completedAt, feedOrder, isDismissed FROM episodes WHERE podcastId = 'feed1'")
        assert(epCursor.moveToFirst())
        assertEquals("feed1::raw-guid-1", epCursor.getString(0))
        assertEquals(1, epCursor.getInt(1))
        assertEquals(1500L, epCursor.getLong(2))
        assertEquals(0, epCursor.getInt(3))
        assertEquals(0, epCursor.getInt(4))
        epCursor.close()

        // Verify queue item remapped to feed1::raw-guid-1
        val qCursor = db.query("SELECT episodeId, isManual, orderIndex FROM queue_items WHERE podcastId = 'feed1'")
        assert(qCursor.moveToFirst())
        assertEquals("feed1::raw-guid-1", qCursor.getString(0))
        assertEquals(0, qCursor.getInt(1))
        assertEquals(0, qCursor.getInt(2))
        qCursor.close()

        // Verify inspiration clip remapped to feed1::raw-guid-1
        val cCursor = db.query("SELECT episodeId, note FROM inspiration_clips WHERE id = 'clip-1'")
        assert(cCursor.moveToFirst())
        assertEquals("feed1::raw-guid-1", cCursor.getString(0))
        assertEquals("Great note", cCursor.getString(1))
        cCursor.close()
    }
}
