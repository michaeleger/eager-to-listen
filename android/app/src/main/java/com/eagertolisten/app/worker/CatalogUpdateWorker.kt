package com.eagertolisten.app.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.eagertolisten.app.data.podcastindex.LocalPodcastCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Keeps the local partial copy of the Podcast Index directory fresh.
 * Runs a lightweight daily check on unmetered Wi-Fi (with battery & storage not low) and only
 * downloads a new snapshot when the local DB is missing or older than 21 days.
 */
class CatalogUpdateWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "CatalogUpdateWorker"
        const val UNIQUE_PERIODIC_WORK = "EagerPodcastIndexCatalogRefresh"
        const val UNIQUE_IMMEDIATE_WORK = "EagerPodcastIndexCatalogImmediate"
        const val INPUT_FORCE = "force"

        fun schedulePeriodicCheck(context: Context) {
            val workManager = try {
                WorkManager.getInstance(context)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "WorkManager not initialized; skipping catalog periodic schedule: ${e.message}")
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresStorageNotLow(true)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<CatalogUpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun enqueueIfStale(context: Context, force: Boolean = false) {
            val catalog = LocalPodcastCatalog(context)
            if (!force && !catalog.isStale()) return

            val workManager = try {
                WorkManager.getInstance(context)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "WorkManager not initialized; skipping immediate catalog check: ${e.message}")
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (force) NetworkType.CONNECTED else NetworkType.UNMETERED)
                .setRequiresStorageNotLow(true)
                .build()

            val request = OneTimeWorkRequestBuilder<CatalogUpdateWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(INPUT_FORCE to force))
                .build()

            workManager.enqueueUniqueWork(
                UNIQUE_IMMEDIATE_WORK,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val force = inputData.getBoolean(INPUT_FORCE, false)
        val catalog = LocalPodcastCatalog(appContext)
        val result = catalog.refreshIfStale(force = force, requireUnmetered = !force)
        Log.i(TAG, "Catalog refresh finished: ${result.outcome} (${result.message})")
        when (result.outcome) {
            LocalPodcastCatalog.RefreshOutcome.UP_TO_DATE,
            LocalPodcastCatalog.RefreshOutcome.UPDATED,
            LocalPodcastCatalog.RefreshOutcome.NOT_MODIFIED,
            LocalPodcastCatalog.RefreshOutcome.SKIPPED_METERED -> Result.success()
            LocalPodcastCatalog.RefreshOutcome.FAILED -> Result.retry()
        }
    }
}
