package com.eagertolisten.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eagertolisten.app.ui.theme.BurntOrange
import com.eagertolisten.app.ui.theme.TextMuted
import com.eagertolisten.app.ui.theme.TextSecondary

/**
 * "⋮" menu on an episode row: mark played / unplayed, and Podcast Addict-style
 * "mark this & older / this & newer as played" for the episode's feed.
 */
@Composable
fun EpisodeOverflowMenu(
    isPlayed: Boolean,
    onSetPlayed: (Boolean) -> Unit,
    onMarkThisAndOlderPlayed: (() -> Unit)? = null,
    onMarkThisAndNewerPlayed: (() -> Unit)? = null,
    isDownloaded: Boolean = false,
    onToggleDownload: (() -> Unit)? = null,
    onRemoveFromQueue: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    // Bulk actions can touch hundreds of episodes, so confirm first.
    var confirmOlder by remember { mutableStateOf(false) }
    var confirmNewer by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More episode actions",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // Row actions that used to be separate icons (kept here so episode rows have room for titles)
            if (onToggleDownload != null) {
                DropdownMenuItem(
                    text = { Text(if (isDownloaded) "Delete download" else "Download for offline") },
                    onClick = {
                        expanded = false
                        onToggleDownload()
                    }
                )
            }
            if (onRemoveFromQueue != null) {
                DropdownMenuItem(
                    text = { Text("Remove from queue") },
                    onClick = {
                        expanded = false
                        onRemoveFromQueue()
                    }
                )
            }
            if (onToggleDownload != null || onRemoveFromQueue != null) {
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = { Text(if (isPlayed) "Mark as unplayed" else "Mark as played") },
                onClick = {
                    expanded = false
                    onSetPlayed(!isPlayed)
                }
            )
            if (onMarkThisAndOlderPlayed != null) {
                DropdownMenuItem(
                    text = { Text("Mark this & older as played") },
                    onClick = {
                        expanded = false
                        confirmOlder = true
                    }
                )
            }
            if (onMarkThisAndNewerPlayed != null) {
                DropdownMenuItem(
                    text = { Text("Mark this & newer as played") },
                    onClick = {
                        expanded = false
                        confirmNewer = true
                    }
                )
            }
        }
    }

    if (confirmOlder && onMarkThisAndOlderPlayed != null) {
        ConfirmBulkMark(
            message = "Mark this episode and every older episode of this podcast as played?",
            onConfirm = { confirmOlder = false; onMarkThisAndOlderPlayed() },
            onDismiss = { confirmOlder = false }
        )
    }
    if (confirmNewer && onMarkThisAndNewerPlayed != null) {
        ConfirmBulkMark(
            message = "Mark this episode and every newer episode of this podcast as played?",
            onConfirm = { confirmNewer = false; onMarkThisAndNewerPlayed() },
            onDismiss = { confirmNewer = false }
        )
    }
}

@Composable
private fun ConfirmBulkMark(message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark as played") },
        text = { Text("$message You can mark episodes unplayed again one at a time.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Mark played", color = BurntOrange) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) } }
    )
}
