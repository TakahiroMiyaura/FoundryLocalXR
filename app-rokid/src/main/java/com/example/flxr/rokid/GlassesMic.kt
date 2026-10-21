// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.rokid

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Captures the Rokid glasses microphone over Bluetooth SCO (HFP) as 16 kHz mono 16-bit PCM.
 */
object GlassesMic {
    private const val TAG = "FLXR-Rokid-Mic"

    /** Returns the Bluetooth SCO communication device, or null if no headset is connected. */
    fun findScoDevice(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        return am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    /** Returns a short description of the connected SCO headset for display. */
    fun describe(context: Context): String {
        findScoDevice(context) ?: return "BT ヘッドセット(SCO) が見つかりません"
        val name = context.getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }?.productName
        return "BT SCO: ${name ?: "接続済み"}"
    }

    /** Returns a cold flow of PCM chunks recorded from the SCO headset; routing is restored on completion. */
    @SuppressLint("MissingPermission")
    fun pcm(context: Context, chunkBytes: Int = 3200, onInfo: (String) -> Unit = {}): Flow<ByteArray> = flow {
        val am = context.getSystemService(AudioManager::class.java)
        val sco = findScoDevice(context)
            ?: error("グラスが Bluetooth ヘッドセットとして接続されていません（SCO デバイスなし）")
        val previousMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        check(am.setCommunicationDevice(sco)) { "setCommunicationDevice に失敗しました" }
        var recorder: AudioRecord? = null
        try {
            for (i in 0 until 30) {
                if (am.communicationDevice?.id == sco.id) break
                delay(100)
            }
            onInfo("入力: ${sco.productName} (SCO)")

            val minBuffer = AudioRecord.getMinBufferSize(
                Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            require(minBuffer > 0) { "16 kHz mono PCM の録音に対応していません" }
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                Pcm.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, chunkBytes * 4),
            )
            recorder = r
            check(r.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord の初期化に失敗しました" }
            am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?.let { r.setPreferredDevice(it) }

            r.startRecording()
            Log.i(TAG, "recording from ${r.routedDevice?.productName} type=${r.routedDevice?.type}")
            val buffer = ByteArray(chunkBytes)
            while (currentCoroutineContext().isActive) {
                val read = r.read(buffer, 0, buffer.size)
                if (read < 0) error("AudioRecord.read = $read")
                if (read > 0) emit(buffer.copyOf(read))
            }
        } finally {
            recorder?.let {
                runCatching { it.stop() }
                it.release()
            }
            runCatching { am.clearCommunicationDevice() }
            am.mode = previousMode
        }
    }.flowOn(Dispatchers.IO)
}
