package com.eagertolisten.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.eagertolisten.app.data.model.Episode
import com.eagertolisten.app.ui.theme.BurntOrange
import com.eagertolisten.app.ui.theme.ForestGreenDarkest
import com.eagertolisten.app.ui.theme.ForestGreenElevated
import com.eagertolisten.app.ui.theme.Priority1Color
import com.eagertolisten.app.ui.theme.Priority2Color
import com.eagertolisten.app.ui.theme.Priority3Color
import com.eagertolisten.app.ui.theme.Priority4Color
import com.eagertolisten.app.ui.theme.Priority5Color
import com.eagertolisten.app.ui.theme.TextMuted
import com.eagertolisten.app.ui.theme.TextPrimary
import com.eagertolisten.app.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EpisodeItem(
    episode: Episode,
    priority: Int? = null,
    orderIndex: Int? = null,
    strategyTag: String? = null,
    podcastTitle: String? = null,
    onPlayClick: () -> Unit,
    onEnqueueClick: (() -> Unit)? = null,
    onRemoveClick: (() -> Unit)? = null,
    onDownloadClick: (() -> Unit)? = null,
    onItemClick: (() -> Unit)? = null,
    onSetPlayed: ((Boolean) -> Unit)? = null,
    onMarkThisAndOlderPlayed: (() -> Unit)? = null,
    onMarkThisAndNewerPlayed: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onItemClick?.invoke() ?: onPlayClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ForestGreenElevated)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Artwork with memory-optimized downsampling (168x168px max decode)
            if (!episode.artworkUrl.isNullOrBlank()) {
                val context = LocalContext.current
                val imageRequest = remember(episode.artworkUrl) {
                    ImageRequest.Builder(context)
                        .data(episode.artworkUrl)
                        .size(168, 168)
                        .crossfade(true)
                        .build()
                }
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (orderIndex != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = BurntOrange.copy(alpha = 0.25f)
                        ) {
                            Text(
                                text = if (orderIndex == 1) "#1 NEXT" else "#$orderIndex",
                                maxLines = 1,
                                softWrap = false,
                                color = BurntOrange,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 10.sp
                                ),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (priority != null) {
                        PriorityBadge(priority = priority)
                    }

                    if (episode.isLive) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Priority1Color
                        ) {
                            Text(
                                text = "● LIVE",
                                maxLines = 1,
                                softWrap = false,
                                color = androidx.compose.ui.graphics.Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 10.sp
                                ),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (episode.isCompleted) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = ForestGreenDarkest
                        ) {
                            Text(
                                text = "✓ Played",
                                maxLines = 1,
                                softWrap = false,
                                color = TextMuted,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 9.sp
                                ),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    if (episode.isDownloaded) {
                        // One compact indicator (the label used to appear three times and squeeze the row)
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Downloaded",
                            tint = BurntOrange,
                            modifier = Modifier.size(13.dp)
                        )
                    }

                    if (strategyTag != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = ForestGreenDarkest
                        ) {
                            Text(
                                text = strategyTag,
                                maxLines = 1,
                                softWrap = false,
                                color = TextSecondary,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 9.sp
                                ),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    Text(
                        text = podcastTitle ?: episode.podcastTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = BurntOrange,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // The show name gives up space first, so badges never get squeezed into wraps
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    ),
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 2.dp)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatDate(episode.publishedAt),
                        maxLines = 1,
                        softWrap = false,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    if (episode.durationSeconds > 0) {
                        Text(
                            text = "•  ${formatDuration(episode.durationSeconds)}",
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    if (episode.transcriptUrl != null) {
                        Text(
                            text = "•  CC",
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = BurntOrange
                        )
                    }
                    if (episode.chaptersUrl != null) {
                        Text(
                            text = "•  Chapters",
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = BurntOrange
                        )
                    }
                }
            }

            // Actions
            Row(verticalAlignment = Alignment.CenterVertically) {
                // With the ⋮ menu present, Download and Remove live in the menu to free up the row.
                val hasMenu = onSetPlayed != null
                if (onDownloadClick != null && !hasMenu) {
                    IconButton(onClick = onDownloadClick) {
                        Icon(
                            imageVector = if (episode.isDownloaded) Icons.Default.CheckCircle else Icons.Default.Download,
                            contentDescription = if (episode.isDownloaded) "Downloaded (Tap to delete)" else "Download for offline play",
                            tint = if (episode.isDownloaded) BurntOrange else TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (onEnqueueClick != null) {
                    IconButton(onClick = onEnqueueClick) {
                        Icon(
                            imageVector = Icons.Default.PlaylistAdd,
                            contentDescription = "Play Next (Add to Top)",
                            tint = BurntOrange
                        )
                    }
                }

                if (onRemoveClick != null && !hasMenu) {
                    IconButton(onClick = onRemoveClick) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove from Queue",
                            tint = TextMuted
                        )
                    }
                }

                if (onSetPlayed != null) {
                    EpisodeOverflowMenu(
                        isPlayed = episode.isCompleted,
                        onSetPlayed = onSetPlayed,
                        onMarkThisAndOlderPlayed = onMarkThisAndOlderPlayed,
                        onMarkThisAndNewerPlayed = onMarkThisAndNewerPlayed,
                        isDownloaded = episode.isDownloaded,
                        onToggleDownload = onDownloadClick,
                        onRemoveFromQueue = onRemoveClick
                    )
                }

                IconButton(onClick = onPlayClick) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
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
    }
}

@Composable
fun PriorityBadge(priority: Int) {
    val color = when (priority) {
        1 -> Priority1Color
        2 -> Priority2Color
        3 -> Priority3Color
        4 -> Priority4Color
        else -> Priority5Color
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.2f)
    ) {
        Text(
            text = "P$priority",
            color = color,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            ),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val hrs = mins / 60
    return if (hrs > 0) {
        "${hrs}h ${mins % 60}m"
    } else {
        "${mins}m"
    }
}
