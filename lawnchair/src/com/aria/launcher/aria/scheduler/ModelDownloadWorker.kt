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
            setForeground(createForegroundInfo("Downloading on-device model…"))
            modelManager.modelFile.parentFile?.mkdirs()
            downloadModel()
            Log.d(TAG, "Model download complete: ${modelManager.modelFile.length()} bytes")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed (attempt $runAttemptCount)", e)
            // Clean up partial file
            modelManager.modelFile.delete()
            if (runAttemptCount < 3) Result.retry() else Result.failure()
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

            modelManager.modelFile.sink().buffer().use { sink ->
                val source = body.source()
                var bytesRead = 0L
                val buffer = okio.Buffer()
                while (source.read(buffer, BUFFER_SIZE) != -1L) {
                    sink.write(buffer, buffer.size)
                    bytesRead += buffer.size
                    if (totalBytes > 0) {
                        val progress = (bytesRead * 100 / totalBytes).toInt()
                        setProgressAsync(
                            androidx.work.Data.Builder()
                                .putInt("progress", progress)
                                .build(),
                        )
                    }
                }
            }
        }
    }

    private fun createForegroundInfo(title: String): ForegroundInfo {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "ARIA Model Download",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        private const val TAG = "ARIA.ModelDownload"
        private const val WORK_NAME = "litert_model_download"
        private const val CHANNEL_ID = "aria_model_download"
        private const val NOTIFICATION_ID = 9015
        private const val BUFFER_SIZE = 8192L

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
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
