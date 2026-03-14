package com.aria.launcher.aria.scheduler

import android.content.Context
import android.os.PowerManager
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aria.launcher.aria.engine.PredictionEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Nightly background job that recomputes app prediction scores.
 *
 * Constraints:
 * - Device must be charging (battery-first philosophy)
 * - Scheduled for ~3 AM to overlap with typical sleep window
 *
 * The worker delegates all logic to [PredictionEngine] which reads usage events
 * from Room, groups by [ContextKey], normalises scores, and writes [AppPrediction]
 * rows back. The home screen reads those rows at unlock time.
 */
@HiltWorker
class NightlyPredictionWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val predictionEngine: PredictionEngine,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Extra guard: only run if the screen is off or device is idle,
        // so we never burn CPU during active evening use on the charger
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            // Screen is on — skip and retry next cycle
            return Result.retry()
        }

        // TODO: read home/work WiFi SSIDs from user preferences (SharedPreferences / DataStore)
        val homeWifiSsid: String? = null
        val workWifiSsid: String? = null

        return try {
            predictionEngine.generatePredictions(
                homeWifiSsid = homeWifiSsid,
                workWifiSsid = workWifiSsid,
            )
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "aria_nightly_prediction"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .build()

            val delayMs = msUntil3AM()

            val request = PeriodicWorkRequestBuilder<NightlyPredictionWorker>(
                1, TimeUnit.DAYS,
            )
                .setConstraints(constraints)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Returns milliseconds from now until the next 3:00 AM.
         */
        private fun msUntil3AM(): Long {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 3)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }
    }
}
