// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.unity

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.example.flxr.core.VoiceAssistant
import com.example.flxr.core.micPcm
import com.example.flxr.unity.mirza.GlassMic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Static JNI facade over [VoiceAssistant] for Unity, delivering results to a GameObject via UnitySendMessage.
 */
object FoundryLocalBridge {

    /** Bridge version reported in the log. */
    const val VERSION = "0.1.0"
    private const val TAG = UnityMessenger.TAG

    private var appContext: Context? = null
    private var messenger: UnityMessenger? = null
    private var scope: CoroutineScope? = null
    private var assistant: VoiceAssistant? = null
    private var pcmChannel: Channel<ByteArray>? = null
    private var pendingListener: BridgeListener? = null

    /** Creates the assistant and starts loading the models, replacing any previous instance. */
    @JvmStatic
    @Synchronized
    fun init(activity: Activity, gameObjectName: String, configJson: String?): Boolean {
        releaseInternal()
        val m = UnityMessenger(gameObjectName).also { it.listener = pendingListener }
        messenger = m
        val config = try {
            BridgeJson.parseConfig(configJson)
        } catch (e: Exception) {
            m.send("OnBridgeError", BridgeJson.error("bad_config", e.message ?: e.toString()))
            return false
        }
        val ctx = activity.applicationContext
        appContext = ctx
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        val a = VoiceAssistant(ctx, s, config)
        assistant = a
        a.onFinalTranscript = { m.send("OnBridgeTranscript", BridgeJson.text(it)) }
        a.onReplyDelta = { m.send("OnBridgeReplyDelta", BridgeJson.text(it)) }
        a.onReply = { m.send("OnBridgeReply", BridgeJson.text(it)) }
        s.launch {
            a.state.map(BridgeJson::view).distinctUntilChanged().collect {
                m.send("OnBridgeState", BridgeJson.state(it))
            }
        }
        Log.i(TAG, "init v$VERSION go=$gameObjectName mode=${config.speechMode} lang=${config.language} chat=${config.chatEnabled}")
        a.prepare()
        return true
    }

    /** Starts listening on the device's default microphone. */
    @JvmStatic
    @Synchronized
    fun startMic(): Boolean = listen("startMic") { micPcm() }

    /** Starts listening on the MiRZA glasses microphone. */
    @JvmStatic
    @Synchronized
    fun startGlassMic(): Boolean {
        val ctx = appContext ?: return notInitialized("startGlassMic")
        return listen("startGlassMic") { GlassMic.pcm(ctx) }
    }

    /** Starts push mode, in which the host supplies PCM through [pushPcm]. */
    @JvmStatic
    @Synchronized
    fun startPcmInput(): Boolean {
        if (pcmChannel != null) return true
        val ch = Channel<ByteArray>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val ok = listen("startPcmInput") { ch.receiveAsFlow() }
        if (ok) pcmChannel = ch else ch.close()
        return ok
    }

    /** Pushes 16 kHz mono PCM16LE audio, starting push mode if needed. */
    @JvmStatic
    fun pushPcm(pcm: ByteArray?) {
        if (pcm == null || pcm.isEmpty()) return
        val ch = pcmChannel ?: synchronized(this) {
            if (pcmChannel == null && !startPcmInput()) return
            pcmChannel
        } ?: return
        ch.trySend(pcm)
    }

    /** Ends push mode after transcribing the utterance in progress. */
    @JvmStatic
    @Synchronized
    fun stopPcmInput() {
        pcmChannel?.close()
        pcmChannel = null
    }

    /** Stops listening immediately on any input. */
    @JvmStatic
    @Synchronized
    fun stopMic() {
        pcmChannel?.close()
        pcmChannel = null
        assistant?.stopListening()
    }

    /** Sends a text question to the assistant. */
    @JvmStatic
    @Synchronized
    fun ask(text: String?) {
        val a = assistant ?: run { notInitialized("ask"); return }
        if (text.isNullOrBlank()) return
        if (!a.state.value.ready) {
            messenger?.send("OnBridgeError", BridgeJson.error("not_ready", "モデル準備中です"))
            return
        }
        a.ask(text.trim())
    }

    /** Returns whether the models are ready. */
    @JvmStatic
    fun isReady(): Boolean = assistant?.state?.value?.ready == true

    /** Returns whether a listening session is running. */
    @JvmStatic
    fun isListening(): Boolean = assistant?.state?.value?.listening == true

    /** Returns whether RECORD_AUDIO is granted. */
    @JvmStatic
    fun hasMicPermission(): Boolean =
        appContext?.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Returns a description of the available audio input devices. */
    @JvmStatic
    fun describeInputDevices(): String = appContext?.let { GlassMic.describeInputs(it) } ?: ""

    /** Sets a listener that receives messages instead of UnitySendMessage. */
    @JvmStatic
    @Synchronized
    fun setListener(listener: BridgeListener?) {
        pendingListener = listener
        messenger?.listener = listener
    }

    /** Unloads the models and releases the runtime. */
    @JvmStatic
    @Synchronized
    fun release() = releaseInternal()

    private fun releaseInternal() {
        pcmChannel?.close()
        pcmChannel = null
        assistant?.close()
        assistant = null
        scope?.cancel()
        scope = null
        messenger = null
    }

    private fun listen(caller: String, source: () -> Flow<ByteArray>): Boolean {
        val a = assistant ?: return notInitialized(caller)
        val m = messenger
        when {
            !a.state.value.ready -> {
                m?.send("OnBridgeError", BridgeJson.error("not_ready", "$caller: モデル準備中です"))
                return false
            }
            a.state.value.listening -> {
                m?.send("OnBridgeError", BridgeJson.error("busy", "$caller: すでに聞き取り中です（stopMic を先に）"))
                return false
            }
            !hasMicPermission() && caller != "startPcmInput" -> {
                m?.send("OnBridgeError", BridgeJson.error("no_permission", "$caller: RECORD_AUDIO が未許可です"))
                return false
            }
        }
        a.startListening(source())
        return true
    }

    private fun notInitialized(caller: String): Boolean {
        Log.w(TAG, "$caller before init")
        messenger?.send("OnBridgeError", BridgeJson.error("not_initialized", "$caller: init が先です"))
        return false
    }
}
