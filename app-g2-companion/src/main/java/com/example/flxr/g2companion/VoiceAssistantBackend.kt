// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.g2companion

import com.example.flxr.core.VoiceAssistant
import com.example.flxr.g2companion.server.AssistantBackend
import com.example.flxr.g2companion.server.BackendListener
import com.example.flxr.g2companion.server.BackendState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Adapts the shared core [VoiceAssistant] to the server's [AssistantBackend]. */
class VoiceAssistantBackend(private val assistant: VoiceAssistant, scope: CoroutineScope) : AssistantBackend {

    override val state: StateFlow<BackendState> = assistant.state
        .map { BackendState(it.ready, it.listening, it.status, it.partial, it.error, it.models) }
        .stateIn(scope, SharingStarted.Eagerly, BackendState())

    override fun setListener(listener: BackendListener) {
        assistant.onFinalTranscript = listener::onFinalTranscript
        assistant.onReplyDelta = listener::onReplyDelta
        assistant.onReply = listener::onReply
    }

    override fun startListening(pcm: Flow<ByteArray>) { assistant.startListening(pcm) }
    override fun ask(text: String) = assistant.ask(text)
}
