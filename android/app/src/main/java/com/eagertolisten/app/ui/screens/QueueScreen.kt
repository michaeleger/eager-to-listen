package com.eagertolisten.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.data.repository.QueueSortRule
import com.eagertolisten.app.ui.components.EpisodeItem
import com.eagertolisten.app.ui.theme.BurntOrange
import com.eagertolisten.app.ui.theme.ForestGreenDarkest
import com.eagertolisten.app.ui.theme.ForestGreenElevated
import com.eagertolisten.app.ui.theme.TextMuted
import com.eagertolisten.app.ui.theme.TextPrimary
import com.eagertolisten.app.ui.theme.TextSecondary
import com.eagertolisten.app.ui.viewmodel.QueueFilterMode
import com.eagertolisten.app.ui.viewmodel.QueueViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    queueViewModel: QueueViewModel,
    onPlayEpisode: (Episode) -> Unit,
    onNavigateToDiscover: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayedEpisodes by queueViewModel.displayedEpisodes.collectAsState()
    val queueEpisodes by queueViewModel.queueEpisodes.collectAsState()
    val hotListEpisodes by queueViewModel.hotListEpisodes.collectAsState()
    val unplayedEpisodes by queueViewModel.unplayedEpisodes.collectAsState()
    val playedEpisodes by queueViewModel.playedEpisodes.collectAsState()
    val podcasts by queueViewModel.podcasts.collectAsState()
    val filterMode by queueViewModel.filterMode.collectAsState()
    val activeSortRule by queueViewModel.activeSortRule.collectAsState()

    val podcastsMap = remember(podcasts) { podcasts.associateBy { it.id } }
    var showSortMenu by remember { mutableStateOf(false) }

    val totalPendingDurationSec = remember(displayedEpisodes) {
        displayedEpisodes.sumOf { it.durationSeconds }
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = when (filterMode) {
                            QueueFilterMode.QUEUE -> "Pending Playlist"
                            QueueFilterMode.HOT_LIST -> "Hot List (Priority 1)"
                            QueueFilterMode.UNPLAYED -> "Unplayed Episodes"
                            QueueFilterMode.PLAYED -> "Played / Completed"
                        },
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = BurntOrange
                    )
                    if (displayedEpisodes.isNotEmpty()) {
                        val durationStr = formatTotalDuration(totalPendingDurationSec)
                        Text(
                            text = "${displayedEpisodes.size} episodes${if (durationStr.isNotEmpty()) " • $durationStr" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            },
            actions = {
                // Refresh & regenerate fair interleaved playlist
                IconButton(onClick = { queueViewModel.rebuildProgrammedQueue() }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Programmed Order",
                        tint = TextPrimary
                    )
                }

                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.Sort,
                            contentDescription = "Sort Queue",
                            tint = TextPrimary
                        )
                    }

                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Curated Airwaves: Radio U (Fair Flow + Anti-Binge)") },
                            onClick = {
                                queueViewModel.setSortRule(QueueSortRule.PROGRAMMED_FAIR)
                                showSortMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Priority, then Date (Oldest First) [Classic]") },
                            onClick = {
                                queueViewModel.setSortRule(QueueSortRule.PRIORITY_THEN_DATE_ASC)
                                showSortMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Priority, then Date (Newest First)") },
                            onClick = {
                                queueViewModel.setSortRule(QueueSortRule.PRIORITY_THEN_DATE_DESC)
                                showSortMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Date (Newest First)") },
                            onClick = {
                                queueViewModel.setSortRule(QueueSortRule.DATE_DESC)
                                showSortMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Date (Oldest First)") },
                            onClick = {
                                queueViewModel.setSortRule(QueueSortRule.DATE_ASC)
                                showSortMenu = false
                            }
                        )
                    }
                }

                if (filterMode == QueueFilterMode.QUEUE && displayedEpisodes.isNotEmpty()) {
                    IconButton(onClick = { queueViewModel.clearQueue() }) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear Queue",
                            tint = TextMuted
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = ForestGreenDarkest
            )
        )

        // Filter Chips: Pending Queue, Hot List (Priority 1), Unplayed, Played
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filterMode == QueueFilterMode.QUEUE,
                onClick = { queueViewModel.setFilterMode(QueueFilterMode.QUEUE) },
                label = { Text("Pending (${queueEpisodes.size})") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = BurntOrange,
                    selectedLabelColor = TextPrimary,
                    containerColor = ForestGreenElevated,
                    labelColor = TextSecondary
                )
            )

            FilterChip(
                selected = filterMode == QueueFilterMode.HOT_LIST,
                onClick = { queueViewModel.setFilterMode(QueueFilterMode.HOT_LIST) },
                label = { Text("Hot List (${hotListEpisodes.size})") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = BurntOrange,
                    selectedLabelColor = TextPrimary,
                    containerColor = ForestGreenElevated,
                    labelColor = TextSecondary
                )
            )

            FilterChip(
                selected = filterMode == QueueFilterMode.UNPLAYED,
                onClick = { queueViewModel.setFilterMode(QueueFilterMode.UNPLAYED) },
                label = { Text("Unplayed (${unplayedEpisodes.size})") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = BurntOrange,
                    selectedLabelColor = TextPrimary,
                    containerColor = ForestGreenElevated,
                    labelColor = TextSecondary
                )
            )

            FilterChip(
                selected = filterMode == QueueFilterMode.PLAYED,
                onClick = { queueViewModel.setFilterMode(QueueFilterMode.PLAYED) },
                label = { Text("Played (${playedEpisodes.size})") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = BurntOrange,
                    selectedLabelColor = TextPrimary,
                    containerColor = ForestGreenElevated,
                    labelColor = TextSecondary
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (displayedEpisodes.isEmpty()) {
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
                        text = when (filterMode) {
                            QueueFilterMode.QUEUE -> "Your pending playlist is empty"
                            QueueFilterMode.HOT_LIST -> "No Priority 1 episodes waiting"
                            QueueFilterMode.UNPLAYED -> "No unplayed episodes"
                            QueueFilterMode.PLAYED -> "No completed episodes"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = TextMuted
                    )
                    OutlinedButton(onClick = onNavigateToDiscover) {
                        Text("Discover Podcasts", color = BurntOrange)
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
                itemsIndexed(displayedEpisodes, key = { _, ep -> ep.id }) { index, ep ->
                    val podcast = podcastsMap[ep.podcastId]
                    val strategyBadge = if (ep.skipCount > 0 && ep.skippedAt != null) {
                        "Skipped"
                    } else if (filterMode == QueueFilterMode.QUEUE && podcast != null) {
                        when (podcast.playOrderStrategy) {
                            "CHRONOLOGICAL" -> "Serial"
                            "NEWS" -> "News"
                            "REVERSE_CHRONOLOGICAL" -> "Reverse"
                            else -> "Evergreen"
                        }
                    } else null

                    EpisodeItem(
                        episode = ep,
                        priority = podcast?.priority,
                        orderIndex = if (filterMode == QueueFilterMode.QUEUE) index + 1 else null,
                        strategyTag = strategyBadge,
                        podcastTitle = podcast?.title,
                        onPlayClick = { onPlayEpisode(ep) },
                        onEnqueueClick = if (filterMode != QueueFilterMode.QUEUE) {
                            // Play Next: Insert ONLY this episode at top of queue
                            { queueViewModel.enqueueEpisodeTop(ep.id, ep.podcastId) }
                        } else null,
                        onRemoveClick = if (filterMode == QueueFilterMode.QUEUE) {
                            { queueViewModel.removeEpisode(ep.id) }
                        } else null,
                        onDownloadClick = { queueViewModel.toggleEpisodeDownload(ep) }
                    )
                }
            }
        }
    }
}

private fun formatTotalDuration(seconds: Long): String {
    if (seconds <= 0) return ""
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
