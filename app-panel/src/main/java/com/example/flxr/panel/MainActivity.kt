// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.panel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.flxr.core.AssistantConfig
import com.example.flxr.core.SpeechMode
import com.example.flxr.core.VoiceAssistant
import com.example.flxr.core.micPcm

/** Holds the [VoiceAssistant] for the 2D panel screen. */
class PanelViewModel(app: Application) : AndroidViewModel(app) {
    var assistant by mutableStateOf<VoiceAssistant?>(null)
        private set

    var asrModel: String? = null
    var chatModel: String? = null

    /** Creates and prepares a new assistant for the given speech mode, closing the previous one. */
    fun start(mode: SpeechMode) {
        assistant?.close()
        val defaults = AssistantConfig()
        val asr = asrModel?.let { listOf(it) }
        assistant = VoiceAssistant(
            getApplication(), viewModelScope,
            defaults.copy(
                speechMode = mode,
                language = if (mode == SpeechMode.STREAMING_EN) "en" else "ja",
                whisperModels = asr ?: defaults.whisperModels,
                streamingModels = asr ?: defaults.streamingModels,
                chatModels = chatModel?.let { listOf(it) } ?: defaults.chatModels
            )
        ).also { it.prepare() }
    }

    override fun onCleared() {
        assistant?.close()
    }
}

/** Entry activity that shows the panel UI, or the glasses UI in glasses builds. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = if (intent.getStringExtra("mode") == "en") SpeechMode.STREAMING_EN else SpeechMode.WHISPER
        if (BuildConfig.GLASSES) {
            showGlasses()
            return
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF101418)) {
                    PanelScreen(mode, intent.getStringExtra("asr"), intent.getStringExtra("chat"))
                }
            }
        }
    }

    private var glassesVm: GlassesViewModel? = null

    private fun showGlasses() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            GlassesLog.append(this, "CRASH ${e.javaClass.simpleName}: ${e.message}")
            e.stackTrace.take(6).forEach { GlassesLog.append(this, "  at $it") }
            previous?.uncaughtException(t, e)
        }
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            var granted by remember {
                mutableStateOf(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
            val vm: GlassesViewModel = viewModel()
            glassesVm = vm
            GlassesScreen(granted, onNeedMic = { launcher.launch(Manifest.permission.RECORD_AUDIO) }, vm = vm)
        }
    }

    /** Toggles listening on the glasses when the touchpad or confirm key is pressed. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val vm = glassesVm
        if (vm != null && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            vm.listen()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}

/** Panel UI with speech mode selection, microphone and text input, and the conversation log. */
@Composable
fun PanelScreen(initialMode: SpeechMode, asrModel: String? = null, chatModel: String? = null) {
    val vm: PanelViewModel = viewModel()
    vm.asrModel = asrModel
    vm.chatModel = chatModel
    val context = LocalContext.current
    var mode by remember { mutableStateOf(initialMode) }
    var input by remember { mutableStateOf("") }

    LaunchedEffect(mode) { vm.start(mode) }
    val assistant = vm.assistant ?: return
    val state by assistant.state.collectAsState()

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) assistant.startListening(micPcm())
    }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Foundry Local XR", fontSize = 26.sp, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeechMode.entries.forEach { m ->
                FilterChip(selected = mode == m, onClick = { if (!state.listening) mode = m }, label = { Text(m.label) })
            }
        }
        Text(state.status, color = Color(0xFF9FB3C8))
        state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        if (state.models.isNotEmpty()) Text(state.models, fontSize = 12.sp, color = Color(0xFF6F8296))
        state.error?.let { Text(it, color = Color(0xFFFF8A80)) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = state.ready,
                onClick = {
                    if (state.listening) assistant.stopListening()
                    else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        assistant.startListening(micPcm())
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            ) { Text(if (state.listening) "■ 停止" else "● 話す") }
            Spacer(Modifier.width(12.dp))
            Text(if (state.listening) "聞き取り中… ${state.partial}" else "", color = Color(0xFFFFD54F))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), label = { Text("テキストで質問") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button(enabled = state.ready && input.isNotBlank(), onClick = { assistant.ask(input); input = "" }) { Text("送信") }
        }

        val listState = rememberLazyListState()
        LaunchedEffect(state.log.size, state.log.lastOrNull()?.reply) {
            if (state.log.isNotEmpty()) listState.animateScrollToItem(state.log.size - 1)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.log) { u ->
                Column(
                    Modifier.fillMaxWidth().background(Color(0xFF1B232B), RoundedCornerShape(10.dp)).padding(12.dp)
                ) {
                    Text("あなた: ${u.user}", color = Color(0xFFE3F2FD), fontSize = 18.sp)
                    if (u.reply.isNotEmpty() || u.replying) {
                        Text("AI: ${u.reply}${if (u.replying) " ▍" else ""}", color = Color(0xFFA5D6A7), fontSize = 18.sp)
                    }
                }
            }
        }
    }
}
