package com.eagertolisten.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import com.eagertolisten.app.data.sync.SyncManager
import com.eagertolisten.app.data.sync.SyncStatus
import com.eagertolisten.app.ui.theme.BurntOrange
import com.eagertolisten.app.ui.theme.ForestGreenDarkest
import com.eagertolisten.app.ui.theme.ForestGreenElevated
import com.eagertolisten.app.ui.theme.ForestGreenSurface
import com.eagertolisten.app.ui.theme.Priority4Color
import com.eagertolisten.app.ui.theme.TextMuted
import com.eagertolisten.app.ui.theme.TextPrimary
import com.eagertolisten.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GoogleDriveSyncSheet(
    syncManager: SyncManager,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val syncState by syncManager.state.collectAsState()
    val scope = rememberCoroutineScope()

    var isSyncingNow by remember { mutableStateOf(false) }
    var syncFeedbackMessage by remember { mutableStateOf<String?>(null) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            syncManager.setSyncFolder(uri)
            scope.launch {
                isSyncingNow = true
                val result = syncManager.performSync()
                isSyncingNow = false
                result.onSuccess {
                    syncFeedbackMessage = it.message
                }.onFailure {
                    syncFeedbackMessage = "Sync failed: ${it.localizedMessage}"
                }
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = ForestGreenDarkest
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (syncState.status == SyncStatus.CONNECTED) Icons.Default.CloudDone else Icons.Default.CloudSync,
                        contentDescription = null,
                        tint = if (syncState.status == SyncStatus.CONNECTED) Priority4Color else BurntOrange,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Google Drive Cloud Sync",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "Native Android Storage • Zero API Keys",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                }
            }

            HorizontalDivider(color = ForestGreenElevated)

            if (syncState.status == SyncStatus.CONNECTED) {
                // Connected Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Status: Connected & Ready",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = Priority4Color
                            )
                            Icon(Icons.Default.Check, contentDescription = null, tint = Priority4Color, modifier = Modifier.size(18.dp))
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = BurntOrange, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = syncState.folderDisplayName ?: "Google Drive Folder",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = TextPrimary
                            )
                        }

                        if (syncState.lastSyncTimestamp > 0) {
                            val formattedTime = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
                                .format(Date(syncState.lastSyncTimestamp))
                            Text(
                                text = "Last synced: $formattedTime",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }

                        if (syncFeedbackMessage != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = ForestGreenSurface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = syncFeedbackMessage!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (syncFeedbackMessage!!.startsWith("Sync failed")) BurntOrange else Priority4Color,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        HorizontalDivider(color = ForestGreenSurface)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Auto-Sync", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = TextPrimary)
                                Text("Sync progress on app close and start", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            }
                            Switch(
                                checked = syncState.autoSyncEnabled,
                                onCheckedChange = { syncManager.setAutoSync(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = BurntOrange,
                                    checkedTrackColor = ForestGreenSurface
                                )
                            )
                        }
                    }
                }

                // Sync Actions
                Button(
                    onClick = {
                        scope.launch {
                            isSyncingNow = true
                            val result = syncManager.performSync()
                            isSyncingNow = false
                            result.onSuccess {
                                syncFeedbackMessage = it.message
                            }.onFailure {
                                syncFeedbackMessage = "Sync failed: ${it.localizedMessage}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = BurntOrange),
                    shape = RoundedCornerShape(10.dp),
                    enabled = !isSyncingNow
                ) {
                    if (isSyncingNow) {
                        CircularProgressIndicator(color = ForestGreenDarkest, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Syncing with Google Drive...")
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sync Now with Google Drive", fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = { folderPickerLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Change Google Drive Folder", color = TextPrimary)
                }

                OutlinedButton(
                    onClick = { syncManager.disconnect() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Disconnect Sync", color = BurntOrange)
                }

            } else {
                // Not Connected Guide Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = Priority4Color, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "How Native Cloud Sync Works",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                        }

                        Text(
                            text = "Eager to Listen connects directly through Android's built-in Google Drive storage provider. There are zero developer keys, zero account passwords, and zero OAuth setups required.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )

                        Text("1. Tap 'Select Google Drive Sync Folder' below.", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                        Text("2. In Android's file manager, open the side menu (☰) and tap Google Drive.", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                        Text("3. Choose or create a folder (e.g. PodcastsSync) and tap 'Use this folder'.", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                        Text("4. As long as your Google account stays on this phone, your queue, progress, and subscriptions remain continuously connected!", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    }
                }

                Button(
                    onClick = { folderPickerLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = BurntOrange),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select Google Drive Sync Folder", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
