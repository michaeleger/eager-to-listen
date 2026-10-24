package com.eagertolisten.app

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.eagertolisten.app.service.PlaybackController
import com.eagertolisten.app.ui.components.MiniPlayer
import com.eagertolisten.app.ui.components.PlayerSheet
import com.eagertolisten.app.ui.screens.DiscoverScreen
import com.eagertolisten.app.ui.screens.QueueScreen
import com.eagertolisten.app.ui.screens.SettingsScreen
import com.eagertolisten.app.data.sync.SyncManager
import com.eagertolisten.app.ui.screens.SubscriptionsScreen
import com.eagertolisten.app.ui.theme.BurntOrange
import com.eagertolisten.app.ui.theme.EagerToListenTheme
import com.eagertolisten.app.ui.theme.ForestGreenDarkest
import com.eagertolisten.app.ui.theme.ForestGreenElevated
import com.eagertolisten.app.ui.theme.ForestGreenSurface
import com.eagertolisten.app.ui.theme.TextMuted
import com.eagertolisten.app.ui.theme.TextPrimary
import com.eagertolisten.app.ui.viewmodel.FeedViewModel
import com.eagertolisten.app.ui.viewmodel.PlayerViewModel
import com.eagertolisten.app.ui.viewmodel.QueueViewModel

enum class NavigationTab(val title: String, val icon: ImageVector) {
    QUEUE("Queue", Icons.Default.FormatListBulleted),
    SUBSCRIPTIONS("Podcasts", Icons.Default.Subscriptions),
    DISCOVER("Discover", Icons.Default.Explore),
    SETTINGS("Settings", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme_NoActionBar)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        handleVoiceOrSearchIntent(intent)

        // Schedule periodic background updates and backups (default 12 hours)
        val eagerPrefs = getSharedPreferences("eager_settings", android.content.Context.MODE_PRIVATE)
        val updateInterval = eagerPrefs.getInt(
            com.eagertolisten.app.worker.ScheduledUpdateWorker.PREF_KEY_UPDATE_INTERVAL_HOURS,
            com.eagertolisten.app.worker.ScheduledUpdateWorker.DEFAULT_UPDATE_INTERVAL_HOURS
        )
        com.eagertolisten.app.worker.ScheduledUpdateWorker.scheduleOrUpdate(this, updateInterval)

        setContent {
            EagerToListenTheme {
                val playerViewModel: PlayerViewModel = viewModel()
                val queueViewModel: QueueViewModel = viewModel()
                val feedViewModel: FeedViewModel = viewModel()
                val syncManager = remember { SyncManager(this@MainActivity) }

                val playbackState by playerViewModel.playbackState.collectAsState()
                val currentPodcast by playerViewModel.currentPodcast.collectAsState()
                val isPlayerExpanded by playerViewModel.isExpanded.collectAsState()
                val chapters by playerViewModel.chapters.collectAsState()
                val transcriptCues by playerViewModel.transcriptCues.collectAsState()
                val queueEpisodes by queueViewModel.queueEpisodes.collectAsState()
                val userMessage by feedViewModel.userMessage.collectAsState()

                val snackbarHostState = remember { SnackbarHostState() }
                var selectedTab by remember { mutableIntStateOf(0) }

                LaunchedEffect(userMessage) {
                    userMessage?.let {
                        snackbarHostState.showSnackbar(it)
                        feedViewModel.clearUserMessage()
                    }
                }

                // Silently refresh podcast feeds on startup and check for expired live sessions
                LaunchedEffect(Unit) {
                    feedViewModel.refreshAllFeeds(silent = true)
                    com.eagertolisten.app.service.LiveSessionManager.checkAndRevertIfExpired(
                        applicationContext,
                        com.eagertolisten.app.data.repository.PodcastRepository(applicationContext)
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ForestGreenDarkest
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Scaffold(
                            containerColor = ForestGreenDarkest,
                            snackbarHost = { SnackbarHost(snackbarHostState) },
                            bottomBar = {
                                Column {
                                    // Docked MiniPlayer (if audio loaded)
                                    if (playbackState.currentEpisode != null && !isPlayerExpanded) {
                                        MiniPlayer(
                                            playbackState = playbackState,
                                            onTogglePlayPause = { playerViewModel.togglePlayPause() },
                                            onSeekBack = { playerViewModel.seekBack() },
                                            onSeekForward = { playerViewModel.seekForward() },
                                            onClick = { playerViewModel.setExpanded(true) }
                                        )
                                    }

                                    // One-Handed Bottom Navigation Bar
                                    NavigationBar(
                                        containerColor = ForestGreenSurface,
                                        contentColor = TextPrimary
                                    ) {
                                        NavigationTab.values().forEachIndexed { index, tab ->
                                            NavigationBarItem(
                                                selected = selectedTab == index,
                                                onClick = { selectedTab = index },
                                                icon = {
                                                    Icon(
                                                        imageVector = tab.icon,
                                                        contentDescription = tab.title
                                                    )
                                                },
                                                label = {
                                                    Text(
                                                        text = tab.title,
                                                        fontSize = 11.sp
                                                    )
                                                },
                                                colors = NavigationBarItemDefaults.colors(
                                                    selectedIconColor = TextPrimary,
                                                    selectedTextColor = BurntOrange,
                                                    indicatorColor = BurntOrange,
                                                    unselectedIconColor = TextMuted,
                                                    unselectedTextColor = TextMuted
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        ) { paddingValues ->
                            Box(modifier = Modifier.padding(bottom = paddingValues.calculateBottomPadding())) {
                                when (selectedTab) {
                                    0 -> QueueScreen(
                                        queueViewModel = queueViewModel,
                                        onPlayEpisode = { ep ->
                                            playerViewModel.playEpisode(ep, ep.podcastId)
                                        },
                                        onNavigateToDiscover = { selectedTab = 2 }
                                    )
                                    1 -> SubscriptionsScreen(
                                        feedViewModel = feedViewModel,
                                        onPlayEpisode = { ep ->
                                            playerViewModel.playEpisode(ep, ep.podcastId)
                                        },
                                        onNavigateToDiscover = { selectedTab = 2 }
                                    )
                                    2 -> DiscoverScreen(
                                        feedViewModel = feedViewModel
                                    )
                                    3 -> SettingsScreen(syncManager = syncManager)
                                }
                            }
                        }

                        // Expandable Now Playing Player Sheet
                        AnimatedVisibility(
                            visible = isPlayerExpanded && playbackState.currentEpisode != null,
                            enter = slideInVertically(initialOffsetY = { it }),
                            exit = slideOutVertically(targetOffsetY = { it })
                        ) {
                            PlayerSheet(
                                playbackState = playbackState,
                                chapters = chapters,
                                transcriptCues = transcriptCues,
                                queueEpisodes = queueEpisodes,
                                currentPodcast = currentPodcast,
                                onTogglePlayPause = { playerViewModel.togglePlayPause() },
                                onSeekTo = { playerViewModel.seekTo(it) },
                                onSeekBack = { playerViewModel.seekBack() },
                                onSeekForward = { playerViewModel.seekForward() },
                                onSetSpeed = { playerViewModel.setSpeed(it) },
                                onPlayQueueEpisode = { ep ->
                                    playerViewModel.playEpisode(ep, ep.podcastId)
                                },
                                onDismiss = { playerViewModel.setExpanded(false) }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) {
            val repository = com.eagertolisten.app.data.repository.PodcastRepository(applicationContext)
            com.eagertolisten.app.service.LiveSessionManager.checkAndRevertIfExpired(applicationContext, repository)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceOrSearchIntent(intent)
    }

    private fun handleVoiceOrSearchIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        android.util.Log.i("MainActivity", "handleVoiceOrSearchIntent: action=$action, extras=${intent.extras}")
        if (action == "android.media.action.MEDIA_PLAY_FROM_SEARCH" ||
            action == "android.intent.action.MEDIA_SEARCH" ||
            action == "com.google.android.gms.actions.SEARCH_ACTION") {
            val query = intent.getStringExtra(SearchManager.QUERY)
                ?: intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE)
                ?: intent.getStringExtra("query")
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            android.util.Log.i("MainActivity", "Extracted voice search query: '$query'")
            PlaybackController.playFromSearch(this, query)
        }
    }

    override fun onDestroy() {
        PlaybackController.release()
        super.onDestroy()
    }
}
