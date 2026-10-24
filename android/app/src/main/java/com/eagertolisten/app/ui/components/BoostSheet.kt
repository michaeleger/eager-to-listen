package com.eagertolisten.app.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.eagertolisten.app.FeatureFlags
import com.eagertolisten.app.data.model.PodcastValue
import com.eagertolisten.app.data.v4v.BoostagramPayload
import com.eagertolisten.app.data.v4v.NwcClient
import com.eagertolisten.app.data.v4v.NwcKeyStorage
import com.eagertolisten.app.data.v4v.V4VSplitMath
import com.eagertolisten.app.ui.theme.BurntOrange
import com.eagertolisten.app.ui.theme.ForestGreenDarkest
import com.eagertolisten.app.ui.theme.ForestGreenElevated
import com.eagertolisten.app.ui.theme.ForestGreenSurface
import com.eagertolisten.app.ui.theme.TextMuted
import com.eagertolisten.app.ui.theme.TextPrimary
import com.eagertolisten.app.ui.theme.TextSecondary
import java.net.URLEncoder
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BoostSheet(
    podcastTitle: String,
    episodeTitle: String,
    value: PodcastValue,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val quickSatsOptions = listOf(500L, 1000L, 2100L, 5000L, 10000L, 25000L)
    var selectedSats by remember { mutableStateOf(value.suggested?.toLong()?.coerceAtLeast(100L) ?: 1000L) }
    var customSatsText by remember { mutableStateOf(selectedSats.toString()) }
    var boostagramMessage by remember { mutableStateOf("") }
    var senderName by remember { mutableStateOf("") }
    var showRecipientsDetail by remember { mutableStateOf(false) }

    val totalSplit = value.recipients.sumOf { it.split }.coerceAtLeast(1)

    val scope = rememberCoroutineScope()
    val nwcKeyStorage = remember { NwcKeyStorage(context) }
    val nwcConnection = remember { if (FeatureFlags.V4V_NWC) nwcKeyStorage.getConnection() else null }
    var isSendingNwc by remember { mutableStateOf(false) }
    var nwcStatusMsg by remember { mutableStateOf<String?>(null) }

    fun sendNwcBoost() {
        val conn = nwcConnection ?: return
        val sats = customSatsText.toLongOrNull() ?: selectedSats
        val splits = V4VSplitMath.calculateSplits(sats, value.recipients)
        if (splits.isEmpty()) {
            Toast.makeText(context, "No valid recipients for split", Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch {
            isSendingNwc = true
            nwcStatusMsg = "Sending boost via Nostr Wallet Connect..."
            val client = NwcClient()
            var successCount = 0
            var errorMsg: String? = null

            for (split in splits) {
                val payload = BoostagramPayload(
                    podcast = podcastTitle,
                    feedUrl = "",
                    episode = episodeTitle,
                    action = "boost",
                    message = boostagramMessage.ifBlank { null },
                    senderName = senderName.ifBlank { null },
                    valueMsat = split.amountMsats,
                    valueMsatTotal = sats * 1000L
                )
                val reqJson = client.buildKeysendPayload(
                    recipientPubkey = split.recipient.address,
                    amountMsats = split.amountMsats,
                    tlvHex = payload.toTlvHex()
                )
                val result = client.sendRequest(conn, reqJson)
                if (result.isSuccess) {
                    successCount++
                } else {
                    errorMsg = result.exceptionOrNull()?.message
                }
            }
            isSendingNwc = false
            if (successCount == splits.size) {
                Toast.makeText(context, "⚡ Boost of %,d sats sent successfully!".format(sats), Toast.LENGTH_LONG).show()
                onDismiss()
            } else {
                nwcStatusMsg = "Sent $successCount of ${splits.size} splits: ${errorMsg ?: "Partial failure"}"
            }
        }
    }

    fun sendBoostIntent() {
        val sats = customSatsText.toLongOrNull() ?: selectedSats
        val primary = value.recipients.firstOrNull { !it.fee } ?: value.recipients.firstOrNull()
        if (primary == null) {
            Toast.makeText(context, "No recipient node address found in podcast feed", Toast.LENGTH_SHORT).show()
            return
        }

        val addr = primary.address
        val isLightningAddress = addr.contains("@")
        val encodedMsg = if (boostagramMessage.isNotBlank()) {
            val fullMsg = if (senderName.isNotBlank()) "$boostagramMessage (from: $senderName)" else boostagramMessage
            URLEncoder.encode(fullMsg, "UTF-8")
        } else ""

        // Standard Lightning Intent URI:
        // lightning:<address>?amount=<sats_or_msats>&message=<msg>
        val uriString = if (isLightningAddress) {
            val msats = sats * 1000L
            val commentParam = if (encodedMsg.isNotBlank()) "&comment=$encodedMsg" else ""
            "lightning:$addr?amount=$msats$commentParam"
        } else {
            val msgParam = if (encodedMsg.isNotBlank()) "&message=$encodedMsg" else ""
            "lightning:$addr?amount=$sats$msgParam"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Fallback: Copy keysend / lightning details to clipboard
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = buildString {
                appendLine("⚡ Lightning Boost Details")
                appendLine("Podcast: $podcastTitle")
                appendLine("Episode: $episodeTitle")
                appendLine("Recipient: ${primary.name}")
                appendLine("Address / Node Pubkey: ${primary.address}")
                if (!primary.customKey.isNullOrBlank() && !primary.customValue.isNullOrBlank()) {
                    appendLine("Custom Key: ${primary.customKey} | Value: ${primary.customValue}")
                }
                appendLine("Amount: $sats sats")
                if (boostagramMessage.isNotBlank()) appendLine("Message: $boostagramMessage")
                if (senderName.isNotBlank()) appendLine("Sender: $senderName")
                appendLine("URI: $uriString")
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("Lightning Boost", clipText))
            Toast.makeText(
                context,
                "No Lightning wallet app found. Boost details copied to clipboard!",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun copyDetails() {
        val sats = customSatsText.toLongOrNull() ?: selectedSats
        val primary = value.recipients.firstOrNull { !it.fee } ?: value.recipients.firstOrNull()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = buildString {
            appendLine("⚡ Podcasting 2.0 Value for Value Details")
            appendLine("Podcast: $podcastTitle")
            appendLine("Episode: $episodeTitle")
            appendLine("Method: ${value.method} (${value.type})")
            appendLine("Amount: $sats satoshis")
            if (boostagramMessage.isNotBlank()) appendLine("Boostagram: $boostagramMessage")
            if (senderName.isNotBlank()) appendLine("Sender: $senderName")
            appendLine("Recipients:")
            value.recipients.forEach { r ->
                val pct = (r.split.toFloat() / totalSplit * 100).roundToInt()
                appendLine(" - ${r.name}: ${r.address} ($pct%${if (r.fee) ", fee" else ""})")
            }
            if (primary != null) {
                appendLine("Primary Node: ${primary.address}")
            }
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Boost Details", clipText))
        Toast.makeText(context, "Boost details copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ForestGreenSurface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ElectricBolt,
                        contentDescription = null,
                        tint = BurntOrange,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Send Boostagram (V4V)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary
                    )
                }
            }

            // Episode Info Banner
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = podcastTitle,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = BurntOrange,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = episodeTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Quick Satoshi Presets
            Text(
                text = "Select Amount (Satoshis)",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickSatsOptions.forEach { sats ->
                    val isSelected = (selectedSats == sats && customSatsText == sats.toString())
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) BurntOrange else ForestGreenDarkest,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) BurntOrange else ForestGreenElevated
                        ),
                        modifier = Modifier.clickable {
                            selectedSats = sats
                            customSatsText = sats.toString()
                        }
                    ) {
                        Text(
                            text = "⚡ %,d".format(sats),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (isSelected) TextPrimary else TextSecondary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // Custom Satoshi Input
            OutlinedTextField(
                value = customSatsText,
                onValueChange = {
                    val filtered = it.filter { ch -> ch.isDigit() }
                    customSatsText = filtered
                    selectedSats = filtered.toLongOrNull() ?: 0L
                },
                label = { Text("Custom Amount (Sats)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BurntOrange,
                    unfocusedBorderColor = ForestGreenElevated,
                    focusedLabelColor = BurntOrange,
                    unfocusedLabelColor = TextMuted,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                shape = RoundedCornerShape(10.dp)
            )

            // Boostagram Message
            OutlinedTextField(
                value = boostagramMessage,
                onValueChange = { boostagramMessage = it },
                label = { Text("Boostagram Message (optional)") },
                placeholder = { Text("Great episode! Thanks for making this.") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BurntOrange,
                    unfocusedBorderColor = ForestGreenElevated,
                    focusedLabelColor = BurntOrange,
                    unfocusedLabelColor = TextMuted,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                shape = RoundedCornerShape(10.dp)
            )

            // Sender Name
            OutlinedTextField(
                value = senderName,
                onValueChange = { senderName = it },
                label = { Text("Your Name / Handle (optional)") },
                placeholder = { Text("Anonymous") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BurntOrange,
                    unfocusedBorderColor = ForestGreenElevated,
                    focusedLabelColor = BurntOrange,
                    unfocusedLabelColor = TextMuted,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                shape = RoundedCornerShape(10.dp)
            )

            // Recipients Split Breakdown Card
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showRecipientsDetail = !showRecipientsDetail },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = BurntOrange,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Value Recipients (${value.recipients.size})",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                        }

                        Icon(
                            imageVector = if (showRecipientsDetail) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    if (showRecipientsDetail) {
                        Spacer(modifier = Modifier.height(8.dp))
                        value.recipients.forEach { r ->
                            val pct = (r.split.toFloat() / totalSplit * 100).roundToInt()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = r.name,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = r.address,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = "$pct%${if (r.fee) " (fee)" else ""}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = BurntOrange
                                )
                            }
                        }
                    } else {
                        val summaryText = value.recipients.take(3).joinToString(", ") { r ->
                            val pct = (r.split.toFloat() / totalSplit * 100).roundToInt()
                            "${r.name} ($pct%)"
                        } + if (value.recipients.size > 3) " +${value.recipients.size - 3} more" else ""
                        Text(
                            text = summaryText,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            if (nwcStatusMsg != null) {
                Text(
                    text = nwcStatusMsg!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = BurntOrange,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            // Action Buttons
            if (FeatureFlags.V4V_NWC && nwcConnection != null) {
                Button(
                    onClick = { sendNwcBoost() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = BurntOrange),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isSendingNwc
                ) {
                    if (isSendingNwc) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TextPrimary, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Default.ElectricBolt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    val satsDisplay = customSatsText.toLongOrNull() ?: selectedSats
                    Text("Boost %,d Sats via NWC".format(satsDisplay), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { copyDetails() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BurntOrange),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Copy Info", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = { sendBoostIntent() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BurntOrange),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("External App", fontSize = 12.sp)
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { copyDetails() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BurntOrange),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Info", fontSize = 13.sp)
                    }

                    Button(
                        onClick = { sendBoostIntent() },
                        modifier = Modifier.weight(1.3f),
                        colors = ButtonDefaults.buttonColors(containerColor = BurntOrange),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ElectricBolt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val satsDisplay = customSatsText.toLongOrNull() ?: selectedSats
                        Text("Send %,d Sats".format(satsDisplay), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
