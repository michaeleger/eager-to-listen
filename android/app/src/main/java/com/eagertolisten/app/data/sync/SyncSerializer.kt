package com.eagertolisten.app.data.sync

import com.eagertolisten.app.data.EagerDatabase
import com.eagertolisten.app.data.dao.EpisodeSyncProjection
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.model.FeedUrl
import com.eagertolisten.app.data.model.InspirationClip
import com.eagertolisten.app.data.model.PendingRemoteEpisode
import com.eagertolisten.app.data.model.Podcast
import com.eagertolisten.app.data.model.QueueItem
import org.json.JSONArray
import org.json.JSONObject

data class RemotePodcastRules(
    val priority: Int = 3,
    val playOrderStrategy: String = "EVERGREEN",
    val skipIntroSeconds: Int = 0,
    val skipOutroSeconds: Int = 0,
    val playbackSpeed: Float = 1.0f,
    val autoAddToQueue: Boolean = true,
    val maxEpisodesInQueue: Int = 5,
    val consecutivePlayLimit: Int = -1,
    val newsRetentionDays: Int = 3,
    val category: String = "General",
    val customDonationUrl: String? = null
)

data class RemotePodcast(
    val id: String,
    val feedUrl: String,
    val title: String,
    val artworkUrl: String = "",
    val author: String = "",
    val description: String = "",
    val rules: RemotePodcastRules = RemotePodcastRules(),
    val settingsUpdatedAt: Long = 0L,
    val unsubscribedAt: Long? = null
)

data class RemoteEpisode(
    val id: String,
    val pos: Long = 0L,
    val completed: Boolean = false,
    val completedAt: Long? = null,
    val dismissed: Boolean = false,
    val dismissReason: String? = null,
    val skippedAt: Long? = null,
    val skipCount: Int = 0,
    val updatedAt: Long = 0L,
    val unplayedExplicit: Boolean = false
)

data class RemoteManualQueueItem(
    val episodeId: String,
    val manualIndex: Int = 0,
    val updatedAt: Long = 0L
)

data class RemoteSyncState(
    val format: Int,
    val deviceId: String,
    val writtenAt: Long,
    val podcasts: List<RemotePodcast>,
    val episodes: List<RemoteEpisode>,
    val manualQueue: List<RemoteManualQueueItem>,
    val inspirations: List<InspirationClip>
)

interface SyncDatabaseOps {
    suspend fun getAllPodcastsIncludingUnsubscribed(): List<Podcast>
    suspend fun getEpisodesForSync(): List<EpisodeSyncProjection>
    suspend fun getPinnedItems(): List<QueueItem>
    suspend fun getAllInspirations(): List<InspirationClip>
    suspend fun getPodcastById(id: String): Podcast?
    suspend fun getPodcastByFeedUrl(url: String): Podcast?
    suspend fun updateFeedRules(
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
    ): Int
    suspend fun softUnsubscribe(id: String, timestamp: Long): Int
    suspend fun clearUnsubscribed(id: String): Int
    suspend fun insertPodcast(podcast: Podcast)
    suspend fun getEpisodeById(id: String): Episode?
    suspend fun updateEpisodeSyncState(
        episodeId: String,
        pos: Long,
        completed: Boolean,
        completedAt: Long?,
        dismissed: Boolean,
        dismissReason: String?,
        skippedAt: Long?,
        skipCount: Int,
        updatedAt: Long
    ): Int
    suspend fun insertOrUpdatePendingEpisodes(episodes: List<PendingRemoteEpisode>)
    suspend fun getAllQueueItems(): List<QueueItem>
    suspend fun insertQueueItem(item: QueueItem)
    suspend fun removeEpisodesForPodcast(podcastId: String): Int
    suspend fun upsertInspirations(clips: List<InspirationClip>)
    suspend fun getQueueCount(): Int
}

class RoomSyncDatabaseOps(private val db: EagerDatabase) : SyncDatabaseOps {
    override suspend fun getAllPodcastsIncludingUnsubscribed(): List<Podcast> =
        db.podcastDao().getAllPodcastsIncludingUnsubscribedSync()

    override suspend fun getEpisodesForSync(): List<EpisodeSyncProjection> =
        db.episodeDao().getEpisodesForSync()

    override suspend fun getPinnedItems(): List<QueueItem> =
        db.queueDao().getPinnedItemsSync()

    override suspend fun getAllInspirations(): List<InspirationClip> =
        db.inspirationDao().getAllInspirationsSync()

    override suspend fun getPodcastById(id: String): Podcast? =
        db.podcastDao().getPodcastByIdSync(id)

    override suspend fun getPodcastByFeedUrl(url: String): Podcast? =
        db.podcastDao().getPodcastByFeedUrlSync(url)

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
    ): Int = db.podcastDao().updateFeedRules(
        podcastId, priority, skipIntro, skipOutro, speed, autoAdd, maxEpisodes,
        category, customDonationUrl, playOrderStrategy, newsRetentionDays,
        consecutivePlayLimit, settingsUpdatedAt
    )

    override suspend fun softUnsubscribe(id: String, timestamp: Long): Int =
        db.podcastDao().softUnsubscribe(id, timestamp)

    override suspend fun clearUnsubscribed(id: String): Int =
        db.podcastDao().clearUnsubscribed(id)

    override suspend fun insertPodcast(podcast: Podcast) =
        db.podcastDao().insertPodcast(podcast)

    override suspend fun getEpisodeById(id: String): Episode? =
        db.episodeDao().getEpisodeByIdSync(id)

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
    ): Int = db.episodeDao().updateEpisodeSyncState(
        episodeId, pos, completed, completedAt, dismissed, dismissReason, skippedAt, skipCount, updatedAt
    )

    override suspend fun insertOrUpdatePendingEpisodes(episodes: List<PendingRemoteEpisode>) =
        db.pendingRemoteEpisodeDao().insertOrUpdate(episodes)

    override suspend fun getAllQueueItems(): List<QueueItem> =
        db.queueDao().getAllQueueItemsSync()

    override suspend fun insertQueueItem(item: QueueItem) =
        db.queueDao().insertQueueItem(item)

    override suspend fun removeEpisodesForPodcast(podcastId: String): Int =
        db.queueDao().removeEpisodesForPodcast(podcastId)

    override suspend fun upsertInspirations(clips: List<InspirationClip>) =
        db.inspirationDao().upsertInspirations(clips)

    override suspend fun getQueueCount(): Int =
        db.queueDao().getQueueCount()
}

object SyncSerializer {

    const val CURRENT_FORMAT = 2

    suspend fun serializeV2(
        db: EagerDatabase,
        deviceId: String,
        writtenAt: Long = System.currentTimeMillis()
    ): JSONObject = serializeV2(RoomSyncDatabaseOps(db), deviceId, writtenAt)

    suspend fun serializeV2(
        ops: SyncDatabaseOps,
        deviceId: String,
        writtenAt: Long = System.currentTimeMillis()
    ): JSONObject {
        val podcasts = ops.getAllPodcastsIncludingUnsubscribed()
        val episodes = ops.getEpisodesForSync()
        val manualQueue = ops.getPinnedItems()
        val inspirations = ops.getAllInspirations()

        return JSONObject().apply {
            put("format", CURRENT_FORMAT)
            put("deviceId", deviceId)
            put("writtenAt", writtenAt)

            val pArray = JSONArray()
            podcasts.forEach { p ->
                pArray.put(JSONObject().apply {
                    put("id", p.id)
                    put("feedUrl", p.feedUrl)
                    put("title", p.title)
                    put("artworkUrl", p.artworkUrl)
                    put("author", p.author)
                    put("description", p.description)
                    put("settingsUpdatedAt", p.settingsUpdatedAt)
                    if (p.unsubscribedAt != null) {
                        put("unsubscribedAt", p.unsubscribedAt)
                    } else {
                        put("unsubscribedAt", JSONObject.NULL)
                    }

                    put("rules", JSONObject().apply {
                        put("priority", p.priority)
                        put("playOrderStrategy", p.playOrderStrategy)
                        put("skipIntroSeconds", p.skipIntroSeconds)
                        put("skipOutroSeconds", p.skipOutroSeconds)
                        put("playbackSpeed", p.playbackSpeed.toDouble())
                        put("autoAddToQueue", p.autoAddToQueue)
                        put("maxEpisodesInQueue", p.maxEpisodesInQueue)
                        put("consecutivePlayLimit", p.consecutivePlayLimit)
                        put("newsRetentionDays", p.newsRetentionDays)
                        put("category", p.category)
                        if (p.customDonationUrl != null) {
                            put("customDonationUrl", p.customDonationUrl)
                        } else {
                            put("customDonationUrl", JSONObject.NULL)
                        }
                    })
                })
            }
            put("podcasts", pArray)

            val eArray = JSONArray()
            episodes.forEach { ep ->
                eArray.put(JSONObject().apply {
                    put("id", ep.id)
                    put("pos", ep.playbackPositionSeconds)
                    put("completed", ep.isCompleted)
                    if (ep.completedAt != null) put("completedAt", ep.completedAt) else put("completedAt", JSONObject.NULL)
                    put("dismissed", ep.isDismissed)
                    if (ep.dismissReason != null) put("dismissReason", ep.dismissReason) else put("dismissReason", JSONObject.NULL)
                    if (ep.skippedAt != null) put("skippedAt", ep.skippedAt) else put("skippedAt", JSONObject.NULL)
                    put("skipCount", ep.skipCount)
                    put("updatedAt", ep.updatedAt)
                    if (!ep.isCompleted && ep.playbackPositionSeconds == 0L && ep.updatedAt > 0L) {
                        put("unplayedExplicit", true)
                    }
                })
            }
            put("episodes", eArray)

            val qArray = JSONArray()
            manualQueue.forEach { q ->
                qArray.put(JSONObject().apply {
                    put("episodeId", q.episodeId)
                    put("manualIndex", q.manualIndex ?: 0)
                    put("updatedAt", q.addedAt)
                })
            }
            put("manualQueue", qArray)

            val iArray = JSONArray()
            inspirations.forEach { ins ->
                iArray.put(JSONObject().apply {
                    put("id", ins.id)
                    put("episodeId", ins.episodeId)
                    put("podcastId", ins.podcastId)
                    put("podcastTitle", ins.podcastTitle)
                    put("episodeTitle", ins.episodeTitle)
                    put("timestampSeconds", ins.timestampSeconds)
                    put("audioUrl", ins.audioUrl)
                    put("note", ins.note)
                    put("quoteText", ins.quoteText)
                    put("tags", ins.tags)
                    put("createdAt", ins.createdAt)
                })
            }
            put("inspirations", iArray)
        }
    }

    fun parseJson(json: JSONObject): RemoteSyncState {
        val format = json.optInt("format", json.optInt("version", 1))
        val deviceId = json.optString("deviceId", "remote")
        val writtenAt = json.optLong("writtenAt", json.optLong("timestamp", System.currentTimeMillis()))

        val podcastList = mutableListOf<RemotePodcast>()
        val pArray = json.optJSONArray("podcasts")
        if (pArray != null) {
            for (i in 0 until pArray.length()) {
                val obj = pArray.getJSONObject(i)
                val id = obj.getString("id")
                val feedUrl = obj.optString("feedUrl", id)
                val title = obj.optString("title", "Podcast")
                val artworkUrl = obj.optString("artworkUrl", "")
                val author = obj.optString("author", "")
                val description = obj.optString("description", "")
                val settingsUpdatedAt = obj.optLong("settingsUpdatedAt", 0L)
                val unsubscribedAt = if (obj.has("unsubscribedAt") && !obj.isNull("unsubscribedAt")) {
                    obj.optLong("unsubscribedAt")
                } else null

                val rulesObj = obj.optJSONObject("rules")
                val rules = if (rulesObj != null) {
                    RemotePodcastRules(
                        priority = rulesObj.optInt("priority", 3),
                        playOrderStrategy = rulesObj.optString("playOrderStrategy", "EVERGREEN"),
                        skipIntroSeconds = rulesObj.optInt("skipIntroSeconds", 0),
                        skipOutroSeconds = rulesObj.optInt("skipOutroSeconds", 0),
                        playbackSpeed = rulesObj.optDouble("playbackSpeed", 1.0).toFloat(),
                        autoAddToQueue = rulesObj.optBoolean("autoAddToQueue", true),
                        maxEpisodesInQueue = rulesObj.optInt("maxEpisodesInQueue", 5),
                        consecutivePlayLimit = rulesObj.optInt("consecutivePlayLimit", -1),
                        newsRetentionDays = rulesObj.optInt("newsRetentionDays", 3),
                        category = rulesObj.optString("category", "General"),
                        customDonationUrl = if (rulesObj.has("customDonationUrl") && !rulesObj.isNull("customDonationUrl")) {
                            rulesObj.optString("customDonationUrl")
                        } else null
                    )
                } else {
                    RemotePodcastRules(
                        priority = obj.optInt("priority", 3),
                        playOrderStrategy = obj.optString("playOrderStrategy", "EVERGREEN"),
                        skipIntroSeconds = obj.optInt("skipIntroSeconds", 0),
                        skipOutroSeconds = obj.optInt("skipOutroSeconds", 0),
                        playbackSpeed = obj.optDouble("playbackSpeed", 1.0).toFloat(),
                        autoAddToQueue = obj.optBoolean("autoAddToQueue", true),
                        maxEpisodesInQueue = obj.optInt("maxEpisodesInQueue", 5),
                        consecutivePlayLimit = obj.optInt("consecutivePlayLimit", -1),
                        newsRetentionDays = obj.optInt("newsRetentionDays", 3),
                        category = obj.optString("category", "General"),
                        customDonationUrl = if (obj.has("customDonationUrl") && !obj.isNull("customDonationUrl")) {
                            obj.optString("customDonationUrl")
                        } else null
                    )
                }

                podcastList.add(
                    RemotePodcast(
                        id = id,
                        feedUrl = feedUrl,
                        title = title,
                        artworkUrl = artworkUrl,
                        author = author,
                        description = description,
                        rules = rules,
                        settingsUpdatedAt = settingsUpdatedAt,
                        unsubscribedAt = unsubscribedAt
                    )
                )
            }
        }

        val episodeList = mutableListOf<RemoteEpisode>()
        val eArray = json.optJSONArray("episodes")
        if (eArray != null) {
            for (i in 0 until eArray.length()) {
                val obj = eArray.getJSONObject(i)
                val id = obj.getString("id")
                val pos = obj.optLong("pos", obj.optLong("playbackPositionSeconds", 0L))
                val completed = obj.optBoolean("completed", obj.optBoolean("isCompleted", false))
                val completedAt = if (obj.has("completedAt") && !obj.isNull("completedAt")) obj.optLong("completedAt") else null
                val dismissed = obj.optBoolean("dismissed", obj.optBoolean("isDismissed", false))
                val dismissReason = if (obj.has("dismissReason") && !obj.isNull("dismissReason")) obj.optString("dismissReason") else null
                val skippedAt = if (obj.has("skippedAt") && !obj.isNull("skippedAt")) obj.optLong("skippedAt") else null
                val skipCount = obj.optInt("skipCount", 0)
                val updatedAt = obj.optLong("updatedAt", 0L)
                val unplayedExplicit = obj.optBoolean("unplayedExplicit", false)

                episodeList.add(
                    RemoteEpisode(
                        id = id,
                        pos = pos,
                        completed = completed,
                        completedAt = completedAt,
                        dismissed = dismissed,
                        dismissReason = dismissReason,
                        skippedAt = skippedAt,
                        skipCount = skipCount,
                        updatedAt = updatedAt,
                        unplayedExplicit = unplayedExplicit
                    )
                )
            }
        }

        val manualQueueList = mutableListOf<RemoteManualQueueItem>()
        val qArray = json.optJSONArray("manualQueue")
        if (qArray != null) {
            for (i in 0 until qArray.length()) {
                val item = qArray.opt(i)
                if (item is JSONObject) {
                    manualQueueList.add(
                        RemoteManualQueueItem(
                            episodeId = item.getString("episodeId"),
                            manualIndex = item.optInt("manualIndex", i),
                            updatedAt = item.optLong("updatedAt", 0L)
                        )
                    )
                } else if (item is String) {
                    manualQueueList.add(
                        RemoteManualQueueItem(
                            episodeId = item,
                            manualIndex = i,
                            updatedAt = 0L
                        )
                    )
                }
            }
        }

        val inspirationList = mutableListOf<InspirationClip>()
        val iArray = json.optJSONArray("inspirations")
        if (iArray != null) {
            for (i in 0 until iArray.length()) {
                val obj = iArray.getJSONObject(i)
                inspirationList.add(
                    InspirationClip(
                        id = obj.getString("id"),
                        episodeId = obj.optString("episodeId", ""),
                        podcastId = obj.optString("podcastId", ""),
                        podcastTitle = obj.optString("podcastTitle", ""),
                        episodeTitle = obj.optString("episodeTitle", ""),
                        timestampSeconds = obj.optLong("timestampSeconds", 0L),
                        audioUrl = obj.optString("audioUrl", ""),
                        note = obj.optString("note", ""),
                        quoteText = obj.optString("quoteText", ""),
                        tags = obj.optString("tags", ""),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        }

        return RemoteSyncState(
            format = format,
            deviceId = deviceId,
            writtenAt = writtenAt,
            podcasts = podcastList,
            episodes = episodeList,
            manualQueue = manualQueueList,
            inspirations = inspirationList
        )
    }

    suspend fun mergeState(
        db: EagerDatabase,
        remoteState: RemoteSyncState,
        onNewPodcastSubscribed: (suspend (String) -> Unit)? = null
    ): SyncSummary = mergeState(RoomSyncDatabaseOps(db), remoteState, onNewPodcastSubscribed)

    suspend fun mergeState(
        ops: SyncDatabaseOps,
        remoteState: RemoteSyncState,
        onNewPodcastSubscribed: (suspend (String) -> Unit)? = null
    ): SyncSummary {
        var mergedPodcasts = 0
        var mergedEpisodes = 0
        var mergedQueue = 0
        val newlySubscribedPodcasts = mutableListOf<String>()

        // 1. Merge Podcasts
        for (rp in remoteState.podcasts) {
            val normalizedUrl = FeedUrl.normalize(rp.feedUrl)
            val existing = ops.getPodcastById(rp.id)
                ?: if (normalizedUrl.isNotBlank()) ops.getPodcastByFeedUrl(normalizedUrl) else null

            if (existing != null) {
                // Rules: Last-writer-wins by settingsUpdatedAt
                if (rp.settingsUpdatedAt > existing.settingsUpdatedAt) {
                    ops.updateFeedRules(
                        podcastId = existing.id,
                        priority = rp.rules.priority,
                        skipIntro = rp.rules.skipIntroSeconds,
                        skipOutro = rp.rules.skipOutroSeconds,
                        speed = rp.rules.playbackSpeed,
                        autoAdd = rp.rules.autoAddToQueue,
                        maxEpisodes = rp.rules.maxEpisodesInQueue,
                        category = rp.rules.category,
                        customDonationUrl = rp.rules.customDonationUrl,
                        playOrderStrategy = rp.rules.playOrderStrategy,
                        newsRetentionDays = rp.rules.newsRetentionDays,
                        consecutivePlayLimit = rp.rules.consecutivePlayLimit,
                        settingsUpdatedAt = rp.settingsUpdatedAt
                    )
                }

                // Unsubscribe tombstone: the newer of (subscribe, unsubscribe) wins
                if (rp.unsubscribedAt != null) {
                    if (existing.unsubscribedAt == null) {
                        if (existing.subscribedAt > rp.unsubscribedAt) {
                            // Local re-subscribed after remote unsubscribe; keep local subscribed
                        } else {
                            ops.softUnsubscribe(existing.id, rp.unsubscribedAt)
                            ops.removeEpisodesForPodcast(existing.id)
                        }
                    }
                } else {
                    if (existing.unsubscribedAt != null) {
                        if (rp.settingsUpdatedAt > existing.unsubscribedAt) {
                            ops.clearUnsubscribed(existing.id)
                        }
                    }
                }
            } else {
                if (rp.unsubscribedAt != null) {
                    val p = Podcast(
                        id = rp.id,
                        title = rp.title,
                        feedUrl = rp.feedUrl,
                        artworkUrl = rp.artworkUrl,
                        author = rp.author,
                        description = rp.description,
                        priority = rp.rules.priority,
                        skipIntroSeconds = rp.rules.skipIntroSeconds,
                        skipOutroSeconds = rp.rules.skipOutroSeconds,
                        playbackSpeed = rp.rules.playbackSpeed,
                        autoAddToQueue = rp.rules.autoAddToQueue,
                        maxEpisodesInQueue = rp.rules.maxEpisodesInQueue,
                        category = rp.rules.category,
                        playOrderStrategy = rp.rules.playOrderStrategy,
                        newsRetentionDays = rp.rules.newsRetentionDays,
                        consecutivePlayLimit = rp.rules.consecutivePlayLimit,
                        subscribedAt = rp.settingsUpdatedAt,
                        unsubscribedAt = rp.unsubscribedAt,
                        settingsUpdatedAt = rp.settingsUpdatedAt
                    )
                    ops.insertPodcast(p)
                } else {
                    val p = Podcast(
                        id = rp.id,
                        title = rp.title,
                        feedUrl = rp.feedUrl,
                        artworkUrl = rp.artworkUrl,
                        author = rp.author,
                        description = rp.description,
                        priority = rp.rules.priority,
                        skipIntroSeconds = rp.rules.skipIntroSeconds,
                        skipOutroSeconds = rp.rules.skipOutroSeconds,
                        playbackSpeed = rp.rules.playbackSpeed,
                        autoAddToQueue = rp.rules.autoAddToQueue,
                        maxEpisodesInQueue = rp.rules.maxEpisodesInQueue,
                        category = rp.rules.category,
                        playOrderStrategy = rp.rules.playOrderStrategy,
                        newsRetentionDays = rp.rules.newsRetentionDays,
                        consecutivePlayLimit = rp.rules.consecutivePlayLimit,
                        subscribedAt = rp.settingsUpdatedAt,
                        unsubscribedAt = null,
                        settingsUpdatedAt = rp.settingsUpdatedAt
                    )
                    ops.insertPodcast(p)
                    mergedPodcasts++
                    newlySubscribedPodcasts.add(rp.id)
                }
            }
        }

        // 2. Merge Episodes
        for (re in remoteState.episodes) {
            val localEp = ops.getEpisodeById(re.id)
            if (localEp == null) {
                val podcastId = if (re.id.contains("::")) re.id.substringBefore("::") else ""
                ops.insertOrUpdatePendingEpisodes(
                    listOf(
                        PendingRemoteEpisode(
                            episodeId = re.id,
                            podcastId = podcastId,
                            playbackPositionSeconds = re.pos,
                            isCompleted = re.completed,
                            completedAt = re.completedAt,
                            isDismissed = re.dismissed,
                            dismissReason = re.dismissReason,
                            skippedAt = re.skippedAt,
                            skipCount = re.skipCount,
                            updatedAt = re.updatedAt
                        )
                    )
                )
            } else {
                var newCompleted = localEp.isCompleted
                var newCompletedAt = localEp.completedAt
                var newPos = localEp.playbackPositionSeconds
                var newDismissed = localEp.isDismissed
                var newDismissReason = localEp.dismissReason
                var newSkippedAt = localEp.skippedAt
                var newSkipCount = localEp.skipCount
                val newUpdatedAt = maxOf(localEp.updatedAt, re.updatedAt)

                // Completion & explicit unplayed
                if (localEp.isCompleted && !re.completed) {
                    val localCompTime = localEp.completedAt ?: localEp.updatedAt
                    if (re.unplayedExplicit && re.updatedAt > localCompTime) {
                        newCompleted = false
                        newCompletedAt = null
                        newPos = 0L
                        newDismissed = false
                        newDismissReason = "UNPLAYED"
                        newSkippedAt = null
                        newSkipCount = 0
                    } else {
                        newCompleted = true
                    }
                } else if (!localEp.isCompleted && re.completed) {
                    newCompleted = true
                    newCompletedAt = re.completedAt ?: re.updatedAt
                    newPos = if (localEp.durationSeconds > 0) localEp.durationSeconds else maxOf(localEp.playbackPositionSeconds, re.pos)
                } else if (localEp.isCompleted && re.completed) {
                    newCompleted = true
                    newCompletedAt = maxOf(localEp.completedAt ?: 0L, re.completedAt ?: 0L).takeIf { it > 0L }
                }

                // Playback Position (if not completed)
                if (!newCompleted) {
                    if (re.updatedAt > localEp.updatedAt) {
                        newPos = re.pos
                    }
                }

                // Dismissed
                if (re.dismissed) {
                    newDismissed = true
                    if (re.updatedAt > localEp.updatedAt || !localEp.isDismissed) {
                        newDismissReason = re.dismissReason ?: localEp.dismissReason
                    }
                }

                // Skips
                if (newCompleted || (re.unplayedExplicit && re.updatedAt > (localEp.completedAt ?: localEp.updatedAt))) {
                    // No skips
                } else {
                    newSkipCount = maxOf(localEp.skipCount, re.skipCount)
                    val localSkip = localEp.skippedAt ?: 0L
                    val remoteSkip = re.skippedAt ?: 0L
                    newSkippedAt = maxOf(localSkip, remoteSkip).takeIf { it > 0L }
                }

                val changed = (newCompleted != localEp.isCompleted) ||
                              (newCompletedAt != localEp.completedAt) ||
                              (newPos != localEp.playbackPositionSeconds) ||
                              (newDismissed != localEp.isDismissed) ||
                              (newDismissReason != localEp.dismissReason) ||
                              (newSkippedAt != localEp.skippedAt) ||
                              (newSkipCount != localEp.skipCount)

                if (changed) {
                    ops.updateEpisodeSyncState(
                        episodeId = re.id,
                        pos = newPos,
                        completed = newCompleted,
                        completedAt = newCompletedAt,
                        dismissed = newDismissed,
                        dismissReason = newDismissReason,
                        skippedAt = newSkippedAt,
                        skipCount = newSkipCount,
                        updatedAt = newUpdatedAt
                    )
                    mergedEpisodes++
                }
            }
        }

        // 3. Merge Manual Queue
        for (rmq in remoteState.manualQueue) {
            val localEp = ops.getEpisodeById(rmq.episodeId)
            if (localEp != null && !localEp.isCompleted && !localEp.isDismissed) {
                val existingQ = ops.getAllQueueItems().find { it.episodeId == rmq.episodeId }
                if (existingQ == null) {
                    ops.insertQueueItem(
                        QueueItem(
                            episodeId = rmq.episodeId,
                            podcastId = localEp.podcastId,
                            orderIndex = rmq.manualIndex,
                            addedAt = rmq.updatedAt,
                            isManual = true,
                            manualIndex = rmq.manualIndex
                        )
                    )
                    mergedQueue++
                } else if (rmq.updatedAt > existingQ.addedAt) {
                    ops.insertQueueItem(
                        existingQ.copy(
                            isManual = true,
                            manualIndex = rmq.manualIndex,
                            orderIndex = rmq.manualIndex,
                            addedAt = rmq.updatedAt
                        )
                    )
                }
            }
        }

        // 4. Merge Inspirations
        if (remoteState.inspirations.isNotEmpty()) {
            ops.upsertInspirations(remoteState.inspirations)
        }

        // Trigger callback for any new podcasts that need feed fetching
        if (onNewPodcastSubscribed != null) {
            for (newPodcastId in newlySubscribedPodcasts) {
                try {
                    onNewPodcastSubscribed(newPodcastId)
                } catch (_: Exception) {}
            }
        }

        return SyncSummary(
            podcastsSynced = mergedPodcasts,
            episodesSynced = mergedEpisodes,
            queueCount = ops.getQueueCount(),
            message = "Synced $mergedPodcasts podcasts, $mergedEpisodes episodes, and merged queue"
        )
    }
}
