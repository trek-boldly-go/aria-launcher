// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.llm

import android.app.ActivityManager
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

/**
 * Available on-device LLM model variants.
 * Both use the same LiteRT-LM SDK and `.litertlm` format.
 */
enum class OnDeviceModel(
    val displayName: String,
    val filename: String,
    val downloadUrl: String,
    val sizeDescription: String,
    val sizeBytes: Long,
    val minRamMb: Long,
    val qualityDescription: String,
    val modelIdTag: String,
) {
    GEMMA_1B(
        displayName = "Gemma3 1B",
        filename = "gemma3-1b-it-int4.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
        sizeDescription = "~600 MB",
        sizeBytes = 584_000_000L,
        minRamMb = 2048,
        qualityDescription = "Fast, lightweight \u2022 Good for quick tasks",
        modelIdTag = "gemma3-1b",
    ),
    GEMMA_3N_E4B(
        displayName = "Gemma 3n E4B",
        filename = "gemma-3n-E4B-it-int4.litertlm",
        downloadUrl = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm",
        sizeDescription = "~4.6 GB",
        sizeBytes = 4_650_000_000L,
        minRamMb = 3072,
        qualityDescription = "Higher quality \u2022 Better reasoning and curation",
        modelIdTag = "gemma-3n-e4b",
    ),
}

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
    /** Set by [LlmProviderManager] on init and model switch. */
    @Volatile var selectedModel: OnDeviceModel = OnDeviceModel.GEMMA_1B

    val modelFile: File get() = File(context.filesDir, "litert_models/${selectedModel.filename}")

    fun isModelDownloaded(): Boolean = modelFile.exists() && modelFile.length() > 0

    fun isModelDownloaded(model: OnDeviceModel): Boolean {
        val file = File(context.filesDir, "litert_models/${model.filename}")
        return file.exists() && file.length() > 0
    }

    val modelPath: String get() = modelFile.absolutePath

    fun deleteModelFile(model: OnDeviceModel) {
        val file = File(context.filesDir, "litert_models/${model.filename}")
        if (file.exists()) file.delete()
    }

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
        fun getDeviceTotalRamMb(context: Context): Long {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            return memInfo.totalMem / (1024 * 1024)
        }

        fun isModelEligible(context: Context, model: OnDeviceModel): Boolean = getDeviceTotalRamMb(context) >= model.minRamMb
    }
}
