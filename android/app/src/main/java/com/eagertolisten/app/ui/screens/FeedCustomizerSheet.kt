package com.eagertolisten.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.platform.LocalContext
import com.eagertolisten.app.data.model.PodcastValue
import com.eagertolisten.app.data.model.Podcast
import com.eagertolisten.app.ui.components.PriorityBadge
import com.eagertolisten.app.ui.theme.BurntOrange
import com.eagertolisten.app.ui.theme.ForestGreenDarkest
import com.eagertolisten.app.ui.theme.ForestGreenElevated
import com.eagertolisten.app.ui.theme.ForestGreenSurface
import com.eagertolisten.app.ui.theme.Priority1Color
import com.eagertolisten.app.ui.theme.Priority2Color
import com.eagertolisten.app.ui.theme.Priority3Color
import com.eagertolisten.app.ui.theme.Priority4Color
import com.eagertolisten.app.ui.theme.Priority5Color
import com.eagertolisten.app.ui.theme.TextMuted
import com.eagertolisten.app.ui.theme.TextPrimary
import com.eagertolisten.app.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
fun FeedCustomizerSheet(
    podcast: Podcast,
    onSave: (
        priority: Int,
        skipIntro: Int,
        skipOutro: Int,
        speed: Float,
        autoAdd: Boolean,
        maxEpisodes: Int,
        category: String,
        playOrderStrategy: String,
        newsRetentionDays: Int,
        consecutivePlayLimit: Int,
        customDonationUrl: String?
    ) -> Unit,
    onUnsubscribe: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var priority by remember { mutableIntStateOf(podcast.priority) }
    var skipIntro by remember { mutableIntStateOf(podcast.skipIntroSeconds) }
    var skipOutro by remember { mutableIntStateOf(podcast.skipOutroSeconds) }
    var speed by remember { mutableFloatStateOf(podcast.playbackSpeed) }
    var autoAdd by remember { mutableStateOf(podcast.autoAddToQueue) }
    var maxEpisodes by remember { mutableIntStateOf(podcast.maxEpisodesInQueue) }
    var category by remember { mutableStateOf(podcast.category) }
    var playOrderStrategy by remember { mutableStateOf(podcast.playOrderStrategy) }
    var newsRetentionDays by remember { mutableIntStateOf(podcast.newsRetentionDays) }
    var consecutiveLimit by remember { mutableIntStateOf(podcast.consecutivePlayLimit) }
    var customDonationUrl by remember { mutableStateOf(podcast.customDonationUrl ?: "") }
    var showConfirmDelete by remember { mutableStateOf(false) }

    fun triggerAutoSave(
        newPriority: Int = priority,
        newSkipIntro: Int = skipIntro,
        newSkipOutro: Int = skipOutro,
        newSpeed: Float = speed,
        newAutoAdd: Boolean = autoAdd,
        newMaxEpisodes: Int = maxEpisodes,
        newCategory: String = category,
        newStrategy: String = playOrderStrategy,
        newNewsDays: Int = newsRetentionDays,
        newLimit: Int = consecutiveLimit,
        newDonationUrl: String? = customDonationUrl.ifBlank { null }
    ) {
        onSave(
            newPriority,
            newSkipIntro,
            newSkipOutro,
            newSpeed,
            newAutoAdd,
            newMaxEpisodes,
            newCategory,
            newStrategy,
            newNewsDays,
            newLimit,
            newDonationUrl
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = ForestGreenSurface
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
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Feed Settings",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = BurntOrange
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = ForestGreenDarkest
                        ) {
                            Text(
                                text = "Auto-saving",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = podcast.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        maxLines = 1
                    )
                }
                IconButton(onClick = {
                    triggerAutoSave()
                    onDismiss()
                }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary
                    )
                }
            }

            // Priority Setting (1 to 5)
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Priority Ranking",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = TextPrimary
                        )
                        PriorityBadge(priority = priority)
                    }

                    Text(
                        text = when (priority) {
                            1 -> "Priority 1 (Highest / Immediate - added to Hot List)"
                            2 -> "Priority 2 (High)"
                            3 -> "Priority 3 (Normal / Default)"
                            4 -> "Priority 4 (Low)"
                            else -> "Priority 5 (Lowest / Background)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    Slider(
                        value = priority.toFloat(),
                        onValueChange = { priority = it.roundToInt() },
                        onValueChangeFinished = { triggerAutoSave(newPriority = priority) },
                        valueRange = 1f..5f,
                        steps = 3,
                        colors = SliderDefaults.colors(
                            thumbColor = BurntOrange,
                            activeTrackColor = BurntOrange,
                            inactiveTrackColor = ForestGreenDarkest
                        )
                    )
                }
            }

            // Skip Intro & Skip Outro
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Skip Intro & Outro",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Skip Intro:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text("${skipIntro}s", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = BurntOrange)
                    }
                    Slider(
                        value = skipIntro.toFloat(),
                        onValueChange = { skipIntro = (it / 5).roundToInt() * 5 },
                        onValueChangeFinished = { triggerAutoSave(newSkipIntro = skipIntro) },
                        valueRange = 0f..180f,
                        steps = 35,
                        colors = SliderDefaults.colors(
                            thumbColor = BurntOrange,
                            activeTrackColor = BurntOrange,
                            inactiveTrackColor = ForestGreenDarkest
                        )
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Skip Outro:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text("${skipOutro}s", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = BurntOrange)
                    }
                    Slider(
                        value = skipOutro.toFloat(),
                        onValueChange = { skipOutro = (it / 5).roundToInt() * 5 },
                        onValueChangeFinished = { triggerAutoSave(newSkipOutro = skipOutro) },
                        valueRange = 0f..180f,
                        steps = 35,
                        colors = SliderDefaults.colors(
                            thumbColor = BurntOrange,
                            activeTrackColor = BurntOrange,
                            inactiveTrackColor = ForestGreenDarkest
                        )
                    )
                }
            }

            // Custom Playback Speed
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Playback Speed Override",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = TextPrimary
                        )
                        Text(
                            text = String.format("%.2fx", speed),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = BurntOrange
                        )
                    }
                    Slider(
                        value = speed,
                        onValueChange = { speed = ((it * 20).roundToInt() / 20f) },
                        onValueChangeFinished = { triggerAutoSave(newSpeed = speed) },
                        valueRange = 0.8f..2.5f,
                        steps = 16,
                        colors = SliderDefaults.colors(
                            thumbColor = BurntOrange,
                            activeTrackColor = BurntOrange,
                            inactiveTrackColor = ForestGreenDarkest
                        )
                    )
                }
            }

            // Queue Rules: Auto-Add & Max Retention
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Auto-Add New Episodes to Queue",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = TextPrimary
                            )
                            Text(
                                text = "Automatically enqueues new episodes upon refresh",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                        Switch(
                            checked = autoAdd,
                            onCheckedChange = {
                                autoAdd = it
                                triggerAutoSave(newAutoAdd = it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TextPrimary,
                                checkedTrackColor = BurntOrange,
                                uncheckedTrackColor = ForestGreenDarkest
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Max Episodes in Queue:",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Text(
                            text = if (maxEpisodes < 0) "Unlimited" else "$maxEpisodes ep",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = BurntOrange
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(-1 to "Unlimited", 1 to "1 ep", 2 to "2 ep", 3 to "3 ep", 5 to "5 ep").forEach { (limitVal, label) ->
                            val isSel = maxEpisodes == limitVal
                            androidx.compose.material3.FilterChip(
                                selected = isSel,
                                onClick = {
                                    maxEpisodes = limitVal
                                    triggerAutoSave(newMaxEpisodes = limitVal)
                                },
                                label = { Text(label) },
                                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BurntOrange,
                                    selectedLabelColor = TextPrimary,
                                    containerColor = ForestGreenDarkest,
                                    labelColor = TextSecondary
                                )
                            )
                        }
                    }
                }
            }

            // 4 Play Orders Strategy Card
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Play Order Strategy",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = TextPrimary
                    )

                    val strategies = listOf(
                        Triple("CHRONOLOGICAL", "Chronological (Serial)", "Oldest unplayed first up to newest. For serialized stories, audiobooks & classes."),
                        Triple("REVERSE_CHRONOLOGICAL", "Reverse Chronological", "Newest unplayed first down to oldest. Traditional episodic order."),
                        Triple("NEWS", "News (Updates First)", "Fresh updates jump to top. Plays last X days unplayed in order (morning to evening)."),
                        Triple("EVERGREEN", "Evergreen / Knowledge", "Newest unplayed episode first, then next newest unplayed.")
                    )

                    strategies.forEach { (stratKey, stratTitle, stratDesc) ->
                        val isSelected = playOrderStrategy == stratKey
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) BurntOrange.copy(alpha = 0.2f) else ForestGreenDarkest,
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, BurntOrange) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    playOrderStrategy = stratKey
                                    triggerAutoSave(newStrategy = stratKey)
                                }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = stratTitle,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (isSelected) BurntOrange else TextPrimary
                                )
                                Text(
                                    text = stratDesc,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    // News retention window slider if News strategy selected
                    if (playOrderStrategy == "NEWS") {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("News Retention Window:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            Text("$newsRetentionDays days", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = BurntOrange)
                        }
                        Slider(
                            value = newsRetentionDays.toFloat(),
                            onValueChange = { newsRetentionDays = it.roundToInt() },
                            onValueChangeFinished = { triggerAutoSave(newNewsDays = newsRetentionDays) },
                            valueRange = 1f..14f,
                            steps = 12,
                            colors = SliderDefaults.colors(
                                thumbColor = BurntOrange,
                                activeTrackColor = BurntOrange,
                                inactiveTrackColor = ForestGreenDarkest
                            )
                        )
                    }
                }
            }

            // Feed Play Limit Card (Anti-Binge Diversity)
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Feed Play Limit (Anti-Binge)",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = TextPrimary
                        )
                        Text(
                            text = if (consecutiveLimit <= 0) "Global Default" else "$consecutiveLimit ep",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = BurntOrange
                        )
                    }

                    Text(
                        text = "Takes precedence over importance. Limits consecutive episodes from this show before the playlist must rotate to other podcasts.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(-1 to "Global", 1 to "1 ep", 2 to "2 ep", 3 to "3 ep").forEach { (limitVal, label) ->
                            val isSel = consecutiveLimit == limitVal
                            androidx.compose.material3.FilterChip(
                                selected = isSel,
                                onClick = {
                                    consecutiveLimit = limitVal
                                    triggerAutoSave(newLimit = limitVal)
                                },
                                label = { Text(label) },
                                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BurntOrange,
                                    selectedLabelColor = TextPrimary,
                                    containerColor = ForestGreenDarkest,
                                    labelColor = TextSecondary
                                )
                            )
                        }
                    }
                }
            }

            // Creator Support & Donations Card
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = null,
                                tint = BurntOrange,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Creator Support & Donations",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = TextPrimary
                            )
                        }
                    }

                    Text(
                        text = "Set a direct support link (PayPal, Stripe, Patreon, Buy Me a Coffee) or use feed funding tags to support the creator.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )

                    // Feed Funding Info (if feed has <podcast:funding>)
                    if (!podcast.fundingUrl.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ForestGreenDarkest,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "Feed Funding Link:",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = BurntOrange
                                )
                                Text(
                                    text = podcast.fundingUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 1
                                )
                                if (!podcast.fundingMessage.isNullOrBlank()) {
                                    Text(
                                        text = "\"${podcast.fundingMessage}\"",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Feed Lightning V4V Info (if feed has <podcast:value>)
                    val podcastValue = remember(podcast.valueJson) { PodcastValue.fromJson(podcast.valueJson) }
                    if (podcastValue != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ForestGreenDarkest,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ElectricBolt,
                                    contentDescription = null,
                                    tint = BurntOrange,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column {
                                    Text(
                                        text = "⚡ Podcasting 2.0 V4V Lightning Active",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "${podcastValue.recipients.size} value recipient(s) configured in feed",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                }
                            }
                        }
                    }

                    // Custom Donation / Support URL Input Field (Auto-saving)
                    OutlinedTextField(
                        value = customDonationUrl,
                        onValueChange = {
                            customDonationUrl = it
                            triggerAutoSave(newDonationUrl = it.trim().ifBlank { null })
                        },
                        label = { Text("Custom Support / Donation Link") },
                        placeholder = { Text("https://buymeacoffee.com/... or paypal.me/...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BurntOrange,
                            unfocusedBorderColor = ForestGreenDarkest,
                            focusedLabelColor = BurntOrange,
                            unfocusedLabelColor = TextMuted,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )

                    if (customDonationUrl.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val safeUrl = if (!customDonationUrl.startsWith("http://") && !customDonationUrl.startsWith("https://")) {
                                        "https://$customDonationUrl"
                                    } else customDonationUrl
                                    runCatching {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl))
                                        context.startActivity(intent)
                                    }
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BurntOrange),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Test Link", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Unsubscribe Button (Save button removed as all settings auto-save immediately)
            OutlinedButton(
                onClick = { showConfirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Priority1Color),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text("Unsubscribe from Podcast")
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    if (showConfirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = { Text("Unsubscribe?", color = TextPrimary) },
            text = { Text("Are you sure you want to unsubscribe from ${podcast.title} and remove its downloaded episodes?", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDelete = false
                        onUnsubscribe()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Priority1Color)
                ) {
                    Text("Unsubscribe")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirmDelete = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = ForestGreenSurface
        )
    }
}
