// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.rokid

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Audio input source for speech recognition. */
enum class InputSource(val label: String) { GLASSES("グラスのマイク (BT)"), PHONE("スマホのマイク") }

/** Wires the [VoiceAssistant], audio input and glasses display together. */
class RokidViewModel(app: Application) : AndroidViewModel(app) {
    var assistant by mutableStateOf<VoiceAssistant?>(null)
        private set
    var input by mutableStateOf(InputSource.GLASSES)
    val logs = mutableStateListOf<String>()

    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun log(msg: String) {
        Log.i(TAG, msg)
        viewModelScope.launch {
            logs.add(0, "${time.format(Date())} $msg")
            while (logs.size > 60) logs.removeAt(logs.lastIndex)
        }
    }

    val lan = LanGlassesLink(app, ::log)
    val display = GlassesDisplay(viewModelScope, lan).also { it.start() }
    private var statusJob: Job? = null

    /** Creates and prepares a new [VoiceAssistant] for [mode], replacing any existing one. */
    fun start(mode: SpeechMode) {
        assistant?.close()
        statusJob?.cancel()
        val a = VoiceAssistant(
            getApplication(), viewModelScope,
            AssistantConfig(
                speechMode = mode, language = if (mode == SpeechMode.STREAMING_EN) "en" else "ja",
                whisperModels = listOf("openai-whisper-small", "openai-whisper-base"),
                vadMinThreshold = 40.0
            ),
        )
        a.onFinalTranscript = { text -> display.update { it.copy(user = text, reply = "", replying = a.config.chatEnabled) } }
        a.onReplyDelta = { delta -> display.update { it.copy(reply = it.reply + delta, replying = true) } }
        a.onReply = { reply -> display.update { it.copy(reply = reply, replying = false) } }
        statusJob = viewModelScope.launch {
            a.state.collect { s ->
                val progress = s.progress
                val status = when {
                    s.error != null -> "エラー: ${s.status}"
                    s.listening && s.partial.isNotBlank() -> "● ${s.partial}"
                    s.listening -> "● 聞き取り中"
                    progress != null -> "${s.status} ${(progress * 100).toInt()}%"
                    else -> s.status
                }
                display.update { it.copy(status = status) }
            }
        }
        assistant = a
        a.prepare()
    }

    /** Starts listening from the selected input source. */
    fun startListening() {
        val a = assistant ?: return
        val pcm = when (input) {
            InputSource.GLASSES -> GlassesMic.pcm(getApplication(), onInfo = ::log)
            InputSource.PHONE -> micPcm()
        }
        log("聞き取り開始: ${input.label}")
        a.startListening(pcm)
    }

    /** Sends a test message to the glasses. */
    fun testDisplay() {
        display.update {
            it.copy(user = "テスト表示", reply = "Foundry Local XR からの表示テストです。${time.format(Date())}", replying = false)
        }
    }

    override fun onCleared() {
        assistant?.close()
        display.stop()
        lan.close()
    }

    companion object {
        const val TAG = "FLXR-Rokid"
    }
}

/** Phone-side entry point for the Rokid glasses assistant. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF101418)) { RokidScreen() }
            }
        }
    }
}

private val RUNTIME_PERMISSIONS = arrayOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.BLUETOOTH_CONNECT,
)

/** Main screen: glasses connection, input source, assistant controls and logs. */
@Composable
fun RokidScreen() {
    val vm: RokidViewModel = viewModel()
    val context = LocalContext.current
    var mode by remember { mutableStateOf(SpeechMode.WHISPER) }
    var question by remember { mutableStateOf("") }
    var manualIp by remember { mutableStateOf("") }
    var scoInfo by remember { mutableStateOf(GlassesMic.describe(context)) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scoInfo = GlassesMic.describe(context)
    }
    fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    LaunchedEffect(Unit) {
        val missing = RUNTIME_PERMISSIONS.filterNot { granted(it) }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }
    LaunchedEffect(mode) { vm.start(mode) }

    val assistant = vm.assistant ?: return
    val state by assistant.state.collectAsState()
    val linkStatus by vm.lan.status.collectAsState()
    val glassesView by vm.display.view.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.padding(top = 8.dp)); Text("Foundry Local × Rokid", fontSize = 24.sp, color = Color.White) }

        item { Section("1. グラスとの接続（Wi-Fi）") }
        item {
            Text("状態: ${linkStatus.state} / ${linkStatus.detail}", color = linkColor(linkStatus.state), fontSize = 14.sp)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.lan.connect() }) { Text("グラスを探す") }
                OutlinedButton(onClick = { vm.testDisplay() }) { Text("テスト表示") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(manualIp, { manualIp = it }, Modifier.weight(1f), singleLine = true,
                    label = { Text("グラスの IP（手入力）") })
                Spacer(Modifier.width(8.dp))
                Button(enabled = manualIp.isNotBlank(), onClick = { vm.lan.setManualTarget(manualIp) }) { Text("設定") }
            }
        }

        item { Section("2. 音声の入力源") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InputSource.entries.forEach { s ->
                    FilterChip(selected = vm.input == s, onClick = { if (!state.listening) vm.input = s }, label = { Text(s.label) })
                }
            }
        }
        if (vm.input == InputSource.GLASSES) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(scoInfo, color = Color(0xFF9FB3C8), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { scoInfo = GlassesMic.describe(context) }) { Text("再確認") }
                }
            }
        }

        item { Section("3. Foundry Local") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeechMode.entries.forEach { m ->
                    FilterChip(selected = mode == m, onClick = { if (!state.listening) mode = m }, label = { Text(m.label, fontSize = 12.sp) })
                }
            }
        }
        item { Text(state.status, color = Color(0xFF9FB3C8)) }
        state.progress?.let { p -> item { LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) } }
        if (state.models.isNotEmpty()) item { Text(state.models, fontSize = 12.sp, color = Color(0xFF6F8296)) }
        state.error?.let { e -> item { Text(e, color = Color(0xFFFF8A80)) } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = state.ready,
                    onClick = {
                        if (state.listening) assistant.stopListening()
                        else if (granted(Manifest.permission.RECORD_AUDIO)) vm.startListening()
                        else permissions.launch(RUNTIME_PERMISSIONS)
                    },
                ) { Text(if (state.listening) "■ 停止" else "● 話す") }
                Spacer(Modifier.width(12.dp))
                Text(if (state.listening) "聞き取り中… ${state.partial}" else "", color = Color(0xFFFFD54F))
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(question, { question = it }, Modifier.weight(1f), label = { Text("テキストで質問") }, singleLine = true)
                Spacer(Modifier.width(8.dp))
                Button(enabled = state.ready && question.isNotBlank(), onClick = { assistant.ask(question); question = "" }) { Text("送信") }
            }
        }

        item { Section("グラスに送っている内容（ミラー）") }
        item {
            Column(Modifier.fillMaxWidth().background(Color.Black, RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(glassesView.status, color = Color(0xFF7FB77E), fontSize = 12.sp)
                Text("Q: ${glassesView.user}", color = Color(0xFFB9F6CA), fontSize = 16.sp)
                Text("A: ${glassesView.reply}${if (glassesView.replying) " ▍" else ""}", color = Color(0xFF69F0AE), fontSize = 16.sp)
            }
        }

        item { Section("会話") }
        items(state.log.reversed()) { u ->
            Column(Modifier.fillMaxWidth().background(Color(0xFF1B232B), RoundedCornerShape(10.dp)).padding(10.dp)) {
                Text("あなた: ${u.user}", color = Color(0xFFE3F2FD))
                if (u.reply.isNotEmpty() || u.replying) Text("AI: ${u.reply}${if (u.replying) " ▍" else ""}", color = Color(0xFFA5D6A7))
            }
        }

        item { Section("接続ログ") }
        items(vm.logs) { line -> Text(line, fontSize = 11.sp, color = Color(0xFF8899AA)) }
        item { Spacer(Modifier.padding(bottom = 24.dp)) }
    }
}

@Composable
private fun Section(title: String) {
    Column {
        HorizontalDivider(color = Color(0xFF2A3540))
        Text(title, color = Color(0xFFCFD8DC), fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

private fun linkColor(s: LinkState) = when (s) {
    LinkState.CONNECTED -> Color(0xFF81C784)
    LinkState.SEARCHING -> Color(0xFFFFD54F)
    LinkState.UNAVAILABLE -> Color(0xFFFF8A80)
    LinkState.IDLE -> Color(0xFF9FB3C8)
}
