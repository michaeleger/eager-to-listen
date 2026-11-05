package com.eagertolisten.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eagertolisten.app.diagnostics.AppInfo
import com.eagertolisten.app.diagnostics.CrashReporter
import com.eagertolisten.app.ui.theme.BurntOrange
import com.eagertolisten.app.ui.theme.ForestGreenDarkest
import com.eagertolisten.app.ui.theme.ForestGreenElevated
import com.eagertolisten.app.ui.theme.ForestGreenSurface
import com.eagertolisten.app.ui.theme.Priority4Color
import com.eagertolisten.app.ui.theme.TextMuted
import com.eagertolisten.app.ui.theme.TextPrimary
import com.eagertolisten.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ForestGreenDarkest
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = ForestGreenElevated,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = BurntOrange,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "About Eager to Listen",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
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

            // App Identity Hero
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = ForestGreenSurface,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Radio,
                            contentDescription = null,
                            tint = BurntOrange,
                            modifier = Modifier
                                .padding(12.dp)
                                .size(32.dp)
                        )
                    }

                    Text(
                        text = AppInfo.APP_NAME,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )

                    Text(
                        text = AppInfo.APP_TAGLINE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = BurntOrange
                    ) {
                        Text(
                            text = AppInfo.fullVersionBadge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }
                }
            }

            // Version & Diagnostics Details Card
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Version & Runtime Diagnostics",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )

                    InfoRow(label = "App Version", value = AppInfo.versionName)
                    InfoRow(label = "Version Code", value = AppInfo.versionCode.toString())
                    InfoRow(label = "Build Variant", value = AppInfo.buildType)
                    InfoRow(label = "Database Schema", value = "Version ${AppInfo.databaseVersion}")
                    InfoRow(label = "Application ID", value = AppInfo.applicationId)
                    InfoRow(label = "Testing Track", value = AppInfo.ALPHA_TRACK)
                    InfoRow(label = "Device", value = AppInfo.deviceSummary)

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = { AppInfo.copyDiagnosticsToClipboard(context) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = ForestGreenSurface),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = BurntOrange,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Copy Version & Diagnostics",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                    }
                }
            }

            // Alpha Highlights Card
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Key Highlights in ${AppInfo.versionName}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )

                    HighlightBullet("🚗 Car & Headset Navigation: Jump (+90s/-15s) or Skip-Episode setting")
                    HighlightBullet("▶️ Edge-to-Edge Player: Reachable controls with Back gesture collapse")
                    HighlightBullet("🎙️ Virtual Feeds: Add local folders of audio files as shows")
                    HighlightBullet("⚡ Duplicate Prevention: Show matching via addresses & <podcast:guid>")
                    HighlightBullet("📥 Importers: AntennaPod .db, Podverse OPML & full Podcast Addict backups")
                    HighlightBullet("📊 Crash Diagnostics: Automatic error logging with one-tap email reports")
                }
            }

            // Feedback & Tester Support Card
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestGreenElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Alpha Feedback & Support",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )

                    Text(
                        text = "Contact developer Michael Eger directly with feedback, bugs, or questions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:${AppInfo.DEVELOPER_EMAIL}")
                                putExtra(Intent.EXTRA_SUBJECT, "[Eager to Listen ${AppInfo.displayVersion} Feedback]")
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Hi Michael,\n\nHere is my feedback on Eager to Listen ${AppInfo.displayVersion}:\n\n" +
                                        "--- System Diagnostics ---\n" +
                                        AppInfo.buildDiagnosticBlock() +
                                        "\n--------------------------\n\n"
                                )
                            }
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                android.widget.Toast.makeText(
                                    context,
                                    "Contact: ${AppInfo.DEVELOPER_EMAIL}",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
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

                    OutlinedButton(
                        onClick = {
                            val report = CrashReporter.latestReport(context)
                            if (report == null) {
                                android.widget.Toast.makeText(context, "No crash reports saved. App is healthy!", android.widget.Toast.LENGTH_SHORT).show()
                            } else if (!CrashReporter.emailReport(context, report)) {
                                android.widget.Toast.makeText(context, "Contact: ${CrashReporter.DEVELOPER_EMAIL}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Email, contentDescription = null, tint = BurntOrange, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Email Last Crash Report", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    }

                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppInfo.GITHUB_URL))
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.OpenInNew, contentDescription = null, tint = BurntOrange, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("GitHub Repository & Issue Tracker", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    }

                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppInfo.PRIVACY_POLICY_URL))
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Security, contentDescription = null, tint = Priority4Color, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Privacy Policy", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = TextPrimary
        )
    }
}

@Composable
private fun HighlightBullet(text: String) {
    Text(
        text = "• $text",
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary
    )
}
