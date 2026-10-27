package com.eagertolisten.app.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.eagertolisten.app.R

class DownloadQueueWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "DownloadQueueWorker"
        const val CHANNEL_ID = "downloads_channel"
        const val NOTIFICATION_ID = 1002
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo("Preparing download queue...", 0, 0, true)
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "DownloadQueueWorker started")
        val prefs = applicationContext.getSharedPreferences("eager_settings", Context.MODE_PRIVATE)
        val autoDownloadEnabled = prefs.getBoolean(PodcastDownloadManager.PREF_KEY_AUTO_DOWNLOAD_ENABLED, true)
        if (!autoDownloadEnabled) {
            Log.d(TAG, "Auto-download disabled, stopping worker")
            return Result.success()
        }

        val targetHours = prefs.getInt(
            PodcastDownloadManager.PREF_KEY_AUTO_DOWNLOAD_HOURS,
            PodcastDownloadManager.DEFAULT_AUTO_DOWNLOAD_HOURS
        )

        try {
            setForeground(createForegroundInfo("Preparing download queue...", 0, 0, true))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to setForeground: ${e.message}")
        }

        val downloadManager = PodcastDownloadManager(applicationContext)

        val downloaded = downloadManager.performAutoDownloadQueue(
            targetHours = targetHours,
            onProgress = { currentTitle, current, total, indeterminate ->
                try {
                    setForegroundAsync(createForegroundInfo(currentTitle, current, total, indeterminate))
                } catch (_: Exception) {}
            }
        )

        Log.i(TAG, "DownloadQueueWorker finished: $downloaded episodes downloaded (target: ${targetHours}h)")
        return Result.success()
    }

    private fun createForegroundInfo(
        title: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean
    ): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Podcast Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of podcast auto-downloads"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading Podcasts")
            .setContentText(title)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max, progress, indeterminate)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
