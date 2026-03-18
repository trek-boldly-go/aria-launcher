// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.llm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiteRtModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val modelFile: File get() = File(context.filesDir, "litert_models/$MODEL_FILENAME")

    fun isModelDownloaded(): Boolean = modelFile.exists() && modelFile.length() > 0

    val modelPath: String get() = modelFile.absolutePath

    companion object {
        const val MODEL_FILENAME = "gemma3-1b.litertlm"
        const val MODEL_DOWNLOAD_URL =
            "https://kaggle.com/models/google/gemma-3/tfLite/gemma3-1b-it-int4/1/download"
    }
}
