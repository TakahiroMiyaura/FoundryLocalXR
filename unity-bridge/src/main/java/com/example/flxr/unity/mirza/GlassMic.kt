// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.unity.mirza

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.flxr.core.Pcm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/** PCM source for the MiRZA glasses microphone, which appears as a TYPE_IP input device. */
object GlassMic {
    private const val TAG = "FLXR-Unity"

    /** Returns the glasses microphone device, or null if it is not connected. */
    fun findGlassMic(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_IP }
    }

    /** Returns a description of the available audio input devices. */
    fun describeInputs(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString("; ") {
            "id=${it.id} type=${it.type} name=${it.productName}"
        }
    }

    /** Streams 16 kHz mono PCM16 chunks from the glasses microphone. */
    @SuppressLint("MissingPermission")
    fun pcm(context: Context, chunkBytes: Int = 3200): Flow<ByteArray> = flow {
        val device = findGlassMic(context)
            ?: throw IllegalStateException("MiRZA のマイク(TYPE_IP)が見つかりません: ${describeInputs(context)}")
        val minBuffer = AudioRecord.getMinBufferSize(
            Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "Audio input not supported" }
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Pcm.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, chunkBytes * 4))
            .build()
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Failed to initialize glasses microphone" }
        if (!recorder.setPreferredDevice(device)) Log.w(TAG, "setPreferredDevice(${device.productName}) rejected")
        try {
            recorder.startRecording()
            Log.i(TAG, "glass mic: ${device.productName} routed=${recorder.routedDevice?.productName}")
            val buffer = ByteArray(chunkBytes)
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read < 0) break
                if (read > 0) emit(buffer.copyOf(read))
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }.flowOn(Dispatchers.IO)
}
