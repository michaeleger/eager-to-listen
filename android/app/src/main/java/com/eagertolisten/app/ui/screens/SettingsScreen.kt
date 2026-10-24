package com.eagertolisten.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.eagertolisten.app.importer.PodcastAddictImporter
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eagertolisten.app.data.sync.SyncManager
import com.eagertolisten.app.data.sync.SyncStatus
import com.eagertolisten.app.ui.theme.BurntOrange
import com.eagertolisten.app.ui.theme.ForestGreenDarkest
import kotlinx.coroutines.launch
import com.eagertolisten.app.ui.theme.ForestGreenElevated
import com.eagertolisten.app.ui.theme.ForestGreenSurface
import com.eagertolisten.app.ui.theme.Priority4Color
import com.eagertolisten.app.ui.theme.TextMuted
import com.eagertolisten.app.ui.theme.TextPrimary
import com.eagertolisten.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    syncManager: SyncManager,
    modifier: Modifier = Modifier
) {
    val syncState by syncManager.state.collectAsState()
    var showSyncSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { com.eagertolisten.app.data.repository.PodcastRepository(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var consecutiveLimit by remember { androidx.compose.runtime.mutableIntStateOf(repository.getGlobalConsecutivePlayLimit()) }
    var newsDays by remember { androidx.compose.runtime.mutableIntStateOf(repository.getDefaultNewsRetentionDays()) }

    val prefs = remember { context.getSharedPreferences("eager_settings", android.content.Context.MODE_PRIVATE) }
    var updateIntervalHours by remember {
        androidx.compose.runtime.mutableIntStateOf(
            prefs.getInt(
                com.eagertolisten.app.worker.ScheduledUpdateWorker.PREF_KEY_UPDATE_INTERVAL_HOURS,
                com.eagertolisten.app.worker.ScheduledUpdateWorker.DEFAULT_UPDATE_INTERVAL_HOURS
            )
        )
    }
    var autoDownloadEnabled by remember {
        mutableStateOf(
            prefs.getBoolean(
                com.eagertolisten.app.data.download.PodcastDownloadManager.PREF_KEY_AUTO_DOWNLOAD_ENABLED,
                true
            )
        )
    }
    var autoDownloadHours by remember {
        androidx.compose.runtime.mutableIntStateOf(
            prefs.getInt(
                com.eagertolisten.app.data.download.PodcastDownloadManager.PREF_KEY_AUTO_DOWNLOAD_HOURS,
                com.eagertolisten.app.data.download.PodcastDownloadManager.DEFAULT_AUTO_DOWNLOAD_HOURS
            )
        )
    }
    var allowCellularDownloads by remember {
        mutableStateOf(
            prefs.getBoolean(
                com.eagertolisten.app.data.download.PodcastDownloadManager.PREF_KEY_ALLOW_CELLULAR,
                false
            )
        )
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }
    var piApiKey by remember {
        mutableStateOf(
            prefs.getString(
                com.eagertolisten.app.data.podcastindex.PodcastIndexClient.PREF_KEY_PI_API_KEY,
                ""
            ) ?: ""
        )
    }
    var piApiSecret by remember {
        mutableStateOf(
            prefs.getString(
                com.eagertolisten.app.data.podcastindex.PodcastIndexClient.PREF_KEY_PI_API_SECRET,
                ""
            ) ?: ""
        )
    }
    var showInspirationStudio by remember { mutableStateOf(false) }
    var isManualUpdating by remember { mutableStateOf(false) }
    var manualUpdateStatus by remember { mutableStateOf<String?>(null) }
    var isDownloadingQueue by remember { mutableStateOf(false) }
    var downloadStatusMsg by remember { mutableStateOf<String?>(null) }

    var isImporting by remember { mutableStateOf(false) }
    var importStatusMessage by remember { mutableStateOf<String?>(null) }
    var backupStatusMessage by remember { mutableStateOf<String?>(null) }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            scope.launch {
                backupStatusMessage = "Exporting database backup..."
                val result = syncManager.exportBackupToUri(uri)
                result.onSuccess {
                    backupStatusMessage = "Backup exported successfully!"
                }.onFailure { err ->
                    backupStatusMessage = "Backup export failed: ${err.localizedMessage ?: "Unknown error"}"
                }
            }
        }
    }

    val restoreBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            scope.launch {
                backupStatusMessage = "Restoring from backup..."
                val result = syncManager.restoreBackupFromUri(uri)
                result.onSuccess { summary ->
                    repository.generateProgrammedQueue()
                    backupStatusMessage = summary.message
                }.onFailure { err ->
                    backupStatusMessage = "Restore failed: ${err.localizedMessage ?: "Unknown error"}"
                }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            scope.launch {
                isImporting = true
                importStatusMessage = "Analyzing and importing file..."
                val result = PodcastAddictImporter(context).importFromUri(uri)
                isImporting = false
                result.onSuccess { res ->
                    importStatusMessage = res.message
                    repository.generateProgrammedQueue()
                }.onFailure { err ->
                    importStatusMessage = "Import failed: ${err.localizedMessage ?: "Unknown error"}"
                }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Settings & Preferences",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ForestGreenDarkest)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Playlist Programming & Feed Limits Card
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = BurntOrange,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(
                            text = "Curated Airwaves (Radio U): Anti-Binge Rules",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }

                    Text(
                        text = "Programs your queue like a personal radio station: fair rotation takes precedence over priority so you aren't stuck bingeing only one show.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    // Consecutive Limit Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Max Consecutive Episodes per Feed:",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                            Text(
                                text = "$consecutiveLimit ep",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = BurntOrange
                            )
                        }
                        androidx.compose.material3.Slider(
                            value = consecutiveLimit.toFloat(),
                            onValueChange = {
                                consecutiveLimit = kotlin.math.round(it).toInt()
                                repository.setGlobalConsecutivePlayLimit(consecutiveLimit)
                            },
                            valueRange = 1f..5f,
                            steps = 3,
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = BurntOrange,
                                activeTrackColor = BurntOrange,
                                inactiveTrackColor = ForestGreenDarkest
                            )
                        )
                    }

                    // News Retention Days Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Default News Retention Window:",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                            Text(
                                text = "$newsDays days",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = BurntOrange
                            )
                        }
                        androidx.compose.material3.Slider(
                            value = newsDays.toFloat(),
                            onValueChange = {
                                newsDays = kotlin.math.round(it).toInt()
                                repository.setDefaultNewsRetentionDays(newsDays)
                            },
                            valueRange = 1f..14f,
                            steps = 12,
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = BurntOrange,
                                activeTrackColor = BurntOrange,
                                inactiveTrackColor = ForestGreenDarkest
                            )
                        )
                        Text(
                            text = "News older than $newsDays days is omitted so you only hear fresh updates (in chronological order).",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                repository.generateProgrammedQueue()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = BurntOrange),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Rebuild Programmed Playlist Now", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Scheduled Background Updates & Backup Card (Requirement 4)
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Schedule, contentDescription = null, tint = BurntOrange, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(
                            text = "Scheduled Updates & Backup",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }

                    Text(
                        text = "Periodically syncs all feeds, purges stale news, generates queue, and exports a database backup (user-adjustable, default 12 hours).",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    // Interval Selection Chips
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Update & Backup Frequency:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = TextPrimary)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val intervals = listOf(Pair("Disabled", 0), Pair("3h", 3), Pair("6h", 6), Pair("12h (Def)", 12), Pair("24h", 24))
                            intervals.forEach { (label, hours) ->
                                val selected = updateIntervalHours == hours
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (selected) BurntOrange else ForestGreenSurface,
                                    modifier = Modifier.clickable {
                                        updateIntervalHours = hours
                                        prefs.edit().putInt(com.eagertolisten.app.worker.ScheduledUpdateWorker.PREF_KEY_UPDATE_INTERVAL_HOURS, hours).apply()
                                        com.eagertolisten.app.worker.ScheduledUpdateWorker.scheduleOrUpdate(context, hours)
                                    }
                                ) {
                                    Text(
                                        text = label,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) TextPrimary else TextSecondary
                                    )
                                }
                            }
                        }
                    }

                    val lastRun = prefs.getLong(com.eagertolisten.app.worker.ScheduledUpdateWorker.PREF_KEY_LAST_SCHEDULED_RUN, 0L)
                    val lastBackup = prefs.getLong(com.eagertolisten.app.worker.ScheduledUpdateWorker.PREF_KEY_LAST_BACKUP_TIME, 0L)
                    if (lastRun > 0 || lastBackup > 0) {
                        val fmt = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
                        Text(
                            text = "Last sync: ${if (lastRun > 0) fmt.format(java.util.Date(lastRun)) else "None"} • Last backup: ${if (lastBackup > 0) fmt.format(java.util.Date(lastBackup)) else "None"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }

                    if (manualUpdateStatus != null) {
                        Text(text = manualUpdateStatus!!, style = MaterialTheme.typography.labelSmall, color = Priority4Color)
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                isManualUpdating = true
                                manualUpdateStatus = "Syncing all feeds, updating news, and creating backup..."
                                val (ok, errs) = repository.refreshAllFeeds()
                                repository.purgeExpiredNewsEpisodes()
                                repository.generateProgrammedQueue()
                                syncManager.performSync(isManual = true)
                                isManualUpdating = false
                                manualUpdateStatus = "Sync & backup complete! ($ok feeds updated, ${errs.size} failed)"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BurntOrange),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isManualUpdating
                    ) {
                        if (isManualUpdating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TextPrimary, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Update Feeds & Backup Now", fontWeight = FontWeight.Bold)
                    }

                    if (backupStatusMessage != null) {
                        Text(
                            text = backupStatusMessage!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = Priority4Color
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                                exportBackupLauncher.launch("eagertolisten_backup_$timestamp.json")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreenSurface),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Export Backup", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
                        }

                        Button(
                            onClick = {
                                restoreBackupLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreenSurface),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Restore Backup", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val removed = repository.permanentlyDeleteRemovedPodcasts()
                                backupStatusMessage = if (removed > 0) "Permanently purged $removed removed show(s) and history." else "No removed podcasts found."
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Purge History of Removed Shows", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                }
            }

            // Auto-Download Queue Ahead Card (Requirement 5)
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Download, contentDescription = null, tint = BurntOrange, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.size(10.dp))
                            Text(
                                text = "Auto-Download Queue Ahead",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                        }
                        Switch(
                            checked = autoDownloadEnabled,
                            onCheckedChange = { isChecked ->
                                autoDownloadEnabled = isChecked
                                prefs.edit().putBoolean(com.eagertolisten.app.data.download.PodcastDownloadManager.PREF_KEY_AUTO_DOWNLOAD_ENABLED, isChecked).apply()
                                if (isChecked) {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                        if (androidx.core.content.ContextCompat.checkSelfPermission(
                                                context,
                                                android.Manifest.permission.POST_NOTIFICATIONS
                                            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                        ) {
                                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                    com.eagertolisten.app.data.download.PodcastDownloadManager.enqueueAutoDownload(context)
                                } else {
                                    androidx.work.WorkManager.getInstance(context).cancelUniqueWork("eager-downloads")
                                }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = BurntOrange, checkedTrackColor = BurntOrange.copy(alpha = 0.4f))
                        )
                    }

                    Text(
                        text = "Automatically caches upcoming queue episodes for offline playing without network hiccups. Excludes news podcasts (which are timely and refreshed right before playing). Can go over buffer threshold, never under.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    if (autoDownloadEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "Allow downloads over cellular",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = TextPrimary
                                )
                                Text(
                                    text = "When disabled, queue will only auto-download on Wi-Fi",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = allowCellularDownloads,
                                onCheckedChange = { allowCell ->
                                    allowCellularDownloads = allowCell
                                    prefs.edit().putBoolean(
                                        com.eagertolisten.app.data.download.PodcastDownloadManager.PREF_KEY_ALLOW_CELLULAR,
                                        allowCell
                                    ).apply()
                                    com.eagertolisten.app.data.download.PodcastDownloadManager.enqueueAutoDownload(context)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = BurntOrange,
                                    checkedTrackColor = BurntOrange.copy(alpha = 0.4f)
                                )
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Target Programming Buffer:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = TextPrimary)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val bufferHours = listOf(2, 4, 6, 8)
                                bufferHours.forEach { hours ->
                                    val selected = autoDownloadHours == hours
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = if (selected) BurntOrange else ForestGreenSurface,
                                        modifier = Modifier.clickable {
                                            autoDownloadHours = hours
                                            prefs.edit().putInt(com.eagertolisten.app.data.download.PodcastDownloadManager.PREF_KEY_AUTO_DOWNLOAD_HOURS, hours).apply()
                                            com.eagertolisten.app.data.download.PodcastDownloadManager.enqueueAutoDownload(context)
                                        }
                                    ) {
                                        Text(
                                            text = if (hours == 4) "4h (Default)" else "${hours}h",
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            fontSize = 11.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selected) TextPrimary else TextSecondary
                                        )
                                    }
                                }
                            }
                        }

                        if (downloadStatusMsg != null) {
                            Text(text = downloadStatusMsg!!, style = MaterialTheme.typography.labelSmall, color = Priority4Color)
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    isDownloadingQueue = true
                                    downloadStatusMsg = "Downloading upcoming queue episodes..."
                                    val count = com.eagertolisten.app.data.download.PodcastDownloadManager(context).performAutoDownloadQueue(autoDownloadHours)
                                    isDownloadingQueue = false
                                    downloadStatusMsg = "Download complete: $count new episode(s) cached offline."
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BurntOrange),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isDownloadingQueue
                        ) {
                            if (isDownloadingQueue) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TextPrimary, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Download Queue Ahead Now", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Creator Studio & Inspirations Card (Requirement 7)
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Lightbulb, contentDescription = null, tint = BurntOrange, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(
                            text = "Creator Studio & Inspirations",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }

                    Text(
                        text = "Review moments you bookmarked while listening (from Android Auto or player). View timestamps, edit notes for blogs, copy Markdown quotes, and export 'Best-Of' feeds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Button(
                        onClick = { showInspirationStudio = true },
                        colors = ButtonDefaults.buttonColors(containerColor = BurntOrange),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Creator Inspiration Studio", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // PodcastIndex.org API Settings Card (Requirement 3)
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Key, contentDescription = null, tint = BurntOrange, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(
                            text = "PodcastIndex.org API Credentials",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }

                    Text(
                        text = "Add your developer API Key and Secret from podcastindex.org for live authenticated category discovery and index queries. (An automatic iTunes directory fallback is used if omitted).",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.podcastindex.org/tos_accept"))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                android.util.Log.e("Settings", "Failed to launch ToS URL: ${e.message}")
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BurntOrange),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Accept Terms & Get Free API Keys (api.podcastindex.org)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedTextField(
                        value = piApiKey,
                        onValueChange = {
                            piApiKey = it
                            prefs.edit().putString(com.eagertolisten.app.data.podcastindex.PodcastIndexClient.PREF_KEY_PI_API_KEY, it).apply()
                        },
                        label = { Text("API Key", color = TextMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = piApiSecret,
                        onValueChange = {
                            piApiSecret = it
                            prefs.edit().putString(com.eagertolisten.app.data.podcastindex.PodcastIndexClient.PREF_KEY_PI_API_SECRET, it).apply()
                        },
                        label = { Text("API Secret", color = TextMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }

            // Google Drive AppData Sync Card
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.clickable { showSyncSheet = true }
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.size(10.dp))
                            Text(
                                text = "Google Drive Cloud Sync",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                        }
                    }

                    Text(
                        text = "Continuous state synchronization via Android's native Google Drive storage provider. Zero developer keys, zero account passwords.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ForestGreenSurface
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (syncState.status) {
                                    SyncStatus.CONNECTED -> "Status: Connected (${syncState.folderDisplayName ?: "Google Drive"}) • Ready"
                                    SyncStatus.SYNCING -> "Status: Syncing in progress..."
                                    else -> "Status: Not Connected • Tap to Connect Drive"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (syncState.status == SyncStatus.CONNECTED) Priority4Color else BurntOrange
                            )
                        }
                    }

                    Button(
                        onClick = { showSyncSheet = true },
                        colors = ButtonDefaults.buttonColors(containerColor = BurntOrange),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (syncState.status == SyncStatus.CONNECTED) "Manage Drive Sync" else "Select Google Drive Sync Folder")
                    }
                }
            }

            // Curated Airwaves (Radio U) Features Card
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Tune, contentDescription = null, tint = BurntOrange, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(
                            text = "Curated Airwaves (Radio U) Engine",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }

                    Text("• NPR-Style Flow: Programs your queue like a radio clock without binge fatigue", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("• Fair Interleaving & Anti-Binge Limits (1–5 ep consecutive cap)", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("• 4 Play Order Strategies (News rolling window, Chronological, Reverse, Evergreen)", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("• 5-Tier Priority Matrix & 1-tap Hot List (Priority 1 shows)", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("• JIT News Refresh right before playback starts", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("• Per-feed Skip Intro & Skip Outro trim offsets", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("• 15s Rewind & 90s Forward Skip hardware & UI controls", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }

            // Podcasting 2.0 Features Card
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = BurntOrange, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(
                            text = "Podcasting 2.0 Standards",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }

                    Text("• <podcast:chapters> with artwork & instant seek", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("• <podcast:transcript> WebVTT/SRT reader with live click-to-seek", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("• <podcast:person> host & guest roles", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("• <podcast:soundbite> highlight previews", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("• <podcast:funding> Value4Value & creator support", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }

            // Importers Card
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.FileUpload, contentDescription = null, tint = BurntOrange, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(
                            text = "Import & Migration",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }

                    Text(
                        text = "Migrate your podcasts, playback history, and per-feed preferences directly from a Podcast Addict backup (.backup / .db) or standard OPML file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    if (isImporting) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = BurntOrange,
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = importStatusMessage ?: "Importing...",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                        }
                    } else if (importStatusMessage != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ForestGreenSurface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = importStatusMessage!!,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = if (importStatusMessage!!.startsWith("Import failed")) BurntOrange else Priority4Color,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Button(
                        onClick = {
                            filePickerLauncher.launch(
                                arrayOf(
                                    "*/*",
                                    "application/octet-stream",
                                    "application/zip",
                                    "application/x-sqlite3",
                                    "text/xml"
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = BurntOrange),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isImporting
                    ) {
                        Text("Select Podcast Addict Backup (.backup / .db) or OPML", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Alpha Feedback & Support Card
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Email, contentDescription = null, tint = BurntOrange, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(
                            text = "Alpha Feedback & Support",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }

                    Text(
                        text = "Have feedback, found a bug, or want to suggest a feature? Contact Michael Eger directly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ForestGreenSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Developer Contact",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                                Text(
                                    text = "michael.eger@michaelegerconsulting.com",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = BurntOrange
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:michael.eger@michaelegerconsulting.com")
                                putExtra(Intent.EXTRA_SUBJECT, "[Eager to Listen Alpha Feedback]")
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Hi Michael,\n\nHere is my feedback on Eager to Listen:\n\nDevice: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})\n\n"
                                )
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "Contact: michael.eger@michaelegerconsulting.com", android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = BurntOrange),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Send Alpha Feedback Email", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    // Google Drive Sync Setup Modal Sheet
    if (showSyncSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSyncSheet = false },
            sheetState = sheetState
        ) {
            GoogleDriveSyncSheet(
                syncManager = syncManager,
                onDismiss = { showSyncSheet = false }
            )
        }
    }

    // Creator Inspiration Studio Sheet
    if (showInspirationStudio) {
        InspirationStudioSheet(
            onDismiss = { showInspirationStudio = false }
        )
    }
}
