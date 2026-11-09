package com.eagertolisten.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.eagertolisten.app.data.model.FeedLink
import com.eagertolisten.app.data.model.FeedPreview
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
import com.eagertolisten.app.ui.viewmodel.FeedViewModel
import com.eagertolisten.app.ui.viewmodel.FeedViewModel.AddShowState
import java.text.DateFormat
import java.util.Date

/**
 * Add show: what opens when a podcast or RSS link is tapped, shared to the app, or pasted. Shows
 * what the feed is, then subscribes at the priority picked (priority 2 unless you change it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddShowSheet(feedViewModel: FeedViewModel) {
    val link by feedViewModel.addShowLink.collectAsState()
    val state by feedViewModel.addShowState.collectAsState()
    if (link == null) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var priority by remember(link) { mutableIntStateOf(FeedLink.DEFAULT_PRIORITY) }

    ModalBottomSheet(
        onDismissRequest = { feedViewModel.closeAddShow() },
        sheetState = sheetState,
        containerColor = ForestGreenDarkest
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.RssFeed, contentDescription = null, tint = BurntOrange, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Add show",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { feedViewModel.closeAddShow() }) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            when (val s = state) {
                is AddShowState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = BurntOrange, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Reading the feed…", color = TextSecondary)
                    }
                    Text(link.orEmpty(), color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }

                is AddShowState.Failed -> {
                    Text(s.message, color = TextPrimary)
                    Text(link.orEmpty(), color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { link?.let { feedViewModel.openAddShow(it) } },
                            colors = ButtonDefaults.buttonColors(containerColor = BurntOrange)
                        ) { Text("Try again") }
                        OutlinedButton(onClick = { feedViewModel.closeAddShow() }) { Text("Close", color = TextPrimary) }
                    }
                }

                is AddShowState.Ready -> PreviewBody(s.preview, priority, { priority = it }, busy = false,
                    onSubscribe = { feedViewModel.subscribeFromPreview(s.preview, priority) },
                    onClose = { feedViewModel.closeAddShow() })

                is AddShowState.Subscribing -> PreviewBody(s.preview, priority, { priority = it }, busy = true,
                    onSubscribe = {}, onClose = { feedViewModel.closeAddShow() })
            }
        }
    }
}

@Composable
private fun PreviewBody(
    preview: FeedPreview,
    priority: Int,
    onPriority: (Int) -> Unit,
    busy: Boolean,
    onSubscribe: () -> Unit,
    onClose: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = preview.artworkUrl.ifBlank { null },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(10.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                preview.title.ifBlank { "Untitled show" },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (preview.author.isNotBlank()) {
                Text(preview.author, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val latest = if (preview.newestEpisodeAt > 0) {
                " · latest ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(preview.newestEpisodeAt))}"
            } else ""
            Text(
                "${preview.episodeCount} episode${if (preview.episodeCount == 1) "" else "s"}$latest",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    if (preview.description.isNotBlank()) {
        Text(
            preview.description.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim(),
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }

    val following = preview.existing != null && preview.existing.unsubscribedAt == null
    if (following) {
        Text("You already follow this show.", color = TextPrimary)
        OutlinedButton(onClick = onClose) { Text("Close", color = TextPrimary) }
        return
    }

    // Following again keeps the show's own settings, priority included, so there's nothing to pick
    if (preview.existing == null) {
    Text("Priority", color = TextPrimary, style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..5).forEach { p ->
            val color = priorityColor(p)
            val selected = p == priority
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (selected) color else ForestGreenElevated,
                border = BorderStroke(1.dp, color),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !busy) { onPriority(p) }
            ) {
                Text(
                    "P$p",
                    color = if (selected) Color.Black else TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
    Text(
        when (priority) {
            1 -> "Highest: new episodes go straight to the Hot List"
            2 -> "High: near the top of Radio U"
            3 -> "Normal"
            4 -> "Low"
            else -> "Lowest: background listening"
        },
        color = TextMuted,
        style = MaterialTheme.typography.bodySmall
    )
    }

    Spacer(Modifier.height(2.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onSubscribe,
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = BurntOrange)
        ) {
            if (busy) {
                CircularProgressIndicator(color = TextPrimary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(if (preview.existing != null) "Follow again" else "Subscribe")
        }
        OutlinedButton(onClick = onClose, enabled = !busy) { Text("Not now", color = TextPrimary) }
    }
    if (preview.existing != null) {
        Text("You followed this before. Following again keeps its history and settings (priority ${preview.existing.priority}).", color = TextMuted, style = MaterialTheme.typography.bodySmall)
    }
}

private fun priorityColor(p: Int): Color = when (p) {
    1 -> Priority1Color
    2 -> Priority2Color
    3 -> Priority3Color
    4 -> Priority4Color
    else -> Priority5Color
}
