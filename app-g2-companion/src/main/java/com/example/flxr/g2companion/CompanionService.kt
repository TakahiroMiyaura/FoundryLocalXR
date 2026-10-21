// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.g2companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.example.flxr.core.AssistantConfig
import com.example.flxr.core.SpeechMode
import com.example.flxr.core.VoiceAssistant
import com.example.flxr.g2companion.server.CompanionServer
import com.example.flxr.g2companion.server.SessionHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Foreground service that owns the [VoiceAssistant] and the loopback [CompanionServer]. */
class CompanionService : Service() {

    /** Snapshot of the running service exposed to the UI. */
    data class Running(
        val assistant: VoiceAssistant,
        val hub: SessionHub,
        val port: Int,
        val serverError: String?
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var assistant: VoiceAssistant? = null
    private var server: CompanionServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground("起動中…")
        if (assistant == null) {
            val mode = if (intent?.getStringExtra(EXTRA_MODE) == "en") SpeechMode.STREAMING_EN else SpeechMode.WHISPER
            startPipeline(mode)
        }
        return START_NOT_STICKY
    }

    private fun startPipeline(mode: SpeechMode) {
        val a = VoiceAssistant(
            applicationContext, scope,
            AssistantConfig(
                speechMode = mode,
                language = if (mode == SpeechMode.STREAMING_EN) "en" else "ja",
                whisperModels = listOf("openai-whisper-small", "openai-whisper-base"),
                systemPrompt = "あなたはスマートグラスに表示される音声アシスタントです。日本語で、1〜2 文・80 文字以内で簡潔に答えてください。",
                maxReplyTokens = 80
            )
        )
        assistant = a
        val hub = SessionHub(VoiceAssistantBackend(a, scope), scope)
        val srv = CompanionServer(hub, CompanionServer.DEFAULT_PORT, version = BuildConfig.VERSION_NAME)
        val error = runCatching { srv.start() }.exceptionOrNull()?.let {
            Log.e(TAG, "server start failed", it)
            "サーバ起動失敗 (port ${CompanionServer.DEFAULT_PORT}): ${it.message}"
        }
        if (error == null) server = srv
        _running.value = Running(a, hub, CompanionServer.DEFAULT_PORT, error)
        a.prepare()

        scope.launch {
            a.state.map { it.status to it.listening }.distinctUntilChanged().collect { (status, listening) ->
                goForeground(if (error != null) error else "127.0.0.1:${CompanionServer.DEFAULT_PORT} 待受中 — $status")
                setWakeLock(listening)
            }
        }
    }

    /** Holds a partial wake lock while the assistant is listening. */
    private fun setWakeLock(on: Boolean) {
        if (on && wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flxr:g2companion").apply { acquire(60 * 60 * 1000L) }
        } else if (!on) {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        }
    }

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "G2 companion", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CompanionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Foundry Local × Even G2")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        assistant?.close()
        assistant = null
        setWakeLock(false)
        _running.value = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val TAG = "FLXR-G2"
        const val ACTION_STOP = "com.example.flxr.g2companion.STOP"
        const val EXTRA_MODE = "mode"
        private const val CHANNEL_ID = "companion"
        private const val NOTIFICATION_ID = 1

        private val _running = MutableStateFlow<Running?>(null)
        val running: StateFlow<Running?> = _running.asStateFlow()

        /** Starts the service; [mode] `"en"` selects English streaming recognition. */
        fun start(context: Context, mode: String? = null) {
            context.startForegroundService(Intent(context, CompanionService::class.java).putExtra(EXTRA_MODE, mode))
        }

        /** Stops the service. */
        fun stop(context: Context) {
            context.startService(Intent(context, CompanionService::class.java).setAction(ACTION_STOP))
        }
    }
}
