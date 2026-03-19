// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.scheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aria.launcher.aria.llm.AriaLlmClient
import com.aria.launcher.aria.llm.LiteRtModelManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink

@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val modelManager: LiteRtModelManager,
    @AriaLlmClient private val client: OkHttpClient,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (modelManager.isModelDownloaded()) {
            Log.d(TAG, "Model already downloaded, skipping")
            return Result.success()
        }

        return try {
            setForeground(createForegroundInfo("Downloading on-device model…", 0, 0))
            modelManager.modelFile.parentFile?.mkdirs()
            Log.i(TAG, "Starting model download from ${LiteRtModelManager.MODEL_DOWNLOAD_URL} (attempt $runAttemptCount)")
            downloadModel()
            val fileSize = modelManager.modelFile.length()
            Log.i(TAG, "Model download complete: $fileSize bytes (${fileSize / 1_048_576} MB)")
            dismissNotification()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed (attempt $runAttemptCount): ${e.message}", e)
            modelManager.modelFile.delete()
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                val errorMsg = humanizeDownloadError(e.message ?: "Unknown error")
                Log.e(TAG, "Model download permanently failed after $runAttemptCount attempts: $errorMsg")
                Result.failure(workDataOf(KEY_ERROR to errorMsg))
            }
        }
    }

    private fun downloadModel() {
        val request = Request.Builder()
            .url(LiteRtModelManager.MODEL_DOWNLOAD_URL)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Download failed: HTTP ${response.code}")
            }
            val body = response.body ?: error("Empty response body")
            val totalBytes = body.contentLength()
            Log.d(TAG, "Download response: HTTP ${response.code}, content-length: $totalBytes bytes")

            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            var lastNotifiedProgress = -1

            modelManager.modelFile.sink().buffer().use { sink ->
                val source = body.source()
                var bytesRead = 0L
                val buffer = okio.Buffer()
                while (source.read(buffer, BUFFER_SIZE) != -1L) {
                    sink.write(buffer, buffer.size)
                    bytesRead += buffer.size
                    if (totalBytes > 0) {
                        val progress = (bytesRead * 100 / totalBytes).toInt()
                        setProgressAsync(workDataOf(KEY_PROGRESS to progress))
                        // Update notification every 2% to avoid excessive updates
                        if (progress >= lastNotifiedProgress + 2) {
                            lastNotifiedProgress = progress
                            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                                .setContentTitle("Downloading on-device model\u2026 $progress%")
                                .setSmallIcon(android.R.drawable.stat_sys_download)
                                .setOngoing(true)
                                .setProgress(100, progress, false)
                                .build()
                            nm.notify(NOTIFICATION_ID, notification)
                            if (progress % 10 == 0) {
                                Log.d(TAG, "Download progress: $progress% ($bytesRead / $totalBytes bytes)")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun createForegroundInfo(title: String, progress: Int, max: Int): ForegroundInfo {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "ARIA Model Download",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val indeterminate = max == 0
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(max, progress, indeterminate)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun dismissNotification() {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    companion object {
        private const val TAG = "ARIA.ModelDownload"
        const val WORK_NAME = "litert_model_download"
        private const val CHANNEL_ID = "aria_model_download"
        private const val NOTIFICATION_ID = 9015
        private const val BUFFER_SIZE = 8192L
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"

        fun enqueue(context: Context, bypassConstraints: Boolean = false) {
            val constraints = if (bypassConstraints) {
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            } else {
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(true)
                    .build()
            }
            Log.i(TAG, "Enqueuing model download (bypassConstraints=$bypassConstraints)")
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun humanizeDownloadError(raw: String): String = when {
            "HTTP 4" in raw -> "Server rejected the request. The download URL may have changed."

            "HTTP 5" in raw -> "Server error. Try again later."

            "ConnectException" in raw || "ECONNREFUSED" in raw || "UnknownHostException" in raw ->
                "Can't reach the download server. Check your internet connection."

            "SocketTimeoutException" in raw || "timeout" in raw.lowercase() ->
                "Connection timed out. Try again on a faster network."

            "No space" in raw || "ENOSPC" in raw ->
                "Not enough storage space. Free up ~1 GB and try again."

            "IOException" in raw || "ProtocolException" in raw ->
                "Network error during download. Try again."

            else -> "Download failed: ${raw.take(200)}"
        }
    }
}
