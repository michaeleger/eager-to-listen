package com.eagertolisten.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eagertolisten.app.data.model.EpisodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
                    durationSeconds, publishedAt, isCompleted, playbackPositionSeconds, updatedAt,
                    description, isLive, isDownloaded
                ) VALUES (
                    'raw-guid-1', 'feed1', 'Test Show', 'Ep 1', 'https://example.com/1.mp3',
                    300, 1000, 1, 300, 1500,
                    'Episode Description', 0, 0
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

    private fun insertV5Podcast(db: SupportSQLiteDatabase, id: String, subscribedAt: Long, priority: Int) {
        db.execSQL(
            """
            INSERT INTO podcasts (
                id, title, feedUrl, artworkUrl, author, description,
                subscribedAt, priority, skipIntroSeconds, skipOutroSeconds,
                playbackSpeed, autoAddToQueue, maxEpisodesInQueue, category,
                playOrderStrategy, newsRetentionDays, consecutivePlayLimit
            ) VALUES (?, 'Show', ?, '', '', '', ?, ?, 0, 0, 1.0, 1, 5, 'General', 'EVERGREEN', 3, -1)
            """,
            arrayOf(id, id, subscribedAt, priority)
        )
    }

    private fun insertV5Episode(db: SupportSQLiteDatabase, id: String, podcastId: String, isCompleted: Boolean) {
        db.execSQL(
            """
            INSERT INTO episodes (
                id, podcastId, podcastTitle, title, audioUrl,
                durationSeconds, publishedAt, isCompleted, playbackPositionSeconds, updatedAt,
                description, isLive, isDownloaded
            ) VALUES (?, ?, 'Show', 'Ep', 'https://example.com/ep.mp3', 300, 1000, ?, ?, 1500, '', 0, 0)
            """,
            arrayOf(id, podcastId, if (isCompleted) 1 else 0, if (isCompleted) 300 else 0)
        )
    }

    @Test
    fun migrate5To6_remapsLegacyFeedUrlIdsToParserFormat() {
        val legacyId = "https://Example.com/feed/podcast/"
        val canonicalId = "https://example.com/feed/podcast"
        val rawGuid = "  abc\n"

        var db = helper.createDatabase(TEST_DB, 5).apply {
            insertV5Podcast(this, legacyId, subscribedAt = 1000, priority = 1)
            insertV5Episode(this, rawGuid, legacyId, isCompleted = true)
            execSQL("INSERT INTO queue_items (id, episodeId, podcastId, orderIndex, addedAt) VALUES (1, ?, ?, 0, 1200)", arrayOf(rawGuid, legacyId))
            execSQL(
                """
                INSERT INTO inspiration_clips (
                    id, episodeId, podcastId, podcastTitle, episodeTitle,
                    timestampSeconds, audioUrl, note, quoteText, tags, createdAt
                ) VALUES ('clip-1', ?, ?, 'Show', 'Ep', 45, 'https://example.com/ep.mp3', 'n', 'q', '', 1400)
                """,
                arrayOf(rawGuid, legacyId)
            )
            close()
        }

        db = helper.runMigrationsAndValidate(TEST_DB, 6, true, EagerDatabase.MIGRATION_5_6)

        // The ID FeedParser will produce on the next refresh of this feed
        val expectedEpisodeId = EpisodeId.of(canonicalId, rawGuid, "https://example.com/ep.mp3")
        assertEquals("https://example.com/feed/podcast::abc", expectedEpisodeId)

        db.query("SELECT id, feedUrl, priority FROM podcasts").use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(canonicalId, c.getString(0))
            assertEquals(canonicalId, c.getString(1))
            assertEquals(1, c.getInt(2))
        }
        db.query("SELECT id, podcastId, isCompleted FROM episodes").use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(expectedEpisodeId, c.getString(0))
            assertEquals(canonicalId, c.getString(1))
            assertEquals(1, c.getInt(2))
        }
        db.query("SELECT episodeId, podcastId FROM queue_items").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(expectedEpisodeId, c.getString(0))
            assertEquals(canonicalId, c.getString(1))
        }
        db.query("SELECT episodeId, podcastId FROM inspiration_clips").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(expectedEpisodeId, c.getString(0))
            assertEquals(canonicalId, c.getString(1))
        }
    }

    @Test
    fun migrate5To6_mergesSameFeedSubscribedUnderTwoSpellings() {
        val slashId = "https://example.com/rss/"
        val canonicalId = "https://example.com/rss"

        var db = helper.createDatabase(TEST_DB, 5).apply {
            insertV5Podcast(this, slashId, subscribedAt = 1000, priority = 2)
            insertV5Podcast(this, canonicalId, subscribedAt = 2000, priority = 4)
            insertV5Episode(this, "g1", slashId, isCompleted = true)
            insertV5Episode(this, "g2", canonicalId, isCompleted = false)
            // Same GUID seen with and without whitespace: collapses to one row, played state wins
            insertV5Episode(this, " g3 ", slashId, isCompleted = true)
            insertV5Episode(this, "g3", canonicalId, isCompleted = false)
            execSQL("INSERT INTO queue_items (id, episodeId, podcastId, orderIndex, addedAt) VALUES (1, ' g3 ', ?, 0, 1200)", arrayOf(slashId))
            execSQL("INSERT INTO queue_items (id, episodeId, podcastId, orderIndex, addedAt) VALUES (2, 'g3', ?, 1, 1200)", arrayOf(canonicalId))
            close()
        }

        db = helper.runMigrationsAndValidate(TEST_DB, 6, true, EagerDatabase.MIGRATION_5_6)

        db.query("SELECT id, priority FROM podcasts").use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(canonicalId, c.getString(0))
            assertEquals("Row already in canonical form keeps its rules", 4, c.getInt(1))
        }
        db.query("SELECT id, isCompleted FROM episodes WHERE podcastId = ? ORDER BY id", arrayOf(canonicalId)).use { c ->
            assertEquals(3, c.count)
            c.moveToNext(); assertEquals("$canonicalId::g1", c.getString(0)); assertEquals(1, c.getInt(1))
            c.moveToNext(); assertEquals("$canonicalId::g2", c.getString(0)); assertEquals(0, c.getInt(1))
            c.moveToNext(); assertEquals("$canonicalId::g3", c.getString(0)); assertEquals(1, c.getInt(1))
        }
        db.query("SELECT episodeId FROM queue_items").use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("$canonicalId::g3", c.getString(0))
        }
    }
}
