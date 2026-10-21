// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.core

import android.content.Context
import android.util.Log
import com.microsoft.foundrylocal.api.Model
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Speech-to-text strategy used by [VoiceAssistant]. */
enum class SpeechMode(val label: String) {
    /** Whisper on VAD-segmented utterances; supports any Whisper language. */
    WHISPER("Whisper（日本語可・発話ごと）"),
    /** Native streaming ASR; English only. */
    STREAMING_EN("Nemotron（英語・ストリーミング）")
}

/** Configuration of a [VoiceAssistant]: speech mode, language, chat options and candidate model ids. */
data class AssistantConfig(
    val speechMode: SpeechMode = SpeechMode.WHISPER,
    val language: String = "ja",
    val chatEnabled: Boolean = true,
    /** Minimum RMS level treated as speech by the VAD. */
    val vadMinThreshold: Double = 120.0,
    /** Maximum number of tokens in a chat reply. */
    val maxReplyTokens: Int = 200,
    val systemPrompt: String = "あなたは XR デバイス上で動く音声アシスタントです。日本語で、2 文以内で簡潔に答えてください。",
    val whisperModels: List<String> = listOf("openai-whisper-base", "openai-whisper-small", "openai-whisper-tiny"),
    val streamingModels: List<String> = listOf("nemotron-speech-streaming-en"),
    val chatModels: List<String> = listOf(
        "qwen2.5-1.5b-instruct", "phi-4-mini-instruct", "qwen2.5-0.5b-instruct", "qwen2.5-coder-0.5b-instruct"
    )
)

/** One conversation entry: the user's utterance and the assistant's reply. */
data class Utterance(val user: String, val reply: String = "", val replying: Boolean = false)

/** Observable state of a [VoiceAssistant]. */
data class AssistantState(
    val status: String = "未初期化",
    val progress: Float? = null,
    val ready: Boolean = false,
    val listening: Boolean = false,
    val partial: String = "",
    val log: List<Utterance> = emptyList(),
    val error: String? = null,
    val models: String = ""
)

/**
 * Speech-to-text and chat pipeline shared by all device adapters; adapters supply a PCM flow and render [state].
 */
class VoiceAssistant(
    private val context: Context,
    private val scope: CoroutineScope,
    val config: AssistantConfig = AssistantConfig()
) {
    private val _state = MutableStateFlow(AssistantState())
    /** Current pipeline state. */
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    /** Called with each partial transcript. */
    var onPartial: (String) -> Unit = {}
    /** Called with each finished user utterance. */
    var onFinalTranscript: (String) -> Unit = {}
    /** Called with each finished reply. */
    var onReply: (String) -> Unit = {}
    /** Called with each streamed reply delta. */
    var onReplyDelta: (String) -> Unit = {}

    private var engine: FoundryEngine? = null
    private var speechModel: Model? = null
    private var chatModel: Model? = null
    private var transcriber: LiveTranscriber? = null
    private var whisper: ChunkedWhisperTranscriber? = null
    private var chat: ChatSession? = null
    private var listenJob: Job? = null
    private val chatLock = Mutex()

    /** Initializes the runtime and downloads and loads the speech and chat models. */
    fun prepare(): Job = scope.launch(Dispatchers.IO) {
        try {
            setStatus("ランタイム初期化中")
            val eng = engine ?: FoundryEngine.create(context).also { engine = it }
            eng.logCatalog()

            val speechId = when (config.speechMode) {
                SpeechMode.WHISPER -> eng.resolve(config.whisperModels, "whisper")
                SpeechMode.STREAMING_EN -> eng.resolve(config.streamingModels, "streaming")
            }
            val sm = eng.prepare(speechId, ::setStatus, ::setProgress)
            speechModel = sm
            val audio = sm.createAudioClient()
            val workDir = File(context.cacheDir, "flxr").apply { mkdirs() }
            transcriber = when (config.speechMode) {
                SpeechMode.WHISPER -> ChunkedWhisperTranscriber(audio, config.language, workDir) {
                    EnergyVad(minThreshold = config.vadMinThreshold)
                }.also { whisper = it }
                SpeechMode.STREAMING_EN -> StreamingTranscriber(audio, "en") { EnergyVad(minThreshold = config.vadMinThreshold) }
            }

            var chatId = "-"
            if (config.chatEnabled) {
                chatId = eng.resolve(config.chatModels, "instruct")
                val cm = eng.prepare(chatId, ::setStatus, ::setProgress)
                chatModel = cm
                chat = ChatSession(cm.createChatClient(), config.systemPrompt, maxTokens = config.maxReplyTokens)
            }
            _state.update {
                it.copy(status = "準備完了", progress = null, ready = true, error = null,
                    models = "音声: $speechId\nチャット: $chatId")
            }
            Log.i(FoundryEngine.TAG, "ready speech=$speechId chat=$chatId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("初期化失敗", e)
        }
    }

    /**
     * Starts transcribing [pcm] until [stopListening] is called or the flow ends.
     * Returns false if the speech model is not ready or a session is already running.
     */
    fun startListening(pcm: Flow<ByteArray>): Boolean {
        val t = transcriber ?: run { fail("音声モデルが未準備", null); return false }
        if (listenJob?.isActive == true) return false
        _state.update { it.copy(listening = true, partial = "", error = null) }
        listenJob = scope.launch(Dispatchers.IO) {
            val finals = Channel<String>(Channel.UNLIMITED)
            val replier = launch { for (text in finals) handleFinal(text) }
            try {
                t.transcribe(pcm).collect { event ->
                    when (event) {
                        is TranscriptEvent.Partial -> {
                            onPartial(event.text)
                            _state.update { it.copy(partial = event.text) }
                        }
                        is TranscriptEvent.Final -> finals.send(event.text)
                    }
                }
                finals.close()
                replier.join()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("文字起こし失敗", e)
            } finally {
                Log.i(FoundryEngine.TAG, "listening ended")
                _state.update { it.copy(listening = false, partial = "") }
            }
        }
        return true
    }

    /** Stops the current listening session. */
    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
    }

    /** Handles [text] as a finished user utterance. */
    fun ask(text: String) {
        scope.launch(Dispatchers.IO) { handleFinal(text) }
    }

    private suspend fun handleFinal(text: String) {
        Log.i(FoundryEngine.TAG, "final: $text")
        onFinalTranscript(text)
        _state.update { it.copy(partial = "", log = (it.log + Utterance(text, replying = chat != null)).takeLast(20)) }
        val session = chat ?: return
        chatLock.withLock {
            try {
                val reply = StringBuilder()
                session.reply(text).collect { delta ->
                    reply.append(delta)
                    onReplyDelta(delta)
                    updateLastReply(reply.toString(), replying = true)
                }
                updateLastReply(reply.toString().trim(), replying = false)
                Log.i(FoundryEngine.TAG, "reply: ${reply.toString().trim()}")
                onReply(reply.toString().trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateLastReply("(応答失敗)", replying = false)
                fail("チャット失敗", e)
            }
        }
    }

    private fun updateLastReply(reply: String, replying: Boolean) = _state.update { s ->
        val idx = s.log.indexOfLast { it.replying || it.reply.isEmpty() }
        if (idx < 0) s else s.copy(log = s.log.toMutableList().also { it[idx] = it[idx].copy(reply = reply, replying = replying) })
    }

    private fun setStatus(text: String) {
        Log.i(FoundryEngine.TAG, "status: $text")
        _state.update { it.copy(status = text) }
    }

    private fun setProgress(p: Float) = _state.update { it.copy(progress = p / 100f) }

    private fun fail(message: String, e: Throwable?) {
        Log.e(FoundryEngine.TAG, message, e)
        _state.update { it.copy(status = message, progress = null, error = "$message: ${e?.message ?: ""}") }
    }

    /** Stops listening and unloads the models and runtime. */
    fun close() {
        stopListening()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { speechModel?.unload() }
            runCatching { chatModel?.unload() }
            runCatching { engine?.close() }
        }
    }
}
