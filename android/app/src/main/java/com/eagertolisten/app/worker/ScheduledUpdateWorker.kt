package com.eagertolisten.app.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eagertolisten.app.data.EagerDatabase
import com.eagertolisten.app.data.download.PodcastDownloadManager
import com.eagertolisten.app.data.repository.PodcastRepository
import com.eagertolisten.app.data.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class ScheduledUpdateWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "ScheduledUpdateWorker"
        const val UNIQUE_WORK_NAME = "EagerScheduledUpdateAndBackup"
        const val PREF_KEY_UPDATE_INTERVAL_HOURS = "scheduled_update_interval_hours"
        const val DEFAULT_UPDATE_INTERVAL_HOURS = 12
        const val PREF_KEY_LAST_SCHEDULED_RUN = "last_scheduled_run_timestamp"
        const val PREF_KEY_LAST_BACKUP_TIME = "last_backup_timestamp"

        fun scheduleOrUpdate(context: Context, intervalHours: Int = DEFAULT_UPDATE_INTERVAL_HOURS) {
            val workManager = WorkManager.getInstance(context)
            if (intervalHours <= 0) {
                Log.i(TAG, "Canceling scheduled update worker (disabled)")
                workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<ScheduledUpdateWorker>(
                intervalHours.toLong(), TimeUnit.HOURS,
                30, TimeUnit.MINUTES // Flex interval
            )
                .setConstraints(constraints)
                .build()

            Log.i(TAG, "Enqueuing periodic update and backup worker every $intervalHours hours")
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Scheduled background update and backup started")
        val prefs = appContext.getSharedPreferences("eager_settings", Context.MODE_PRIVATE)

        try {
            val repository = PodcastRepository(appContext)
            val syncManager = SyncManager(appContext)
            val downloadManager = PodcastDownloadManager(appContext)

            // 1. Refresh all podcast feeds (which also purges expired news, rebuilds queue, and enqueues downloads)
            val (successCount, errors) = repository.refreshAllFeeds()
            Log.i(TAG, "Feed refresh completed: $successCount succeeded, ${errors.size} failed")

            // 2. Perform Scheduled Backup
            performLocalAndDriveBackup(syncManager)

            // 3. Ensure background auto-download queue is enqueued if enabled
            val autoDownloadEnabled = prefs.getBoolean(PodcastDownloadManager.PREF_KEY_AUTO_DOWNLOAD_ENABLED, true)
            if (autoDownloadEnabled) {
                PodcastDownloadManager.enqueueAutoDownload(appContext)
            }

            prefs.edit()
                .putLong(PREF_KEY_LAST_SCHEDULED_RUN, System.currentTimeMillis())
                .apply()

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error during scheduled update & backup: ${e.message}", e)
            Result.retry()
        }
    }

    private suspend fun performLocalAndDriveBackup(syncManager: SyncManager) {
        val db = EagerDatabase.getInstance(appContext)

        // 1. Local JSON Backup file (Format 2 serializer)
        val backupDir = File(appContext.filesDir, "backups")
        if (!backupDir.exists()) backupDir.mkdirs()

        val backupJson = com.eagertolisten.app.data.sync.SyncSerializer.serializeV2(db, syncManager.getOrCreateDeviceId())
        val latestBackupFile = File(backupDir, "eagertolisten_backup_latest.json")
        latestBackupFile.writeText(backupJson.toString(2), Charsets.UTF_8)
        Log.i(TAG, "Local database backup written to: ${latestBackupFile.absolutePath}")

        val prefs = appContext.getSharedPreferences("eager_settings", Context.MODE_PRIVATE)
        prefs.edit().putLong(PREF_KEY_LAST_BACKUP_TIME, System.currentTimeMillis()).apply()

        // 2. Also run Drive sync if user connected a Drive folder and autoSyncEnabled is true
        if (syncManager.state.value.folderUri != null && syncManager.state.value.autoSyncEnabled) {
            val syncResult = syncManager.performSync()
            if (syncResult.isSuccess) {
                Log.i(TAG, "Drive backup/sync completed successfully: ${syncResult.getOrNull()?.message}")
            } else {
                Log.w(TAG, "Drive backup/sync encountered error: ${syncResult.exceptionOrNull()?.message}")
            }
        }
    }
}
