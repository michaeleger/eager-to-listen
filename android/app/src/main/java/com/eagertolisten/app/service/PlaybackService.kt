package com.eagertolisten.app.service

import android.app.PendingIntent
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.common.ForwardingPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import com.eagertolisten.app.MainActivity
import com.eagertolisten.app.data.EagerDatabase
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.model.InspirationClip
import com.eagertolisten.app.data.model.Podcast
import com.eagertolisten.app.data.repository.PodcastRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var rawPlayer: ExoPlayer
    private lateinit var player: Player
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null

    private lateinit var database: EagerDatabase

    companion object {
        const val ACTION_PLAY_EPISODE = "com.eagertolisten.app.PLAY_EPISODE"
        const val ACTION_REVERT_TO_MAIN_QUEUE = "com.eagertolisten.app.REVERT_TO_MAIN_QUEUE"
        const val ACTION_PLAY_FROM_SEARCH = "com.eagertolisten.app.PLAY_FROM_SEARCH"
        const val EXTRA_EPISODE_ID = "extra_episode_id"
        const val EXTRA_PODCAST_ID = "extra_podcast_id"
        const val EXTRA_SEARCH_QUERY = "extra_search_query"

        // Android Auto & Custom Transport Actions
        const val CUSTOM_ACTION_REWIND = "com.eagertolisten.app.ACTION_REWIND"
        const val CUSTOM_ACTION_FAST_FORWARD = "com.eagertolisten.app.ACTION_FAST_FORWARD"
        const val CUSTOM_ACTION_SKIP_NEXT = "com.eagertolisten.app.ACTION_SKIP_NEXT"
        const val CUSTOM_ACTION_SAVE_INSPIRATION = "com.eagertolisten.app.ACTION_SAVE_INSPIRATION"

        // Android Auto Media Browsing Categories
        const val ROOT_ID = "root"
        const val CATEGORY_PENDING_QUEUE = "category_pending_queue"
        const val CATEGORY_HOT_LIST = "category_hot_list"
        const val CATEGORY_NEWS = "category_news"
        const val CATEGORY_SUBSCRIPTIONS = "category_subscriptions"

        private const val MAX_AUTO_BROWSE_ITEMS = 100
    }

    override fun onCreate() {
        super.onCreate()
        database = EagerDatabase.getInstance(applicationContext)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // 1. Configure deep audio buffer (30s min to 180s max, 180s back-buffer for instant rewinds)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 180_000,
                /* bufferForPlaybackMs = */ 3_000,
                /* bufferForPlaybackAfterRebufferMs = */ 15_000
            )
            .setBackBuffer(
                /* backBufferDurationMs = */ 180_000,
                /* retainBackBufferFromKeyframe = */ true
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // 2. Configure resilient HTTP datasource with 30s connect and 60s read timeout
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(60_000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(PodcastRepository.USER_AGENT)

        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        rawPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(15_000)      // 15-second rewind
            .setSeekForwardIncrementMs(90_000)   // 90-second forward skip
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // 3. Wrap in ForwardingPlayer to guarantee podcast transport controls:
        // - Keep COMMAND_SEEK_TO_NEXT / PREVIOUS advertised as available so Media3's
        //   MediaSessionStub & MediaSessionLegacyStub accept car steering wheel / Bluetooth AVRCP
        //   next/prev button presses instead of rejecting them, and route ALL of them to
        //   performSeekForward(90_000L) and performSeekBack(15_000L).
        player = object : ForwardingPlayer(rawPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_FORWARD)
                    .add(Player.COMMAND_SEEK_BACK)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                if (command == Player.COMMAND_SEEK_FORWARD ||
                    command == Player.COMMAND_SEEK_BACK ||
                    command == Player.COMMAND_SEEK_TO_NEXT ||
                    command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                ) {
                    return true
                }
                return super.isCommandAvailable(command)
            }

            override fun play() {
                if (rawPlayer.currentMediaItem == null) {
                    loadAndPlayTopQueueItem()
                } else {
                    super.play()
                }
            }

            override fun seekToNext() {
                skipToNextEpisode(markCompleted = false)
            }

            override fun seekToNextMediaItem() {
                skipToNextEpisode(markCompleted = false)
            }

            override fun seekToPrevious() {
                skipToPreviousEpisode()
            }

            override fun seekToPreviousMediaItem() {
                skipToPreviousEpisode()
            }

            override fun seekForward() {
                performSeekForward(90_000L)
            }

            override fun seekBack() {
                performSeekBack(15_000L)
            }

            override fun seekToDefaultPosition(mediaItemIndex: Int) {
                super.seekToDefaultPosition(mediaItemIndex)
            }

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                super.seekTo(mediaItemIndex, positionMs)
            }
        }

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, CustomMediaLibrarySessionCallback())
            .setSessionActivity(sessionActivityIntent)
            .setId("EagerToListenMediaLibrarySession")
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                val episodeId = mediaItem?.mediaId ?: return
                serviceScope.launch(Dispatchers.IO) {
                    val episode = database.episodeDao().getEpisodeByIdSync(episodeId) ?: return@launch
                    val podcast = database.podcastDao().getPodcastByIdSync(episode.podcastId)
                    val speed = podcast?.playbackSpeed ?: 1.0f
                    val skipIntro = podcast?.skipIntroSeconds ?: 0
                    val skipOutro = podcast?.skipOutroSeconds ?: 0

                    val prevEp = PlaybackStateManager.state.value.currentEpisode
                    if (prevEp != null && prevEp.id != episode.id) {
                        persistCurrentProgress()
                        database.queueDao().removeEpisodeFromQueue(prevEp.id)
                    }

                    launch(Dispatchers.Main) {
                        PlaybackStateManager.setEpisode(
                            episode = episode,
                            speed = speed,
                            skipIntro = skipIntro,
                            skipOutro = skipOutro,
                            isPlaying = player.isPlaying
                        )
                        player.playbackParameters = PlaybackParameters(speed)

                        // If freshly transitioning from beginning, apply saved position or skip intro
                        if (player.currentPosition < 2000L) {
                            val startPosMs = if (episode.playbackPositionSeconds > 0) {
                                episode.playbackPositionSeconds * 1000L
                            } else if (skipIntro > 0) {
                                skipIntro * 1000L
                            } else {
                                0L
                            }
                            if (startPosMs > 0 && player.currentPosition < startPosMs) {
                                player.seekTo(player.currentMediaItemIndex, startPosMs)
                            }
                        }
                    }

                    // Replenish upcoming playlist items so Android Auto always has 3-5 queued up
                    val upcoming = database.queueDao().getQueueEpisodesSync()
                        .filter { it.id != episode.id && !it.isCompleted }
                        .take(5)
                    if (upcoming.isNotEmpty()) {
                        val upcomingItems = upcoming.map { createPlayableEpisodeItem(it) }
                        launch(Dispatchers.Main) {
                            val remaining = rawPlayer.mediaItemCount - (rawPlayer.currentMediaItemIndex + 1)
                            if (remaining < 3) {
                                if (rawPlayer.mediaItemCount > rawPlayer.currentMediaItemIndex + 1) {
                                    rawPlayer.removeMediaItems(rawPlayer.currentMediaItemIndex + 1, rawPlayer.mediaItemCount)
                                }
                                rawPlayer.addMediaItems(upcomingItems)
                            }
                        }
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                PlaybackStateManager.setPlaying(isPlaying)
                LiveSessionManager.recordListeningHeartbeat(applicationContext)
                if (isPlaying) {
                    val currentEp = PlaybackStateManager.state.value.currentEpisode
                    if (currentEp?.isLive == true && LiveSessionManager.isLiveSessionExpired(applicationContext)) {
                        android.util.Log.i("PlaybackService", "Live session expired during pause (> 1 hour). Reverting to main queue.")
                        player.pause()
                        LiveSessionManager.clearLiveSession(applicationContext)
                        loadAndPlayTopQueueItem()
                        return
                    }
                    stopLiveInactivityWatchdog()
                    startProgressTracker()
                } else {
                    stopProgressTracker()
                    persistCurrentProgress()
                    val currentEp = PlaybackStateManager.state.value.currentEpisode
                    if (currentEp?.isLive == true) {
                        startLiveInactivityWatchdog()
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> PlaybackStateManager.setBuffering(true)
                    Player.STATE_READY -> PlaybackStateManager.setBuffering(false)
                    Player.STATE_ENDED -> {
                        PlaybackStateManager.setPlaying(false)
                        val currentEp = PlaybackStateManager.state.value.currentEpisode
                        if (currentEp?.isLive == true || currentEp?.liveStatus != null) {
                            android.util.Log.i("PlaybackService", "Live stream ended. Automatically reverting to main queue.")
                            LiveSessionManager.clearLiveSession(applicationContext)
                            serviceScope.launch(Dispatchers.IO) {
                                database.episodeDao().updatePlaybackProgress(
                                    episodeId = currentEp.id,
                                    positionSec = 0,
                                    isCompleted = true,
                                    updatedAt = System.currentTimeMillis()
                                )
                                database.queueDao().removeEpisodeFromQueue(currentEp.id)
                                database.queueDao().purgeLiveItemsFromQueue()
                                launch(Dispatchers.Main) {
                                    loadAndPlayTopQueueItem()
                                }
                            }
                        } else {
                            handleEpisodeEnded()
                        }
                    }
                    else -> {}
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                super.onPlayerError(error)
                android.util.Log.w("PlaybackService", "Player error encountered (${error.errorCodeName}): ${error.message}")
                val currentEp = PlaybackStateManager.state.value.currentEpisode
                if (currentEp?.isLive == true || currentEp?.liveStatus != null) {
                    android.util.Log.i("PlaybackService", "Live stream disconnected or error. Automatically reverting to main queue.")
                    LiveSessionManager.clearLiveSession(applicationContext)
                    serviceScope.launch(Dispatchers.IO) {
                        database.episodeDao().updatePlaybackProgress(
                            episodeId = currentEp.id,
                            positionSec = 0,
                            isCompleted = true,
                            updatedAt = System.currentTimeMillis()
                        )
                        database.queueDao().removeEpisodeFromQueue(currentEp.id)
                        database.queueDao().purgeLiveItemsFromQueue()
                        launch(Dispatchers.Main) {
                            loadAndPlayTopQueueItem()
                        }
                    }
                } else {
                    // For standard podcasts: Save progress and attempt reconnection rather than skipping
                    persistCurrentProgress()
                    serviceScope.launch(Dispatchers.Main) {
                        PlaybackStateManager.setBuffering(true)
                        delay(2500)
                        if (isActive) {
                            try {
                                player.prepare()
                                player.play()
                            } catch (e: Exception) {
                                android.util.Log.e("PlaybackService", "Recovery prepare failed", e)
                                PlaybackStateManager.setBuffering(false)
                            }
                        }
                    }
                }
            }
        })

        // Prime the top queue item immediately on service creation so Android Auto and notification
        // displays are ready with the current episode and saved position as soon as connected.
        if (rawPlayer.currentMediaItem == null) {
            loadAndPrimeTopQueueItem()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_EPISODE -> {
                val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID)
                val podcastId = intent.getStringExtra(EXTRA_PODCAST_ID)
                if (!episodeId.isNullOrBlank()) {
                    loadAndPlayEpisode(episodeId, podcastId)
                }
            }
            ACTION_REVERT_TO_MAIN_QUEUE -> {
                loadAndPrimeTopQueueItem()
            }
            ACTION_PLAY_FROM_SEARCH,
            "android.media.action.MEDIA_PLAY_FROM_SEARCH",
            "android.intent.action.MEDIA_SEARCH",
            "com.google.android.gms.actions.SEARCH_ACTION" -> {
                val query = intent.getStringExtra(SearchManager.QUERY)
                    ?: intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE)
                    ?: intent.getStringExtra(EXTRA_SEARCH_QUERY)
                    ?: intent.getStringExtra("query")
                    ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                handleVoiceQueryAndPlay(query)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun handleVoiceQueryAndPlay(query: String?) {
        android.util.Log.i("PlaybackService", "handleVoiceQueryAndPlay received query: '$query'")
        serviceScope.launch(Dispatchers.IO) {
            val targetMediaItem = if (!query.isNullOrBlank()) {
                findPlayableItemForQuery(query)
            } else {
                null
            }

            val episodeToPlay = if (targetMediaItem != null) {
                database.episodeDao().getEpisodeByIdSync(targetMediaItem.mediaId)
            } else {
                database.queueDao().getQueueEpisodesSync().firstOrNull()
                    ?: database.episodeDao().getRecentUnplayedEpisodesSync(1).firstOrNull()
            }

            if (episodeToPlay != null) {
                android.util.Log.i("PlaybackService", "handleVoiceQueryAndPlay: playing episode '${episodeToPlay.title}' from podcast '${episodeToPlay.podcastTitle}'")
                launch(Dispatchers.Main) {
                    loadAndPlayEpisode(episodeToPlay.id, episodeToPlay.podcastId)
                }
            } else {
                android.util.Log.i("PlaybackService", "handleVoiceQueryAndPlay: no matching episode, falling back to top queue item")
                launch(Dispatchers.Main) {
                    loadAndPlayTopQueueItem()
                }
            }
        }
    }

    private suspend fun buildPlaylistForCurrentEpisode(currentEp: Episode): List<MediaItem> {
        val currentItem = createPlayableEpisodeItem(currentEp)
        val upcomingEpisodes = database.queueDao().getQueueEpisodesSync()
            .filter { it.id != currentEp.id && !it.isCompleted }
            .take(5)
        val list = mutableListOf<MediaItem>()
        list.add(currentItem)
        for (ep in upcomingEpisodes) {
            list.add(createPlayableEpisodeItem(ep))
        }
        return list
    }

    private fun loadAndPrimeTopQueueItem() {
        serviceScope.launch(Dispatchers.IO) {
            database.queueDao().purgeLiveItemsFromQueue()
            val topEpisode = database.queueDao().getQueueEpisodesSync().firstOrNull()
                ?: database.episodeDao().getRecentUnplayedEpisodesSync(1).firstOrNull()
                ?: return@launch
            val podcast = database.podcastDao().getPodcastByIdSync(topEpisode.podcastId)
            val speed = podcast?.playbackSpeed ?: 1.0f
            val skipIntro = podcast?.skipIntroSeconds ?: 0
            val skipOutro = podcast?.skipOutroSeconds ?: 0

            val playlist = buildPlaylistForCurrentEpisode(topEpisode)

            launch(Dispatchers.Main) {
                if (rawPlayer.isPlaying) return@launch
                PlaybackStateManager.setEpisode(topEpisode, speed, skipIntro, skipOutro, isPlaying = false)
                val startPosMs = if (topEpisode.playbackPositionSeconds > 0) {
                    topEpisode.playbackPositionSeconds * 1000L
                } else if (skipIntro > 0) {
                    skipIntro * 1000L
                } else {
                    0L
                }
                player.setMediaItems(playlist, 0, startPosMs)
                player.playbackParameters = PlaybackParameters(speed)
                player.prepare()
                player.pause()
            }
        }
    }

    private fun loadAndPlayTopQueueItem() {
        serviceScope.launch(Dispatchers.IO) {
            database.queueDao().purgeLiveItemsFromQueue()
            val topEpisode = database.queueDao().getQueueEpisodesSync().firstOrNull()
                ?: database.episodeDao().getRecentUnplayedEpisodesSync(1).firstOrNull()
                ?: return@launch
            val podcast = database.podcastDao().getPodcastByIdSync(topEpisode.podcastId)
            val speed = podcast?.playbackSpeed ?: 1.0f
            val skipIntro = podcast?.skipIntroSeconds ?: 0
            val skipOutro = podcast?.skipOutroSeconds ?: 0

            stopLiveInactivityWatchdog()
            LiveSessionManager.clearLiveSession(applicationContext)

            val playlist = buildPlaylistForCurrentEpisode(topEpisode)

            launch(Dispatchers.Main) {
                PlaybackStateManager.setEpisode(topEpisode, speed, skipIntro, skipOutro, isPlaying = true)
                val startPosMs = if (topEpisode.playbackPositionSeconds > 0) {
                    topEpisode.playbackPositionSeconds * 1000L
                } else if (skipIntro > 0) {
                    skipIntro * 1000L
                } else {
                    0L
                }
                player.setMediaItems(playlist, 0, startPosMs)
                player.playbackParameters = PlaybackParameters(speed)
                player.prepare()
                player.play()
            }
        }
    }

    private var liveInactivityJob: Job? = null

    private fun startLiveInactivityWatchdog() {
        liveInactivityJob?.cancel()
        liveInactivityJob = serviceScope.launch {
            delay(LiveSessionManager.LIVE_INACTIVITY_TIMEOUT_MS)
            if (isActive) {
                val currentEp = PlaybackStateManager.state.value.currentEpisode
                if (currentEp?.isLive == true && !player.isPlaying) {
                    android.util.Log.i("PlaybackService", "Live station paused for > 1 hour. Reverting back to main playlist.")
                    LiveSessionManager.clearLiveSession(applicationContext)
                    loadAndPrimeTopQueueItem()
                }
            }
        }
    }

    private fun stopLiveInactivityWatchdog() {
        liveInactivityJob?.cancel()
        liveInactivityJob = null
    }

    private fun loadAndPlayEpisode(episodeId: String, podcastId: String?) {
        serviceScope.launch(Dispatchers.IO) {
            val episode = database.episodeDao().getEpisodeByIdSync(episodeId) ?: return@launch
            val podcast = if (podcastId != null) database.podcastDao().getPodcastByIdSync(podcastId) else database.podcastDao().getPodcastByIdSync(episode.podcastId)

            val speed = podcast?.playbackSpeed ?: 1.0f
            val skipIntro = podcast?.skipIntroSeconds ?: 0
            val skipOutro = podcast?.skipOutroSeconds ?: 0

            stopLiveInactivityWatchdog()
            if (episode.isLive) {
                LiveSessionManager.recordPlaybackActive(applicationContext, episode)
            } else {
                LiveSessionManager.clearLiveSession(applicationContext)
            }

            val playlist = if (episode.isLive) listOf(createPlayableEpisodeItem(episode)) else buildPlaylistForCurrentEpisode(episode)

            launch(Dispatchers.Main) {
                PlaybackStateManager.setEpisode(episode, speed, skipIntro, skipOutro, isPlaying = true)
                val startPosMs = if (episode.playbackPositionSeconds > 0) {
                    episode.playbackPositionSeconds * 1000L
                } else if (skipIntro > 0) {
                    skipIntro * 1000L
                } else {
                    0L
                }
                player.setMediaItems(playlist, 0, startPosMs)
                player.playbackParameters = PlaybackParameters(speed)
                player.prepare()
                player.play()
            }
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            var tickCount = 0
            while (isActive) {
                val currentPos = player.currentPosition
                val duration = player.duration
                val buffered = player.bufferedPosition

                PlaybackStateManager.setProgress(currentPos, duration, buffered)
                LiveSessionManager.recordListeningHeartbeat(applicationContext)

                // Periodically persist playback progress every 10 seconds (20 ticks)
                tickCount++
                if (tickCount % 20 == 0) {
                    persistCurrentProgress()
                }

                // Check Skip Outro rule (only when episode is longer than skipOutro + 15s)
                val currentState = PlaybackStateManager.state.value
                val skipOutroMs = currentState.skipOutroSeconds * 1000L
                if (skipOutroMs > 0 && duration > skipOutroMs + 15_000L && (duration - currentPos) <= skipOutroMs) {
                    handleEpisodeEnded(forceEnd = true)
                    break
                }

                delay(500)
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun persistCurrentProgress() {
        val currentEpisode = PlaybackStateManager.state.value.currentEpisode ?: return
        val currentPosSec = (player.currentPosition / 1000L).coerceAtLeast(0L)
        val rawDuration = player.duration
        val isCompleted = rawDuration > 30_000L && player.currentPosition >= (rawDuration * 0.98)

        serviceScope.launch(Dispatchers.IO) {
            database.episodeDao().updatePlaybackProgress(
                episodeId = currentEpisode.id,
                positionSec = currentPosSec,
                isCompleted = isCompleted,
                updatedAt = System.currentTimeMillis()
            )
            if (isCompleted) {
                database.queueDao().removeEpisodeFromQueue(currentEpisode.id)
            }
        }
    }

    private fun handleEpisodeEnded(forceEnd: Boolean = false) {
        val currentEpisode = PlaybackStateManager.state.value.currentEpisode ?: return
        val currentPos = player.currentPosition
        val duration = player.duration
        val skipOutroMs = PlaybackStateManager.state.value.skipOutroSeconds * 1000L

        // Safety verification against premature stream severance (weak connection / dropped socket):
        // If duration is valid (> 30s) and playback stopped far before the actual ending:
        if (!forceEnd && duration > 30_000L && (duration - currentPos) > maxOf(20_000L, skipOutroMs)) {
            android.util.Log.w(
                "PlaybackService",
                "handleEpisodeEnded: Premature EOF detected at ${currentPos / 1000}s / ${duration / 1000}s for '${currentEpisode.title}'. Weak network connection dropped. Attempting reconnect without skipping."
            )
            persistCurrentProgress()
            serviceScope.launch(Dispatchers.Main) {
                PlaybackStateManager.setBuffering(true)
                delay(1500)
                try {
                    player.prepare()
                    player.seekTo(currentPos)
                    player.play()
                } catch (e: Exception) {
                    android.util.Log.e("PlaybackService", "Failed to resume after premature EOF", e)
                    player.pause()
                }
            }
            return
        }

        val rawDurMs = try { player.duration } catch (e: Exception) { -1L }
        val durationSec = if (rawDurMs > 0L) rawDurMs / 1000L else currentEpisode.durationSeconds

        android.util.Log.i("PlaybackService", "handleEpisodeEnded: finished '${currentEpisode.title}' (duration: ${durationSec}s), advancing queue")

        serviceScope.launch(Dispatchers.IO) {
            database.episodeDao().updatePlaybackProgress(
                episodeId = currentEpisode.id,
                positionSec = durationSec,
                isCompleted = true,
                updatedAt = System.currentTimeMillis()
            )
            database.queueDao().removeEpisodeFromQueue(currentEpisode.id)
            database.queueDao().purgeLiveItemsFromQueue()
            try {
                com.eagertolisten.app.data.download.PodcastDownloadManager(applicationContext).deleteDownloadedEpisode(currentEpisode.id)
            } catch (_: Exception) {}

            // Play next episode in pending playlist with just-in-time news feed update
            val nextEpisodes = database.queueDao().getQueueEpisodesSync().filter { it.id != currentEpisode.id }
            if (nextEpisodes.isNotEmpty()) {
                val next = nextEpisodes.first()
                playNextQueueEpisodeWithNewsCheck(next)
            } else {
                android.util.Log.i("PlaybackService", "handleEpisodeEnded: queue is now empty")
                launch(Dispatchers.Main) {
                    player.stop()
                    PlaybackStateManager.setPlaying(false)
                }
            }
        }
    }

    fun performSeekForward(incrementMs: Long = 90_000L) {
        val rawDuration = rawPlayer.duration
        val fallbackDuration = (PlaybackStateManager.state.value.currentEpisode?.durationSeconds ?: 0L) * 1000L
        val duration = if (rawDuration > 0) rawDuration else fallbackDuration
        val currentPos = rawPlayer.currentPosition.coerceAtLeast(0L)
        val skipOutroMs = PlaybackStateManager.state.value.skipOutroSeconds * 1000L
        val effectiveEndMs = if (duration > 0 && skipOutroMs > 0 && duration > skipOutroMs + 15_000L) {
            duration - skipOutroMs
        } else {
            duration
        }

        android.util.Log.i(
            "PlaybackService",
            "performSeekForward: currentPos=${currentPos}ms, increment=${incrementMs}ms, duration=${duration}ms, effectiveEnd=${effectiveEndMs}ms"
        )

        // If skipping forward reaches or passes the end of the episode (or outro threshold),
        // finish the episode and advance to the next queued episode ("if I really hate an episode I can just do a few skips and it will be over")
        if (effectiveEndMs > 0 && (currentPos + incrementMs >= effectiveEndMs - 1500L)) {
            android.util.Log.i("PlaybackService", "performSeekForward: reached end of episode ($currentPos + $incrementMs >= $effectiveEndMs). Finishing and advancing to next episode.")
            handleEpisodeEnded(forceEnd = true)
        } else {
            val targetPos = if (duration > 0) minOf(currentPos + incrementMs, duration) else (currentPos + incrementMs)
            rawPlayer.seekTo(targetPos)
            PlaybackStateManager.setProgress(targetPos, duration, rawPlayer.bufferedPosition)
            persistCurrentProgress()
        }
    }

    fun performSeekBack(decrementMs: Long = 15_000L) {
        val currentPos = rawPlayer.currentPosition.coerceAtLeast(0L)
        val skipIntroMs = PlaybackStateManager.state.value.skipIntroSeconds * 1000L
        val minPos = if (skipIntroMs > 0 && currentPos > skipIntroMs) skipIntroMs else 0L
        val targetPos = maxOf(minPos, currentPos - decrementMs)
        val duration = rawPlayer.duration
        android.util.Log.i(
            "PlaybackService",
            "performSeekBack: currentPos=${currentPos}ms, decrement=${decrementMs}ms, targetPos=${targetPos}ms"
        )
        rawPlayer.seekTo(targetPos)
        PlaybackStateManager.setProgress(targetPos, duration, rawPlayer.bufferedPosition)
        persistCurrentProgress()
    }

    fun skipToNextEpisode(markCompleted: Boolean = false) {
        val currentEp = PlaybackStateManager.state.value.currentEpisode
        android.util.Log.i("PlaybackService", "skipToNextEpisode requested (current: '${currentEp?.title}', markCompleted=$markCompleted)")

        if (rawPlayer.hasNextMediaItem()) {
            serviceScope.launch(Dispatchers.IO) {
                if (currentEp != null) {
                    val currentPosSec = (player.currentPosition / 1000L).coerceAtLeast(0L)
                    val rawDurMs = try { player.duration } catch (e: Exception) { -1L }
                    val durationSec = if (rawDurMs > 0L) rawDurMs / 1000L else currentEp.durationSeconds
                    val isReallyCompleted = markCompleted || (rawDurMs > 0 && player.currentPosition >= (rawDurMs * 0.98))
                    val posToSave = if (isReallyCompleted) durationSec else currentPosSec

                    database.episodeDao().updatePlaybackProgress(
                        episodeId = currentEp.id,
                        positionSec = posToSave,
                        isCompleted = isReallyCompleted,
                        updatedAt = System.currentTimeMillis()
                    )
                    database.queueDao().removeEpisodeFromQueue(currentEp.id)
                }
                launch(Dispatchers.Main) {
                    rawPlayer.seekToNextMediaItem()
                }
            }
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            if (currentEp != null) {
                val currentPosSec = (player.currentPosition / 1000L).coerceAtLeast(0L)
                val rawDurMs = try { player.duration } catch (e: Exception) { -1L }
                val durationSec = if (rawDurMs > 0L) rawDurMs / 1000L else currentEp.durationSeconds
                val isReallyCompleted = markCompleted || (rawDurMs > 0 && player.currentPosition >= (rawDurMs * 0.98))
                val posToSave = if (isReallyCompleted) durationSec else currentPosSec

                database.episodeDao().updatePlaybackProgress(
                    episodeId = currentEp.id,
                    positionSec = posToSave,
                    isCompleted = isReallyCompleted,
                    updatedAt = System.currentTimeMillis()
                )
                database.queueDao().removeEpisodeFromQueue(currentEp.id)
                database.queueDao().purgeLiveItemsFromQueue()
            }

            val nextEpisodes = database.queueDao().getQueueEpisodesSync().filter { it.id != currentEp?.id }
            if (nextEpisodes.isNotEmpty()) {
                val next = nextEpisodes.first()
                playNextQueueEpisodeWithNewsCheck(next)
            } else {
                launch(Dispatchers.Main) {
                    player.stop()
                    PlaybackStateManager.setPlaying(false)
                }
            }
        }
    }

    fun skipToPreviousEpisode() {
        val currentPos = player.currentPosition
        val skipIntroMs = PlaybackStateManager.state.value.skipIntroSeconds * 1000L
        val minPos = if (skipIntroMs > 0) skipIntroMs else 0L

        // If played more than 5 seconds into episode, restart current episode
        if (currentPos > (minPos + 5000L)) {
            android.util.Log.i("PlaybackService", "skipToPreviousEpisode: restarting current episode at ${minPos}ms")
            player.seekTo(minPos)
            return
        }

        if (rawPlayer.hasPreviousMediaItem()) {
            android.util.Log.i("PlaybackService", "skipToPreviousEpisode: moving to previous media item in playlist")
            rawPlayer.seekToPreviousMediaItem()
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            val played = database.episodeDao().getRecentPlayedEpisodesSync(2)
            val currentEp = PlaybackStateManager.state.value.currentEpisode
            val targetEp = played.firstOrNull { it.id != currentEp?.id }
            if (targetEp != null) {
                android.util.Log.i("PlaybackService", "skipToPreviousEpisode: playing previous episode '${targetEp.title}'")
                launch(Dispatchers.Main) {
                    loadAndPlayEpisode(targetEp.id, targetEp.podcastId)
                }
            } else {
                launch(Dispatchers.Main) {
                    player.seekTo(minPos)
                }
            }
        }
    }

    /**
     * Requirement 6: When the next podcast is expected to be a NEWS one, update that podcast
     * feed before playing to make sure the content is timely and correct.
     */
    private suspend fun playNextQueueEpisodeWithNewsCheck(next: Episode) {
        val podcast = database.podcastDao().getPodcastByIdSync(next.podcastId)
        if (podcast?.playOrderStrategy == "NEWS") {
            android.util.Log.i("PlaybackService", "Next podcast is NEWS ('${podcast.title}'). Performing just-in-time feed update before playback...")
            try {
                val repo = PodcastRepository(applicationContext)
                withTimeout(6000L) {
                    repo.refreshFeed(podcast.id)
                }
                repo.purgeExpiredNewsEpisodes()
            } catch (e: Exception) {
                android.util.Log.w("PlaybackService", "Just-in-time news feed update timed out or failed; playing existing queued episode", e)
            }

            // Pick the latest top queue episode after refresh
            val refreshedNext = database.queueDao().getQueueEpisodesSync().firstOrNull() ?: next
            serviceScope.launch(Dispatchers.Main) {
                loadAndPlayEpisode(refreshedNext.id, refreshedNext.podcastId)
            }
        } else {
            serviceScope.launch(Dispatchers.Main) {
                loadAndPlayEpisode(next.id, next.podcastId)
            }
        }
    }

    /**
     * Requirement 7: Content creator inspiration bookmarking from Android Auto or app
     */
    fun saveCurrentInspiration(noteText: String = "Captured from Android Auto") {
        val currentEp = PlaybackStateManager.state.value.currentEpisode ?: return
        val posSec = player.currentPosition / 1000L
        serviceScope.launch(Dispatchers.IO) {
            val clip = InspirationClip(
                episodeId = currentEp.id,
                podcastId = currentEp.podcastId,
                podcastTitle = currentEp.podcastTitle,
                episodeTitle = currentEp.title,
                timestampSeconds = posSec,
                audioUrl = currentEp.audioUrl,
                note = noteText,
                tags = "Inspiration"
            )
            database.inspirationDao().insertInspiration(clip)
            android.util.Log.i("PlaybackService", "Saved inspiration clip for '${currentEp.title}' at ${posSec}s: $noteText")
        }
    }

    private fun getNewsRetentionDays(): Int {
        val prefs = applicationContext.getSharedPreferences("eager_settings", Context.MODE_PRIVATE)
        return prefs.getInt("default_news_days", 3)
    }

    private fun createCategoryItem(
        id: String,
        title: String,
        subtitle: String,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
    ): MediaItem {
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .build()
            )
            .build()
    }

    private fun createPodcastFolderItem(podcast: Podcast): MediaItem {
        return MediaItem.Builder()
            .setMediaId("podcast_${podcast.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(podcast.title)
                    .setArtist(podcast.author ?: "Podcast")
                    .setArtworkUri(podcast.artworkUrl?.let { Uri.parse(it) })
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
                    .build()
            )
            .build()
    }

    private fun createPlayableEpisodeItem(episode: Episode, prefix: String = ""): MediaItem {
        val downloadPrefix = if (episode.isDownloaded) "💾 " else ""
        val titleText = if (prefix.isNotEmpty()) "$prefix$downloadPrefix${episode.title}" else "$downloadPrefix${episode.title}"
        val artistText = if (episode.isDownloaded) "✓ Downloaded • ${episode.podcastTitle}" else episode.podcastTitle
        val metadata = MediaMetadata.Builder()
            .setTitle(titleText)
            .setArtist(artistText)
            .setSubtitle(artistText)
            .setArtworkUri(episode.artworkUrl?.let { Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .build()

        val uri = if (episode.isDownloaded && !episode.localFilePath.isNullOrBlank() && File(episode.localFilePath).exists()) {
            android.util.Log.i("PlaybackService", "Playing offline local file: ${episode.localFilePath}")
            Uri.fromFile(File(episode.localFilePath))
        } else {
            Uri.parse(episode.audioUrl)
        }

        return MediaItem.Builder()
            .setMediaId(episode.id)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun isSystemBrowseId(mediaId: String): Boolean {
        return mediaId == ROOT_ID ||
                mediaId == CATEGORY_PENDING_QUEUE ||
                mediaId == CATEGORY_HOT_LIST ||
                mediaId == CATEGORY_NEWS ||
                mediaId == CATEGORY_SUBSCRIPTIONS ||
                mediaId.startsWith("podcast_")
    }

    private fun cleanVoiceQuery(query: String): String {
        var s = query.lowercase().trim()

        // Strip app target phrases
        s = s.replace(" on eager to listen", "")
            .replace(" in eager to listen", "")
            .replace(" with eager to listen", "")
            .replace(" using eager to listen", "")

        // Strip common voice command prefixes
        val prefixes = listOf(
            "play the podcast ",
            "play podcast ",
            "listen to the podcast ",
            "listen to podcast ",
            "play the show ",
            "listen to the show ",
            "play ",
            "listen to ",
            "resume ",
            "start ",
            "open "
        )
        for (prefix in prefixes) {
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length).trim()
                break
            }
        }

        // Strip noise suffixes
        val suffixes = listOf(
            " podcast",
            " show",
            " episodes",
            " episode"
        )
        for (suffix in suffixes) {
            if (s.endsWith(suffix)) {
                s = s.removeSuffix(suffix).trim()
            }
        }

        // Clean punctuation
        s = s.replace(Regex("[^a-zA-Z0-9\\s]"), " ").trim()
        s = s.replace(Regex("\\s+"), " ")

        return s
    }

    private suspend fun getPlayOrderSortedEpisodesForPodcast(podcastId: String): List<Episode> {
        val podcast = database.podcastDao().getPodcastByIdSync(podcastId)
        val allEpisodes = database.episodeDao().getEpisodesForPodcastSync(podcastId)
            .filter { !it.isLive && it.liveStatus == null }
        if (allEpisodes.isEmpty()) return emptyList()

        val (unplayed, completed) = allEpisodes.partition { !it.isCompleted }
        val strategy = try {
            com.eagertolisten.app.data.model.PlayOrderStrategy.valueOf(podcast?.playOrderStrategy ?: "EVERGREEN")
        } catch (_: Exception) {
            com.eagertolisten.app.data.model.PlayOrderStrategy.EVERGREEN
        }

        val sortedUnplayed = when (strategy) {
            com.eagertolisten.app.data.model.PlayOrderStrategy.CHRONOLOGICAL -> {
                unplayed.sortedBy { it.publishedAt }
            }
            com.eagertolisten.app.data.model.PlayOrderStrategy.NEWS -> {
                val defaultDays = getNewsRetentionDays()
                val retentionDays = if (podcast != null && podcast.newsRetentionDays > 0) podcast.newsRetentionDays else defaultDays
                val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * 86_400_000L)
                val fresh = unplayed.filter { it.publishedAt >= cutoff }.sortedBy { it.publishedAt }
                fresh.ifEmpty { unplayed.sortedByDescending { it.publishedAt } }
            }
            com.eagertolisten.app.data.model.PlayOrderStrategy.REVERSE_CHRONOLOGICAL,
            com.eagertolisten.app.data.model.PlayOrderStrategy.EVERGREEN -> {
                unplayed.sortedByDescending { it.publishedAt }
            }
        }

        return sortedUnplayed + completed.sortedByDescending { it.publishedAt }
    }

    private suspend fun findPlayableItemForQuery(rawQuery: String): MediaItem? {
        val clean = cleanVoiceQuery(rawQuery)
        android.util.Log.i("PlaybackService", "findPlayableItemForQuery: raw='$rawQuery', clean='$clean'")

        // Check A: App-level or resume queries (empty, "eager to listen", "my podcast", "queue", "playlist")
        if (clean.isEmpty() ||
            clean == "eager to listen" ||
            clean == "eager" ||
            clean == "my podcast" ||
            clean == "podcasts" ||
            clean == "queue" ||
            clean == "pending playlist" ||
            clean == "playlist" ||
            clean == "continue" ||
            clean == "resume") {
            val top = database.queueDao().getQueueEpisodesSync().firstOrNull()
                ?: database.episodeDao().getRecentUnplayedEpisodesSync(1).firstOrNull()
            if (top != null) return createPlayableEpisodeItem(top)
        }

        // Check B: Flagship podcast ("Eager to Be Healthy")
        if (clean.contains("eager to be healthy") ||
            clean.contains("eagertobehealthy") ||
            clean.contains("eager healthy") ||
            clean.contains("health and happiness") ||
            clean == "eager") {
            val podcasts = database.podcastDao().searchPodcastsSync("Eager to Be Healthy", 5)
            val eagerPodcast = podcasts.firstOrNull { 
                it.title.contains("Healthy", ignoreCase = true) || it.feedUrl.contains("eagertobehealthy", ignoreCase = true)
            } ?: podcasts.firstOrNull()
            
            if (eagerPodcast != null) {
                val targetEp = getPlayOrderSortedEpisodesForPodcast(eagerPodcast.id).firstOrNull()
                if (targetEp != null) return createPlayableEpisodeItem(targetEp)
            }
        }

        // Check C: Search Podcasts by title or author
        val matchingPodcasts = database.podcastDao().searchPodcastsSync(clean, 5)
        if (matchingPodcasts.isNotEmpty()) {
            val podcast = matchingPodcasts.first()
            val targetEp = getPlayOrderSortedEpisodesForPodcast(podcast.id).firstOrNull()
            if (targetEp != null) return createPlayableEpisodeItem(targetEp)
        }

        // Check D: Token search for podcast name
        val stopWords = setOf("the", "a", "an", "and", "or", "in", "on", "at", "to", "for", "with", "by", "of")
        val tokens = clean.split(" ").filter { it.length > 2 && it !in stopWords }
        for (token in tokens) {
            val tokenPodcasts = database.podcastDao().searchPodcastsSync(token, 3)
            if (tokenPodcasts.isNotEmpty()) {
                val podcast = tokenPodcasts.first()
                val targetEp = getPlayOrderSortedEpisodesForPodcast(podcast.id).firstOrNull()
                if (targetEp != null) return createPlayableEpisodeItem(targetEp)
            }
        }

        // Check E: Search Episodes by title or podcastTitle
        val matchingEpisodes = database.episodeDao().searchEpisodesSync(clean, 5)
        if (matchingEpisodes.isNotEmpty()) {
            val targetEp = matchingEpisodes.firstOrNull { !it.isCompleted } ?: matchingEpisodes.first()
            return createPlayableEpisodeItem(targetEp)
        }

        // Check F: Episode search by tokens
        for (token in tokens) {
            val tokenEpisodes = database.episodeDao().searchEpisodesSync(token, 5)
            if (tokenEpisodes.isNotEmpty()) {
                val targetEp = tokenEpisodes.firstOrNull { !it.isCompleted } ?: tokenEpisodes.first()
                return createPlayableEpisodeItem(targetEp)
            }
        }

        return null
    }

    private fun <T> coroutineFuture(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        serviceScope.launch(Dispatchers.IO) {
            try {
                val result = block()
                future.set(result)
            } catch (e: Throwable) {
                future.setException(e)
            }
        }
        return future
    }

    private inner class CustomMediaLibrarySessionCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT))
                .add(SessionCommand(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN))
                .add(SessionCommand(SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM))
                .add(SessionCommand(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH))
                .add(SessionCommand(SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT))
                .add(SessionCommand(CUSTOM_ACTION_REWIND, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_ACTION_FAST_FORWARD, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_ACTION_SKIP_NEXT, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_ACTION_SAVE_INSPIRATION, Bundle.EMPTY))
                .build()

            // Keep COMMAND_SEEK_TO_NEXT / PREVIOUS enabled so Media3's MediaSessionStub and
            // MediaSessionLegacyStub accept car steering wheel / Bluetooth AVRCP next/prev buttons
            // and route them into ForwardingPlayer.seekToNext() -> performSeekForward(90_000L).
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_FORWARD)
                .add(Player.COMMAND_SEEK_BACK)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

            val customRewindBtn = CommandButton.Builder()
                .setDisplayName("Rewind 15s")
                .setSessionCommand(SessionCommand(CUSTOM_ACTION_REWIND, Bundle.EMPTY))
                .setIconResId(android.R.drawable.ic_media_rew)
                .build()

            val customForwardBtn = CommandButton.Builder()
                .setDisplayName("Forward 90s")
                .setSessionCommand(SessionCommand(CUSTOM_ACTION_FAST_FORWARD, Bundle.EMPTY))
                .setIconResId(android.R.drawable.ic_media_ff)
                .build()

            val customInspirationBtn = CommandButton.Builder()
                .setDisplayName("Save Idea")
                .setSessionCommand(SessionCommand(CUSTOM_ACTION_SAVE_INSPIRATION, Bundle.EMPTY))
                .setIconResId(android.R.drawable.btn_star_big_on)
                .build()

            val autoCustomLayout = listOf(customRewindBtn, customForwardBtn, customInspirationBtn)
            session.setCustomLayout(autoCustomLayout)

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setCustomLayout(autoCustomLayout)
                .build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Eager to Listen")
                        .setIsPlayable(false)
                        .setIsBrowsable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = coroutineFuture {
            val items = mutableListOf<MediaItem>()
            when (parentId) {
                ROOT_ID -> {
                    items.add(createCategoryItem(CATEGORY_PENDING_QUEUE, "Pending Playlist", "Your programmed queue", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS))
                    items.add(createCategoryItem(CATEGORY_HOT_LIST, "Hot List", "Priority 1 Podcasts", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS))
                    items.add(createCategoryItem(CATEGORY_NEWS, "Daily News", "Fresh & Timely Updates", MediaMetadata.MEDIA_TYPE_FOLDER_NEWS))
                    items.add(createCategoryItem(CATEGORY_SUBSCRIPTIONS, "Podcasts", "All Subscribed Feeds", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS))
                }
                CATEGORY_PENDING_QUEUE -> {
                    val episodes = database.queueDao().getQueueEpisodesSync().take(MAX_AUTO_BROWSE_ITEMS)
                    episodes.forEachIndexed { index, ep ->
                        val badge = if (index == 0) "#1 NEXT: " else "#${index + 1}: "
                        items.add(createPlayableEpisodeItem(ep, prefix = badge))
                    }
                }
                CATEGORY_HOT_LIST -> {
                    val episodes = database.episodeDao().getHotListEpisodesSync(50)
                    episodes.forEach { ep ->
                        items.add(createPlayableEpisodeItem(ep))
                    }
                }
                CATEGORY_NEWS -> {
                    val retentionDays = getNewsRetentionDays()
                    val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * 86_400_000L)
                    val episodes = database.episodeDao().getRecentNewsEpisodesSync(cutoff, 50)
                    episodes.forEach { ep ->
                        items.add(createPlayableEpisodeItem(ep))
                    }
                }
                CATEGORY_SUBSCRIPTIONS -> {
                    val podcasts = database.podcastDao().getAllPodcastsSync()
                    podcasts.forEach { p ->
                        items.add(createPodcastFolderItem(p))
                    }
                }
                else -> {
                    if (parentId.startsWith("podcast_")) {
                        val podcastId = parentId.removePrefix("podcast_")
                        val episodes = getPlayOrderSortedEpisodesForPodcast(podcastId).take(MAX_AUTO_BROWSE_ITEMS)
                        episodes.forEach { ep ->
                            items.add(createPlayableEpisodeItem(ep))
                        }
                    }
                }
            }

            // Support Media3 pagination when requested by Android Auto
            val pagedItems = if (pageSize in 1 until Int.MAX_VALUE && page >= 0) {
                val fromIndex = (page * pageSize).coerceAtMost(items.size)
                val toIndex = ((page + 1) * pageSize).coerceAtMost(items.size)
                items.subList(fromIndex, toIndex)
            } else {
                items
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(pagedItems), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> = coroutineFuture {
            when (mediaId) {
                ROOT_ID -> LibraryResult.ofItem(
                    createCategoryItem(ROOT_ID, "Eager to Listen", "Podcast Library"),
                    null
                )
                CATEGORY_PENDING_QUEUE -> LibraryResult.ofItem(
                    createCategoryItem(CATEGORY_PENDING_QUEUE, "Pending Playlist", "Your programmed queue"),
                    null
                )
                CATEGORY_HOT_LIST -> LibraryResult.ofItem(
                    createCategoryItem(CATEGORY_HOT_LIST, "Hot List", "Priority 1 Podcasts"),
                    null
                )
                CATEGORY_NEWS -> LibraryResult.ofItem(
                    createCategoryItem(CATEGORY_NEWS, "Daily News", "Fresh & Timely Updates"),
                    null
                )
                CATEGORY_SUBSCRIPTIONS -> LibraryResult.ofItem(
                    createCategoryItem(CATEGORY_SUBSCRIPTIONS, "Podcasts", "All Subscribed Feeds"),
                    null
                )
                else -> {
                    if (mediaId.startsWith("podcast_")) {
                        val podcastId = mediaId.removePrefix("podcast_")
                        val podcast = database.podcastDao().getPodcastByIdSync(podcastId)
                        if (podcast != null) {
                            LibraryResult.ofItem(createPodcastFolderItem(podcast), null)
                        } else {
                            LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                        }
                    } else {
                        val episode = database.episodeDao().getEpisodeByIdSync(mediaId)
                        if (episode != null) {
                            LibraryResult.ofItem(createPlayableEpisodeItem(episode), null)
                        } else {
                            LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                        }
                    }
                }
            }
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = coroutineFuture {
            // Check if user was listening to a live show that has since expired (> 90 min inactivity)
            val liveExpired = LiveSessionManager.isLiveSessionExpired(applicationContext)
            if (liveExpired) {
                LiveSessionManager.clearLiveSession(applicationContext)
            }

            // 1. Check current playing/paused episode (if not an expired live show)
            val currentEp = PlaybackStateManager.state.value.currentEpisode
            if (!liveExpired && currentEp != null) {
                val freshEp = database.episodeDao().getEpisodeByIdSync(currentEp.id) ?: currentEp
                val currentPos = if (PlaybackStateManager.state.value.currentPositionMs > 0L) {
                    PlaybackStateManager.state.value.currentPositionMs
                } else {
                    freshEp.playbackPositionSeconds * 1000L
                }
                val playlist = buildPlaylistForCurrentEpisode(freshEp)
                return@coroutineFuture MediaSession.MediaItemsWithStartPosition(
                    playlist,
                    0,
                    currentPos
                )
            }

            // 2. Fall back to top pending playlist item
            val queueEpisodes = database.queueDao().getQueueEpisodesSync()
            if (queueEpisodes.isNotEmpty()) {
                val first = queueEpisodes.first()
                val podcast = database.podcastDao().getPodcastByIdSync(first.podcastId)
                val speed = podcast?.playbackSpeed ?: 1.0f
                val skipIntro = podcast?.skipIntroSeconds ?: 0
                val skipOutro = podcast?.skipOutroSeconds ?: 0
                val posMs = if (first.playbackPositionSeconds > 0) {
                    first.playbackPositionSeconds * 1000L
                } else if (skipIntro > 0) {
                    skipIntro * 1000L
                } else {
                    0L
                }
                val playlist = buildPlaylistForCurrentEpisode(first)
                serviceScope.launch(Dispatchers.Main) {
                    PlaybackStateManager.setEpisode(first, speed, skipIntro, skipOutro, isPlaying = true)
                }
                return@coroutineFuture MediaSession.MediaItemsWithStartPosition(
                    playlist,
                    0,
                    posMs
                )
            }

            // 3. Fall back to most recent unplayed episode
            val unplayed = database.episodeDao().getRecentUnplayedEpisodesSync(1)
            if (unplayed.isNotEmpty()) {
                val first = unplayed.first()
                val posMs = if (first.playbackPositionSeconds > 0) first.playbackPositionSeconds * 1000L else 0L
                val item = createPlayableEpisodeItem(first)
                return@coroutineFuture MediaSession.MediaItemsWithStartPosition(
                    listOf(item),
                    0,
                    posMs
                )
            }

            throw UnsupportedOperationException("No playable media found for resumption")
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> = coroutineFuture {
            val clean = cleanVoiceQuery(query)
            val episodes = database.episodeDao().searchEpisodesSync(clean, 20)
            val podcasts = database.podcastDao().searchPodcastsSync(clean, 5)
            session.notifySearchResultChanged(browser, query, episodes.size + podcasts.size, params)
            LibraryResult.ofVoid(params)
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = coroutineFuture {
            val results = mutableListOf<MediaItem>()
            val clean = cleanVoiceQuery(query)
            val podcasts = database.podcastDao().searchPodcastsSync(clean, 5)
            val episodes = database.episodeDao().searchEpisodesSync(clean, 30)

            podcasts.forEach { p ->
                results.add(createPodcastFolderItem(p))
            }
            episodes.forEach { ep ->
                results.add(createPlayableEpisodeItem(ep))
            }

            LibraryResult.ofItemList(ImmutableList.copyOf(results), params)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = coroutineFuture {
            val resolvedList = mutableListOf<MediaItem>()
            for (item in mediaItems) {
                val resolved = resolveMediaItem(item)
                resolvedList.add(resolved)
            }
            MediaSession.MediaItemsWithStartPosition(resolvedList, startIndex, startPositionMs)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> = coroutineFuture {
            mediaItems.map { resolveMediaItem(it) }
        }

        private suspend fun resolveMediaItem(item: MediaItem): MediaItem {
            // 1. Check Voice Search Query (Google Assistant / Gemini / MediaStore)
            val rawQuery = item.requestMetadata.searchQuery
                ?: item.requestMetadata.extras?.getString(MediaStore.EXTRA_MEDIA_TITLE)
                ?: item.requestMetadata.extras?.getString(SearchManager.QUERY)
                ?: item.requestMetadata.extras?.getString("query")
                ?: (if (!isSystemBrowseId(item.mediaId) && database.episodeDao().getEpisodeByIdSync(item.mediaId) == null) item.mediaId else null)

            android.util.Log.i("PlaybackService", "resolveMediaItem: mediaId='${item.mediaId}', rawQuery='$rawQuery'")

            if (!rawQuery.isNullOrBlank()) {
                val matched = findPlayableItemForQuery(rawQuery)
                if (matched != null) {
                    return matched
                }
            }

            // 2. Android Auto browse category / folder resolution
            when (item.mediaId) {
                CATEGORY_PENDING_QUEUE, ROOT_ID -> {
                    val top = database.queueDao().getQueueEpisodesSync().firstOrNull()
                    if (top != null) return createPlayableEpisodeItem(top)
                }
                CATEGORY_HOT_LIST -> {
                    val top = database.episodeDao().getHotListEpisodesSync(1).firstOrNull()
                    if (top != null) return createPlayableEpisodeItem(top)
                }
                CATEGORY_NEWS -> {
                    val retentionDays = getNewsRetentionDays()
                    val cutoff = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
                    val top = database.episodeDao().getRecentNewsEpisodesSync(cutoff, 1).firstOrNull()
                    if (top != null) return createPlayableEpisodeItem(top)
                }
                CATEGORY_SUBSCRIPTIONS -> {
                    val top = database.queueDao().getQueueEpisodesSync().firstOrNull()
                    if (top != null) return createPlayableEpisodeItem(top)
                }
                else -> {
                    if (item.mediaId.startsWith("podcast_")) {
                        val podcastId = item.mediaId.removePrefix("podcast_")
                        val targetEp = getPlayOrderSortedEpisodesForPodcast(podcastId).firstOrNull()
                        if (targetEp != null) return createPlayableEpisodeItem(targetEp)
                    }
                }
            }

            // 3. Direct episode lookup by mediaId
            val episode = database.episodeDao().getEpisodeByIdSync(item.mediaId)
            if (episode != null) {
                return createPlayableEpisodeItem(episode)
            }

            // 4. Return item if already playable
            if (item.requestMetadata.mediaUri != null || item.localConfiguration?.uri != null) {
                return item
            }

            // 5. Ultimate Fallback: Never return unplayable media item to prevent YouTube Music failover
            val fallbackQueue = database.queueDao().getQueueEpisodesSync().firstOrNull()
            if (fallbackQueue != null) {
                return createPlayableEpisodeItem(fallbackQueue)
            }
            val fallbackUnplayed = database.episodeDao().getRecentUnplayedEpisodesSync(1).firstOrNull()
            if (fallbackUnplayed != null) {
                return createPlayableEpisodeItem(fallbackUnplayed)
            }

            return item
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CUSTOM_ACTION_REWIND -> {
                    performSeekBack(15_000L)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_ACTION_FAST_FORWARD -> {
                    performSeekForward(90_000L)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_ACTION_SKIP_NEXT -> {
                    skipToNextEpisode(markCompleted = false)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_ACTION_SAVE_INSPIRATION -> {
                    saveCurrentInspiration("Captured from Android Auto")
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (keyEvent != null) {
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
                    KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                            performSeekForward(90_000L)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                            skipToNextEpisode(markCompleted = false)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND,
                    KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
                    KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                            performSeekBack(15_000L)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                            skipToPreviousEpisode()
                        }
                        return true
                    }
                }
            }
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }
    }

    override fun onDestroy() {
        persistCurrentProgress()
        progressJob?.cancel()
        stopLiveInactivityWatchdog()
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
