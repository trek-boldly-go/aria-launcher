// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
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
import com.aria.launcher.aria.llm.LiteRtModelManager
import com.aria.launcher.aria.llm.OnDeviceModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink

@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val modelManager: LiteRtModelManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Sync selectedModel from inputData so modelManager points at the right file
        val modelTag = inputData.getString(KEY_MODEL_TAG)
        if (modelTag != null) {
            val model = OnDeviceModel.entries.firstOrNull { it.modelIdTag == modelTag }
            if (model != null) modelManager.selectedModel = model
        }

        if (modelManager.isModelDownloaded()) {
            Log.d(TAG, "Model already downloaded, skipping")
            return Result.success()
        }

        val displayName = inputData.getString(KEY_MODEL_DISPLAY_NAME)
            ?: modelManager.selectedModel.displayName

        return try {
            setForeground(createForegroundInfo("Downloading $displayName\u2026", 0, 0))
            modelManager.modelFile.parentFile?.mkdirs()
            val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL)
                ?: modelManager.selectedModel.downloadUrl
            Log.i(TAG, "Starting model download from $downloadUrl (attempt $runAttemptCount)")
            downloadModel(downloadUrl)
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

    private fun downloadModel(downloadUrl: String) {
        val hfToken = inputData.getString(KEY_HF_TOKEN)
        val displayName = inputData.getString(KEY_MODEL_DISPLAY_NAME)
            ?: modelManager.selectedModel.displayName
        val requestBuilder = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "ARIA-Launcher/1.0")
        if (!hfToken.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $hfToken")
        }
        val request = requestBuilder.build()

        downloadClient.newCall(request).execute().use { response ->
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
                                .setContentTitle("Downloading $displayName\u2026 $progress%")
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
        /** Dedicated client for large file downloads — longer timeouts than the LLM client. */
        private val downloadClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        private const val TAG = "ARIA.ModelDownload"
        const val WORK_NAME = "litert_model_download"
        private const val CHANNEL_ID = "aria_model_download"
        private const val NOTIFICATION_ID = 9015
        private const val BUFFER_SIZE = 8192L
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        private const val KEY_HF_TOKEN = "hf_token"
        private const val KEY_DOWNLOAD_URL = "download_url"
        private const val KEY_MODEL_DISPLAY_NAME = "model_display_name"
        private const val KEY_MODEL_TAG = "model_tag"

        fun enqueue(
            context: Context,
            model: OnDeviceModel,
            bypassConstraints: Boolean = false,
            hfToken: String? = null,
        ) {
            if (hfToken.isNullOrBlank()) {
                Log.w(TAG, "No HuggingFace token provided — download will likely fail (model is gated)")
            }
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
            Log.i(TAG, "Enqueuing ${model.displayName} download (bypassConstraints=$bypassConstraints, hasToken=${!hfToken.isNullOrBlank()})")
            val inputData = workDataOf(
                KEY_HF_TOKEN to hfToken,
                KEY_DOWNLOAD_URL to model.downloadUrl,
                KEY_MODEL_DISPLAY_NAME to model.displayName,
                KEY_MODEL_TAG to model.modelIdTag,
            )
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun humanizeDownloadError(raw: String): String = when {
            "HTTP 401" in raw || "HTTP 403" in raw ->
                "Authentication required. Add your HuggingFace token in ARIA settings."

            "HTTP 4" in raw -> "Server rejected the request. The download URL may have changed."

            "HTTP 5" in raw -> "Server error. Try again later."

            "ConnectException" in raw || "ECONNREFUSED" in raw || "UnknownHostException" in raw ->
                "Can't reach the download server. Check your internet connection."

            "SocketTimeoutException" in raw || "timeout" in raw.lowercase() ->
                "Connection timed out. Try again on a faster network."

            "No space" in raw || "ENOSPC" in raw ->
                "Not enough storage space. Free up some space and try again."

            "IOException" in raw || "ProtocolException" in raw ->
                "Network error during download. Try again."

            else -> "Download failed: ${raw.take(200)}"
        }
    }
}
