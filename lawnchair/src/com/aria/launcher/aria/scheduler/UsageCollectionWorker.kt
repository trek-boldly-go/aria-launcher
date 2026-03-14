package com.aria.launcher.aria.scheduler

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aria.launcher.aria.data.UsageStatsCollector
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic background job that ingests recent usage events into the ARIA database.
 * Runs every [INTERVAL_HOURS] hours regardless of charging state — it's lightweight
 * (just a DB write). The heavy nightly prediction job is separate.
 */
@HiltWorker
class UsageCollectionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val collector: UsageStatsCollector,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "UsageCollectionWorker started")
        if (!collector.hasPermission()) {
            Log.w(TAG, "PACKAGE_USAGE_STATS permission not granted — skipping collection")
            return Result.success()
        }
        return try {
            collector.collectAndStore()
            Log.d(TAG, "UsageCollectionWorker completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "UsageCollectionWorker failed", e)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "ARIA.UsageWorker"
        private const val WORK_NAME = "aria_usage_collection"
        private const val INTERVAL_HOURS = 4L

        /** Trigger a one-time collection now (for testing/debugging). */
        fun runOnce(context: Context) {
            Log.d(TAG, "Enqueuing one-time usage collection")
            val request = OneTimeWorkRequestBuilder<UsageCollectionWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<UsageCollectionWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
