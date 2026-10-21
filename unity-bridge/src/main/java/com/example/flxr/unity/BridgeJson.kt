// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.unity

import com.example.flxr.core.AssistantConfig
import com.example.flxr.core.AssistantState
import com.example.flxr.core.SpeechMode
import org.json.JSONArray
import org.json.JSONObject

/** Converts between the bridge's JSON messages and core types. */
internal object BridgeJson {

    /** Parses a config JSON into an [AssistantConfig]; missing fields fall back to the defaults. */
    fun parseConfig(json: String?): AssistantConfig {
        val d = AssistantConfig()
        if (json.isNullOrBlank()) return d
        val o = JSONObject(json)
        val mode = when (o.optString("speechMode", "").lowercase()) {
            "streaming_en", "streaming", "nemotron", "en" -> SpeechMode.STREAMING_EN
            "whisper", "" -> SpeechMode.WHISPER
            else -> throw IllegalArgumentException("unknown speechMode: ${o.optString("speechMode")}")
        }
        return d.copy(
            speechMode = mode,
            language = o.optString("language", "").ifBlank {
                if (mode == SpeechMode.STREAMING_EN) "en" else d.language
            },
            chatEnabled = o.optBoolean("chatEnabled", d.chatEnabled),
            systemPrompt = o.optString("systemPrompt", "").ifBlank { d.systemPrompt },
            whisperModels = o.optStringList("whisperModels") ?: d.whisperModels,
            streamingModels = o.optStringList("streamingModels") ?: d.streamingModels,
            chatModels = o.optStringList("chatModels") ?: d.chatModels
        )
    }

    private fun JSONObject.optStringList(key: String): List<String>? {
        val a: JSONArray = optJSONArray(key) ?: return null
        return (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }.ifEmpty { null }
    }

    /** Projection of [AssistantState] sent to the host, without the conversation log. */
    data class StateView(
        val status: String,
        val progress: Float,
        val ready: Boolean,
        val listening: Boolean,
        val partial: String,
        val error: String,
        val models: String
    )

    /** Projects [s] to a [StateView]. */
    fun view(s: AssistantState) = StateView(
        status = s.status,
        progress = s.progress ?: -1f,
        ready = s.ready,
        listening = s.listening,
        partial = s.partial,
        error = s.error ?: "",
        models = s.models
    )

    /** Serializes [v] as an OnBridgeState message. */
    fun state(v: StateView): String = JSONObject()
        .put("status", v.status)
        .put("progress", v.progress.toDouble())
        .put("ready", v.ready)
        .put("listening", v.listening)
        .put("partial", v.partial)
        .put("error", v.error)
        .put("models", v.models)
        .toString()

    /** Builds a `{"text"}` message. */
    fun text(text: String): String = JSONObject().put("text", text).toString()

    /** Builds a `{"code","message"}` error message. */
    fun error(code: String, message: String): String =
        JSONObject().put("code", code).put("message", message).toString()
}
