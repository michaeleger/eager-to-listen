package com.eagertolisten.app.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.eagertolisten.app.data.EagerDatabase
import com.eagertolisten.app.data.model.Chapter
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.model.PlayOrderStrategy
import com.eagertolisten.app.data.model.Podcast
import com.eagertolisten.app.data.model.QueueItem
import com.eagertolisten.app.data.model.TranscriptCue
import com.eagertolisten.app.domain.EngineEpisode
import com.eagertolisten.app.domain.EngineFeed
import com.eagertolisten.app.domain.PinnedItem
import com.eagertolisten.app.domain.buildQueue
import com.eagertolisten.app.parser.ChapterParser
import com.eagertolisten.app.parser.FeedParser
import com.eagertolisten.app.parser.TranscriptParser
import com.eagertolisten.app.service.PlaybackStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class QueueSortRule {
    PROGRAMMED_FAIR,          // Smart Fair Interleaved Order (Play Orders + Feed Limit above priority)
    PRIORITY_THEN_DATE_ASC,   // Radio U Classic: Priority 1 first, then oldest to newest
    PRIORITY_THEN_DATE_DESC,  // Priority 1 first, then newest
    DATE_DESC,                // Newest first regardless of priority
    DATE_ASC                  // Oldest first
}

class PodcastRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = EagerDatabase.getInstance(context)
    private val podcastDao = db.podcastDao()
    private val episodeDao = db.episodeDao()
    private val queueDao = db.queueDao()
    private val pendingRemoteEpisodeDao = db.pendingRemoteEpisodeDao()
    private val prefs = context.getSharedPreferences("eager_settings", Context.MODE_PRIVATE)

    init {
        if (!initCleanupDone) {
            initCleanupDone = true
            repositoryScope.launch {
                try {
                    episodeDao.markEndedLiveEpisodesCompleted()
                    queueDao.purgeLiveItemsFromQueue()
                    purgeExpiredNewsEpisodes()
                } catch (e: Exception) {
                    Log.e("PodcastRepository", "Failed to purge live items or expired news on init", e)
                }
            }
        }
    }

    companion object {
        const val USER_AGENT = "EagerToListen/0.9.2-alpha (Android; +https://eagertobehealthy.com/eager-to-listen)"
        const val ACCEPT_HEADER = "application/rss+xml, application/xml, text/xml, application/atom+xml, text/html;q=0.9, */*;q=0.8"

        private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Process-wide: every ViewModel, worker and service creates its own PodcastRepository,
        // so these locks have to live here to actually serialize queue rebuilds and feed refreshes.
        private val rebuildMutex = Mutex()
        private val rebuildRequestedAfterCurrent = AtomicBoolean(false)
        private val refreshAllMutex = Mutex()

        @Volatile
        private var initCleanupDone = false

        @Volatile
        private var sharedClient: OkHttpClient? = null

        fun getOkHttpClient(): OkHttpClient {
            return sharedClient ?: synchronized(this) {
                sharedClient ?: OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .retryOnConnectionFailure(true)
                    .build().also { sharedClient = it }
            }
        }
    }

    private val client = getOkHttpClient()

    private val feedParser = FeedParser()
    private val transcriptParser = TranscriptParser()
    private val chapterParser = ChapterParser()

    // Flows
    val allPodcasts: Flow<List<Podcast>> = podcastDao.getAllPodcasts()
    val queueEpisodes: Flow<List<Episode>> = queueDao.getQueueEpisodes()
    val unplayedEpisodes: Flow<List<Episode>> = episodeDao.getUnplayedEpisodes()
    val playedEpisodes: Flow<List<Episode>> = episodeDao.getPlayedEpisodes()
    val hotListEpisodes: Flow<List<Episode>> = episodeDao.getHotListEpisodes()
    val liveEpisodes: Flow<List<Episode>> = episodeDao.getLiveEpisodes()

    fun getPodcast(id: String): Flow<Podcast?> = podcastDao.getPodcastById(id)
    suspend fun getPodcastById(id: String): Podcast? = withContext(Dispatchers.IO) { podcastDao.getPodcastByIdSync(id) }
    fun getEpisodesForPodcast(podcastId: String): Flow<List<Episode>> = episodeDao.getEpisodesForPodcast(podcastId)
    fun getEpisode(id: String): Flow<Episode?> = episodeDao.getEpisodeById(id)
    suspend fun getTopQueueEpisode(): Episode? = withContext(Dispatchers.IO) { queueDao.getQueueEpisodesSync().firstOrNull() }

    suspend fun subscribe(feedUrl: String, priority: Int = 3): Result<Podcast> = withContext(Dispatchers.IO) {
        try {
            val normalizedUrl = com.eagertolisten.app.data.model.FeedUrl.normalize(feedUrl).ifBlank { feedUrl.trim() }
            val existing = podcastDao.getPodcastByIdSync(normalizedUrl) ?: podcastDao.getPodcastByFeedUrlSync(normalizedUrl)
            if (existing != null) {
                // If it was soft-unsubscribed, reactivate it while preserving history and settings (I2 & I3)
                if (existing.unsubscribedAt != null) {
                    podcastDao.clearUnsubscribed(existing.id)
                }
                refreshFeedInternal(existing)
                generateProgrammedQueue()
                return@withContext Result.success(existing)
            }

            val request = Request.Builder()
                .url(normalizedUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", ACCEPT_HEADER)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code} (${response.message})"))
                }
                val body = response.body?.byteStream() ?: return@withContext Result.failure(Exception("Empty body from server"))
                val parsed = feedParser.parse(body, normalizedUrl, podcastId = normalizedUrl)

                val podcastWithPriority = parsed.podcast.copy(id = normalizedUrl, feedUrl = normalizedUrl, priority = priority)
                val episodes = parsed.episodes.map { it.copy(podcastId = podcastWithPriority.id) }

                db.withTransaction {
                    podcastDao.insertPodcast(podcastWithPriority)
                    episodeDao.insertEpisodes(episodes)
                }

                // Auto-enqueue unplayed using programmed order if enabled
                if (podcastWithPriority.autoAddToQueue) {
                    generateProgrammedQueue()
                }
                Result.success(podcastWithPriority)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun refreshFeedInternal(podcast: Podcast): Result<Unit> {
        // Local folder shows have no feed to fetch: refreshing rescans the folder.
        if (com.eagertolisten.app.data.local.LocalFolderFormat.isLocalFeed(podcast.feedUrl)) {
            return com.eagertolisten.app.data.local.LocalFolderScanner(appContext).rescan(podcast).map { }
        }
        return try {
            Log.d("EagerRefresh", "Fetching feed for '${podcast.title}' (ID: ${podcast.id}, URL: ${podcast.feedUrl})")
            val requestBuilder = Request.Builder()
                .url(podcast.feedUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", ACCEPT_HEADER)
                .header("Accept-Language", "en-US,en;q=0.9")

            podcast.httpEtag?.let { etag ->
                if (etag.isNotBlank()) requestBuilder.header("If-None-Match", etag)
            }
            podcast.httpLastModified?.let { lastMod ->
                if (lastMod.isNotBlank()) requestBuilder.header("If-Modified-Since", lastMod)
            }

            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                if (response.code == 304) {
                    Log.d("EagerRefresh", "Feed '${podcast.title}' not modified (304)")
                    return Result.success(Unit)
                }
                if (!response.isSuccessful) {
                    val msg = "HTTP ${response.code} (${response.message})"
                    Log.w("EagerRefresh", "Failed '${podcast.title}': $msg")
                    return Result.failure(Exception(msg))
                }
                val body = response.body?.byteStream() ?: return Result.failure(Exception("Empty body from server"))
                val parsed = feedParser.parse(body, podcast.feedUrl, podcastId = podcast.id)

                // Handle 301/308 permanent redirects
                var prior = response.priorResponse
                var wasPermanentRedirect = false
                while (prior != null) {
                    if (prior.code == 301 || prior.code == 308) {
                        wasPermanentRedirect = true
                        break
                    }
                    prior = prior.priorResponse
                }
                val finalUrl = response.request.url.toString()
                if (wasPermanentRedirect && finalUrl.isNotBlank() && finalUrl != podcast.feedUrl) {
                    Log.i("EagerRefresh", "Permanent redirect for '${podcast.title}': ${podcast.feedUrl} -> $finalUrl")
                    podcastDao.updateFeedUrl(podcast.id, finalUrl)
                }

                // Handle itunes:new-feed-url
                if (!parsed.newFeedUrl.isNullOrBlank() && parsed.newFeedUrl != podcast.feedUrl) {
                    Log.i("EagerRefresh", "itunes:new-feed-url for '${podcast.title}': ${podcast.feedUrl} -> ${parsed.newFeedUrl}")
                    podcastDao.updateFeedUrl(podcast.id, parsed.newFeedUrl)
                }

                // Guarantee podcastId matches podcast.id so foreign keys and queries always align
                val episodes = parsed.episodes.map { it.copy(podcastId = podcast.id) }
                val realTitle = if (parsed.podcast.title.isNotBlank() && parsed.podcast.title != "Podcast") parsed.podcast.title else podcast.title
                val newArtwork = parsed.podcast.artworkUrl.ifBlank { podcast.artworkUrl }
                val newAuthor = parsed.podcast.author.ifBlank { podcast.author }
                val newDesc = parsed.podcast.description.ifBlank { podcast.description }
                val newEtag = response.header("ETag") ?: podcast.httpEtag
                val newLastModified = response.header("Last-Modified") ?: podcast.httpLastModified

                db.withTransaction {
                    // Deactivate any existing live episodes for this podcast before updating with fresh feed data
                    episodeDao.deactivateLiveEpisodesForPodcast(podcast.id)
                    episodeDao.markEndedLiveEpisodesCompleted()

                    // Insert any brand new episodes
                    episodeDao.insertEpisodes(episodes)

                    // Refresh mutable feed fields on existing episodes without touching playback state
                    for (ep in episodes) {
                        episodeDao.updateEpisodeFeedFields(
                            id = ep.id,
                            title = ep.title,
                            audioUrl = ep.audioUrl,
                            durationSeconds = ep.durationSeconds,
                            description = ep.description,
                            artworkUrl = ep.artworkUrl,
                            feedOrder = ep.feedOrder
                        )
                        episodeDao.updateEpisodeMetadata(
                            id = ep.id,
                            transcriptUrl = ep.transcriptUrl,
                            transcriptType = ep.transcriptType,
                            chaptersUrl = ep.chaptersUrl,
                            personsJson = ep.personsJson,
                            soundbitesJson = ep.soundbitesJson,
                            fundingUrl = ep.fundingUrl,
                            valueJson = ep.valueJson,
                            isLive = ep.isLive,
                            liveStatus = ep.liveStatus
                        )
                    }
                    // Purge any live items from the queue to prevent competition with the main playlist
                    queueDao.purgeLiveItemsFromQueue()

                    // Purge expired news episodes for news podcasts
                    if (podcast.playOrderStrategy == "NEWS") {
                        val defaultNewsDays = getDefaultNewsRetentionDays()
                        val retentionDays = if (podcast.newsRetentionDays > 0) podcast.newsRetentionDays else defaultNewsDays
                        val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * 86_400_000L)
                        val purgedCount = episodeDao.dismissExpiredNews(podcast.id, cutoff, System.currentTimeMillis())
                        queueDao.removeExpiredEpisodesForPodcast(podcast.id, cutoff)
                        if (purgedCount > 0) {
                            Log.i("EagerRefresh", "Dismissed $purgedCount expired news episodes for '${podcast.title}' (cutoff: $cutoff)")
                        }
                    }

                    // Column-targeted update: Never overwrite user-editable rules (priority, speed, strategy, etc.)
                    podcastDao.updateFeedMetadata(
                        id = podcast.id,
                        title = realTitle,
                        artworkUrl = newArtwork,
                        author = newAuthor,
                        description = newDesc,
                        fundingUrl = parsed.podcast.fundingUrl ?: podcast.fundingUrl,
                        fundingMessage = parsed.podcast.fundingMessage ?: podcast.fundingMessage,
                        valueJson = parsed.podcast.valueJson ?: podcast.valueJson,
                        httpEtag = newEtag,
                        httpLastModified = newLastModified
                    )
                    if (realTitle.isNotBlank()) {
                        episodeDao.updatePodcastTitleForEpisodes(podcast.id, realTitle)
                    }

                    // Apply any pending remote sync episode states for this podcast
                    val pending = pendingRemoteEpisodeDao.getPendingForPodcast(podcast.id)
                    if (pending.isNotEmpty()) {
                        for (p in pending) {
                            val ep = episodeDao.getEpisodeByIdSync(p.episodeId)
                            if (ep != null) {
                                episodeDao.updateEpisodeSyncState(
                                    episodeId = p.episodeId,
                                    pos = p.playbackPositionSeconds,
                                    completed = p.isCompleted,
                                    completedAt = p.completedAt,
                                    dismissed = p.isDismissed,
                                    dismissReason = p.dismissReason,
                                    skippedAt = p.skippedAt,
                                    skipCount = p.skipCount,
                                    updatedAt = p.updatedAt
                                )
                            }
                        }
                        pendingRemoteEpisodeDao.deleteForPodcast(podcast.id)
                        Log.d("EagerRefresh", "Applied ${pending.size} pending sync states for '${podcast.title}'")
                    }
                }

                Log.d("EagerRefresh", "Saved ${episodes.size} episodes for '${podcast.title}' (ID: ${podcast.id})")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e("EagerRefresh", "Exception refreshing '${podcast.title}': ${e.message}", e)
            Result.failure(e)
        } catch (e: OutOfMemoryError) {
            // A very large feed can exhaust the heap while parsing (up to 4 parse at once).
            // Fail this one feed instead of taking the whole app down.
            Log.e("EagerRefresh", "Out of memory refreshing '${podcast.title}'", e)
            Result.failure(Exception("Feed too large to load (out of memory)"))
        }
    }

    suspend fun refreshFeed(podcastId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val podcast = podcastDao.getPodcastByIdSync(podcastId)
            ?: podcastDao.getPodcastByFeedUrlSync(podcastId)
            ?: return@withContext Result.failure(Exception("Podcast not found: $podcastId"))

        val result = refreshFeedInternal(podcast)
        if (result.isSuccess && podcast.autoAddToQueue) {
            generateProgrammedQueue()
        }
        result
    }

    suspend fun refreshAllFeeds(): Pair<Int, List<String>> = withContext(Dispatchers.IO) {
        // One refresh at a time across the app (startup refresh, pull-to-refresh, the 12-hour worker,
        // "Update Feeds & Backup Now"). A second caller waits for the first to finish.
        refreshAllMutex.withLock {
            try {
                refreshAllFeedsLocked()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("EagerRefresh", "refreshAllFeeds failed", e)
                Pair(0, listOf("Refresh failed: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    private suspend fun refreshAllFeedsLocked(): Pair<Int, List<String>> {
        val podcasts = podcastDao.getAllPodcastsSync()
        if (podcasts.isEmpty()) return Pair(0, emptyList())

        Log.d("EagerRefresh", "refreshAllFeeds started for ${podcasts.size} podcasts")
        var successCount = 0
        val errors = mutableListOf<String>()
        val refreshSemaphore = Semaphore(4)

        // Concurrently refresh feeds across IO coroutines (bounded to 4 simultaneous parses)
        coroutineScope {
            val deferreds = podcasts.map { podcast ->
                async {
                    refreshSemaphore.withPermit {
                        val res = refreshFeedInternal(podcast)
                        Pair(podcast, res)
                    }
                }
            }
            val results = deferreds.awaitAll()
            for ((podcast, res) in results) {
                if (res.isSuccess) {
                    successCount++
                } else {
                    val err = res.exceptionOrNull()?.message ?: "Unknown error"
                    errors.add("${podcast.title}: $err")
                }
            }
        }

        // Rebuild programmed queue once after all feeds are refreshed. A failure here must not
        // crash the app: the feeds are already saved, and the next rebuild will catch up.
        try {
            generateProgrammedQueue()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("EagerRefresh", "Queue rebuild after refresh failed", e)
            errors.add("Queue rebuild: ${e.message ?: e.javaClass.simpleName}")
        }
        Log.d("EagerRefresh", "refreshAllFeeds finished: $successCount succeeded, ${errors.size} failed")

        return Pair(successCount, errors)
    }

    // Global settings for queue diversity & news
    fun getGlobalConsecutivePlayLimit(): Int = prefs.getInt("global_consecutive_limit", 1)

    fun setGlobalConsecutivePlayLimit(limit: Int) {
        prefs.edit().putInt("global_consecutive_limit", limit.coerceAtLeast(1)).apply()
    }

    fun getDefaultNewsRetentionDays(): Int = prefs.getInt("default_news_days", 3)

    fun setDefaultNewsRetentionDays(days: Int) {
        prefs.edit().putInt("default_news_days", days.coerceIn(1, 30)).apply()
    }

    fun getSavedSortRule(): QueueSortRule {
        val name = prefs.getString("queue_sort_rule", QueueSortRule.PROGRAMMED_FAIR.name) ?: QueueSortRule.PROGRAMMED_FAIR.name
        return try {
            QueueSortRule.valueOf(name)
        } catch (_: Exception) {
            QueueSortRule.PROGRAMMED_FAIR
        }
    }

    fun setSavedSortRule(rule: QueueSortRule) {
        prefs.edit().putString("queue_sort_rule", rule.name).apply()
    }

    suspend fun updateFeedRules(
        podcastId: String,
        priority: Int,
        skipIntro: Int,
        skipOutro: Int,
        speed: Float,
        autoAdd: Boolean,
        maxEpisodes: Int,
        category: String,
        customDonationUrl: String? = null,
        playOrderStrategy: String,
        newsRetentionDays: Int,
        consecutivePlayLimit: Int
    ) = withContext(Dispatchers.IO) {
        podcastDao.updateFeedRules(
            podcastId = podcastId,
            priority = priority,
            skipIntro = skipIntro,
            skipOutro = skipOutro,
            speed = speed,
            autoAdd = autoAdd,
            maxEpisodes = maxEpisodes,
            category = category,
            customDonationUrl = customDonationUrl,
            playOrderStrategy = playOrderStrategy,
            newsRetentionDays = newsRetentionDays,
            consecutivePlayLimit = consecutivePlayLimit
        )
        if (playOrderStrategy == "NEWS") {
            val retentionDays = if (newsRetentionDays > 0) newsRetentionDays else getDefaultNewsRetentionDays()
            val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * 86_400_000L)
            episodeDao.dismissExpiredNews(podcastId, cutoff, System.currentTimeMillis())
            queueDao.removeExpiredEpisodesForPodcast(podcastId, cutoff)
        } else {
            episodeDao.restoreExpiredNews(podcastId, System.currentTimeMillis())
        }
        if (autoAdd) {
            generateProgrammedQueue()
        }
    }

    suspend fun updateCustomDonationUrl(podcastId: String, url: String?) = withContext(Dispatchers.IO) {
        podcastDao.updateCustomDonationUrl(podcastId, url)
    }

    // Single Episode "Play Next" Priority Insertion:
    // When selected, ONLY this single episode is added to the very top (index 0) of the pending playlist.
    // When it finishes, playback naturally returns to the programmed order!
    suspend fun enqueueEpisodeTop(episodeId: String, podcastId: String) = withContext(Dispatchers.IO) {
        val ep = episodeDao.getEpisodeByIdSync(episodeId)
        if (ep?.isLive == true || ep?.liveStatus != null) {
            Log.w("PodcastRepository", "Rejecting live episode from top queue: $episodeId")
            return@withContext
        }
        val podcast = podcastDao.getPodcastByIdSync(podcastId)
        if (podcast?.playOrderStrategy == "NEWS") {
            val defaultNewsDays = getDefaultNewsRetentionDays()
            val retentionDays = if (podcast.newsRetentionDays > 0) podcast.newsRetentionDays else defaultNewsDays
            val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * 86_400_000L)
            if (ep != null && ep.publishedAt < cutoff) {
                Log.w("PodcastRepository", "Rejecting expired news episode from top queue: ${ep.title} (${ep.id})")
                return@withContext
            }
        }
        // If manually enqueuing a completed episode, reset its completed state and position
        if (ep != null && ep.isCompleted) {
            episodeDao.updatePlaybackProgress(
                episodeId = episodeId,
                positionSec = 0,
                isCompleted = false,
                updatedAt = System.currentTimeMillis()
            )
        }
        queueDao.enqueueEpisodeTop(episodeId, podcastId)
    }

    suspend fun enqueueEpisode(episodeId: String, podcastId: String) = withContext(Dispatchers.IO) {
        val ep = episodeDao.getEpisodeByIdSync(episodeId)
        if (ep?.isLive == true || ep?.liveStatus != null) {
            Log.w("PodcastRepository", "Rejecting live episode from queue: $episodeId")
            return@withContext
        }
        val podcast = podcastDao.getPodcastByIdSync(podcastId)
        if (podcast?.playOrderStrategy == "NEWS") {
            val defaultNewsDays = getDefaultNewsRetentionDays()
            val retentionDays = if (podcast.newsRetentionDays > 0) podcast.newsRetentionDays else defaultNewsDays
            val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * 86_400_000L)
            if (ep != null && ep.publishedAt < cutoff) {
                Log.w("PodcastRepository", "Rejecting expired news episode from queue: ${ep.title} (${ep.id})")
                return@withContext
            }
        }
        // If manually enqueuing a completed episode, reset its completed state and position
        if (ep != null && ep.isCompleted) {
            episodeDao.updatePlaybackProgress(
                episodeId = episodeId,
                positionSec = 0,
                isCompleted = false,
                updatedAt = System.currentTimeMillis()
            )
        }
        val maxOrder = queueDao.getMaxOrderIndex() ?: -1
        val item = QueueItem(
            episodeId = episodeId,
            podcastId = podcastId,
            orderIndex = maxOrder + 1
        )
        queueDao.insertQueueItem(item)
    }

    suspend fun removeEpisodeFromQueue(episodeId: String) = withContext(Dispatchers.IO) {
        queueDao.removeEpisodeFromQueue(episodeId)
    }

    suspend fun clearQueue() = withContext(Dispatchers.IO) {
        queueDao.clearQueue()
    }

    suspend fun purgeExpiredNewsEpisodes() = withContext(Dispatchers.IO) {
        val podcasts = podcastDao.getAllPodcastsSync()
        val defaultNewsDays = getDefaultNewsRetentionDays()
        val now = System.currentTimeMillis()

        for (podcast in podcasts) {
            if (podcast.playOrderStrategy == "NEWS") {
                val retentionDays = if (podcast.newsRetentionDays > 0) podcast.newsRetentionDays else defaultNewsDays
                val cutoff = now - (retentionDays.toLong() * 86_400_000L)
                val dismissedEpisodes = episodeDao.dismissExpiredNews(podcast.id, cutoff, now)
                val removedQueue = queueDao.removeExpiredEpisodesForPodcast(podcast.id, cutoff)
                if (dismissedEpisodes > 0 || removedQueue > 0) {
                    Log.i("PodcastRepository", "Purged expired news for '${podcast.title}': $dismissedEpisodes episodes dismissed, $removedQueue removed from queue (cutoff: $cutoff)")
                }
            }
        }
    }

    suspend fun generateProgrammedQueue() = withContext(Dispatchers.IO) {
        if (rebuildMutex.isLocked) {
            rebuildRequestedAfterCurrent.set(true)
            return@withContext
        }
        rebuildMutex.withLock {
            do {
                rebuildRequestedAfterCurrent.set(false)
                generateProgrammedQueueInternal()
            } while (rebuildRequestedAfterCurrent.get())
        }
    }

    private suspend fun generateProgrammedQueueInternal() {
        purgeExpiredNewsEpisodes()

        val podcasts = podcastDao.getAllPodcastsSync()
        if (podcasts.isEmpty()) return

        val globalLimit = getGlobalConsecutivePlayLimit()
        val defaultNewsDays = getDefaultNewsRetentionDays()
        val now = System.currentTimeMillis()

        val highWaterMarks = episodeDao.getAllHeardHighWaterMarks().associate { it.podcastId to it.maxPub }
        val lastTurns = episodeDao.getAllLastTurns().associate { it.podcastId to it.lastTurn }

        val engineFeeds = podcasts.map { p ->
            val strategy = try {
                PlayOrderStrategy.valueOf(p.playOrderStrategy)
            } catch (_: Exception) {
                PlayOrderStrategy.EVERGREEN
            }
            EngineFeed(
                id = p.id,
                title = p.title,
                priority = p.priority,
                strategy = strategy,
                newsRetentionDays = p.newsRetentionDays,
                consecutiveLimit = p.consecutivePlayLimit,
                maxInQueue = p.maxEpisodesInQueue,
                autoAdd = p.autoAddToQueue,
                lastTurnAt = lastTurns[p.id] ?: 0L,
                subscribedAt = p.subscribedAt,
                heardHighWaterMark = highWaterMarks[p.id] ?: 0L
            )
        }

        val podcastIds = podcasts.map { it.id }
        val eligibleProjections = episodeDao.getEligibleEpisodesForQueueSync(podcastIds)
        val engineEpisodes = eligibleProjections.map { ep ->
            EngineEpisode(
                id = ep.id,
                feedId = ep.podcastId,
                publishedAt = ep.publishedAt,
                feedOrder = ep.feedOrder,
                positionSec = ep.playbackPositionSeconds,
                isCompleted = ep.isCompleted,
                isDismissed = ep.isDismissed,
                skippedAt = ep.skippedAt,
                skipCount = ep.skipCount
            )
        }

        val pinnedQueueItems = queueDao.getPinnedItemsSync()
        val enginePinned = pinnedQueueItems.map {
            PinnedItem(episodeId = it.episodeId, manualIndex = it.manualIndex ?: 0)
        }

        val activeStateEp = PlaybackStateManager.state.value.currentEpisode
        val existingQueue = queueDao.getQueueEpisodesSync()
        val nowPlayingId = when {
            activeStateEp != null && !activeStateEp.isCompleted && !activeStateEp.isDismissed && !activeStateEp.isLive -> activeStateEp.id
            existingQueue.firstOrNull()?.let { !it.isCompleted && !it.isDismissed && !it.isLive && it.playbackPositionSeconds > 0 } == true -> existingQueue.first().id
            else -> null
        }

        val queuedIds = buildQueue(
            feeds = engineFeeds,
            episodes = engineEpisodes,
            pinned = enginePinned,
            nowPlayingId = nowPlayingId,
            now = now,
            globalLimit = globalLimit,
            defaultNewsDays = defaultNewsDays
        )

        val epFeedMap = eligibleProjections.associate { it.id to it.podcastId }
        val pinnedMap = pinnedQueueItems.associateBy { it.episodeId }

        val newItems = queuedIds.mapIndexed { index, epId ->
            val pinned = pinnedMap[epId]
            QueueItem(
                episodeId = epId,
                podcastId = epFeedMap[epId] ?: activeStateEp?.takeIf { it.id == epId }?.podcastId ?: "",
                orderIndex = index,
                isManual = pinned != null,
                manualIndex = pinned?.manualIndex
            )
        }

        db.withTransaction {
            queueDao.clearQueue()
            queueDao.insertQueueItems(newItems)
            queueDao.purgeLiveItemsFromQueue()
        }

        val savedRule = getSavedSortRule()
        if (savedRule != QueueSortRule.PROGRAMMED_FAIR) {
            applySortRuleToCurrentQueue(savedRule)
        }

        try {
            com.eagertolisten.app.data.download.PodcastDownloadManager.enqueueAutoDownload(appContext)
        } catch (e: Exception) {
            Log.w("PodcastRepository", "Failed to enqueue auto download: ${e.message}")
        }
    }

    // Radio U Smart Sorting
    suspend fun sortQueue(rule: QueueSortRule) = withContext(Dispatchers.IO) {
        setSavedSortRule(rule)
        if (rule == QueueSortRule.PROGRAMMED_FAIR) {
            generateProgrammedQueue()
            return@withContext
        }

        purgeExpiredNewsEpisodes()
        applySortRuleToCurrentQueue(rule)
    }

    private suspend fun applySortRuleToCurrentQueue(rule: QueueSortRule) {
        val allItems = queueDao.getAllQueueItemsSync()
        if (allItems.isEmpty()) return

        val (protectedItems, programmedItems) = allItems.partition { it.isManual || it.orderIndex == 0 }
        if (programmedItems.isEmpty()) return

        val programmedEpisodeIds = programmedItems.map { it.episodeId }.toSet()
        val allEpisodes = queueDao.getQueueEpisodesSync()
        val programmedEpisodes = allEpisodes.filter { it.id in programmedEpisodeIds }

        val podcastsMap = podcastDao.getAllPodcastsSync().associateBy { it.id }

        val sortedProgrammed = when (rule) {
            QueueSortRule.PROGRAMMED_FAIR -> programmedEpisodes
            QueueSortRule.PRIORITY_THEN_DATE_ASC -> {
                programmedEpisodes.sortedWith(
                    compareBy<Episode> { podcastsMap[it.podcastId]?.priority ?: 3 }
                        .thenBy { it.publishedAt }
                )
            }
            QueueSortRule.PRIORITY_THEN_DATE_DESC -> {
                programmedEpisodes.sortedWith(
                    compareBy<Episode> { podcastsMap[it.podcastId]?.priority ?: 3 }
                        .thenByDescending { it.publishedAt }
                )
            }
            QueueSortRule.DATE_DESC -> programmedEpisodes.sortedByDescending { it.publishedAt }
            QueueSortRule.DATE_ASC -> programmedEpisodes.sortedBy { it.publishedAt }
        }

        val programmedItemMap = programmedItems.associateBy { it.episodeId }
        val newOrderedProgrammedItems = sortedProgrammed.mapNotNull { programmedItemMap[it.id] }

        val finalOrdered = protectedItems + newOrderedProgrammedItems
        val reindexed = finalOrdered.mapIndexed { idx, item -> item.copy(orderIndex = idx) }
        db.withTransaction {
            queueDao.clearQueue()
            queueDao.insertQueueItems(reindexed)
        }
    }

    suspend fun updatePlaybackProgress(episodeId: String, positionSec: Long, isCompleted: Boolean) = withContext(Dispatchers.IO) {
        if (isCompleted) {
            val loadedNow = PlaybackStateManager.state.value.currentEpisode?.id == episodeId
            if (loadedNow) {
                // Still loaded in the player (playing, paused, ducked or buffering in the last 2%).
                // The progress tracker reports "completed" on every 10s tick here, so just record it as
                // played, once. Deleting the download and rebuilding the queue wait until playback
                // actually moves on (PlaybackService.onEpisodeFinished) or the download sweep, so the
                // audio is never deleted while the episode can still be resumed.
                val existing = episodeDao.getEpisodeByIdSync(episodeId)
                if (existing != null && !existing.isCompleted) {
                    episodeDao.markEpisodeFinished(episodeId, System.currentTimeMillis())
                    // Drop it from the queue now (cheap, no rebuild) so a restart doesn't put a
                    // finished episode back on top.
                    queueDao.removeEpisodeFromQueue(episodeId)
                }
            } else {
                markEpisodeFinished(episodeId)
            }
        } else {
            val existing = episodeDao.getEpisodeByIdSync(episodeId)
            // Invariant I2: Never convert isCompleted = true to false except explicit mark unplayed
            if (existing != null && existing.isCompleted) {
                return@withContext
            }
            episodeDao.updatePlaybackProgress(episodeId, positionSec, false, System.currentTimeMillis())
        }
    }

    suspend fun markEpisodeFinished(episodeId: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        episodeDao.markEpisodeFinished(episodeId, now)
        queueDao.removeEpisodeFromQueue(episodeId)
        try {
            com.eagertolisten.app.data.download.PodcastDownloadManager(appContext).deleteDownloadedEpisode(episodeId)
        } catch (_: Exception) {}
        generateProgrammedQueue()
    }

    suspend fun markEpisodeSkipped(episodeId: String, positionSec: Long) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        episodeDao.markEpisodeSkipped(episodeId, positionSec, now)
        queueDao.removeEpisodeFromQueue(episodeId)
        generateProgrammedQueue()
    }

    suspend fun markEpisodeUnplayed(episodeId: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        episodeDao.markEpisodeUnplayed(episodeId, now)
        generateProgrammedQueue()
    }

    /**
     * Adds a folder of audio files on this device as a show (Settings > Import). The caller must
     * have taken a persistable read permission on [treeUri]. Files are never modified or deleted.
     */
    suspend fun addLocalFolder(treeUri: android.net.Uri): Result<Podcast> = withContext(Dispatchers.IO) {
        val result = com.eagertolisten.app.data.local.LocalFolderScanner(appContext).addFolder(treeUri)
        if (result.isSuccess) {
            try {
                generateProgrammedQueue()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PodcastRepository", "Queue rebuild after adding local folder failed", e)
            }
        }
        result
    }

    /** Marks one episode played or unplayed (episode overflow menu). */
    suspend fun setEpisodePlayed(episodeId: String, played: Boolean) {
        if (played) markEpisodeFinished(episodeId) else markEpisodeUnplayed(episodeId)
    }

    /**
     * Marks [episodeId] and every older (or newer) episode in the same feed as played.
     * Episodes with no publish date are only included when they're the one picked.
     * Returns how many episodes changed.
     */
    suspend fun markFeedEpisodesPlayed(episodeId: String, includeOlder: Boolean): Int = withContext(Dispatchers.IO) {
        val anchor = episodeDao.getEpisodeByIdSync(episodeId) ?: return@withContext 0
        val now = System.currentTimeMillis()
        val changed = db.withTransaction {
            var count = 0
            if (!anchor.isCompleted) {
                count += episodeDao.markEpisodeFinished(anchor.id, now)
            }
            if (anchor.publishedAt > 0) {
                count += if (includeOlder) {
                    episodeDao.markEpisodesPlayedUpTo(anchor.podcastId, anchor.publishedAt, now)
                } else {
                    episodeDao.markEpisodesPlayedFrom(anchor.podcastId, anchor.publishedAt, now)
                }
            }
            queueDao.purgeCompletedFromQueue()
            count
        }
        if (changed > 0) {
            val downloadMgr = com.eagertolisten.app.data.download.PodcastDownloadManager(appContext)
            for (id in episodeDao.getDownloadedIdsCompletedAt(anchor.podcastId, now)) {
                try {
                    downloadMgr.deleteDownloadedEpisode(id)
                } catch (_: Exception) {}
            }
            generateProgrammedQueue()
        }
        changed
    }

    suspend fun unsubscribe(podcastId: String) = withContext(Dispatchers.IO) {
        // Look up by the stored ID first; fall back to the normalized feed URL for callers passing a raw URL
        val podcast = podcastDao.getPodcastByIdSync(podcastId)
            ?: podcastDao.getPodcastByFeedUrlSync(com.eagertolisten.app.data.model.FeedUrl.normalize(podcastId))
            ?: return@withContext
        val cleanId = podcast.id
        podcastDao.softUnsubscribe(cleanId)
        val queueItems = queueDao.getAllQueueItemsSync().filter { it.podcastId == cleanId }
        val downloadMgr = com.eagertolisten.app.data.download.PodcastDownloadManager(appContext)
        for (item in queueItems) {
            queueDao.removeEpisodeFromQueue(item.episodeId)
            try {
                downloadMgr.deleteDownloadedEpisode(item.episodeId)
            } catch (_: Exception) {}
        }
        val episodes = episodeDao.getEpisodesForPodcastSync(cleanId)
        for (ep in episodes) {
            try {
                downloadMgr.deleteDownloadedEpisode(ep.id)
            } catch (_: Exception) {}
        }
        generateProgrammedQueue()
    }

    suspend fun updatePodcastSpeed(podcastId: String, speed: Float) = withContext(Dispatchers.IO) {
        podcastDao.updatePodcastSpeed(podcastId, speed, System.currentTimeMillis())
    }

    suspend fun permanentlyDeleteRemovedPodcasts() = withContext(Dispatchers.IO) {
        podcastDao.deletePermanentlyRemovedPodcasts()
    }

    // Podcasting 2.0: Fetch interactive transcript
    suspend fun fetchTranscript(episode: Episode): List<TranscriptCue> = withContext(Dispatchers.IO) {
        val url = episode.transcriptUrl ?: return@withContext emptyList()
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val text = response.body?.string() ?: return@withContext emptyList()
                transcriptParser.parse(text, episode.transcriptType, episode.durationSeconds)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Podcasting 2.0: Fetch chapters (with fallback to shownotes description timestamps)
    suspend fun fetchChapters(episode: Episode): List<Chapter> = withContext(Dispatchers.IO) {
        val url = episode.chaptersUrl
        if (!url.isNullOrBlank()) {
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = response.body?.string()
                        if (!json.isNullOrBlank()) {
                            val parsed = chapterParser.parse(json)
                            if (parsed.isNotEmpty()) return@withContext parsed
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Fallback: parse chapters from episode description / shownotes (e.g. Riverside.fm, YouTube)
        if (!episode.description.isNullOrBlank()) {
            val descChapters = chapterParser.parseFromDescription(episode.description)
            if (descChapters.isNotEmpty()) {
                return@withContext descChapters
            }
        }

        emptyList()
    }
}
