package com.eagertolisten.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.model.Podcast
import com.eagertolisten.app.data.model.PodcastValue
import com.eagertolisten.app.ui.components.BoostSheet
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.TextButton
import com.eagertolisten.app.ui.components.openSupportUrl
import com.eagertolisten.app.ui.components.supportUrl
import com.eagertolisten.app.ui.components.PriorityBadge
import com.eagertolisten.app.ui.theme.BurntOrange
import com.eagertolisten.app.ui.theme.ForestGreenDarkest
import com.eagertolisten.app.ui.theme.ForestGreenElevated
import com.eagertolisten.app.ui.theme.ForestGreenSurface
import com.eagertolisten.app.ui.theme.Priority1Color
import com.eagertolisten.app.ui.theme.TextMuted
import com.eagertolisten.app.ui.theme.TextPrimary
import com.eagertolisten.app.ui.theme.TextSecondary
import com.eagertolisten.app.ui.viewmodel.FeedViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    feedViewModel: FeedViewModel,
    onPlayEpisode: ((Episode) -> Unit)? = null,
    onNavigateToDiscover: () -> Unit,
    modifier: Modifier = Modifier
) {
    val podcasts by feedViewModel.subscribedPodcasts.collectAsState()
    val liveEpisodes by feedViewModel.liveEpisodes.collectAsState()
    val isRefreshingAll by feedViewModel.isRefreshingAll.collectAsState()
    val selectedPodcast by feedViewModel.selectedPodcast.collectAsState()
    val feedEpisodes by feedViewModel.feedEpisodes.collectAsState()

    val context = LocalContext.current
    var editingPodcast by remember { mutableStateOf<Podcast?>(null) }
    var boostPodcast by remember { mutableStateOf<Podcast?>(null) }
    var boostEpisode by remember { mutableStateOf<Episode?>(null) }
    val customizerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val detailSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Subscriptions (${podcasts.size})",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
            },
            actions = {
                if (podcasts.isNotEmpty()) {
                    if (isRefreshingAll) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 14.dp)
                                .size(22.dp),
                            color = BurntOrange,
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        IconButton(onClick = { feedViewModel.refreshAllFeeds() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh All Feeds",
                                tint = BurntOrange
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ForestGreenDarkest)
        )

        // Active Live Broadcasts Banner (Podcasting 2.0 Live Streams)
        if (liveEpisodes.isNotEmpty()) {
            val liveEp = liveEpisodes.first()
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clickable { onPlayEpisode?.invoke(liveEp) },
                colors = CardDefaults.cardColors(containerColor = Priority1Color.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Priority1Color)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Priority1Color,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Radio,
                                contentDescription = null,
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "LIVE NOW",
                                color = androidx.compose.ui.graphics.Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = liveEp.title,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = liveEp.podcastTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = BurntOrange,
                            maxLines = 1
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Priority1Color,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Tune in live",
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        if (podcasts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "You haven't subscribed to any podcasts yet.",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextMuted
                    )
                    OutlinedButton(onClick = onNavigateToDiscover) {
                        Text("Find Podcasts to Add", color = BurntOrange)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(podcasts, key = { it.id }) { podcast ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { feedViewModel.selectPodcast(podcast) },
                        colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (podcast.artworkUrl.isNotBlank()) {
                                val imageRequest = remember(podcast.artworkUrl) {
                                    ImageRequest.Builder(context)
                                        .data(podcast.artworkUrl)
                                        .size(180, 180)
                                        .crossfade(true)
                                        .build()
                                }
                                AsyncImage(
                                    model = imageRequest,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    PriorityBadge(priority = podcast.priority)
                                    Text(
                                        text = "${podcast.playbackSpeed}x",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = BurntOrange
                                    )
                                    if (podcast.skipIntroSeconds > 0) {
                                        Text(
                                            text = "• +${podcast.skipIntroSeconds}s",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted
                                        )
                                    }
                                }

                                Text(
                                    text = podcast.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp
                                    ),
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )

                                Text(
                                    text = podcast.author.ifBlank { "Podcast Feed" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Actions: Support (when the show has a donate link), Refresh & Edit Feed Settings
                            val rowSupportUrl = podcast.supportUrl()
                            if (rowSupportUrl != null) {
                                IconButton(onClick = { openSupportUrl(context, rowSupportUrl) }) {
                                    Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = "Support ${podcast.title}",
                                        tint = BurntOrange
                                    )
                                }
                            }

                            IconButton(onClick = { feedViewModel.refreshFeed(podcast.id) }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh",
                                    tint = TextSecondary
                                )
                            }

                            IconButton(onClick = { editingPodcast = podcast }) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Feed Settings",
                                    tint = BurntOrange
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Podcast Detail Bottom Sheet (Podcast RSS Description & Episode List)
    selectedPodcast?.let { podcast ->
        ModalBottomSheet(
            onDismissRequest = { feedViewModel.selectPodcast(null) },
            sheetState = detailSheetState,
            containerColor = ForestGreenSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                // Back to the podcast list (the sheet covers the bottom tabs, so give it an obvious exit)
                TextButton(
                    onClick = { feedViewModel.selectPodcast(null) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = BurntOrange,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("All Podcasts", color = BurntOrange, fontWeight = FontWeight.Bold)
                }

                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (podcast.artworkUrl.isNotBlank()) {
                        val headerImageRequest = remember(podcast.artworkUrl) {
                            ImageRequest.Builder(context)
                                .data(podcast.artworkUrl)
                                .size(210, 210)
                                .crossfade(true)
                                .build()
                        }
                        AsyncImage(
                            model = headerImageRequest,
                            contentDescription = null,
                            modifier = Modifier
                                .size(70.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = podcast.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = podcast.author.ifBlank { "Podcast Feed" },
                            style = MaterialTheme.typography.bodySmall,
                            color = BurntOrange
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            PriorityBadge(priority = podcast.priority)
                            Text(
                                text = "${feedEpisodes.size} episodes",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }

                    IconButton(onClick = { editingPodcast = podcast }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Feed Settings",
                            tint = BurntOrange
                        )
                    }
                }

                // Support Show & Boost Actions Banner. Support (a plain donate link that always works)
                // is the filled, primary button; Boost is secondary.
                val supportUrl = podcast.supportUrl()
                val podcastValue = remember(podcast.valueJson) { PodcastValue.fromJson(podcast.valueJson) }

                if (!supportUrl.isNullOrBlank() || podcastValue != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (supportUrl != null) {
                            Button(
                                onClick = { openSupportUrl(context, supportUrl) },
                                colors = ButtonDefaults.buttonColors(containerColor = BurntOrange),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = TextPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Support Show", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            }
                        }

                        if (podcastValue != null) {
                            OutlinedButton(
                                onClick = {
                                    boostPodcast = podcast
                                    boostEpisode = null
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BurntOrange),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ElectricBolt,
                                    contentDescription = null,
                                    tint = BurntOrange,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Boost", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Scrollable content: Show RSS Description & Episode list
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Podcast Description Card
                    if (podcast.description.isNotBlank()) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = "About Show (RSS Description)",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = BurntOrange
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    AndroidView(
                                        factory = { ctx ->
                                            TextView(ctx).apply {
                                                setTextColor(android.graphics.Color.WHITE)
                                                textSize = 13.5f
                                                setLineSpacing(4f, 1.2f)
                                                movementMethod = LinkMovementMethod.getInstance()
                                                setLinkTextColor(android.graphics.Color.parseColor("#EB5E17"))
                                            }
                                        },
                                        update = { tv ->
                                            tv.text = HtmlCompat.fromHtml(podcast.description, HtmlCompat.FROM_HTML_MODE_COMPACT)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    // Episodes Header
                    item {
                        Text(
                            text = "Episodes (${feedEpisodes.size})",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = BurntOrange,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    // Episode Rows with Expandable Shownotes / Description
                    items(feedEpisodes, key = { it.id }) { ep ->
                        var isExpanded by remember { mutableStateOf(false) }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = ep.title,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = TextPrimary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(top = 4.dp)
                                        ) {
                                            Text(
                                                text = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(ep.publishedAt)),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextMuted
                                            )
                                            if (ep.durationSeconds > 0) {
                                                val m = ep.durationSeconds / 60
                                                val s = ep.durationSeconds % 60
                                                Text(
                                                    text = "• ${m}m ${s}s",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextSecondary
                                                )
                                            }
                                            if (ep.transcriptUrl != null) {
                                                Text("• CC", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = BurntOrange)
                                            }
                                            if (ep.chaptersUrl != null || (ep.description?.contains("Chapters", ignoreCase = true) == true)) {
                                                Text("• Chapters", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = BurntOrange)
                                            }
                                            if (ep.isCompleted) {
                                                Text("• ✓ Played", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = TextMuted)
                                            }
                                            if (ep.isDownloaded) {
                                                Text("• 💾 Downloaded", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = BurntOrange)
                                            }
                                        }
                                    }

                                    // Episode Action Buttons: Download, Add to Queue, Boost & Play
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        // Download for Offline Play
                                        IconButton(
                                            onClick = { feedViewModel.toggleEpisodeDownload(ep) },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (ep.isDownloaded) Icons.Default.CheckCircle else Icons.Default.Download,
                                                contentDescription = if (ep.isDownloaded) "Downloaded (Tap to delete)" else "Download for offline listening",
                                                tint = if (ep.isDownloaded) BurntOrange else TextSecondary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        val effectiveValJson = ep.valueJson ?: podcast.valueJson
                                        if (effectiveValJson != null) {
                                            IconButton(
                                                onClick = {
                                                    boostPodcast = podcast
                                                    boostEpisode = ep
                                                },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.ElectricBolt,
                                                    contentDescription = "Boost Episode",
                                                    tint = BurntOrange,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }

                                        // Add to Queue (Play Next)
                                        IconButton(
                                            onClick = { feedViewModel.enqueueEpisodeTop(ep.id, ep.podcastId) },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlaylistAdd,
                                                contentDescription = "Play Next",
                                                tint = BurntOrange,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }

                                        // Mark played / unplayed, and mark this & older / newer as played
                                        com.eagertolisten.app.ui.components.EpisodeOverflowMenu(
                                            isPlayed = ep.isCompleted,
                                            onSetPlayed = { played -> feedViewModel.setEpisodePlayed(ep, played) },
                                            onMarkThisAndOlderPlayed = { feedViewModel.markFeedEpisodesPlayed(ep, includeOlder = true) },
                                            onMarkThisAndNewerPlayed = { feedViewModel.markFeedEpisodesPlayed(ep, includeOlder = false) }
                                        )

                                        IconButton(onClick = { onPlayEpisode?.invoke(ep) }) {
                                            Surface(
                                                shape = RoundedCornerShape(18.dp),
                                                color = BurntOrange,
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = Icons.Default.PlayArrow,
                                                        contentDescription = "Play",
                                                        tint = TextPrimary,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Toggle Show Notes / RSS Description
                                if (!ep.description.isNullOrBlank()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { isExpanded = !isExpanded }
                                            .padding(top = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isExpanded) "Hide Show Notes" else "View Show Notes / Description",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = BurntOrange
                                        )
                                        Icon(
                                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null,
                                            tint = BurntOrange,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    AnimatedVisibility(visible = isExpanded) {
                                        Column(modifier = Modifier.padding(top = 8.dp)) {
                                            AndroidView(
                                                factory = { ctx ->
                                                    TextView(ctx).apply {
                                                        setTextColor(android.graphics.Color.LTGRAY)
                                                        textSize = 12.5f
                                                        setLineSpacing(4f, 1.2f)
                                                        movementMethod = LinkMovementMethod.getInstance()
                                                        setLinkTextColor(android.graphics.Color.parseColor("#EB5E17"))
                                                    }
                                                },
                                                update = { tv ->
                                                    val raw = ep.description ?: ""
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
            }
        }
    }

    // Feed Customizer Bottom Sheet (Auto-Saving)
    editingPodcast?.let { podcast ->
        ModalBottomSheet(
            onDismissRequest = { editingPodcast = null },
            sheetState = customizerSheetState
        ) {
            FeedCustomizerSheet(
                podcast = podcast,
                onSave = { priority, skipIntro, skipOutro, speed, autoAdd, maxEpisodes, category, playOrderStrategy, newsRetentionDays, consecutivePlayLimit, customDonationUrl ->
                    feedViewModel.updateFeedRules(
                        podcastId = podcast.id,
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
                },
                onUnsubscribe = {
                    feedViewModel.unsubscribe(podcast.id)
                    editingPodcast = null
                    feedViewModel.selectPodcast(null)
                },
                onDismiss = { editingPodcast = null }
            )
        }
    }

    // Podcasting 2.0 Lightning V4V Boost Sheet
    boostPodcast?.let { podcast ->
        val effectiveValJson = boostEpisode?.valueJson ?: podcast.valueJson
        val pVal = remember(effectiveValJson) { PodcastValue.fromJson(effectiveValJson) }
        if (pVal != null) {
            BoostSheet(
                podcastTitle = podcast.title,
                episodeTitle = boostEpisode?.title ?: "Show Support",
                value = pVal,
                onDismiss = {
                    boostPodcast = null
                    boostEpisode = null
                }
            )
        }
    }
}
