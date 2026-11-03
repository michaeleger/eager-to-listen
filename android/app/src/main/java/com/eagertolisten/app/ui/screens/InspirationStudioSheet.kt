package com.eagertolisten.app.ui.screens

import com.eagertolisten.app.data.model.editedAt
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eagertolisten.app.data.EagerDatabase
import com.eagertolisten.app.data.model.InspirationClip
import com.eagertolisten.app.data.sync.BookmarkArchive
import com.eagertolisten.app.data.sync.BookmarkStore
import com.eagertolisten.app.data.sync.SyncManager
import com.eagertolisten.app.data.sync.SyncStatus
import com.eagertolisten.app.service.PlaybackController
import com.eagertolisten.app.ui.theme.BurntOrange
import com.eagertolisten.app.ui.theme.ForestGreenDarkest
import com.eagertolisten.app.ui.theme.ForestGreenElevated
import com.eagertolisten.app.ui.theme.TextMuted
import com.eagertolisten.app.ui.theme.TextPrimary
import com.eagertolisten.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Creator Inspiration Studio. The workflow, same as the desktop:
 * capture while listening -> every sync archives bookmarks to eagertolisten_bookmarks.jsonl in the
 * Drive sync folder (never cleared) -> add notes in a batch -> Share all -> clear what was shared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspirationStudioSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { EagerDatabase.getInstance(context) }
    val store = remember { BookmarkStore(context) }
    val syncManager = remember { SyncManager(context) }
    val syncState by syncManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val inspirations by db.inspirationDao().getAllInspirations().collectAsState(initial = emptyList())
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Ids handed off in the last Share all; offered for clearing once they're all in the archive
    var sharedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<InspirationClip?>(null) }
    // Bumped after a sync so archive status (kept in preferences) is read again
    val archiveTick = syncState.lastSyncTimestamp
    val syncing = syncState.status == SyncStatus.SYNCING
    val hasFolder = syncState.folderUri != null

    fun backUpNow() {
        if (!hasFolder) {
            Toast.makeText(context, "Choose a Google Drive sync folder in Settings first.", Toast.LENGTH_LONG).show()
            return
        }
        scope.launch {
            val r = syncManager.performSync(isManual = true)
            Toast.makeText(context, r.fold({ "Backed up to Drive" }, { "Backup failed: ${it.localizedMessage}" }), Toast.LENGTH_LONG).show()
        }
    }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Lightbulb, contentDescription = null, tint = BurntOrange, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Creator Inspiration Studio",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
            }
            Text(
                text = "Moments you saved while listening (Save Idea starts ${BookmarkArchive.LEAD_IN_SECONDS}s back). " +
                    "Every sync backs them up to ${BookmarkArchive.ARCHIVE_FILE} in your Drive sync folder, and that archive is never cleared.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            if (inspirations.isNotEmpty()) {
                val notArchived = remember(inspirations, archiveTick) { inspirations.count { !store.isArchived(it) } }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                val md = withContext(Dispatchers.IO) { buildShareList(db, store, inspirations) }
                                sharedIds = inspirations.map { it.id }
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Podcast bookmarks (${inspirations.size})")
                                    putExtra(Intent.EXTRA_TEXT, md)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share all bookmarks"))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BurntOrange),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share all ${inspirations.size}", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { backUpNow() },
                        enabled = !syncing && notArchived > 0,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            when {
                                syncing -> "Backing up…"
                                notArchived > 0 -> "Back up $notArchived to Drive"
                                else -> "All backed up ✓"
                            },
                            fontSize = 12.sp,
                            color = BurntOrange
                        )
                    }
                }
            }

            // After sharing: offer to clear exactly what was shared, once it's all in the archive
            val shared = inspirations.filter { it.id in sharedIds }
            if (shared.isNotEmpty()) {
                val unsafe = remember(shared, archiveTick) { shared.count { !store.isArchived(it) } }
                Surface(color = ForestGreenElevated, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Done with these?", fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(
                            when {
                                !hasFolder -> "Choose a Google Drive sync folder in Settings first; bookmarks are only cleared once they're safely in the archive."
                                unsafe > 0 -> "$unsafe of the ${shared.size} you shared ${if (unsafe == 1) "isn't" else "aren't"} in the Drive archive yet."
                                else -> "All ${shared.size} are in the Drive archive. Clearing removes them from the app here and, after the next sync, on your other devices. Bookmarks you add from now on aren't touched."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (hasFolder && unsafe == 0) {
                                Button(onClick = { confirmClear = true }, colors = ButtonDefaults.buttonColors(containerColor = BurntOrange), shape = RoundedCornerShape(8.dp)) {
                                    Text("Clear these ${shared.size}", fontSize = 12.sp)
                                }
                            } else if (hasFolder) {
                                Button(onClick = { backUpNow() }, enabled = !syncing, colors = ButtonDefaults.buttonColors(containerColor = BurntOrange), shape = RoundedCornerShape(8.dp)) {
                                    Text(if (syncing) "Backing up…" else "Back up to Drive now", fontSize = 12.sp)
                                }
                            }
                            TextButton(onClick = { sharedIds = emptyList() }) { Text("Not yet", fontSize = 12.sp, color = TextSecondary) }
                        }
                    }
                }
            }

            if (inspirations.isEmpty()) {
                Surface(color = ForestGreenElevated, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.Lightbulb, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No bookmarks in the app right now", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "While listening, tap Save Idea in the player or in Android Auto. Bookmarks you cleared are still in the Drive archive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f, fill = false)) {
                    items(inspirations, key = { it.id }) { clip ->
                        InspirationItemCard(
                            clip = clip,
                            archived = remember(clip, archiveTick) { store.isArchived(clip) },
                            excerpt = remember(clip.id, archiveTick) { store.excerpt(clip.id) },
                            onPlay = {
                                scope.launch(Dispatchers.IO) {
                                    val ep = db.episodeDao().getEpisodeByIdSync(clip.episodeId)
                                    withContext(Dispatchers.Main) {
                                        if (ep != null) {
                                            // Start right at the bookmark (a seek here would be overwritten by the async load)
                                            PlaybackController.playEpisode(context, ep, clip.podcastId, startPositionMs = clip.timestampSeconds * 1000L)
                                        } else {
                                            Toast.makeText(context, "That episode isn't in your library anymore.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            onCopy = {
                                scope.launch {
                                    val md = withContext(Dispatchers.IO) { buildShareList(db, store, listOf(clip)) }
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Bookmark", md))
                                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDelete = { confirmDelete = clip },
                            onSaveNote = { newNote ->
                                scope.launch(Dispatchers.IO) {
                                    val now = System.currentTimeMillis()
                                    db.inspirationDao().updateInspiration(clip.copy(note = newNote, updatedAt = maxOf(now, clip.editedAt + 1)))
                                }
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (confirmClear) {
        val toClear = inspirations.filter { it.id in sharedIds && store.isArchived(it) }
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear ${toClear.size} bookmarks?") },
            text = { Text("They're removed from the app and stay in the Drive archive.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    sharedIds = emptyList()
                    scope.launch(Dispatchers.IO) {
                        store.recordRemoval(toClear)
                        toClear.forEach { db.inspirationDao().deleteInspirationById(it.id) }
                        if (hasFolder) syncManager.performSync(isManual = false)
                    }
                    Toast.makeText(context, "Cleared ${toClear.size}. They're still in the Drive archive.", Toast.LENGTH_LONG).show()
                }) { Text("Clear", color = BurntOrange) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }

    confirmDelete?.let { clip ->
        val inArchive = store.isArchived(clip)
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this bookmark?") },
            text = {
                Text(
                    "${BookmarkArchive.formatTs(clip.timestampSeconds)} in \"${clip.episodeTitle}\". " +
                        if (inArchive) "It stays in the Drive archive." else "It will still be saved to the Drive archive on the next sync."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch(Dispatchers.IO) {
                        store.recordRemoval(listOf(clip))
                        db.inspirationDao().deleteInspirationById(clip.id)
                    }
                }) { Text("Delete", color = BurntOrange) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

/** The share list (Markdown), with episode page, audio, transcript and moment links. */
private suspend fun buildShareList(db: EagerDatabase, store: BookmarkStore, clips: List<InspirationClip>): String {
    val items = clips.map { c ->
        val ep = db.episodeDao().getEpisodeByIdSync(c.episodeId)
        val show = db.podcastDao().getPodcastByIdSync(c.podcastId)?.title ?: c.podcastTitle
        BookmarkArchive.ShareItem(
            clip = c,
            showTitle = show,
            episodeTitle = ep?.title ?: c.episodeTitle,
            episodeLink = ep?.link,
            transcriptUrl = ep?.transcriptUrl,
            excerpt = store.excerpt(c.id)
        )
    }
    return BookmarkArchive.shareMarkdown(items, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date()))
}

@Composable
private fun InspirationItemCard(
    clip: InspirationClip,
    archived: Boolean,
    excerpt: BookmarkArchive.Excerpt?,
    onPlay: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onSaveNote: (String) -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var noteText by remember(clip.note) { mutableStateOf(clip.note) }

    Card(colors = CardDefaults.cardColors(containerColor = ForestGreenElevated), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(clip.episodeTitle, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(clip.podcastTitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (archived) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(imageVector = Icons.Default.CloudDone, contentDescription = "In the Drive archive", tint = TextSecondary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(8.dp), color = BurntOrange.copy(alpha = 0.2f), modifier = Modifier.padding(start = 8.dp)) {
                    Text(BookmarkArchive.formatTs(clip.timestampSeconds), color = BurntOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isEditing) {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Note / blog idea", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = { noteText = clip.note; isEditing = false }, shape = RoundedCornerShape(6.dp)) { Text("Cancel", fontSize = 11.sp) }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(onClick = { onSaveNote(noteText.trim()); isEditing = false }, colors = ButtonDefaults.buttonColors(containerColor = BurntOrange), shape = RoundedCornerShape(6.dp)) { Text("Save note", fontSize = 11.sp) }
                }
            } else {
                Text(
                    text = clip.note.ifBlank { "Tap Edit to add a note or blog idea" },
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = if (clip.note.isBlank()) FontStyle.Italic else FontStyle.Normal),
                    color = if (clip.note.isNotBlank()) TextPrimary else TextMuted,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            excerpt?.let {
                Text(
                    text = "“${it.text}”" + if (it.timing == "estimated") "  (transcript position estimated)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onPlay, modifier = Modifier.size(32.dp)) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play from here", tint = BurntOrange)
                    }
                    IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy", tint = TextSecondary)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { isEditing = !isEditing },
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("Edit", fontSize = 11.sp) }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = TextMuted)
                    }
                }
            }
        }
    }
}
