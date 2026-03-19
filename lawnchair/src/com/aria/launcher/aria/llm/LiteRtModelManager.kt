// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.llm

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.aria.launcher.aria.scheduler.ModelDownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

sealed interface ModelDownloadState {
    data object NotStarted : ModelDownloadState
    data object Queued : ModelDownloadState
    data class Downloading(val progress: Int) : ModelDownloadState
    data object Completed : ModelDownloadState
    data class Failed(val message: String) : ModelDownloadState
}

@Singleton
class LiteRtModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val modelFile: File get() = File(context.filesDir, "litert_models/$MODEL_FILENAME")

    fun isModelDownloaded(): Boolean = modelFile.exists() && modelFile.length() > 0

    val modelPath: String get() = modelFile.absolutePath

    fun downloadState(): Flow<ModelDownloadState> {
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.WORK_NAME)
            .map { workInfos ->
                val workInfo = workInfos.firstOrNull()
                when {
                    workInfo == null -> {
                        if (isModelDownloaded()) {
                            ModelDownloadState.Completed
                        } else {
                            ModelDownloadState.NotStarted
                        }
                    }

                    workInfo.state == WorkInfo.State.ENQUEUED -> ModelDownloadState.Queued

                    workInfo.state == WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)
                        ModelDownloadState.Downloading(progress)
                    }

                    workInfo.state == WorkInfo.State.SUCCEEDED -> ModelDownloadState.Completed

                    workInfo.state == WorkInfo.State.FAILED -> {
                        val error = workInfo.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                            ?: "Download failed. Tap to retry."
                        ModelDownloadState.Failed(error)
                    }

                    workInfo.state == WorkInfo.State.CANCELLED -> {
                        if (isModelDownloaded()) {
                            ModelDownloadState.Completed
                        } else {
                            ModelDownloadState.NotStarted
                        }
                    }

                    else -> ModelDownloadState.NotStarted
                }
            }
    }

    companion object {
        const val MODEL_FILENAME = "gemma3-1b.litertlm"
        const val MODEL_DOWNLOAD_URL =
            "https://kaggle.com/models/google/gemma-3/tfLite/gemma3-1b-it-int4/1/download"
    }
}
