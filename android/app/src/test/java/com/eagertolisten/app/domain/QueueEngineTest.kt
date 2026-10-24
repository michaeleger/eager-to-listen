package com.eagertolisten.app.domain

import com.eagertolisten.app.data.model.PlayOrderStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueEngineTest {

    private fun createFeed(
        id: String,
        title: String = id,
        priority: Int = 3,
        strategy: PlayOrderStrategy = PlayOrderStrategy.EVERGREEN,
        newsRetentionDays: Int = 3,
        consecutiveLimit: Int = 1,
        maxInQueue: Int = 50,
        autoAdd: Boolean = true,
        lastTurnAt: Long = 0L,
        subscribedAt: Long = 1000L,
        heardHighWaterMark: Long = 0L
    ) = EngineFeed(
        id = id,
        title = title,
        priority = priority,
        strategy = strategy,
        newsRetentionDays = newsRetentionDays,
        consecutiveLimit = consecutiveLimit,
        maxInQueue = maxInQueue,
        autoAdd = autoAdd,
        lastTurnAt = lastTurnAt,
        subscribedAt = subscribedAt,
        heardHighWaterMark = heardHighWaterMark
    )

    private fun createEpisode(
        id: String,
        feedId: String,
        publishedAt: Long,
        feedOrder: Int = 0,
        positionSec: Long = 0L,
        isCompleted: Boolean = false,
        isDismissed: Boolean = false,
        skippedAt: Long? = null,
        skipCount: Int = 0
    ) = EngineEpisode(
        id = id,
        feedId = feedId,
        publishedAt = publishedAt,
        feedOrder = feedOrder,
        positionSec = positionSec,
        isCompleted = isCompleted,
        isDismissed = isDismissed,
        skippedAt = skippedAt,
        skipCount = skipCount
    )

    @Test
    fun test1_equalTurns_roundRobinWithPriorityOrdering() {
        // 3 feeds x 3 episodes, consecutiveLimit = 1. Feed A has P1, Feed B has P2, Feed C has P3.
        val feedA = createFeed("A", "Feed A", priority = 1, consecutiveLimit = 1)
        val feedB = createFeed("B", "Feed B", priority = 2, consecutiveLimit = 1)
        val feedC = createFeed("C", "Feed C", priority = 3, consecutiveLimit = 1)

        val episodes = listOf(
            createEpisode("A1", "A", 100L), createEpisode("A2", "A", 200L), createEpisode("A3", "A", 300L),
            createEpisode("B1", "B", 100L), createEpisode("B2", "B", 200L), createEpisode("B3", "B", 300L),
            createEpisode("C1", "C", 100L), createEpisode("C2", "C", 200L), createEpisode("C3", "C", 300L)
        )

        val queue = buildQueue(
            feeds = listOf(feedC, feedA, feedB), // Pass out of order
            episodes = episodes,
            pinned = emptyList(),
            nowPlayingId = null,
            now = 10_000L,
            globalLimit = 1,
            defaultNewsDays = 3
        )

        // Expected order: Round 1: A, B, C; Round 2: A, B, C; Round 3: A, B, C
        assertEquals(9, queue.size)
        assertEquals("A", queue[0].take(1))
        assertEquals("B", queue[1].take(1))
        assertEquals("C", queue[2].take(1))
        assertEquals("A", queue[3].take(1))
        assertEquals("B", queue[4].take(1))
        assertEquals("C", queue[5].take(1))
        assertEquals("A", queue[6].take(1))
        assertEquals("B", queue[7].take(1))
        assertEquals("C", queue[8].take(1))
    }

    @Test
    fun test2_freshNewsPrecedesP1_expiredContributesNothing() {
        val now = 1_000_000_000L
        val oneDayMs = 86_400_000L

        val newsFeed = createFeed("NEWS", "News Show", priority = 3, strategy = PlayOrderStrategy.NEWS, newsRetentionDays = 2)
        val p1Feed = createFeed("P1", "Important Show", priority = 1, strategy = PlayOrderStrategy.EVERGREEN)

        val freshNewsEp = createEpisode("N_fresh", "NEWS", publishedAt = now - oneDayMs)
        val expiredNewsEp = createEpisode("N_expired", "NEWS", publishedAt = now - (3 * oneDayMs))
        val p1Ep = createEpisode("P1_ep", "P1", publishedAt = now)

        val queue = buildQueue(
            feeds = listOf(newsFeed, p1Feed),
            episodes = listOf(freshNewsEp, expiredNewsEp, p1Ep),
            pinned = emptyList(),
            nowPlayingId = null,
            now = now,
            globalLimit = 2,
            defaultNewsDays = 2
        )

        assertEquals(listOf("N_fresh", "P1_ep"), queue)
        assertFalse("Expired news item must not appear in queue", queue.contains("N_expired"))
    }

    @Test
    fun test3_strategyContrast() {
        val now = 1_000_000L
        // Catalog e1..e10 (e10 newest: pubDate = 1000, e1 oldest: pubDate = 100)
        fun catalogEpisodes() = (1..10).map { i ->
            createEpisode("e$i", "F", publishedAt = i * 100L, feedOrder = 10 - i)
        }

        // Scenario 3a: EVERGREEN, nothing heard -> e10, e1, e2, ..., e9
        val feedEvergreenFresh = createFeed("F", strategy = PlayOrderStrategy.EVERGREEN, heardHighWaterMark = 0L, maxInQueue = 50, consecutiveLimit = 50)
        val q3a = buildQueue(listOf(feedEvergreenFresh), catalogEpisodes(), emptyList(), null, now, 50, 3)
        assertEquals(listOf("e10", "e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8", "e9"), q3a)

        // Scenario 3b: EVERGREEN, e10, e8, e5 completed (highWaterMark = e10 = 1000L)
        // Eligible unplayed: e1, e2, e3, e4, e6, e7, e9 (no new releases > 1000L) -> back catalog oldest first: e1, e2, e3, e4, e6, e7, e9
        val feedEvergreenHeard = createFeed("F", strategy = PlayOrderStrategy.EVERGREEN, heardHighWaterMark = 1000L, maxInQueue = 50, consecutiveLimit = 50)
        val episodesWithHeard = catalogEpisodes().map {
            if (it.id in listOf("e10", "e8", "e5")) it.copy(isCompleted = true) else it
        }
        val q3b = buildQueue(listOf(feedEvergreenHeard), episodesWithHeard, emptyList(), null, now, 50, 3)
        assertEquals(listOf("e1", "e2", "e3", "e4", "e6", "e7", "e9"), q3b)

        // Scenario 3c: Same state, then new release e11 arrives (pubDate = 1100 > 1000L)
        val e11 = createEpisode("e11", "F", publishedAt = 1100L, feedOrder = 0)
        val q3c = buildQueue(listOf(feedEvergreenHeard), episodesWithHeard + e11, emptyList(), null, now, 50, 3)
        assertEquals(listOf("e11", "e1", "e2", "e3", "e4", "e6", "e7", "e9"), q3c)

        // Scenario 3d: REVERSE_CHRONOLOGICAL, e10, e8, e5 completed -> e9, e7, e6, e4, e3, e2, e1
        val feedRevChrono = createFeed("F", strategy = PlayOrderStrategy.REVERSE_CHRONOLOGICAL, maxInQueue = 50, consecutiveLimit = 50)
        val q3d = buildQueue(listOf(feedRevChrono), episodesWithHeard, emptyList(), null, now, 50, 3)
        assertEquals(listOf("e9", "e7", "e6", "e4", "e3", "e2", "e1"), q3d)

        // Scenario 3e: CHRONOLOGICAL, e10, e8, e5 completed, e11 arrives -> e1, e2, e3, e4, e6, e7, e9, e11
        val feedChrono = createFeed("F", strategy = PlayOrderStrategy.CHRONOLOGICAL, maxInQueue = 50, consecutiveLimit = 50)
        val q3e = buildQueue(listOf(feedChrono), episodesWithHeard + e11, emptyList(), null, now, 50, 3)
        assertEquals(listOf("e1", "e2", "e3", "e4", "e6", "e7", "e9", "e11"), q3e)

        // Assert EVERGREEN and REVERSE_CHRONOLOGICAL outputs differ for the same input
        assertNotEquals("EVERGREEN and REVERSE_CHRONOLOGICAL must produce different orders", q3b, q3d)

        // Scenario 3f: Skips in EVERGREEN (nothing heard, e10 skipped once, e1 skipped twice)
        // Non-skipped: e2..e9. e9 is newest non-skipped -> counts as new release. Back catalog: e2..e8.
        // Skipped: e10 (skipCount=1), e1 (skipCount=2).
        // Result: e9, e2, e3, e4, e5, e6, e7, e8, e10, e1
        val episodesWithSkips = catalogEpisodes().map {
            when (it.id) {
                "e10" -> it.copy(skippedAt = 500L, skipCount = 1)
                "e1" -> it.copy(skippedAt = 600L, skipCount = 2)
                else -> it
            }
        }
        val q3f = buildQueue(listOf(feedEvergreenFresh), episodesWithSkips, emptyList(), null, now, 50, 3)
        assertEquals(listOf("e9", "e2", "e3", "e4", "e5", "e6", "e7", "e8", "e10", "e1"), q3f)

        // Scenario 3g: Skip rotation fairness: feeds A and B, equal priority. A's episode was just skipped.
        // A has lastTurnAt = 1000L. B has lastTurnAt = 0L. B must take turn before A!
        val feedA = createFeed("A", "Feed A", priority = 3, lastTurnAt = 1000L, consecutiveLimit = 1)
        val feedB = createFeed("B", "Feed B", priority = 3, lastTurnAt = 0L, consecutiveLimit = 1)
        val epsAB = listOf(createEpisode("a1", "A", 100L), createEpisode("b1", "B", 100L))
        val q3g = buildQueue(listOf(feedA, feedB), epsAB, emptyList(), null, now, 1, 3)
        assertEquals("B should play before A because A just had a turn (skip)", listOf("b1", "a1"), q3g)
    }

    @Test
    fun test4_completedOrDismissedEpisodesNeverAppear() {
        val feed = createFeed("F", strategy = PlayOrderStrategy.EVERGREEN)
        val episodes = listOf(
            createEpisode("ep_active", "F", 100L),
            createEpisode("ep_completed", "F", 200L, isCompleted = true),
            createEpisode("ep_dismissed", "F", 300L, isDismissed = true)
        )

        val queue = buildQueue(listOf(feed), episodes, emptyList(), null, 1000L, 10, 3)
        assertEquals(listOf("ep_active"), queue)
        assertFalse(queue.contains("ep_completed"))
        assertFalse(queue.contains("ep_dismissed"))
    }

    @Test
    fun test5_pinnedItemsSurviveAndKeepOrder_evenWhenAutoAddFalse() {
        val feedAutoOff = createFeed("F_off", autoAdd = false)
        val feedAutoOn = createFeed("F_on", autoAdd = true)

        val ep1 = createEpisode("pinned_1", "F_off", 100L)
        val ep2 = createEpisode("pinned_2", "F_on", 200L)
        val epProg = createEpisode("prog_1", "F_on", 300L)

        val pinned = listOf(
            PinnedItem("pinned_2", manualIndex = 2),
            PinnedItem("pinned_1", manualIndex = 1) // Should come first due to manualIndex = 1
        )

        val queue = buildQueue(
            feeds = listOf(feedAutoOff, feedAutoOn),
            episodes = listOf(ep1, ep2, epProg),
            pinned = pinned,
            nowPlayingId = null,
            now = 1000L,
            globalLimit = 10,
            defaultNewsDays = 3
        )

        assertEquals(listOf("pinned_1", "pinned_2", "prog_1"), queue)
    }

    @Test
    fun test6_maxInQueueRespected_pinnedItemsDoNotCountTowardIt() {
        // maxInQueue = 2
        val feed = createFeed("F", maxInQueue = 2, consecutiveLimit = 10)
        val pinnedEp = createEpisode("pin", "F", 100L)
        val ep1 = createEpisode("e1", "F", 200L)
        val ep2 = createEpisode("e2", "F", 300L)
        val ep3 = createEpisode("e3", "F", 400L)

        val pinned = listOf(PinnedItem("pin", manualIndex = 0))

        val queue = buildQueue(
            feeds = listOf(feed),
            episodes = listOf(pinnedEp, ep1, ep2, ep3),
            pinned = pinned,
            nowPlayingId = null,
            now = 1000L,
            globalLimit = 10,
            defaultNewsDays = 3
        )

        // In Evergreen with nothing heard, e3 is the newest release so it comes first,
        // followed by oldest back catalog e1. e2 is capped by maxInQueue = 2.
        assertEquals(3, queue.size)
        assertEquals("pin", queue[0])
        assertEquals(listOf("pin", "e3", "e1"), queue)
        assertFalse("e2 should be capped by maxInQueue = 2", queue.contains("e2"))
    }

    @Test
    fun test7_nowPlayingStaysFirstAndReducesFirstRoundQuota() {
        val feedA = createFeed("A", consecutiveLimit = 2)
        val feedB = createFeed("B", consecutiveLimit = 2)

        val a_now = createEpisode("a_now", "A", 100L)
        val a1 = createEpisode("a1", "A", 200L)
        val a2 = createEpisode("a2", "A", 300L)
        val b1 = createEpisode("b1", "B", 100L)
        val b2 = createEpisode("b2", "B", 200L)

        val queue = buildQueue(
            feeds = listOf(feedA, feedB),
            episodes = listOf(a_now, a1, a2, b1, b2),
            pinned = emptyList(),
            nowPlayingId = "a_now",
            now = 1000L,
            globalLimit = 2,
            defaultNewsDays = 3
        )

        // nowPlaying is "a_now".
        // Round 1: Feed A quota reduced from 2 to (2-1) = 1. A takes 1 (a1 or next).
        // Feed B takes 2 (b1, b2).
        // Round 2: A takes remaining a2.
        assertEquals(listOf("a_now", "a2", "b2", "b1", "a1"), queue.take(1) + queue.drop(1))
        assertEquals("a_now", queue[0])
        // Total items from A in round 1 should be 1
    }

    @Test
    fun test8_determinism_sameInputsProduceSameOutput() {
        val feedA = createFeed("A", priority = 1, consecutiveLimit = 1)
        val feedB = createFeed("B", priority = 2, consecutiveLimit = 1)
        val feedC = createFeed("C", priority = 3, consecutiveLimit = 1)

        val episodes = (1..15).map { i ->
            val f = when (i % 3) {
                0 -> "A"
                1 -> "B"
                else -> "C"
            }
            createEpisode("ep$i", f, publishedAt = i * 100L)
        }

        val q1 = buildQueue(listOf(feedA, feedB, feedC), episodes, emptyList(), null, 5000L, 1, 3)
        val q2 = buildQueue(listOf(feedA, feedB, feedC), episodes, emptyList(), null, 5000L, 1, 3)

        assertEquals("Same inputs must yield identical output", q1, q2)
    }
}
