package com.eagertolisten.app.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.eagertolisten.app.data.EagerDatabase
import com.eagertolisten.app.data.model.Chapter
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.model.Podcast
import com.eagertolisten.app.data.model.QueueItem
import com.eagertolisten.app.data.model.TranscriptCue
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class QueueSortRule {
    PROGRAMMED_FAIR,          // Smart Fair Interleaved Order (Play Orders + Feed Limit above priority)
    PRIORITY_THEN_DATE_ASC,   // Radio U Classic: Priority 1 first, then oldest to newest
    PRIORITY_THEN_DATE_DESC,  // Priority 1 first, then newest
    DATE_DESC,                // Newest first regardless of priority
    DATE_ASC,                 // Oldest first
    MANUAL                    // Custom drag-and-drop order
}

class PodcastRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = EagerDatabase.getInstance(context)
    private val podcastDao = db.podcastDao()
    private val episodeDao = db.episodeDao()
    private val queueDao = db.queueDao()
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
        const val USER_AGENT = "EagerToListen/0.9.0-alpha (Android; +https://eagertobehealthy.com/eager-to-listen)"
        const val ACCEPT_HEADER = "application/rss+xml, application/xml, text/xml, application/atom+xml, text/html;q=0.9, */*;q=0.8"

        private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                }

                Log.d("EagerRefresh", "Saved ${episodes.size} episodes for '${podcast.title}' (ID: ${podcast.id})")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e("EagerRefresh", "Exception refreshing '${podcast.title}': ${e.message}", e)
            Result.failure(e)
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
        val podcasts = podcastDao.getAllPodcastsSync()
        if (podcasts.isEmpty()) return@withContext Pair(0, emptyList())

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

        // Rebuild programmed queue once after all feeds are refreshed
        generateProgrammedQueue()
        Log.d("EagerRefresh", "refreshAllFeeds finished: $successCount succeeded, ${errors.size} failed")

        Pair(successCount, errors)
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
            val cutoff = System.currentTimeMillis() - (newsRetentionDays.coerceAtLeast(1).toLong() * 86_400_000L)
            episodeDao.purgeExpiredNewsEpisodesForPodcast(podcastId, cutoff, System.currentTimeMillis())
            queueDao.removeExpiredEpisodesForPodcast(podcastId, cutoff)
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

    suspend fun reorderQueue(orderedEpisodeIds: List<String>) = withContext(Dispatchers.IO) {
        setSavedSortRule(QueueSortRule.MANUAL)
        queueDao.reorderQueue(orderedEpisodeIds)
    }

    suspend fun purgeExpiredNewsEpisodes() = withContext(Dispatchers.IO) {
        val podcasts = podcastDao.getAllPodcastsSync()
        val defaultNewsDays = getDefaultNewsRetentionDays()
        val now = System.currentTimeMillis()

        for (podcast in podcasts) {
            if (podcast.playOrderStrategy == "NEWS") {
                val retentionDays = if (podcast.newsRetentionDays > 0) podcast.newsRetentionDays else defaultNewsDays
                val cutoff = now - (retentionDays.toLong() * 86_400_000L)
                val purgedEpisodes = episodeDao.purgeExpiredNewsEpisodesForPodcast(podcast.id, cutoff, now)
                val removedQueue = queueDao.removeExpiredEpisodesForPodcast(podcast.id, cutoff)
                if (purgedEpisodes > 0 || removedQueue > 0) {
                    Log.i("PodcastRepository", "Purged expired news for '${podcast.title}': $purgedEpisodes episodes marked played, $removedQueue removed from queue (cutoff: $cutoff)")
                }
            }
        }
    }

    // Fair Interleaving Queue Algorithm:
    // Considers 4 Play Orders:
    // 1. Chronological (Serial stories & classes: oldest unplayed first)
    // 2. Reverse Chronological (Newest unplayed first)
    // 3. News (Fresh updates first; last X days unplayed in chronological order)
    // 4. Evergreen / Knowledge (Newest unplayed first, then next newest unplayed)
    //
    // Feed Play Limit ("Anti-Binge" Diversity):
    // Taken at a HIGHER LEVEL than importance (priority): Each feed can contribute at most
    // its consecutive episode limit (e.g. 1) before the queue engine rotates to other eligible feeds.
    // Within each tier, feeds are ordered by least-recently completed podcast first so feeds rotate
    // fairly across app restarts and feed refreshes rather than resetting alphabetically.
    suspend fun generateProgrammedQueue() = withContext(Dispatchers.IO) {
        purgeExpiredNewsEpisodes()

        val podcasts = podcastDao.getAllPodcastsSync()
        if (podcasts.isEmpty()) return@withContext

        val globalLimit = getGlobalConsecutivePlayLimit()
        val defaultNewsDays = getDefaultNewsRetentionDays()
        val now = System.currentTimeMillis()

        // Track when each podcast last had an episode completed so same-tier feeds rotate fairly
        val recentPlayed = episodeDao.getRecentPlayedEpisodesSync(200)
        val lastPlayedByPodcast = mutableMapOf<String, Long>()
        for (ep in recentPlayed) {
            val prev = lastPlayedByPodcast[ep.podcastId] ?: 0L
            if (ep.updatedAt > prev) {
                lastPlayedByPodcast[ep.podcastId] = ep.updatedAt
            }
        }

        // Identify currently playing or top in-progress episode to keep pinned at #1
        val existingQueue = queueDao.getQueueEpisodesSync()
        val activeStateEp = PlaybackStateManager.state.value.currentEpisode?.let { current ->
            episodeDao.getEpisodeByIdSync(current.id) ?: current
        }
        val pinnedEpisode: Episode? = when {
            activeStateEp != null && !activeStateEp.isCompleted && !activeStateEp.isLive && activeStateEp.liveStatus == null -> activeStateEp
            existingQueue.firstOrNull()?.let { !it.isCompleted && !it.isLive && it.liveStatus == null && it.playbackPositionSeconds > 0 } == true -> existingQueue.first()
            else -> null
        }

        // Feeds that have autoAddToQueue enabled
        val eligibleFeeds = podcasts.filter { it.autoAddToQueue }
        val feedEpisodeQueues = mutableMapOf<String, MutableList<Episode>>()
        val feedLimits = mutableMapOf<String, Int>()
        val feedTiers = mutableMapOf<String, Int>() // 0 for fresh news, 1-5 for normal priority

        for (podcast in eligibleFeeds) {
            val allEpisodes = episodeDao.getEpisodesForPodcastSync(podcast.id)
            val unplayed = allEpisodes.filter { !it.isCompleted && !it.isLive && it.liveStatus == null && it.id != pinnedEpisode?.id }
            if (unplayed.isEmpty()) continue

            val strategy = try {
                com.eagertolisten.app.data.model.PlayOrderStrategy.valueOf(podcast.playOrderStrategy)
            } catch (_: Exception) {
                com.eagertolisten.app.data.model.PlayOrderStrategy.EVERGREEN
            }

            val retentionDays = if (podcast.newsRetentionDays > 0) podcast.newsRetentionDays else defaultNewsDays
            val newsCutoff = now - (retentionDays.toLong() * 86_400_000L)

            val sortedUnplayed = when (strategy) {
                com.eagertolisten.app.data.model.PlayOrderStrategy.CHRONOLOGICAL -> {
                    // Serial stories & classes: oldest unplayed first -> newest
                    unplayed.sortedBy { it.publishedAt }
                }
                com.eagertolisten.app.data.model.PlayOrderStrategy.REVERSE_CHRONOLOGICAL -> {
                    // Newest first
                    unplayed.sortedByDescending { it.publishedAt }
                }
                com.eagertolisten.app.data.model.PlayOrderStrategy.NEWS -> {
                    // Updates first: only within the last X days, in chronological order (morning before evening)
                    val (fresh, expired) = unplayed.partition { it.publishedAt >= newsCutoff }
                    for (exp in expired) {
                        episodeDao.setEpisodeCompleted(exp.id, true, now)
                        queueDao.removeEpisodeFromQueue(exp.id)
                    }
                    fresh.sortedBy { it.publishedAt }
                }
                com.eagertolisten.app.data.model.PlayOrderStrategy.EVERGREEN -> {
                    // Evergreen: newest unplayed first, then next newest
                    unplayed.sortedByDescending { it.publishedAt }
                }
            }

            if (sortedUnplayed.isEmpty()) continue

            val maxSlots = if (podcast.maxEpisodesInQueue > 0) {
                val reserved = if (pinnedEpisode?.podcastId == podcast.id) 1 else 0
                (podcast.maxEpisodesInQueue - reserved).coerceAtLeast(0)
            } else {
                Int.MAX_VALUE
            }
            val limitedEpisodes = sortedUnplayed.take(maxSlots).toMutableList()

            if (limitedEpisodes.isNotEmpty()) {
                feedEpisodeQueues[podcast.id] = limitedEpisodes
                feedLimits[podcast.id] = if (podcast.consecutivePlayLimit > 0) podcast.consecutivePlayLimit else globalLimit
                // News feeds with fresh unplayed items get Tier 0 to play updates first
                feedTiers[podcast.id] = if (strategy == com.eagertolisten.app.data.model.PlayOrderStrategy.NEWS) 0 else podcast.priority
            }
        }

        // Interleave feeds: Round-robin by tier taking up to feedLimit from each feed
        val programmedEpisodes = mutableListOf<Episode>()
        if (pinnedEpisode != null) {
            programmedEpisodes.add(pinnedEpisode)
        }

        val podcastsById = podcasts.associateBy { it.id }
        var isFirstPass = true

        while (feedEpisodeQueues.values.any { it.isNotEmpty() }) {
            val activeFeedIds = feedEpisodeQueues.filter { it.value.isNotEmpty() }.keys.sortedWith(
                compareBy<String> { feedTiers[it] ?: 3 }
                    .thenBy { id -> lastPlayedByPodcast[id] ?: 0L }
                    .thenBy { id -> podcastsById[id]?.title ?: "" }
            )

            for (feedId in activeFeedIds) {
                val epList = feedEpisodeQueues[feedId] ?: continue
                val limit = feedLimits[feedId] ?: globalLimit
                // If this feed already has the pinned #1 episode, reduce its first-pass consecutive quota by 1
                val effectiveLimit = if (isFirstPass && pinnedEpisode?.podcastId == feedId) {
                    (limit - 1).coerceAtLeast(0)
                } else {
                    limit
                }
                val countToTake = minOf(effectiveLimit, epList.size)
                for (i in 0 until countToTake) {
                    if (epList.isNotEmpty()) {
                        programmedEpisodes.add(epList.removeAt(0))
                    }
                }
            }
            isFirstPass = false
        }

        // Apply programmed items to queue atomically inside a transaction
        val newItems = programmedEpisodes.mapIndexed { index, ep ->
            QueueItem(
                episodeId = ep.id,
                podcastId = ep.podcastId,
                orderIndex = index
            )
        }
        db.withTransaction {
            queueDao.clearQueue()
            queueDao.insertQueueItems(newItems)
            queueDao.purgeLiveItemsFromQueue()
        }

        // If user selected a non-default sort rule, apply it over the refreshed queue
        val savedRule = getSavedSortRule()
        if (savedRule != QueueSortRule.PROGRAMMED_FAIR && savedRule != QueueSortRule.MANUAL) {
            applySortRuleToCurrentQueue(savedRule)
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
        val currentEpisodes = queueDao.getQueueEpisodesSync()
        if (currentEpisodes.isEmpty()) return

        val podcastsMap = podcastDao.getAllPodcastsSync().associateBy { it.id }

        val sorted = when (rule) {
            QueueSortRule.PROGRAMMED_FAIR -> currentEpisodes
            QueueSortRule.PRIORITY_THEN_DATE_ASC -> {
                currentEpisodes.sortedWith(
                    compareBy<Episode> { podcastsMap[it.podcastId]?.priority ?: 3 }
                        .thenBy { it.publishedAt }
                )
            }
            QueueSortRule.PRIORITY_THEN_DATE_DESC -> {
                currentEpisodes.sortedWith(
                    compareBy<Episode> { podcastsMap[it.podcastId]?.priority ?: 3 }
                        .thenByDescending { it.publishedAt }
                )
            }
            QueueSortRule.DATE_DESC -> currentEpisodes.sortedByDescending { it.publishedAt }
            QueueSortRule.DATE_ASC -> currentEpisodes.sortedBy { it.publishedAt }
            QueueSortRule.MANUAL -> currentEpisodes
        }

        queueDao.reorderQueue(sorted.map { it.id })
    }

    suspend fun updatePlaybackProgress(episodeId: String, positionSec: Long, isCompleted: Boolean) = withContext(Dispatchers.IO) {
        if (isCompleted) {
            markEpisodeFinished(episodeId)
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

    suspend fun unsubscribe(podcastId: String) = withContext(Dispatchers.IO) {
        val cleanId = com.eagertolisten.app.data.model.FeedUrl.normalize(podcastId).ifBlank { podcastId.trim() }
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
