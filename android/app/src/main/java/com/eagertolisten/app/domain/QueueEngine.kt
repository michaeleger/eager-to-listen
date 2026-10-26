package com.eagertolisten.app.domain

import com.eagertolisten.app.data.model.PlayOrderStrategy

data class EngineFeed(
    val id: String,
    val title: String,
    val priority: Int,
    val strategy: PlayOrderStrategy,
    val newsRetentionDays: Int,
    val consecutiveLimit: Int,
    val maxInQueue: Int,
    val autoAdd: Boolean,
    val lastTurnAt: Long, // max(completedAt, skippedAt) across the feed's episodes
    val subscribedAt: Long,
    // Newest publishedAt among this feed's COMPLETED episodes; 0 if none heard yet.
    // Not used for ordering since Evergreen went back to newest-first; kept for a future variant.
    val heardHighWaterMark: Long
)

data class EngineEpisode(
    val id: String,
    val feedId: String,
    val publishedAt: Long,
    val feedOrder: Int,
    val positionSec: Long,
    val isCompleted: Boolean,
    val isDismissed: Boolean,
    val skippedAt: Long?,
    val skipCount: Int
)

data class PinnedItem(
    val episodeId: String,
    val manualIndex: Int
)

fun buildQueue(
    feeds: List<EngineFeed>,
    episodes: List<EngineEpisode>,
    pinned: List<PinnedItem>,
    nowPlayingId: String?,
    now: Long,
    globalLimit: Int,
    defaultNewsDays: Int
): List<String> {
    // 1. Filter out all completed or dismissed episodes (Invariant I1)
    val eligibleEpisodesById = episodes
        .filter { !it.isCompleted && !it.isDismissed }
        .associateBy { it.id }

    val placedIds = mutableSetOf<String>()
    val result = mutableListOf<String>()

    // 2. Now Playing episode (stays #1 if eligible)
    var nowPlayingFeedId: String? = null
    if (nowPlayingId != null) {
        val nowPlayingEp = eligibleEpisodesById[nowPlayingId]
        if (nowPlayingEp != null) {
            placedIds.add(nowPlayingEp.id)
            result.add(nowPlayingEp.id)
            nowPlayingFeedId = nowPlayingEp.feedId
        }
    }

    // 3. Pinned manual items (ordered by manualIndex ascending)
    // Note: Manual items from feeds with autoAdd = false are kept (Section 3.2).
    val sortedPinned = pinned.sortedBy { it.manualIndex }
    for (pin in sortedPinned) {
        val ep = eligibleEpisodesById[pin.episodeId]
        if (ep != null && ep.id !in placedIds) {
            placedIds.add(ep.id)
            result.add(ep.id)
        }
    }

    // 4. Per-feed candidate lists for eligible feeds (autoAdd == true)
    val feedEpisodeQueues = mutableMapOf<String, MutableList<EngineEpisode>>()
    val feedLimits = mutableMapOf<String, Int>()
    val feedTiers = mutableMapOf<String, Int>()
    val feedsById = feeds.associateBy { it.id }

    for (feed in feeds) {
        if (!feed.autoAdd) continue

        // Unplaced eligible episodes for this feed
        val unplacedForFeed = episodes.filter {
            it.feedId == feed.id && !it.isCompleted && !it.isDismissed && it.id !in placedIds
        }
        if (unplacedForFeed.isEmpty()) continue

        val (nonSkipped, skipped) = unplacedForFeed.partition { it.skippedAt == null }

        val nonSkippedOrdered = when (feed.strategy) {
            // Evergreen: newest unplayed first, then the next newest, working back through the
            // catalog (the order the Feed Customizer promises). New releases land on top because
            // they are the newest. The back catalog never restarts from episode 1.
            PlayOrderStrategy.REVERSE_CHRONOLOGICAL,
            PlayOrderStrategy.EVERGREEN -> {
                nonSkipped.sortedWith(
                    compareByDescending<EngineEpisode> { it.publishedAt }
                        .thenBy { it.feedOrder }
                )
            }
            PlayOrderStrategy.CHRONOLOGICAL -> {
                nonSkipped.sortedWith(
                    compareBy<EngineEpisode> { it.publishedAt }.thenByDescending { it.feedOrder }
                )
            }
            PlayOrderStrategy.NEWS -> {
                val retentionDays = if (feed.newsRetentionDays > 0) feed.newsRetentionDays else defaultNewsDays
                val cutoff = now - (retentionDays.toLong() * 86_400_000L)
                nonSkipped
                    .filter { it.publishedAt > 0L && it.publishedAt >= cutoff }
                    .sortedWith(compareBy<EngineEpisode> { it.publishedAt }.thenBy { it.feedOrder })
            }
        }

        // Skipped episodes always go to the end of the feed's rotation
        val skippedOrdered = skipped.sortedWith(
            compareBy<EngineEpisode> { it.skipCount }
                .thenBy { it.skippedAt ?: 0L }
                .thenBy { it.feedOrder }
        )

        val fullCandidates = nonSkippedOrdered + skippedOrdered
        if (fullCandidates.isEmpty()) continue

        // Cap per-feed programmed items by maxInQueue (pinned items do not count toward it)
        val cappedCandidates = if (feed.maxInQueue > 0) {
            fullCandidates.take(feed.maxInQueue)
        } else {
            fullCandidates
        }

        if (cappedCandidates.isNotEmpty()) {
            feedEpisodeQueues[feed.id] = cappedCandidates.toMutableList()
            feedLimits[feed.id] = if (feed.consecutiveLimit > 0) feed.consecutiveLimit else globalLimit
            // Tier 0: NEWS feeds with fresh (within retention, known date) non-skipped unplayed items
            feedTiers[feed.id] = if (feed.strategy == PlayOrderStrategy.NEWS && nonSkippedOrdered.isNotEmpty()) {
                0
            } else {
                feed.priority.coerceIn(1, 5)
            }
        }
    }

    // 5. Interleave feeds round-robin by tier taking up to feedLimit from each feed
    var isFirstPass = true

    while (feedEpisodeQueues.values.any { it.isNotEmpty() }) {
        val activeFeedIds = feedEpisodeQueues.filter { it.value.isNotEmpty() }.keys.sortedWith(
            compareBy<String> { feedTiers[it] ?: 3 }
                .thenBy { id -> feedsById[id]?.lastTurnAt ?: 0L }
                .thenBy { id -> feedsById[id]?.title ?: "" }
                .thenBy { id -> id }
        )

        for (feedId in activeFeedIds) {
            val epList = feedEpisodeQueues[feedId] ?: continue
            val feedLimit = feedLimits[feedId] ?: globalLimit

            // If this feed already has the pinned #1 now-playing episode, reduce its first-pass quota by 1
            val effectiveLimit = if (isFirstPass && nowPlayingFeedId == feedId) {
                (feedLimit - 1).coerceAtLeast(0)
            } else {
                feedLimit
            }

            val countToTake = minOf(effectiveLimit, epList.size)
            for (i in 0 until countToTake) {
                if (epList.isNotEmpty()) {
                    val ep = epList.removeAt(0)
                    placedIds.add(ep.id)
                    result.add(ep.id)
                }
            }
        }
        isFirstPass = false
    }

    return result
}
