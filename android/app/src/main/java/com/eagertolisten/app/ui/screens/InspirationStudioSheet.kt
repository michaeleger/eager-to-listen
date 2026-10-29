package com.eagertolisten.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eagertolisten.app.data.EagerDatabase
import com.eagertolisten.app.data.model.InspirationClip
import com.eagertolisten.app.service.PlaybackController
import com.eagertolisten.app.ui.theme.BurntOrange
import com.eagertolisten.app.ui.theme.ForestGreenDarkest
import com.eagertolisten.app.ui.theme.ForestGreenElevated
import com.eagertolisten.app.ui.theme.ForestGreenSurface
import com.eagertolisten.app.ui.theme.TextMuted
import com.eagertolisten.app.ui.theme.TextPrimary
import com.eagertolisten.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspirationStudioSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { EagerDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()
    val inspirations by db.inspirationDao().getAllInspirations().collectAsState(initial = emptyList())
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ForestGreenDarkest
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = BurntOrange,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Creator Inspiration Studio",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }
            }

            Text(
                text = "Capture blog ideas, memorable quotes, and build your 'Best of' podcast feed on the fly (one-tap capture in Android Auto).",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            // Top action buttons: Share All & Export Best Of
            if (inspirations.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val md = buildMarkdownExport(inspirations)
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Podcast Inspirations & Blog Notes")
                                putExtra(Intent.EXTRA_TEXT, md)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Inspiration Notes"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BurntOrange),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share Notes", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            val opml = buildOpmlExport(inspirations)
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/xml"
                                putExtra(Intent.EXTRA_SUBJECT, "Best of Podcasts Feed.opml")
                                putExtra(Intent.EXTRA_TEXT, opml)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Export 'Best-Of' OPML"))
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Export 'Best Of'", fontSize = 12.sp, color = BurntOrange)
                    }
                }
            }

            if (inspirations.isEmpty()) {
                Surface(
                    color = ForestGreenElevated,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lightbulb,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No Inspirations Saved Yet",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "While listening, tap the Star/Lightbulb button on Android Auto or in the player to bookmark moments for blogs and future episodes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    items(inspirations, key = { it.id }) { clip ->
                        InspirationItemCard(
                            clip = clip,
                            onPlay = {
                                scope.launch(Dispatchers.IO) {
                                    val ep = db.episodeDao().getEpisodeByIdSync(clip.episodeId)
                                    if (ep != null) {
                                        withContext(Dispatchers.Main) {
                                            // Start right at the bookmark (a seek here would be overwritten by the async load)
                                            PlaybackController.playEpisode(context, ep, clip.podcastId, startPositionMs = clip.timestampSeconds * 1000L)
                                        }
                                    }
                                }
                            },
                            onDelete = {
                                scope.launch(Dispatchers.IO) {
                                    db.inspirationDao().deleteInspiration(clip)
                                }
                            },
                            onSaveNote = { newNote ->
                                scope.launch(Dispatchers.IO) {
                                    db.inspirationDao().updateInspiration(clip.copy(note = newNote))
                                }
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InspirationItemCard(
    clip: InspirationClip,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onSaveNote: (String) -> Unit
) {
    val context = LocalContext.current
    var isEditing by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf(clip.note) }

    Card(
        colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = clip.episodeTitle,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = clip.podcastTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = BurntOrange.copy(alpha = 0.2f),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = formatTimestamp(clip.timestampSeconds),
                        color = BurntOrange,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isEditing) {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Creator Note / Blog Idea", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = { isEditing = false },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Cancel", fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = {
                            onSaveNote(noteText)
                            isEditing = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BurntOrange),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Save Note", fontSize = 11.sp)
                    }
                }
            } else {
                Text(
                    text = if (clip.note.isNotBlank()) clip.note else "(Tap to add note / blog idea)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (clip.note.isNotBlank()) TextPrimary else TextMuted,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onPlay, modifier = Modifier.size(32.dp)) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play from timestamp", tint = BurntOrange)
                    }
                    IconButton(
                        onClick = {
                            val md = "> \"${clip.episodeTitle}\" - *${clip.podcastTitle}* (${formatTimestamp(clip.timestampSeconds)})\n> Note: ${clip.note}\nAudio: ${clip.audioUrl}"
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Blog Note", md))
                            Toast.makeText(context, "Copied Markdown to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy Markdown", tint = TextSecondary)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { isEditing = !isEditing },
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Edit", fontSize = 11.sp)
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = TextMuted)
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hrs > 0) {
        String.format("%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format("%02d:%02d", mins, secs)
    }
}

private fun buildMarkdownExport(clips: List<InspirationClip>): String {
    val sb = StringBuilder()
    sb.append("# Podcast Inspirations & Creator Best-Of\n\n")
    clips.forEach { clip ->
        sb.append("### ${clip.episodeTitle}\n")
        sb.append("**Podcast**: ${clip.podcastTitle}  \n")
        sb.append("**Timestamp**: [${formatTimestamp(clip.timestampSeconds)}](${clip.audioUrl})  \n")
        if (clip.note.isNotBlank()) {
            sb.append("> ${clip.note}\n")
        }
        sb.append("\n---\n\n")
    }
    return sb.toString()
}

private fun buildOpmlExport(clips: List<InspirationClip>): String {
    val uniquePodcasts = clips.distinctBy { it.podcastId }
    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<opml version=\"2.0\">\n")
    sb.append("  <head>\n")
    sb.append("    <title>Best of Podcasts (Curated by Eager to Listen)</title>\n")
    sb.append("  </head>\n")
    sb.append("  <body>\n")
    uniquePodcasts.forEach { p ->
        sb.append("    <outline text=\"${escapeXml(p.podcastTitle)}\" xmlUrl=\"${escapeXml(p.podcastId)}\" type=\"rss\" />\n")
    }
    sb.append("  </body>\n")
    sb.append("</opml>")
    return sb.toString()
}

private fun escapeXml(str: String): String {
    return str.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
