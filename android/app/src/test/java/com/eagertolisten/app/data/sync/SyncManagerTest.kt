package com.eagertolisten.app.data.sync

import com.eagertolisten.app.data.dao.EpisodeSyncProjection
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.model.InspirationClip
import com.eagertolisten.app.data.model.PendingRemoteEpisode
import com.eagertolisten.app.data.model.Podcast
import com.eagertolisten.app.data.model.QueueItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InMemorySyncDatabaseOps : SyncDatabaseOps {
    val podcasts = mutableMapOf<String, Podcast>()
    val episodes = mutableMapOf<String, Episode>()
    val queueItems = mutableListOf<QueueItem>()
    val inspirations = mutableMapOf<String, InspirationClip>()
    val pendingEpisodes = mutableMapOf<String, PendingRemoteEpisode>()

    override suspend fun getAllPodcastsIncludingUnsubscribed(): List<Podcast> =
        podcasts.values.toList()

    override suspend fun getEpisodesForSync(): List<EpisodeSyncProjection> =
        episodes.values
            .filter { it.playbackPositionSeconds > 0 || it.isCompleted || it.isDismissed || it.skipCount > 0 || it.dismissReason == "UNPLAYED" }
            .map {
                EpisodeSyncProjection(
                    id = it.id,
                    podcastId = it.podcastId,
                    playbackPositionSeconds = it.playbackPositionSeconds,
                    isCompleted = it.isCompleted,
                    completedAt = it.completedAt,
                    isDismissed = it.isDismissed,
                    dismissReason = it.dismissReason,
                    skippedAt = it.skippedAt,
                    skipCount = it.skipCount,
                    updatedAt = it.updatedAt
                )
            }

    override suspend fun getPinnedItems(): List<QueueItem> =
        queueItems.filter { it.isManual }

    override suspend fun getAllInspirations(): List<InspirationClip> =
        inspirations.values.toList()

    override suspend fun getPodcastById(id: String): Podcast? =
        podcasts[id]

    override suspend fun getPodcastByFeedUrl(url: String): Podcast? =
        podcasts.values.find { it.feedUrl == url }

    override suspend fun updateFeedRules(
        podcastId: String,
        priority: Int,
        skipIntro: Int,
        skipOutro: Int,
        speed: Float,
        autoAdd: Boolean,
        maxEpisodes: Int,
        category: String,
        customDonationUrl: String?,
        playOrderStrategy: String,
        newsRetentionDays: Int,
        consecutivePlayLimit: Int,
        settingsUpdatedAt: Long
    ): Int {
        val p = podcasts[podcastId] ?: return 0
        podcasts[podcastId] = p.copy(
            priority = priority,
            skipIntroSeconds = skipIntro,
            skipOutroSeconds = skipOutro,
            playbackSpeed = speed,
            autoAddToQueue = autoAdd,
            maxEpisodesInQueue = maxEpisodes,
            category = category,
            customDonationUrl = customDonationUrl,
            playOrderStrategy = playOrderStrategy,
            newsRetentionDays = newsRetentionDays,
            consecutivePlayLimit = consecutivePlayLimit,
            settingsUpdatedAt = settingsUpdatedAt
        )
        return 1
    }

    override suspend fun softUnsubscribe(id: String, timestamp: Long): Int {
        val p = podcasts[id] ?: return 0
        podcasts[id] = p.copy(unsubscribedAt = timestamp)
        return 1
    }

    override suspend fun clearUnsubscribed(id: String): Int {
        val p = podcasts[id] ?: return 0
        podcasts[id] = p.copy(unsubscribedAt = null)
        return 1
    }

    override suspend fun insertPodcast(podcast: Podcast) {
        podcasts[podcast.id] = podcast
    }

    override suspend fun getEpisodeById(id: String): Episode? =
        episodes[id]

    override suspend fun updateEpisodeSyncState(
        episodeId: String,
        pos: Long,
        completed: Boolean,
        completedAt: Long?,
        dismissed: Boolean,
        dismissReason: String?,
        skippedAt: Long?,
        skipCount: Int,
        updatedAt: Long
    ): Int {
        val ep = episodes[episodeId] ?: return 0
        episodes[episodeId] = ep.copy(
            playbackPositionSeconds = pos,
            isCompleted = completed,
            completedAt = completedAt,
            isDismissed = dismissed,
            dismissReason = dismissReason,
            skippedAt = skippedAt,
            skipCount = skipCount,
            updatedAt = updatedAt
        )
        return 1
    }

    override suspend fun insertOrUpdatePendingEpisodes(eps: List<PendingRemoteEpisode>) {
        eps.forEach { pendingEpisodes[it.episodeId] = it }
    }

    override suspend fun getAllQueueItems(): List<QueueItem> =
        queueItems.toList()

    override suspend fun insertQueueItem(item: QueueItem) {
        queueItems.removeAll { it.episodeId == item.episodeId }
        queueItems.add(item)
    }

    override suspend fun removeEpisodesForPodcast(podcastId: String): Int {
        val before = queueItems.size
        queueItems.removeAll { it.podcastId == podcastId }
        return before - queueItems.size
    }

    override suspend fun upsertInspirations(clips: List<InspirationClip>) {
        clips.forEach { inspirations[it.id] = it }
    }

    override suspend fun getQueueCount(): Int =
        queueItems.size
}

class SyncManagerTest {

    private lateinit var dbA: InMemorySyncDatabaseOps
    private lateinit var dbB: InMemorySyncDatabaseOps

    @Before
    fun setup() {
        dbA = InMemorySyncDatabaseOps()
        dbB = InMemorySyncDatabaseOps()
    }

    private fun createSamplePodcast(id: String, title: String, unsubscribedAt: Long? = null, settingsUpdatedAt: Long = 1000L): Podcast {
        return Podcast(
            id = id,
            title = title,
            feedUrl = "https://example.com/$id/rss",
            artworkUrl = "https://example.com/$id/art.jpg",
            author = "Author $id",
            description = "Desc $id",
            priority = 3,
            skipIntroSeconds = 0,
            skipOutroSeconds = 0,
            playbackSpeed = 1.0f,
            autoAddToQueue = true,
            maxEpisodesInQueue = 5,
            category = "Health",
            playOrderStrategy = "EVERGREEN",
            newsRetentionDays = 3,
            consecutivePlayLimit = -1,
            subscribedAt = 1000L,
            unsubscribedAt = unsubscribedAt,
            settingsUpdatedAt = settingsUpdatedAt
        )
    }

    private fun createSampleEpisode(
        id: String,
        podcastId: String,
        isCompleted: Boolean = false,
        posSec: Long = 0L,
        updatedAt: Long = 1000L,
        completedAt: Long? = if (isCompleted) updatedAt else null
    ): Episode {
        return Episode(
            id = id,
            podcastId = podcastId,
            podcastTitle = "Podcast $podcastId",
            title = "Episode $id",
            audioUrl = "https://example.com/$id.mp3",
            publishedAt = 1000L,
            durationSeconds = 600L,
            playbackPositionSeconds = posSec,
            isCompleted = isCompleted,
            updatedAt = updatedAt,
            completedAt = completedAt
        )
    }

    @Test
    fun testTwoDeviceMerge_completedEpisodeAndUnsubscribeSurvive() = runBlocking {
        // Seed Device A & B with feed F and episode X
        val feedA = createSamplePodcast("feedF", "Feed F")
        val epA = createSampleEpisode("feedF::epX", "feedF", isCompleted = false, posSec = 0L)
        dbA.insertPodcast(feedA)
        dbA.episodes[epA.id] = epA

        val feedB = createSamplePodcast("feedF", "Feed F")
        val epB = createSampleEpisode("feedF::epX", "feedF", isCompleted = false, posSec = 0L)
        dbB.insertPodcast(feedB)
        dbB.episodes[epB.id] = epB

        // Device A completes episode X and unsubscribes feed F at t=2000
        val t2000 = 2000L
        dbA.updateEpisodeSyncState(
            episodeId = "feedF::epX",
            pos = 600L,
            completed = true,
            completedAt = t2000,
            dismissed = false,
            dismissReason = null,
            skippedAt = null,
            skipCount = 0,
            updatedAt = t2000
        )
        dbA.softUnsubscribe("feedF", t2000)

        // Device A exports to JSON (real JSON)
        val exportedJson = SyncSerializer.serializeV2(dbA, "deviceA", t2000)

        // Device B merges Device A's sync state
        val stateFromA = SyncSerializer.parseJson(exportedJson)
        SyncSerializer.mergeState(dbB, stateFromA)

        // Verify on Device B: epX is completed and feedF is soft-unsubscribed
        val epOnB = dbB.getEpisodeById("feedF::epX")
        assertNotNull(epOnB)
        assertTrue("Episode X should be completed on Device B", epOnB!!.isCompleted)
        assertEquals(t2000, epOnB.completedAt)

        val feedOnB = dbB.getPodcastById("feedF")
        assertNotNull(feedOnB)
        assertEquals("Feed F should have unsubscribedAt set on Device B", t2000, feedOnB!!.unsubscribedAt)

        // Device B exports and Device A syncs back: Feed F must NOT be resurrected!
        val exportFromB = SyncSerializer.serializeV2(dbB, "deviceB", 3000L)
        val stateFromB = SyncSerializer.parseJson(exportFromB)
        SyncSerializer.mergeState(dbA, stateFromB)

        val feedOnA = dbA.getPodcastById("feedF")
        assertNotNull(feedOnA)
        assertNotNull("Feed F must not be resurrected on Device A", feedOnA!!.unsubscribedAt)
    }

    @Test
    fun testFeedRulesMerge_lastWriterWins() = runBlocking {
        val feedA = createSamplePodcast("feed1", "Feed 1", settingsUpdatedAt = 5000L)
            .copy(priority = 1, playbackSpeed = 1.75f, skipIntroSeconds = 30)
        dbA.insertPodcast(feedA)

        val feedB = createSamplePodcast("feed1", "Feed 1", settingsUpdatedAt = 2000L)
            .copy(priority = 4, playbackSpeed = 1.0f, skipIntroSeconds = 0)
        dbB.insertPodcast(feedB)

        // Device A exports, Device B merges
        val exportA = SyncSerializer.serializeV2(dbA, "deviceA", 5000L)
        val stateA = SyncSerializer.parseJson(exportA)
        SyncSerializer.mergeState(dbB, stateA)

        val updatedB = dbB.getPodcastById("feed1")
        assertNotNull(updatedB)
        assertEquals(1, updatedB!!.priority)
        assertEquals(1.75f, updatedB.playbackSpeed, 0.01f)
        assertEquals(30, updatedB.skipIntroSeconds)
        assertEquals(5000L, updatedB.settingsUpdatedAt)
    }

    @Test
    fun testExplicitUnplayedRevertsCompleted() = runBlocking {
        // Device A completed episode at t=1000
        val feedA = createSamplePodcast("feed1", "Feed 1")
        dbA.insertPodcast(feedA)
        val epA = createSampleEpisode("feed1::ep1", "feed1", isCompleted = true, completedAt = 1000L, updatedAt = 1000L)
        dbA.episodes[epA.id] = epA

        // Device B has explicit unplayed at t=3000
        val feedB = createSamplePodcast("feed1", "Feed 1")
        dbB.insertPodcast(feedB)
        val epB = createSampleEpisode("feed1::ep1", "feed1", isCompleted = false, posSec = 0L, updatedAt = 3000L)
            .copy(dismissReason = "UNPLAYED")
        dbB.episodes[epB.id] = epB

        val exportB = SyncSerializer.serializeV2(dbB, "deviceB", 3000L)
        val stateB = SyncSerializer.parseJson(exportB)

        // Device A merges state from Device B
        SyncSerializer.mergeState(dbA, stateB)

        val updatedA = dbA.getEpisodeById("feed1::ep1")
        assertNotNull(updatedA)
        assertFalse("Explicit unplayed at t=3000 should revert completion from t=1000", updatedA!!.isCompleted)
        assertNull(updatedA.completedAt)
        assertEquals(0L, updatedA.playbackPositionSeconds)
    }

    @Test
    fun testPendingRemoteEpisodesAppliedOnRefresh() = runBlocking {
        // Device A has feed2 and ep2
        val feedA = createSamplePodcast("feed2", "Feed 2")
        val epA = createSampleEpisode("feed2::ep2", "feed2", isCompleted = true, completedAt = 4000L, updatedAt = 4000L)
        dbA.insertPodcast(feedA)
        dbA.episodes[epA.id] = epA

        // Device B has neither feed2 nor ep2
        val exportA = SyncSerializer.serializeV2(dbA, "deviceA", 4000L)
        val stateA = SyncSerializer.parseJson(exportA)

        SyncSerializer.mergeState(dbB, stateA)

        // On Device B, feed2 should now be subscribed
        val feedOnB = dbB.getPodcastById("feed2")
        assertNotNull("Feed 2 should be subscribed on Device B", feedOnB)

        // ep2 is not yet in episodes on Device B, but should be in pending_remote_episodes
        val epOnBBefore = dbB.getEpisodeById("feed2::ep2")
        assertNull("ep2 not yet inserted in episodes table", epOnBBefore)

        val pending = dbB.pendingEpisodes["feed2::ep2"]
        assertNotNull(pending)
        assertEquals("feed2::ep2", pending!!.episodeId)
        assertTrue(pending.isCompleted)

        // Simulate feed fetch/refresh inserting ep2 into DB
        val newlyFetchedEp = createSampleEpisode("feed2::ep2", "feed2", isCompleted = false, posSec = 0L, updatedAt = 1000L)
        dbB.episodes[newlyFetchedEp.id] = newlyFetchedEp

        // Apply pending state
        dbB.updateEpisodeSyncState(
            episodeId = pending.episodeId,
            pos = pending.playbackPositionSeconds,
            completed = pending.isCompleted,
            completedAt = pending.completedAt,
            dismissed = pending.isDismissed,
            dismissReason = pending.dismissReason,
            skippedAt = pending.skippedAt,
            skipCount = pending.skipCount,
            updatedAt = pending.updatedAt
        )
        dbB.pendingEpisodes.remove("feed2::ep2")

        val epOnBAfter = dbB.getEpisodeById("feed2::ep2")
        assertNotNull(epOnBAfter)
        assertTrue("Pending completed state should now be applied to newly fetched episode", epOnBAfter!!.isCompleted)
        assertEquals(4000L, epOnBAfter.completedAt)
    }

    @Test
    fun testManualQueueItemsMerged() = runBlocking {
        val feedA = createSamplePodcast("feed3", "Feed 3")
        val epA = createSampleEpisode("feed3::ep3", "feed3")
        dbA.insertPodcast(feedA)
        dbA.episodes[epA.id] = epA
        dbA.insertQueueItem(
            QueueItem(
                episodeId = "feed3::ep3",
                podcastId = "feed3",
                orderIndex = 0,
                isManual = true,
                manualIndex = 0,
                addedAt = 1500L
            )
        )

        val feedB = createSamplePodcast("feed3", "Feed 3")
        val epB = createSampleEpisode("feed3::ep3", "feed3")
        dbB.insertPodcast(feedB)
        dbB.episodes[epB.id] = epB

        val exportA = SyncSerializer.serializeV2(dbA, "deviceA", 1500L)
        val stateA = SyncSerializer.parseJson(exportA)
        SyncSerializer.mergeState(dbB, stateA)

        val pinnedOnB = dbB.getPinnedItems()
        assertEquals(1, pinnedOnB.size)
        assertEquals("feed3::ep3", pinnedOnB[0].episodeId)
        assertTrue(pinnedOnB[0].isManual)
    }
}
