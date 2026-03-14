package com.aria.launcher.aria.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives activity recognition results from [ActivityRecognitionClient] and
 * forwards the most probable activity type to [ContextSignalManager].
 *
 * Activity types (from DetectedActivity):
 *   IN_VEHICLE = 0, ON_BICYCLE = 1, ON_FOOT = 2, STILL = 3,
 *   UNKNOWN = 4, TILTING = 5, WALKING = 7, RUNNING = 8
 */
@AndroidEntryPoint
class ActivityUpdateReceiver : BroadcastReceiver() {

    @Inject
    lateinit var contextSignalManager: ContextSignalManager

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val activityType = result.mostProbableActivity.type
        contextSignalManager.onActivityDetected(activityType)
    }
}
