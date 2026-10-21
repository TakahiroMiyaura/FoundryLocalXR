// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.panel

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.media.MediaRecorder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.flxr.core.AssistantConfig
import com.example.flxr.core.VoiceAssistant
import com.example.flxr.core.micPcm
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/** Persistent log file for the standalone glasses build. */
object GlassesLog {
    private const val NAME = "glasses-log.txt"
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Returns the log file in the app's files directory. */
    fun file(context: Context) = File(context.filesDir, NAME)

    /** Appends a timestamped line to the log file. */
    fun append(context: Context, line: String) {
        runCatching { file(context).appendText("${fmt.format(Date())} $line\n") }
    }

    /** Returns the lines of the previous launch, starting at the last "=== start" marker. */
    fun previousRun(context: Context): List<String> {
        val lines = runCatching { file(context).readLines() }.getOrDefault(emptyList())
        val start = lines.indexOfLast { it.contains("=== start") }
        return if (start < 0) emptyList() else lines.drop(start)
    }

    /** Returns the available system memory in megabytes. */
    fun freeMb(context: Context): Long {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }
}

/** Runs the voice assistant on the glasses and keeps an on-screen log of its progress. */
class GlassesViewModel(app: Application) : AndroidViewModel(app) {
    val lines = mutableStateListOf<String>()
    var level by mutableStateOf(0)
        private set
    var assistant by mutableStateOf<VoiceAssistant?>(null)
        private set
    private var started = false

    /** Appends a line to the on-screen log and the log file. */
    fun log(line: String) {
        val ctx = getApplication<Application>()
        GlassesLog.append(ctx, line)
        lines += line
        if (lines.size > 60) lines.removeAt(0)
    }

    /** Creates the assistant with small models and starts preparing it. */
    fun start() {
        if (started) return
        started = true
        val ctx = getApplication<Application>()
        val prev = GlassesLog.previousRun(ctx)
        val chatCrashed = prev.isNotEmpty() && prev.none { it.contains("準備完了") } && prev.any { it.contains("qwen") }
        if (prev.isNotEmpty()) {
            lines += "--- 前回 ---"
            lines += prev.takeLast(12)
            lines += "--- 今回 ---"
        }
        GlassesLog.file(ctx).let { if (it.length() > 200_000) it.delete() }
        log("=== start (Android ${android.os.Build.VERSION.SDK_INT}, 空き ${GlassesLog.freeMb(ctx)}MB)")
        if (chatCrashed) log("前回チャットモデル読込中に落ちたため、今回は文字起こしのみ")

        val a = VoiceAssistant(
            ctx, viewModelScope,
            AssistantConfig(
                chatEnabled = !chatCrashed,
                vadMinThreshold = 40.0,
                maxReplyTokens = 60,
                whisperModels = listOf("openai-whisper-tiny", "openai-whisper-base"),
                chatModels = listOf("qwen2.5-0.5b-instruct", "qwen2.5-coder-0.5b-instruct")
            )
        )
        assistant = a
        a.onFinalTranscript = { log("あなた: $it") }
        a.onReply = { log("AI: $it") }

        viewModelScope.launch {
            var lastStatus = ""
            var lastPct = -1
            var wasListening = false
            a.state.collect { s ->
                if (wasListening && !s.listening) log("聞き取り終了")
                wasListening = s.listening
                if (s.status != lastStatus) {
                    lastStatus = s.status
                    lastPct = -1
                    log("${s.status} (空き ${GlassesLog.freeMb(ctx)}MB)")
                }
                val pct = s.progress?.let { (it * 100).toInt() / 10 * 10 } ?: -1
                if (pct >= 0 && pct != lastPct) {
                    lastPct = pct
                    log("  $pct%")
                }
                if (s.models.isNotEmpty() && lines.none { it.startsWith("モデル") }) log("モデル ${s.models.replace("\n", " / ")}")
                s.error?.let { e -> if (lines.lastOrNull() != "ERR $e") log("ERR $e") }
            }
        }
        a.prepare()
    }

    /** Starts listening once the assistant is ready and the microphone permission is granted. */
    fun onReady(micGranted: Boolean) {
        val a = assistant ?: return
        if (micGranted && !a.state.value.listening) listen()
    }

    private val sources = listOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
        MediaRecorder.AudioSource.MIC to "MIC",
        MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
        MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER",
        MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED"
    )
    private var sourceIndex = 0

    /** Toggles the microphone, switching to the next audio source if the current one stays silent. */
    fun listen() {
        val a = assistant ?: return
        if (a.state.value.listening) {
            a.stopListening(); log("聞き取り停止"); return
        }
        val (src, name) = sources[sourceIndex]
        var windowStart = 0L
        var peak = 0
        var everNonSilent = false
        var switched = false
        val begun = System.currentTimeMillis()
        val pcm = micPcm(audioSource = src).onEach { chunk ->
            val r = rms(chunk)
            level = r
            peak = maxOf(peak, r)
            if (r > 3) everNonSilent = true
            val now = System.currentTimeMillis()
            if (windowStart == 0L) windowStart = now
            if (now - windowStart >= 5000) {
                log("マイク $name 5秒間の最大レベル $peak")
                peak = 0
                windowStart = now
            }
            if (!switched && !everNonSilent && now - begun > 4000 && sourceIndex < sources.size - 1) {
                switched = true
                log("マイク $name は無音のまま。次の入力に切替")
                sourceIndex++
                viewModelScope.launch { a.stopListening(); delay(500); listen() }
            }
        }
        if (a.startListening(pcm)) log("聞き取り開始 マイク=$name（話しかけてください）")
    }

    private fun rms(chunk: ByteArray): Int {
        var sum = 0.0
        var i = 0
        while (i + 1 < chunk.size) {
            val v = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xFF)).toShort().toDouble()
            sum += v * v
            i += 2
        }
        return sqrt(sum / (chunk.size / 2).coerceAtLeast(1)).toInt()
    }

    override fun onCleared() {
        assistant?.close()
    }
}

private val Green = Color(0xFF7CFF7C)

/** Black-background glasses UI showing the assistant status and the running log. */
@Composable
fun GlassesScreen(micGranted: Boolean, onNeedMic: () -> Unit, vm: GlassesViewModel = viewModel()) {
    LaunchedEffect(Unit) { vm.start() }
    val assistant = vm.assistant
    if (assistant == null) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(8.dp)) { Text("起動中…", color = Green, fontSize = 16.sp) }
        return
    }
    val state by assistant.state.collectAsState()

    LaunchedEffect(state.ready, micGranted) {
        if (state.ready) {
            if (!micGranted) onNeedMic()
            vm.onReady(micGranted)
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(8.dp)) {
        val head = when {
            state.listening -> "聞き取り中 レベル ${vm.level}  ${state.partial}"
            state.ready -> "準備完了"
            else -> state.status + (state.progress?.let { " ${(it * 100).toInt()}%" } ?: "")
        }
        Text(head, color = Green, fontSize = 16.sp)
        val listState = rememberLazyListState()
        LaunchedEffect(vm.lines.size) { if (vm.lines.isNotEmpty()) listState.scrollToItem(vm.lines.size - 1) }
        LazyColumn(Modifier.fillMaxWidth(), state = listState) {
            items(vm.lines) { Text(it, color = if (it.startsWith("ERR")) Color(0xFFFF8A80) else Green, fontSize = 13.sp) }
        }
    }
}
