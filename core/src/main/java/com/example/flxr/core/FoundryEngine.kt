// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.core

import android.content.Context
import android.util.Log
import com.microsoft.foundrylocal.api.Configuration
import com.microsoft.foundrylocal.api.FoundryLocalManager
import com.microsoft.foundrylocal.api.Model
import com.microsoft.foundrylocal.api.ModelInfo

/**
 * Owner of [FoundryLocalManager] that provides catalog lookup and the get, download and load model lifecycle.
 */
class FoundryEngine private constructor(val manager: FoundryLocalManager) {

    companion object {
        const val TAG = "FLXR"

        /** Creates the Foundry Local runtime for [context]. */
        suspend fun create(context: Context, appName: String = "FoundryLocalXR"): FoundryEngine {
            val manager = FoundryLocalManager.create(
                context.applicationContext,
                Configuration(appName = appName)
            )
            return FoundryEngine(manager)
        }
    }

    /** Returns all models in the catalog. */
    suspend fun listModels(): List<ModelInfo> = manager.getCatalog().listModels()

    /** Logs every catalog entry and returns the catalog. */
    suspend fun logCatalog(): List<ModelInfo> {
        val models = listModels()
        Log.i(TAG, "catalog: ${models.size} models")
        models.forEach {
            Log.i(TAG, "  model ${modelId(it)} task=${it.task} ep=${it.executionProvider} size=${it.fileSizeMb}MB")
        }
        return models
    }

    /**
     * Returns the id of the first catalog model matching [preferences] (exact id or id prefix),
     * or the smallest model whose id contains [keyword].
     */
    suspend fun resolve(preferences: List<String>, keyword: String): String {
        val models = listModels()
        for (pref in preferences) {
            models.firstOrNull { modelId(it) == pref }?.let { return modelId(it) }
            models.filter { modelId(it).startsWith(pref) }
                .minByOrNull { it.fileSizeMb }
                ?.let { return modelId(it) }
        }
        return models.filter { modelId(it).contains(keyword, ignoreCase = true) }
            .minByOrNull { it.fileSizeMb }
            ?.let { modelId(it) }
            ?: throw IllegalStateException("No catalog model matches '$keyword'")
    }

    /** Returns the model [modelId], downloading and loading it when needed. */
    suspend fun prepare(
        modelId: String,
        onStatus: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {}
    ): Model {
        val model = manager.getCatalog().getModel(modelId)
        if (!model.isCached()) {
            onStatus("ダウンロード中: $modelId")
            model.download(progress = { onProgress(it) })
        }
        if (!model.isLoaded()) {
            onStatus("読み込み中: $modelId")
            model.load()
        }
        onStatus("準備完了: $modelId")
        return model
    }

    /** Closes the runtime. */
    fun close() = manager.close()
}

/** Returns the full model id (`name:version`) of [info]. */
fun modelId(info: ModelInfo): String =
    if (info.name.contains(':')) info.name else "${info.name}:${info.version}"
