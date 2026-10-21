// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.core

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt

/** PCM format used throughout the project: 16 kHz, mono, signed 16-bit little-endian. */
object Pcm {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2
}

/** Streams PCM chunks from the device microphone, optionally routed to [preferredDevice]. */
@SuppressLint("MissingPermission")
fun micPcm(
    chunkBytes: Int = 3200,
    audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    preferredDevice: AudioDeviceInfo? = null
): Flow<ByteArray> = flow {
    val minBuffer = AudioRecord.getMinBufferSize(
        Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    )
    require(minBuffer > 0) { "Audio input not supported" }
    val recorder = AudioRecord(
        audioSource,
        Pcm.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minBuffer, chunkBytes * 4)
    )
    check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Failed to initialize microphone" }
    preferredDevice?.let { recorder.setPreferredDevice(it) }
    try {
        recorder.startRecording()
        val buffer = ByteArray(chunkBytes)
        while (currentCoroutineContext().isActive) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read < 0) {
                Log.w(FoundryEngine.TAG, "mic read error $read, recordingState=${recorder.recordingState}")
                break
            }
            if (read > 0) emit(buffer.copyOf(read))
        }
    } finally {
        runCatching { recorder.stop() }
        recorder.release()
    }
}.flowOn(Dispatchers.IO)

/** Returns the RMS level (0..32768) of the first [length] bytes of a PCM16LE buffer. */
fun rms(pcm: ByteArray, length: Int = pcm.size): Double {
    val samples = length / 2
    if (samples == 0) return 0.0
    var sum = 0.0
    for (i in 0 until samples) {
        val s = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toDouble()
        sum += s * s
    }
    return sqrt(sum / samples)
}

/**
 * Energy-based voice activity detector with an adaptive noise floor that reports speech start and utterance end.
 */
class EnergyVad(
    private val minThreshold: Double = 120.0,
    private val floorRatio: Double = 3.0,
    private val endSilenceMs: Int = 700,
    private val maxUtteranceMs: Int = 12_000,
    private val minSpeechMs: Int = 250
) {
    /** Result of feeding one chunk. */
    enum class Event { NONE, SPEECH_START, UTTERANCE_END }

    private var noiseFloor = minThreshold / floorRatio
    private var inSpeech = false
    private var speechMs = 0
    private var silenceMs = 0
    private var utteranceMs = 0
    private var levelSum = 0.0
    private var levelCount = 0

    /** Whether an utterance is currently in progress. */
    val isInSpeech: Boolean get() = inSpeech

    /** Feeds one PCM chunk and returns the resulting [Event]. */
    fun feed(chunk: ByteArray): Event {
        val ms = chunk.size * 1000 / Pcm.BYTES_PER_SECOND
        val level = rms(chunk)
        val threshold = maxOf(minThreshold, noiseFloor * floorRatio)
        val loud = level > threshold
        trackNoise(level)
        if (!inSpeech) {
            speechMs = if (loud) speechMs + ms else 0
            if (speechMs >= minSpeechMs) {
                inSpeech = true
                silenceMs = 0
                utteranceMs = speechMs
                levelSum = 0.0
                levelCount = 0
                return Event.SPEECH_START
            }
            return Event.NONE
        }
        utteranceMs += ms
        levelSum += level
        levelCount++
        silenceMs = if (level > threshold * 0.5) 0 else silenceMs + ms
        if (silenceMs >= endSilenceMs || utteranceMs >= maxUtteranceMs) {
            if (silenceMs < endSilenceMs && levelCount > 0) {
                noiseFloor = maxOf(noiseFloor, levelSum / levelCount)
            }
            inSpeech = false
            speechMs = 0
            return Event.UTTERANCE_END
        }
        return Event.NONE
    }

    private fun trackNoise(level: Double) {
        if (level < 1.0) return
        noiseFloor = if (level < noiseFloor) noiseFloor * 0.9 + level * 0.1 else noiseFloor * 0.995 + level * 0.005
    }
}

/** Writes 16 kHz mono PCM16 data to [file] as a WAV file. */
fun writeWav(file: File, pcm: ByteArray) {
    RandomAccessFile(file, "rw").use { out ->
        out.setLength(0)
        val dataLen = pcm.size
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        out.write("RIFF".toByteArray()); out.write(le32(36 + dataLen))
        out.write("WAVE".toByteArray()); out.write("fmt ".toByteArray())
        out.write(le32(16)); out.write(le16(1)); out.write(le16(1))
        out.write(le32(Pcm.SAMPLE_RATE)); out.write(le32(Pcm.BYTES_PER_SECOND))
        out.write(le16(2)); out.write(le16(16))
        out.write("data".toByteArray()); out.write(le32(dataLen))
        out.write(pcm)
    }
}
