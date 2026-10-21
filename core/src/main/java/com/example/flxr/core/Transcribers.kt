// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.core

import android.util.Log
import com.microsoft.foundrylocal.api.AudioClient
import com.microsoft.foundrylocal.api.AudioStreamSettings
import com.microsoft.foundrylocal.api.AudioTranscriptionRequest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Event emitted by a [LiveTranscriber]. */
sealed interface TranscriptEvent {
    /** Current hypothesis for the utterance in progress, replacing the previous one. */
    data class Partial(val text: String) : TranscriptEvent
    /** A finished utterance. */
    data class Final(val text: String) : TranscriptEvent
}

/** Converts a live PCM stream into transcript events. */
interface LiveTranscriber {
    /** Transcribes [pcm] until it completes or the collector is cancelled. */
    fun transcribe(pcm: Flow<ByteArray>): Flow<TranscriptEvent>
}

/**
 * Transcriber that runs Whisper on VAD-segmented utterances, emitting text after each utterance ends.
 */
class ChunkedWhisperTranscriber(
    private val client: AudioClient,
    private val language: String?,
    private val workDir: File,
    private val vadFactory: () -> EnergyVad = { EnergyVad() }
) : LiveTranscriber {

    override fun transcribe(pcm: Flow<ByteArray>): Flow<TranscriptEvent> = channelFlow {
        val utterances = Channel<ByteArray>(Channel.UNLIMITED)
        launch {
            try {
                cutUtterances(pcm) { utterances.send(it) }
            } finally {
                utterances.close()
            }
        }
        var index = 0
        for (bytes in utterances) {
            send(TranscriptEvent.Partial("…"))
            val started = System.nanoTime()
            val text = transcribePcm(bytes, "utt_${index++ % 4}.wav")
            Log.i(FoundryEngine.TAG, "whisper ${bytes.size * 1000L / Pcm.BYTES_PER_SECOND}ms audio -> " +
                "${(System.nanoTime() - started) / 1_000_000}ms")
            if (text.isNotBlank() && !isHallucination(text)) send(TranscriptEvent.Final(text))
            else send(TranscriptEvent.Partial(""))
        }
    }

    private suspend fun cutUtterances(pcm: Flow<ByteArray>, onUtterance: suspend (ByteArray) -> Unit) {
        val vad = vadFactory()
        val utterance = ByteArrayOutputStream()
        val preRoll = ArrayDeque<ByteArray>()

        suspend fun flushUtterance() {
            val bytes = utterance.toByteArray()
            utterance.reset()
            if (bytes.size >= Pcm.BYTES_PER_SECOND / 2) onUtterance(bytes)
        }

        pcm.collect { chunk ->
            when (vad.feed(chunk)) {
                EnergyVad.Event.SPEECH_START -> {
                    preRoll.forEach { utterance.write(it) }
                    preRoll.clear()
                    utterance.write(chunk)
                }
                EnergyVad.Event.UTTERANCE_END -> {
                    utterance.write(chunk)
                    flushUtterance()
                }
                EnergyVad.Event.NONE -> {
                    if (vad.isInSpeech) utterance.write(chunk)
                    else {
                        preRoll.addLast(chunk)
                        while (preRoll.sumOf { it.size } > Pcm.BYTES_PER_SECOND * 3 / 10) preRoll.removeFirst()
                    }
                }
            }
        }
        withContext(NonCancellable) { if (utterance.size() > 0) flushUtterance() }
    }

    private suspend fun transcribePcm(pcm: ByteArray, name: String): String {
        val file = File(workDir, name)
        writeWav(file, pcm)
        return transcribeFile(file)
    }

    /** Transcribes a 16 kHz mono PCM16 WAV file and returns the whitespace-normalized text. */
    suspend fun transcribeFile(file: File): String =
        client.transcribe(AudioTranscriptionRequest(filePath = file.absolutePath, language = language))
            .text.replace(Regex("\\s+"), " ").trim()

    private fun isHallucination(text: String): Boolean {
        val t = text.trim().trim('.', '。', '!', ' ').lowercase()
        return t.isEmpty() || t in setOf(
            "thank you", "thanks for watching", "you", "[music]", "(music)", "[blank_audio]",
            "ご視聴ありがとうございました", "ありがとうございました"
        ) || NON_SPEECH.matches(t)
    }

    private companion object {
        val NON_SPEECH = Regex("""^[\[(（【].*[\])）】]$""")
    }
}

/**
 * Transcriber backed by the SDK's native streaming ASR that emits partial text while speaking
 * and uses a VAD to split the running hypothesis into utterances.
 */
class StreamingTranscriber(
    private val client: AudioClient,
    private val language: String? = "en",
    private val vadFactory: () -> EnergyVad = { EnergyVad() }
) : LiveTranscriber {

    override fun transcribe(pcm: Flow<ByteArray>): Flow<TranscriptEvent> = flow {
        val session = client.createStreamSession(
            AudioStreamSettings(sampleRate = Pcm.SAMPLE_RATE, channels = 1, bitsPerSample = 16, language = language)
        )
        val vad = vadFactory()
        var hypothesis = ""
        var committed = 0
        fun pending() = hypothesis.substring(minOf(committed, hypothesis.length)).trim().trimStart(',', '.', ' ')
        var completed = false
        try {
            pcm.collect { chunk ->
                val result = session.pushAudioChunk(chunk)
                if (result.text.isNotEmpty()) {
                    hypothesis = result.text.replace(Regex("\\s+"), " ").trim()
                    emit(TranscriptEvent.Partial(pending()))
                }
                if (vad.feed(chunk) == EnergyVad.Event.UTTERANCE_END) {
                    val text = pending()
                    if (text.isNotEmpty()) {
                        committed = hypothesis.length
                        emit(TranscriptEvent.Final(text))
                    }
                }
            }
            completed = true
        } finally {
            val last = withContext(NonCancellable) { runCatching { session.stop() }.getOrNull() }
            if (completed && last != null && last.text.isNotEmpty()) {
                hypothesis = last.text.replace(Regex("\\s+"), " ").trim()
                val text = pending()
                if (text.isNotEmpty()) emit(TranscriptEvent.Final(text))
            }
        }
    }
}
