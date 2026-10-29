package com.eagertolisten.app.service

import android.app.PendingIntent
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
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
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import com.eagertolisten.app.EagerApp
import com.eagertolisten.app.MainActivity
import com.eagertolisten.app.data.EagerDatabase
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.model.InspirationClip
import com.eagertolisten.app.data.model.Podcast
import com.eagertolisten.app.data.repository.PodcastRepository
import com.eagertolisten.app.settings.PlayerSettings
import com.eagertolisten.app.settings.RemoteButtonAction
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val repository by lazy { EagerApp.instance.repository }
    private val coordinator by lazy {
        PlaybackCoordinator(
            onEpisodeFinished = { episodeId -> onEpisodeFinished(episodeId) },
            onEpisodeSkipped = { episodeId, pos -> onEpisodeSkipped(episodeId, pos) },
            onEpisodeLeft = { episodeId, pos -> onEpisodeLeft(episodeId, pos) }
        )
    }

    private var networkRetryCount = 0
    private var currentErrorEpisodeId: String? = null
    private var errorRetryJob: Job? = null
    private var eofReconnectCount = 0
    private var currentEofEpisodeId: String? = null
    private var hasPrefetchedUpcomingNews = false
    private var queueMirrorJob: Job? = null
    private var currentPodcastObserveJob: Job? = null
    private val playbackHistory = ArrayDeque<String>()

    private fun recordPlaybackHistory(episodeId: String?) {
        if (episodeId == null) return
        if (playbackHistory.isEmpty() || playbackHistory.last() != episodeId) {
            playbackHistory.addLast(episodeId)
            if (playbackHistory.size > 20) {
                playbackHistory.removeFirst()
            }
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Player is accessed on the wrong thread: ${Thread.currentThread().name}"
        }
    }

    companion object {
        const val ACTION_PLAY_EPISODE = "com.eagertolisten.app.PLAY_EPISODE"
        const val ACTION_REVERT_TO_MAIN_QUEUE = "com.eagertolisten.app.REVERT_TO_MAIN_QUEUE"
        const val ACTION_PLAY_FROM_SEARCH = "com.eagertolisten.app.PLAY_FROM_SEARCH"
        const val EXTRA_EPISODE_ID = "extra_episode_id"
        const val EXTRA_PODCAST_ID = "extra_podcast_id"
        /** Optional: start playback at this position (ms) instead of the saved position, e.g. a bookmark. */
        const val EXTRA_START_POSITION_MS = "extra_start_position_ms"
        const val EXTRA_SEARCH_QUERY = "extra_search_query"

        // Android Auto & Custom Transport Actions
        const val CUSTOM_ACTION_REWIND = "com.eagertolisten.app.ACTION_REWIND"
        const val CUSTOM_ACTION_FAST_FORWARD = "com.eagertolisten.app.ACTION_FAST_FORWARD"
        const val CUSTOM_ACTION_SKIP_NEXT = "com.eagertolisten.app.ACTION_SKIP_NEXT"
        const val CUSTOM_ACTION_SKIP_PREVIOUS = "com.eagertolisten.app.ACTION_SKIP_PREVIOUS"
        const val CUSTOM_ACTION_SAVE_INSPIRATION = "com.eagertolisten.app.ACTION_SAVE_INSPIRATION"

        // Android Auto Media Browsing Categories
        const val ROOT_ID = "root"
        const val CATEGORY_PENDING_QUEUE = "category_pending_queue"
        const val CATEGORY_HOT_LIST = "category_hot_list"
        const val CATEGORY_NEWS = "category_news"
        const val CATEGORY_SUBSCRIPTIONS = "category_subscriptions"

        private const val MAX_AUTO_BROWSE_ITEMS = 100

        /** How many queued episodes follow the current one in the player (Android Auto's "Up next"). */
        private const val UPCOMING_QUEUE_ITEMS = 10
    }

    override fun onCreate() {
        super.onCreate()
        PlayerSettings.init(applicationContext)
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
            .setSeekBackIncrementMs(PlayerSettings.current().seekBackMs)
            .setSeekForwardIncrementMs(PlayerSettings.current().seekForwardMs)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // 3. Wrap in ForwardingPlayer to guarantee podcast transport controls:
        // - Keep COMMAND_SEEK_TO_NEXT / PREVIOUS advertised as available so Media3's
        //   MediaSessionStub & MediaSessionLegacyStub accept car steering wheel / Bluetooth AVRCP
        //   next/prev button presses instead of rejecting them.
        // - External Next/Previous (steering wheel, headset, Android Auto, lock screen) follow the
        //   user's "Car / headset Next & Previous" setting: JUMP (default) seeks forward/back by the
        //   configured skip times, SKIP_EPISODE navigates the queue. The in-app skip buttons send
        //   CUSTOM_ACTION_SKIP_NEXT / SKIP_PREVIOUS, so they always skip episodes.
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
                    // After an error (e.g. retries exhausted) the player is idle; re-prepare so play resumes
                    if (rawPlayer.playbackState == Player.STATE_IDLE) {
                        rawPlayer.prepare()
                    }
                    super.play()
                }
            }

            // Android Auto routes steering-wheel next/prev and the on-screen next/prev slots through
            // the same seekToNext/seekToPrevious callbacks, so both follow the remote-button setting.
            // Skipping a whole episode is always available as the "Skip episode" custom action.
            override fun seekToNext() {
                handleRemoteNext()
            }

            override fun seekToNextMediaItem() {
                handleRemoteNext()
            }

            override fun seekToPrevious() {
                handleRemotePrevious()
            }

            override fun seekToPreviousMediaItem() {
                handleRemotePrevious()
            }

            override fun seekForward() {
                performSeekForward(PlayerSettings.current().seekForwardMs)
            }

            override fun seekBack() {
                performSeekBack(PlayerSettings.current().seekBackMs)
            }

            // Report the user's current skip times (ExoPlayer's builder values are fixed at creation).
            override fun getSeekForwardIncrement(): Long = PlayerSettings.current().seekForwardMs

            override fun getSeekBackIncrement(): Long = PlayerSettings.current().seekBackMs

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

        // Keep the Android Auto / notification button labels ("Rewind 15s", "Forward 90s") in step
        // with the skip times chosen in Settings.
        serviceScope.launch {
            PlayerSettings.state
                .map { it.seekBackSeconds to it.seekForwardSeconds }
                .distinctUntilChanged()
                .collect {
                    try {
                        mediaLibrarySession?.setCustomLayout(buildCustomLayout())
                    } catch (e: Exception) {
                        android.util.Log.w("PlaybackService", "Could not refresh custom layout", e)
                    }
                }
        }

        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)
                val oldEpId = oldPosition.mediaItem?.mediaId
                val oldPosMs = oldPosition.positionMs
                val newEpId = newPosition.mediaItem?.mediaId
                val isAuto = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                val isSeek = reason == Player.DISCONTINUITY_REASON_SEEK

                coordinator.onPositionDiscontinuity(
                    oldEpisodeId = oldEpId,
                    oldPosMs = oldPosMs,
                    newEpisodeId = newEpId,
                    isAutoTransition = isAuto,
                    isSeek = isSeek
                )
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                val episodeId = mediaItem?.mediaId ?: return
                val currentId = PlaybackStateManager.state.value.currentEpisode?.id
                if (currentId != null && currentId != episodeId) {
                    recordPlaybackHistory(currentId)
                }
                serviceScope.launch(Dispatchers.IO) {
                    val episode = database.episodeDao().getEpisodeByIdSync(episodeId) ?: return@launch
                    val podcast = database.podcastDao().getPodcastByIdSync(episode.podcastId)
                    val queueSnapshot = database.queueDao().getQueueEpisodesSync()

                    withContext(Dispatchers.Main) {
                        checkMainThread()
                        applyShowSettings(episode, podcast)
                        // Refill "up next" from the queue whenever the playing episode changes
                        // (e.g. a single episode started from Android Auto's browse list). Leave out the
                        // episode we just moved off: its finished/skipped write may not have landed yet.
                        syncUpcomingItemsWithQueue(queueSnapshot, excludeIds = setOfNotNull(currentId))

                        // If freshly transitioning from beginning, apply saved position or skip intro
                        if (player.currentPosition < 2000L) {
                            val skipIntro = if (episode.isLive) 0 else (podcast?.skipIntroSeconds ?: 0)
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
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                PlaybackStateManager.setPlaying(isPlaying)
                LiveSessionManager.recordListeningHeartbeat(applicationContext)
                if (isPlaying) {
                    val currentEp = PlaybackStateManager.state.value.currentEpisode
                    if (currentEp?.isLive == true && LiveSessionManager.isLiveSessionExpired(applicationContext)) {
                        android.util.Log.i("PlaybackService", "Live session expired during pause (>= 90 minutes). Reverting to main queue.")
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
                                withContext(Dispatchers.Main) {
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

            override fun onPlayerError(error: PlaybackException) {
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
                        withContext(Dispatchers.Main) {
                            loadAndPlayTopQueueItem()
                        }
                    }
                } else if (currentEp != null) {
                    if (currentErrorEpisodeId != currentEp.id) {
                        currentErrorEpisodeId = currentEp.id
                        networkRetryCount = 0
                    }

                    val httpStatus = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                    when (val resolution = coordinator.classifyError(error.errorCode, networkRetryCount, httpStatus)) {
                        ErrorResolution.RETRY_NETWORK,
                        ErrorResolution.RETRY_AUDIO_DEVICE -> {
                            networkRetryCount++
                            val backoffMs = coordinator.retryDelayMs(resolution, networkRetryCount)
                            android.util.Log.w("PlaybackService", "Recoverable error (${error.errorCodeName}, $resolution). Retry $networkRetryCount after ${backoffMs}ms")
                            persistCurrentProgress()
                            errorRetryJob?.cancel()
                            errorRetryJob = serviceScope.launch(Dispatchers.Main) {
                                PlaybackStateManager.setBuffering(true)
                                delay(backoffMs)
                                // Respect a pause or episode change the listener made during the backoff
                                val stillCurrent = PlaybackStateManager.state.value.currentEpisode?.id == currentEp.id
                                if (isActive && stillCurrent && player.playWhenReady) {
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
                        ErrorResolution.MAX_RETRIES_REACHED -> {
                            android.util.Log.e("PlaybackService", "Max retries reached for ${currentEp.title} (${error.errorCodeName}). Pausing playback.")
                            persistCurrentProgress()
                            player.pause()
                            PlaybackStateManager.setBuffering(false)
                            Toast.makeText(applicationContext, "Playback interrupted, tap play to retry", Toast.LENGTH_LONG).show()
                        }
                        ErrorResolution.DISMISS_UNPLAYABLE -> {
                            persistCurrentProgress()
                            // Decide from the URI that actually failed: the download row may already be
                            // cleared (finishing at 98% deletes the file while the tail is still playing)
                            val failedLocalFile = player.currentMediaItem?.localConfiguration?.uri?.scheme == "file"
                            serviceScope.launch(Dispatchers.IO) {
                                // A bad or missing local download is not a bad episode: drop it and stream instead
                                if (failedLocalFile) {
                                    android.util.Log.w("PlaybackService", "Local file failed (${error.errorCodeName}); falling back to streaming for ${currentEp.title}")
                                    try {
                                        com.eagertolisten.app.data.download.PodcastDownloadManager(applicationContext).deleteDownloadedEpisode(currentEp.id)
                                    } catch (_: Exception) {}
                                    withContext(Dispatchers.Main) {
                                        loadAndPlayEpisode(currentEp.id, currentEp.podcastId)
                                    }
                                    return@launch
                                }

                                var refreshedEp: Episode? = null
                                try {
                                    repository.refreshFeed(currentEp.podcastId)
                                    refreshedEp = database.episodeDao().getEpisodeByIdSync(currentEp.id)
                                } catch (_: Exception) {}

                                if (refreshedEp != null && refreshedEp.audioUrl != currentEp.audioUrl) {
                                    android.util.Log.i("PlaybackService", "Enclosure URL changed after feed refresh. Retrying with new URL.")
                                    withContext(Dispatchers.Main) {
                                        loadAndPlayEpisode(refreshedEp.id, refreshedEp.podcastId)
                                    }
                                } else {
                                    android.util.Log.w("PlaybackService", "Dismissing unplayable episode: ${currentEp.title}")
                                    database.episodeDao().markEpisodeDismissed(currentEp.id, "UNPLAYABLE")
                                    database.queueDao().removeEpisodeFromQueue(currentEp.id)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(applicationContext, "Episode unplayable: ${currentEp.title}", Toast.LENGTH_SHORT).show()
                                        playNextQueueEpisode()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        })

        startQueueMirroring()

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
                val startPositionMs = intent.getLongExtra(EXTRA_START_POSITION_MS, -1L).takeIf { it >= 0L }
                if (!episodeId.isNullOrBlank()) {
                    loadAndPlayEpisode(episodeId, podcastId, startPositionMs)
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
            .filter { it.id != currentEp.id && !it.isCompleted && !it.isDismissed }
            .take(UPCOMING_QUEUE_ITEMS)
        val list = mutableListOf<MediaItem>()
        list.add(currentItem)
        for (ep in upcomingEpisodes) {
            list.add(createPlayableEpisodeItem(ep))
        }
        return list
    }

    private fun applyShowSettings(episode: Episode, podcast: Podcast?) {
        checkMainThread()
        val speed = if (episode.isLive) 1.0f else (podcast?.playbackSpeed ?: 1.0f)
        val skipIntro = if (episode.isLive) 0 else (podcast?.skipIntroSeconds ?: 0)
        val skipOutro = if (episode.isLive) 0 else (podcast?.skipOutroSeconds ?: 0)

        PlaybackStateManager.setEpisode(
            episode = episode,
            speed = speed,
            skipIntro = skipIntro,
            skipOutro = skipOutro,
            isPlaying = player.isPlaying
        )
        player.playbackParameters = PlaybackParameters(speed)
        hasPrefetchedUpcomingNews = false
        observeCurrentPodcast(episode.podcastId)
    }

    private fun observeCurrentPodcast(podcastId: String) {
        currentPodcastObserveJob?.cancel()
        currentPodcastObserveJob = serviceScope.launch {
            database.podcastDao().getPodcastById(podcastId).collect { podcast ->
                if (podcast != null) {
                    checkMainThread()
                    val currentEp = PlaybackStateManager.state.value.currentEpisode
                    if (currentEp != null && currentEp.podcastId == podcast.id && !currentEp.isLive) {
                        val newSpeed = podcast.playbackSpeed
                        if (player.playbackParameters.speed != newSpeed) {
                            player.playbackParameters = PlaybackParameters(newSpeed)
                            PlaybackStateManager.setSpeed(newSpeed)
                        }
                        val skipIntro = podcast.skipIntroSeconds
                        val skipOutro = podcast.skipOutroSeconds
                        PlaybackStateManager.setEpisode(
                            episode = currentEp,
                            speed = newSpeed,
                            skipIntro = skipIntro,
                            skipOutro = skipOutro,
                            isPlaying = player.isPlaying
                        )
                    }
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun startQueueMirroring() {
        queueMirrorJob?.cancel()
        queueMirrorJob = serviceScope.launch {
            database.queueDao().getQueueEpisodes()
                .debounce(300L)
                .collect { queueEpisodes ->
                    syncUpcomingItemsWithQueue(queueEpisodes)
                }
        }
    }

    /**
     * Keeps the player's "up next" items (what Android Auto, the lock screen and Bluetooth head
     * units show as the queue) in step with the programmed queue: the next [UPCOMING_QUEUE_ITEMS]
     * episodes after whatever is playing. Called when the queue changes AND whenever the playing
     * episode changes, so starting a single episode from Android Auto still shows the rest of the
     * queue (all shows), not just that one episode.
     */
    private fun syncUpcomingItemsWithQueue(queueEpisodes: List<Episode>, excludeIds: Set<String> = emptySet()) {
        checkMainThread()
        val currentItem = rawPlayer.currentMediaItem ?: return
        if (PlaybackStateManager.state.value.currentEpisode?.isLive == true) return
        val currentEpId = currentItem.mediaId

        val upcoming = queueEpisodes
            .filter { it.id != currentEpId && it.id !in excludeIds && !it.isCompleted && !it.isDismissed && !it.isLive }
            .take(UPCOMING_QUEUE_ITEMS)

        val newUpcomingItems = upcoming.map { createPlayableEpisodeItem(it) }
        val currentIndex = rawPlayer.currentMediaItemIndex
        val totalCount = rawPlayer.mediaItemCount

        val existingUpcomingIds = mutableListOf<String>()
        for (i in (currentIndex + 1) until totalCount) {
            existingUpcomingIds.add(rawPlayer.getMediaItemAt(i).mediaId)
        }
        val newUpcomingIds = newUpcomingItems.map { it.mediaId }

        if (existingUpcomingIds != newUpcomingIds) {
            if (totalCount > currentIndex + 1) {
                rawPlayer.removeMediaItems(currentIndex + 1, totalCount)
            }
            if (newUpcomingItems.isNotEmpty()) {
                rawPlayer.addMediaItems(newUpcomingItems)
            }
        }
    }

    private fun loadAndPrimeTopQueueItem() {
        serviceScope.launch(Dispatchers.IO) {
            database.queueDao().purgeLiveItemsFromQueue()
            val topEpisode = database.queueDao().getQueueEpisodesSync().firstOrNull()
                ?: database.episodeDao().getRecentUnplayedEpisodesSync(1).firstOrNull()
                ?: return@launch
            val podcast = database.podcastDao().getPodcastByIdSync(topEpisode.podcastId)

            val playlist = buildPlaylistForCurrentEpisode(topEpisode)

            withContext(Dispatchers.Main) {
                checkMainThread()
                if (rawPlayer.isPlaying) return@withContext
                applyShowSettings(topEpisode, podcast)
                val skipIntro = podcast?.skipIntroSeconds ?: 0
                val startPosMs = if (topEpisode.playbackPositionSeconds > 0) {
                    topEpisode.playbackPositionSeconds * 1000L
                } else if (skipIntro > 0) {
                    skipIntro * 1000L
                } else {
                    0L
                }
                player.setMediaItems(playlist, 0, startPosMs)
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

            stopLiveInactivityWatchdog()
            LiveSessionManager.clearLiveSession(applicationContext)

            val playlist = buildPlaylistForCurrentEpisode(topEpisode)

            withContext(Dispatchers.Main) {
                checkMainThread()
                applyShowSettings(topEpisode, podcast)
                val skipIntro = podcast?.skipIntroSeconds ?: 0
                val startPosMs = if (topEpisode.playbackPositionSeconds > 0) {
                    topEpisode.playbackPositionSeconds * 1000L
                } else if (skipIntro > 0) {
                    skipIntro * 1000L
                } else {
                    0L
                }
                player.setMediaItems(playlist, 0, startPosMs)
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
                    android.util.Log.i("PlaybackService", "Live station paused for >= 90 minutes. Reverting back to main playlist.")
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

    private fun loadAndPlayEpisode(episodeId: String, podcastId: String?, startPositionOverrideMs: Long? = null) {
        val currentId = PlaybackStateManager.state.value.currentEpisode?.id
        if (currentId != null && currentId != episodeId) {
            recordPlaybackHistory(currentId)
        }
        serviceScope.launch(Dispatchers.IO) {
            val episode = database.episodeDao().getEpisodeByIdSync(episodeId) ?: return@launch
            val podcast = if (podcastId != null) database.podcastDao().getPodcastByIdSync(podcastId) else database.podcastDao().getPodcastByIdSync(episode.podcastId)

            stopLiveInactivityWatchdog()
            if (episode.isLive) {
                LiveSessionManager.recordPlaybackActive(applicationContext, episode)
            } else {
                LiveSessionManager.clearLiveSession(applicationContext)
            }

            val playlist = if (episode.isLive) listOf(createPlayableEpisodeItem(episode)) else buildPlaylistForCurrentEpisode(episode)

            withContext(Dispatchers.Main) {
                checkMainThread()
                applyShowSettings(episode, podcast)
                val skipIntro = if (episode.isLive) 0 else (podcast?.skipIntroSeconds ?: 0)
                val startPosMs = if (startPositionOverrideMs != null && !episode.isLive) {
                    startPositionOverrideMs
                } else if (episode.playbackPositionSeconds > 0) {
                    episode.playbackPositionSeconds * 1000L
                } else if (skipIntro > 0) {
                    skipIntro * 1000L
                } else {
                    0L
                }
                player.setMediaItems(playlist, 0, startPosMs)
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
                checkMainThread()
                val currentPos = player.currentPosition
                val duration = player.duration
                val buffered = player.bufferedPosition

                PlaybackStateManager.setProgress(currentPos, duration, buffered)
                LiveSessionManager.recordListeningHeartbeat(applicationContext)

                // Pre-fetch upcoming NEWS feed at 90% progress
                if (!hasPrefetchedUpcomingNews && duration > 30_000L && currentPos >= (duration * 0.90)) {
                    hasPrefetchedUpcomingNews = true
                    prefetchUpcomingNewsIfEligible()
                }

                // Periodically persist playback progress every 10 seconds (20 ticks)
                tickCount++
                // 30 seconds of uninterrupted playback means the last error has recovered;
                // give the next interruption (call, tunnel, Bluetooth hop) a fresh retry budget
                if (tickCount == 60) {
                    networkRetryCount = 0
                }
                if (tickCount % 20 == 0) {
                    persistCurrentProgress()
                }

                // Check Skip Outro rule (only when episode is longer than skipOutro + 15s)
                val currentState = PlaybackStateManager.state.value
                val skipOutroMs = currentState.skipOutroSeconds * 1000L
                if (skipOutroMs > 0 && duration > skipOutroMs + 15_000L && (duration - currentPos) <= skipOutroMs) {
                    val epId = currentState.currentEpisode?.id
                    if (epId != null) {
                        onEpisodeFinished(epId)
                    }
                    playNextQueueEpisode()
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
        checkMainThread()
        val currentEpisode = PlaybackStateManager.state.value.currentEpisode ?: return
        val currentPosSec = (player.currentPosition / 1000L).coerceAtLeast(0L)
        val rawDuration = player.duration
        val isCompleted = rawDuration > 30_000L && player.currentPosition >= (rawDuration * 0.98)

        EagerApp.instance.appScope.launch(Dispatchers.IO) {
            repository.updatePlaybackProgress(currentEpisode.id, currentPosSec, isCompleted)
        }
    }

    private fun handleEpisodeEnded(forceEnd: Boolean = false) {
        checkMainThread()
        val currentEpisode = PlaybackStateManager.state.value.currentEpisode ?: return
        val currentPos = player.currentPosition
        val duration = player.duration
        val skipOutroMs = PlaybackStateManager.state.value.skipOutroSeconds * 1000L

        if (currentEofEpisodeId != currentEpisode.id) {
            currentEofEpisodeId = currentEpisode.id
            eofReconnectCount = 0
        }

        // Safety verification against premature stream severance (weak connection / dropped socket):
        // If duration is valid (> 30s) and playback stopped far before the actual ending:
        if (!forceEnd && duration > 30_000L && (duration - currentPos) > maxOf(20_000L, skipOutroMs) && eofReconnectCount < 3) {
            eofReconnectCount++
            android.util.Log.w(
                "PlaybackService",
                "handleEpisodeEnded: Premature EOF detected (attempt $eofReconnectCount/3) at ${currentPos / 1000}s / ${duration / 1000}s for '${currentEpisode.title}'. Attempting reconnect."
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

        android.util.Log.i("PlaybackService", "handleEpisodeEnded: finished '${currentEpisode.title}', advancing queue")
        onEpisodeFinished(currentEpisode.id)
        playNextQueueEpisode()
    }

    private fun onEpisodeFinished(episodeId: String) {
        EagerApp.instance.appScope.launch(Dispatchers.IO) {
            repository.markEpisodeFinished(episodeId)
            try {
                val syncMgr = com.eagertolisten.app.data.sync.SyncManager(applicationContext)
                if (syncMgr.state.value.folderUri != null && syncMgr.state.value.autoSyncEnabled) {
                    syncMgr.performSync()
                }
            } catch (e: Exception) {
                Log.w("PlaybackService", "Auto-sync after finish failed: ${e.message}")
            }
            try {
                com.eagertolisten.app.data.download.PodcastDownloadManager.enqueueAutoDownload(applicationContext)
            } catch (e: Exception) {
                Log.w("PlaybackService", "Auto-download after finish failed: ${e.message}")
            }
        }
    }

    private fun onEpisodeSkipped(episodeId: String, positionMs: Long) {
        val posSec = (positionMs / 1000L).coerceAtLeast(0L)
        EagerApp.instance.appScope.launch(Dispatchers.IO) {
            repository.markEpisodeSkipped(episodeId, posSec)
        }
    }

    private fun onEpisodeLeft(episodeId: String, positionMs: Long) {
        val posSec = (positionMs / 1000L).coerceAtLeast(0L)
        EagerApp.instance.appScope.launch(Dispatchers.IO) {
            database.episodeDao().updatePlaybackProgress(
                episodeId = episodeId,
                positionSec = posSec,
                isCompleted = false,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private fun playNextQueueEpisode() {
        checkMainThread()
        val currentId = PlaybackStateManager.state.value.currentEpisode?.id
        recordPlaybackHistory(currentId)
        serviceScope.launch(Dispatchers.IO) {
            val currentId = PlaybackStateManager.state.value.currentEpisode?.id
            val nextEpisodes = database.queueDao().getQueueEpisodesSync().filter { it.id != currentId && !it.isCompleted }
            if (nextEpisodes.isNotEmpty()) {
                val next = nextEpisodes.first()
                playNextQueueEpisodeWithNewsCheck(next)
            } else {
                withContext(Dispatchers.Main) {
                    checkMainThread()
                    if (rawPlayer.hasNextMediaItem()) {
                        rawPlayer.seekToNextMediaItem()
                        rawPlayer.play()
                    } else {
                        rawPlayer.stop()
                        PlaybackStateManager.setPlaying(false)
                    }
                }
            }
        }
    }

    private fun prefetchUpcomingNewsIfEligible() {
        serviceScope.launch(Dispatchers.IO) {
            val currentId = PlaybackStateManager.state.value.currentEpisode?.id
            val upcoming = database.queueDao().getQueueEpisodesSync().firstOrNull { it.id != currentId && !it.isCompleted } ?: return@launch
            val podcast = database.podcastDao().getPodcastByIdSync(upcoming.podcastId)
            if (podcast?.playOrderStrategy == "NEWS") {
                android.util.Log.i("PlaybackService", "Pre-fetching upcoming NEWS feed: ${podcast.title}")
                try {
                    withTimeout(8000L) {
                        repository.refreshFeed(podcast.id)
                    }
                    repository.purgeExpiredNewsEpisodes()
                } catch (e: Exception) {
                    android.util.Log.w("PlaybackService", "Pre-fetch of upcoming news failed", e)
                }
            }
        }
    }

    fun performSeekForward(incrementMs: Long = PlayerSettings.current().seekForwardMs) {
        checkMainThread()
        val currentEp = PlaybackStateManager.state.value.currentEpisode
        val rawDuration = rawPlayer.duration
        val fallbackDuration = (currentEp?.durationSeconds ?: 0L) * 1000L
        val durationMs = if (rawDuration > 0) rawDuration else fallbackDuration
        val currentPos = rawPlayer.currentPosition.coerceAtLeast(0L)
        val skipOutro = PlaybackStateManager.state.value.skipOutroSeconds

        val (result, targetPos) = coordinator.calculateSeekForward(
            currentPosMs = currentPos,
            durationMs = durationMs,
            skipOutroSeconds = skipOutro,
            incrementMs = incrementMs
        )

        if (result == SeekForwardResult.FINISH_EPISODE) {
            android.util.Log.i("PlaybackService", "performSeekForward: reached end of episode ($currentPos + $incrementMs >= $targetPos). Finishing and advancing.")
            if (currentEp != null) {
                onEpisodeFinished(currentEp.id)
            }
            playNextQueueEpisode()
        } else {
            rawPlayer.seekTo(targetPos)
            PlaybackStateManager.setProgress(targetPos, durationMs, rawPlayer.bufferedPosition)
            persistCurrentProgress()
        }
    }

    fun performSeekBack(decrementMs: Long = PlayerSettings.current().seekBackMs) {
        checkMainThread()
        val currentPos = rawPlayer.currentPosition.coerceAtLeast(0L)
        val skipIntro = PlaybackStateManager.state.value.skipIntroSeconds
        val targetPos = coordinator.calculateSeekBack(
            currentPosMs = currentPos,
            skipIntroSeconds = skipIntro,
            decrementMs = decrementMs
        )
        val duration = rawPlayer.duration
        rawPlayer.seekTo(targetPos)
        PlaybackStateManager.setProgress(targetPos, duration, rawPlayer.bufferedPosition)
        persistCurrentProgress()
    }

    /** Android Auto / notification custom buttons, labelled with the user's current skip times. */
    private fun buildCustomLayout(): List<CommandButton> {
        val settings = PlayerSettings.current()
        val customSkipBtn = CommandButton.Builder()
            .setDisplayName("Skip episode")
            .setSessionCommand(SessionCommand(CUSTOM_ACTION_SKIP_NEXT, Bundle.EMPTY))
            .setIconResId(android.R.drawable.ic_media_next)
            .build()

        val customRewindBtn = CommandButton.Builder()
            .setDisplayName("Rewind ${settings.seekBackSeconds}s")
            .setSessionCommand(SessionCommand(CUSTOM_ACTION_REWIND, Bundle.EMPTY))
            .setIconResId(android.R.drawable.ic_media_rew)
            .build()

        val customForwardBtn = CommandButton.Builder()
            .setDisplayName("Forward ${settings.seekForwardSeconds}s")
            .setSessionCommand(SessionCommand(CUSTOM_ACTION_FAST_FORWARD, Bundle.EMPTY))
            .setIconResId(android.R.drawable.ic_media_ff)
            .build()

        val customInspirationBtn = CommandButton.Builder()
            .setDisplayName("Save Idea")
            .setSessionCommand(SessionCommand(CUSTOM_ACTION_SAVE_INSPIRATION, Bundle.EMPTY))
            .setIconResId(android.R.drawable.btn_star_big_on)
            .build()

        return listOf(customSkipBtn, customRewindBtn, customForwardBtn, customInspirationBtn)
    }

    /**
     * Next from a steering wheel, headset, Android Auto or the lock screen.
     * Default (JUMP): seek forward by the configured skip time, so a stray press never drops the episode.
     */
    fun handleRemoteNext() {
        when (PlayerSettings.current().remoteButtonAction) {
            RemoteButtonAction.JUMP -> performSeekForward(PlayerSettings.current().seekForwardMs)
            RemoteButtonAction.SKIP_EPISODE -> skipCurrentEpisode()
        }
    }

    /** Previous from a steering wheel, headset, Android Auto or the lock screen. */
    fun handleRemotePrevious() {
        when (PlayerSettings.current().remoteButtonAction) {
            RemoteButtonAction.JUMP -> performSeekBack(PlayerSettings.current().seekBackMs)
            RemoteButtonAction.SKIP_EPISODE -> playPreviousEpisodeOrRestart()
        }
    }

    fun skipCurrentEpisode() {
        checkMainThread()
        val currentEp = PlaybackStateManager.state.value.currentEpisode
        if (currentEp == null) {
            loadAndPlayTopQueueItem()
            return
        }
        val currentPosMs = rawPlayer.currentPosition.coerceAtLeast(0L)
        val rawDuration = rawPlayer.duration
        val fallbackDuration = currentEp.durationSeconds * 1000L
        val durationMs = if (rawDuration > 0) rawDuration else fallbackDuration
        val skipOutro = PlaybackStateManager.state.value.skipOutroSeconds

        val handled = coordinator.onExplicitSkip(
            episodeId = currentEp.id,
            currentPosMs = currentPosMs,
            durationMs = durationMs,
            skipOutroSeconds = skipOutro,
            nowMs = SystemClock.elapsedRealtime()
        )
        if (!handled) {
            android.util.Log.i("PlaybackService", "skipCurrentEpisode ignored (debounced within 1000ms)")
            return
        }

        if (rawPlayer.hasNextMediaItem()) {
            rawPlayer.seekToNextMediaItem()
            rawPlayer.play()
        } else {
            playNextQueueEpisode()
        }
    }
    fun playPreviousEpisodeOrRestart() {
        checkMainThread()
        val currentEp = PlaybackStateManager.state.value.currentEpisode
        val currentPos = rawPlayer.currentPosition
        // If playing more than 5 seconds into the episode, restart current episode
        if (currentPos > 5_000L) {
            val skipIntro = PlaybackStateManager.state.value.skipIntroSeconds
            val startPosMs = if (skipIntro > 0) skipIntro * 1000L else 0L
            rawPlayer.seekTo(startPosMs)
            PlaybackStateManager.setProgress(startPosMs, rawPlayer.duration, rawPlayer.bufferedPosition)
            return
        }

        // At or near start (<= 5s): navigate to previous episode
        if (rawPlayer.hasPreviousMediaItem()) {
            rawPlayer.seekToPreviousMediaItem()
            rawPlayer.play()
            return
        }

        val previousId = playbackHistory.removeLastOrNull()
        if (previousId != null) {
            loadAndPlayEpisode(previousId, null)
        } else {
            rawPlayer.seekTo(0L)
            PlaybackStateManager.setProgress(0L, rawPlayer.duration, rawPlayer.bufferedPosition)
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

            // Pick the latest top queue episode after refresh, skipping the episode that just ended
            // (it can still be at the top of the queue until its "finished" write lands).
            val finishedId = PlaybackStateManager.state.value.currentEpisode?.id
            val refreshedNext = database.queueDao().getQueueEpisodesSync()
                .firstOrNull { it.id != finishedId && !it.isCompleted && !it.isDismissed } ?: next
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
                .add(SessionCommand(CUSTOM_ACTION_SKIP_PREVIOUS, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_ACTION_SAVE_INSPIRATION, Bundle.EMPTY))
                .build()

            // Keep COMMAND_SEEK_TO_NEXT / PREVIOUS enabled so Media3's MediaSessionStub and
            // MediaSessionLegacyStub accept car steering wheel / Bluetooth AVRCP next/prev buttons
            // and route them into ForwardingPlayer.seekToNext() / seekToPrevious(), which follow the
            // remote-button setting (handleRemoteNext / handleRemotePrevious).
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_FORWARD)
                .add(Player.COMMAND_SEEK_BACK)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

            val autoCustomLayout = buildCustomLayout()
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
                val podcast = database.podcastDao().getPodcastByIdSync(freshEp.podcastId)
                val currentPos = if (PlaybackStateManager.state.value.currentPositionMs > 0L) {
                    PlaybackStateManager.state.value.currentPositionMs
                } else {
                    freshEp.playbackPositionSeconds * 1000L
                }
                val playlist = buildPlaylistForCurrentEpisode(freshEp)
                serviceScope.launch(Dispatchers.Main) {
                    applyShowSettings(freshEp, podcast)
                }
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
                val skipIntro = podcast?.skipIntroSeconds ?: 0
                val posMs = if (first.playbackPositionSeconds > 0) {
                    first.playbackPositionSeconds * 1000L
                } else if (skipIntro > 0) {
                    skipIntro * 1000L
                } else {
                    0L
                }
                val playlist = buildPlaylistForCurrentEpisode(first)
                serviceScope.launch(Dispatchers.Main) {
                    applyShowSettings(first, podcast)
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
                val podcast = database.podcastDao().getPodcastByIdSync(first.podcastId)
                val posMs = if (first.playbackPositionSeconds > 0) first.playbackPositionSeconds * 1000L else 0L
                val item = createPlayableEpisodeItem(first)
                serviceScope.launch(Dispatchers.Main) {
                    applyShowSettings(first, podcast)
                }
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
                    performSeekBack(PlayerSettings.current().seekBackMs)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_ACTION_FAST_FORWARD -> {
                    performSeekForward(PlayerSettings.current().seekForwardMs)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_ACTION_SKIP_NEXT -> {
                    skipCurrentEpisode()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_ACTION_SKIP_PREVIOUS -> {
                    playPreviousEpisodeOrRestart()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_ACTION_SAVE_INSPIRATION -> {
                    val note = customCommand.customExtras.getString("note")
                        ?: args.getString("note")
                        ?: "Captured from Android Auto"
                    saveCurrentInspiration(note)
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
                            performSeekForward(PlayerSettings.current().seekForwardMs)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                            handleRemoteNext()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND,
                    KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
                    KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                            performSeekBack(PlayerSettings.current().seekBackMs)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                            handleRemotePrevious()
                        }
                        return true
                    }
                }
            }
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }
    }

    override fun onDestroy() {
        checkMainThread()
        val currentEpisode = PlaybackStateManager.state.value.currentEpisode
        if (currentEpisode != null) {
            val currentPosSec = (player.currentPosition / 1000L).coerceAtLeast(0L)
            val rawDuration = player.duration
            val isCompleted = rawDuration > 30_000L && player.currentPosition >= (rawDuration * 0.98)
            EagerApp.instance.appScope.launch(Dispatchers.IO) {
                repository.updatePlaybackProgress(currentEpisode.id, currentPosSec, isCompleted)
            }
        }
        queueMirrorJob?.cancel()
        currentPodcastObserveJob?.cancel()
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
