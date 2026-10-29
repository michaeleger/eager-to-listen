package com.eagertolisten.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import com.eagertolisten.app.service.PlaybackController
import androidx.compose.material3.Card
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.eagertolisten.app.settings.PlayerSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.eagertolisten.app.data.model.Chapter
import com.eagertolisten.app.data.model.TranscriptCue
import com.eagertolisten.app.service.PlaybackState
import com.eagertolisten.app.ui.theme.BurntOrange
import com.eagertolisten.app.ui.theme.BurntOrangeLight
import com.eagertolisten.app.ui.theme.ForestGreenDarkest
import com.eagertolisten.app.ui.theme.ForestGreenElevated
import com.eagertolisten.app.ui.theme.ForestGreenSurface
import com.eagertolisten.app.ui.theme.Priority1Color
import com.eagertolisten.app.ui.theme.TextMuted
import com.eagertolisten.app.ui.theme.TextPrimary
import com.eagertolisten.app.ui.theme.TextSecondary
import org.json.JSONArray

import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import com.eagertolisten.app.data.model.Podcast
import com.eagertolisten.app.data.model.PodcastValue
import com.eagertolisten.app.ui.components.BoostSheet
import com.eagertolisten.app.data.model.Episode
import androidx.compose.foundation.lazy.itemsIndexed

@Composable
fun PlayerSheet(
    playbackState: PlaybackState,
    chapters: List<Chapter>,
    transcriptCues: List<TranscriptCue>,
    queueEpisodes: List<Episode> = emptyList(),
    currentPodcast: Podcast? = null,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onPlayQueueEpisode: (Episode) -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val episode = playbackState.currentEpisode ?: return
    val context = LocalContext.current
    val playerSettings by PlayerSettings.state.collectAsState()

    val effectiveFundingUrl = episode.fundingUrl ?: currentPodcast?.customDonationUrl ?: currentPodcast?.fundingUrl
    val effectiveValueJson = episode.valueJson ?: currentPodcast?.valueJson
    val podcastValue = remember(effectiveValueJson) { PodcastValue.fromJson(effectiveValueJson) }
    var showBoostSheet by remember { mutableStateOf(false) }

    var selectedTab by remember { mutableIntStateOf(0) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableFloatStateOf(0f) }

    val currentPosition = if (isDraggingSlider) dragPositionMs.toLong() else playbackState.currentPositionMs
    val duration = playbackState.durationMs

    val speeds = listOf(0.8f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    var showSpeedDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = ForestGreenDarkest
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The player is drawn edge-to-edge outside the Scaffold, so keep its controls clear
                // of the status bar, camera cut-out and gesture/navigation bar.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: Collapse icon & Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Collapse",
                        tint = TextSecondary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Text(
                    text = "Now Playing",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextSecondary
                )

                // Speed chip
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ForestGreenElevated,
                    modifier = if (!episode.isLive) Modifier.clickable { showSpeedDialog = true } else Modifier
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Speed",
                            tint = if (episode.isLive) TextSecondary else BurntOrange,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (episode.isLive) "Live: 1.0x" else "${playbackState.playbackSpeed}x",
                            color = if (episode.isLive) TextSecondary else TextPrimary,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tab bar: Player, Notes, Up Next, Chapters, Transcript
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = ForestGreenSurface,
                contentColor = BurntOrange,
                edgePadding = 12.dp,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = BurntOrange
                    )
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Player", fontSize = 12.sp) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Notes", fontSize = 12.sp) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Text(
                            text = "Up Next" + if (queueEpisodes.isNotEmpty()) " (${queueEpisodes.size})" else "",
                            fontSize = 12.sp
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = {
                        Text(
                            text = "Chapters" + if (chapters.isNotEmpty()) " (${chapters.size})" else "",
                            fontSize = 12.sp
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    text = {
                        Text(
                            text = "Transcript" + if (transcriptCues.isNotEmpty()) " (CC)" else "",
                            fontSize = 12.sp
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Body content based on selected tab
            when (selectedTab) {
                0 -> {
                    // MAIN PLAYER VIEW
                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Artwork with memory-optimized downsampling (720x720px max decode)
                        val playerArtRequest = remember(episode.artworkUrl) {
                            ImageRequest.Builder(context)
                                .data(episode.artworkUrl)
                                .size(720, 720)
                                .crossfade(true)
                                .build()
                        }
                        AsyncImage(
                            model = playerArtRequest,
                            contentDescription = null,
                            modifier = Modifier
                                .size(240.dp)
                                .clip(RoundedCornerShape(20.dp)),
                            contentScale = ContentScale.Crop
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Live Stream Indicator & Title
                        if (episode.isLive || playbackState.isLive) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Priority1Color,
                                modifier = Modifier.padding(bottom = 6.dp)
                            ) {
                                Text(
                                    text = "● LIVE STREAM",
                                    color = androidx.compose.ui.graphics.Color.White,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 11.sp
                                    ),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        } else if (episode.isDownloaded) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = ForestGreenElevated,
                                modifier = Modifier.padding(bottom = 6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Downloaded",
                                        tint = BurntOrange,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "DOWNLOADED FOR OFFLINE PLAY",
                                        color = BurntOrange,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp
                                        )
                                    )
                                }
                            }
                        }

                        // Episode Title & Podcast Title
                        Text(
                            text = episode.title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            ),
                            color = TextPrimary,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = episode.podcastTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = BurntOrange,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (!effectiveFundingUrl.isNullOrBlank() || podcastValue != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!effectiveFundingUrl.isNullOrBlank()) {
                                    OutlinedButton(
                                        onClick = {
                                            val safeUrl = if (!effectiveFundingUrl.startsWith("http://") && !effectiveFundingUrl.startsWith("https://")) {
                                                "https://$effectiveFundingUrl"
                                            } else effectiveFundingUrl
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)))
                                            }
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BurntOrange),
                                        shape = RoundedCornerShape(20.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Favorite,
                                            contentDescription = null,
                                            tint = BurntOrange,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Support Show", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (podcastValue != null) {
                                    Button(
                                        onClick = { showBoostSheet = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = BurntOrange),
                                        shape = RoundedCornerShape(20.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ElectricBolt,
                                            contentDescription = null,
                                            tint = TextPrimary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("⚡ Boost", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Scrubber Slider
                        val sliderValue = if (duration > 0) {
                            (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                        } else 0f

                        Slider(
                            value = sliderValue,
                            onValueChange = { frac ->
                                isDraggingSlider = true
                                dragPositionMs = frac * duration
                            },
                            onValueChangeFinished = {
                                onSeekTo(dragPositionMs.toLong())
                                isDraggingSlider = false
                            },
                            enabled = !episode.isLive && !playbackState.isLive,
                            colors = SliderDefaults.colors(
                                thumbColor = if (episode.isLive || playbackState.isLive) Priority1Color else BurntOrange,
                                activeTrackColor = if (episode.isLive || playbackState.isLive) Priority1Color else BurntOrange,
                                inactiveTrackColor = ForestGreenElevated
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Timestamps
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatTime(currentPosition),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            if (episode.isLive || playbackState.isLive) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Priority1Color.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "LIVE BROADCAST",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = Priority1Color,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            } else {
                                Text(
                                    text = if (duration > 0) "-${formatTime(duration - currentPosition)}" else "--:--",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Audio Controls: Previous Episode, Rewind, Play/Pause, Forward, Next Episode (skip times from Settings)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Skip to Previous Episode Button
                            IconButton(
                                onClick = { PlaybackController.skipToPrevious() },
                                modifier = Modifier.size(46.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = "Previous Episode",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(30.dp)
                                )
                            }

                            // Rewind Button (label below the icon so it stays readable)
                            IconButton(
                                onClick = onSeekBack,
                                modifier = Modifier.size(60.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Replay,
                                        contentDescription = "Rewind ${playerSettings.seekBackSeconds} seconds",
                                        tint = TextPrimary,
                                        modifier = Modifier.size(30.dp)
                                    )
                                    Text(
                                        text = "${playerSettings.seekBackSeconds}s",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                            }

                            // Play / Pause Button
                            IconButton(
                                onClick = onTogglePlayPause,
                                modifier = Modifier.size(72.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = BurntOrange,
                                    modifier = Modifier.size(66.dp),
                                    shadowElevation = 6.dp
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                                            tint = TextPrimary,
                                            modifier = Modifier.size(38.dp)
                                        )
                                    }
                                }
                            }

                            // Forward Skip Button (label below the icon so it stays readable)
                            IconButton(
                                onClick = onSeekForward,
                                modifier = Modifier.size(60.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.FastForward,
                                        contentDescription = "Forward ${playerSettings.seekForwardSeconds} seconds",
                                        tint = TextPrimary,
                                        modifier = Modifier.size(30.dp)
                                    )
                                    Text(
                                        text = "${playerSettings.seekForwardSeconds}s",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                            }

                            // Skip to Next Episode Button
                            IconButton(
                                onClick = { PlaybackController.skipToNext() },
                                modifier = Modifier.size(46.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "Skip to Next Episode",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Creator Inspiration Quick Bookmark Button (can be hidden in Settings > Player Controls)
                        if (playerSettings.showBookmarkButton) {
                            OutlinedButton(
                                onClick = {
                                    PlaybackController.saveInspiration("Bookmarked from Player")
                                    android.widget.Toast.makeText(context, "Saved inspiration idea at ${formatTime(currentPosition)}!", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BurntOrange),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lightbulb,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = BurntOrange
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save Idea / Bookmark", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                1 -> {
                    // EPISODE NOTES / RSS DESCRIPTION TAB
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Text(
                            text = "Episode Notes & Summary",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = BurntOrange,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        if (episode.description.isNullOrBlank()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No show notes or RSS description found for this episode.",
                                    color = TextMuted,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                item {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Text(
                                                text = episode.title,
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                color = TextPrimary
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = episode.podcastTitle,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = BurntOrange
                                            )

                                            if (!effectiveFundingUrl.isNullOrBlank() || podcastValue != null) {
                                                Spacer(modifier = Modifier.height(10.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    if (!effectiveFundingUrl.isNullOrBlank()) {
                                                        OutlinedButton(
                                                            onClick = {
                                                                val safeUrl = if (!effectiveFundingUrl.startsWith("http://") && !effectiveFundingUrl.startsWith("https://")) {
                                                                    "https://$effectiveFundingUrl"
                                                                } else effectiveFundingUrl
                                                                runCatching {
                                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)))
                                                                }
                                                            },
                                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = BurntOrange),
                                                            shape = RoundedCornerShape(10.dp),
                                                            modifier = Modifier.weight(1f)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Favorite,
                                                                contentDescription = null,
                                                                tint = BurntOrange,
                                                                modifier = Modifier.size(15.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text("Support Show", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }

                                                    if (podcastValue != null) {
                                                        Button(
                                                            onClick = { showBoostSheet = true },
                                                            colors = ButtonDefaults.buttonColors(containerColor = BurntOrange),
                                                            shape = RoundedCornerShape(10.dp),
                                                            modifier = Modifier.weight(1f)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.ElectricBolt,
                                                                contentDescription = null,
                                                                tint = TextPrimary,
                                                                modifier = Modifier.size(15.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text("⚡ Boost", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                                        }
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(12.dp))

                                            AndroidView(
                                                factory = { ctx ->
                                                    TextView(ctx).apply {
                                                        setTextColor(android.graphics.Color.WHITE)
                                                        textSize = 14f
                                                        setLineSpacing(6f, 1.25f)
                                                        movementMethod = LinkMovementMethod.getInstance()
                                                        setLinkTextColor(android.graphics.Color.parseColor("#EB5E17"))
                                                    }
                                                },
                                                update = { tv ->
                                                    val raw = episode.description ?: ""
                                                    tv.text = HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_COMPACT)
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    // UP NEXT / PENDING PLAYLIST TAB
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Text(
                            text = "Pending Playlist (${queueEpisodes.size} episodes)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = BurntOrange,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        if (queueEpisodes.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No upcoming episodes in pending playlist",
                                    color = TextMuted,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(queueEpisodes, key = { _, ep -> ep.id }) { index, ep ->
                                    EpisodeItem(
                                        episode = ep,
                                        orderIndex = index + 1,
                                        onPlayClick = { onPlayQueueEpisode(ep) }
                                    )
                                }
                            }
                        }
                    }
                }

                3 -> {
                    // CHAPTERS TAB (Podcasting 2.0 & Description Timestamps)
                    if (chapters.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No chapters available for this episode.",
                                color = TextMuted,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(chapters) { chapter ->
                                val isCurrentChapter = (currentPosition / 1000.0) >= chapter.startTime &&
                                    (chapter.endTime == null || (currentPosition / 1000.0) < chapter.endTime)

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSeekTo((chapter.startTime * 1000).toLong()) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isCurrentChapter) BurntOrange.copy(alpha = 0.2f) else ForestGreenElevated
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (!chapter.img.isNullOrBlank()) {
                                            AsyncImage(
                                                model = chapter.img,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(RoundedCornerShape(6.dp)),
                                                contentScale = ContentScale.Crop
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = chapter.title,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = if (isCurrentChapter) BurntOrange else TextPrimary
                                            )
                                            Text(
                                                text = formatTime((chapter.startTime * 1000).toLong()),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextMuted
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                4 -> {
                    // TRANSCRIPT TAB (Podcasting 2.0 & Riverside text/plain)
                    if (transcriptCues.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No transcript found for this episode.",
                                color = TextMuted,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        val listState = rememberLazyListState()
                        val activeIndex = transcriptCues.indexOfFirst {
                            currentPosition in it.startMs..it.endMs
                        }

                        LaunchedEffect(activeIndex) {
                            if (activeIndex >= 0) {
                                listState.animateScrollToItem((activeIndex - 1).coerceAtLeast(0))
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(transcriptCues) { cue ->
                                val isActive = currentPosition in cue.startMs..cue.endMs
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSeekTo(cue.startMs) },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isActive) BurntOrange.copy(alpha = 0.25f) else ForestGreenElevated
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Text(
                                            text = formatTime(cue.startMs),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = if (isActive) BurntOrange else TextMuted,
                                            modifier = Modifier.width(48.dp)
                                        )
                                        
                                        // Detect "Speaker: dialogue" format (e.g. Riverside.fm transcripts)
                                        val colonIndex = if (!cue.text.startsWith("http")) cue.text.indexOf(":") else -1
                                        if (colonIndex in 1..80 && !cue.text.substring(0, colonIndex).contains("\n")) {
                                            val speaker = cue.text.substring(0, colonIndex).trim()
                                            val speech = cue.text.substring(colonIndex + 1).trim()
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = speaker,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = if (isActive) BurntOrange else TextPrimary
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = speech,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = if (isActive) TextPrimary else TextSecondary
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = cue.text,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (isActive) TextPrimary else TextSecondary,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Speed picker bottom sheet / dialog
    if (showSpeedDialog) {
        val availableSpeeds = (speeds + playbackState.playbackSpeed).distinct().sorted()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = {
                Column {
                    Text("Playback Speed", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Applies to every episode of ${episode.podcastTitle}",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableSpeeds.forEach { spd ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSetSpeed(spd)
                                    showSpeedDialog = false
                                }
                                .padding(vertical = 10.dp, horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${spd}x",
                                color = if (playbackState.playbackSpeed == spd) BurntOrange else TextPrimary,
                                fontWeight = if (playbackState.playbackSpeed == spd) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            containerColor = ForestGreenSurface
        )
    }

    if (showBoostSheet && podcastValue != null) {
        BoostSheet(
            podcastTitle = episode.podcastTitle,
            episodeTitle = episode.title,
            value = podcastValue,
            onDismiss = { showBoostSheet = false }
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val mins = totalSec / 60
    val secs = totalSec % 60
    val hrs = mins / 60
    return if (hrs > 0) {
        String.format("%d:%02d:%02d", hrs, mins % 60, secs)
    } else {
        String.format("%02d:%02d", mins, secs)
    }
}
