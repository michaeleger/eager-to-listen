package com.eagertolisten.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.eagertolisten.app.data.model.Podcast

/** The show's donate link: a custom URL set in feed settings wins over the feed's <podcast:funding>. */
fun Podcast.supportUrl(): String? =
    (customDonationUrl ?: fundingUrl)?.takeIf { it.isNotBlank() }

/** Opens a donate / support link in the browser, adding https:// when the feed left it off. */
fun openSupportUrl(context: Context, url: String) {
    val safeUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
