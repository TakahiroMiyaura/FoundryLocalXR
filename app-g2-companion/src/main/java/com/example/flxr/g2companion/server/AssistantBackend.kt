// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.g2companion.server

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Assistant state as seen by the server. */
data class BackendState(
    val ready: Boolean = false,
    val listening: Boolean = false,
    val status: String = "",
    val partial: String = "",
    val error: String? = null,
    val models: String = ""
)

/** Receives transcripts and replies from an [AssistantBackend]. */
interface BackendListener {
    fun onFinalTranscript(text: String)
    fun onReplyDelta(delta: String)
    fun onReply(text: String)
}

/** Speech-to-text and chat backend driven by [SessionHub]. */
interface AssistantBackend {
    /** Current assistant state. */
    val state: StateFlow<BackendState>

    /** Registers the listener that receives transcripts and replies. */
    fun setListener(listener: BackendListener)

    /** Consumes 16 kHz / mono / s16le PCM until the flow completes. */
    fun startListening(pcm: Flow<ByteArray>)

    /** Sends a text question to the chat model. */
    fun ask(text: String)
}
