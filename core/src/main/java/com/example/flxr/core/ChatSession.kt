// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.core

import com.microsoft.foundrylocal.api.ChatClient
import com.microsoft.foundrylocal.api.ChatCompletionRequest
import com.microsoft.foundrylocal.api.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Multi-turn chat session that keeps a bounded conversation history and sends it with every request.
 */
class ChatSession(
    private val client: ChatClient,
    private val systemPrompt: String,
    private val maxTurns: Int = 4,
    private val maxTokens: Int = 200
) {
    private val history = ArrayDeque<ChatMessage>()

    /** Streams the reply to [userText] as text deltas and appends the full reply to the history on completion. */
    fun reply(userText: String): Flow<String> = flow {
        history.addLast(ChatMessage.user(userText))
        while (history.size > maxTurns * 2 - 1) history.removeFirst()
        val request = ChatCompletionRequest(
            messages = listOf(ChatMessage.system(systemPrompt)) + history,
            maxTokens = maxTokens
        )
        val answer = StringBuilder()
        client.completeChatStreaming(request).collect { chunk ->
            if (chunk.delta.isNotEmpty()) {
                answer.append(chunk.delta)
                emit(chunk.delta)
            }
        }
        history.addLast(ChatMessage.assistant(answer.toString()))
    }
}
