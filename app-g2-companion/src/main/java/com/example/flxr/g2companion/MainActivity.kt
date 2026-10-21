// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.g2companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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

/** Status and control screen for [CompanionService]. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF101418)) { CompanionScreen(mode) }
            }
        }
    }
}

/** Shows service state, client connection info and the conversation log. */
@Composable
fun CompanionScreen(mode: String?) {
    val context = LocalContext.current
    val running by CompanionService.running.collectAsState()
    var input by remember { mutableStateOf("") }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        CompanionService.start(context, mode)
    }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Even G2 companion", fontSize = 26.sp, color = Color.White)
        Text("Foundry Local（オンデバイス）で G2 の音声を文字起こし・応答します。", color = Color(0xFF9FB3C8))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = running == null, onClick = {
                if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                    CompanionService.start(context, mode)
                else notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("サービス開始") }
            OutlinedButton(enabled = running != null, onClick = { CompanionService.stop(context) }) { Text("停止") }
            val pm = context.getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    )
                }) { Text("電池最適化を除外") }
            }
        }

        val r = running
        if (r == null) {
            Text("停止中。G2 側アプリは「companion 未起動」と表示します。", color = Color(0xFFFFD54F))
            return@Column
        }
        val state by r.assistant.state.collectAsState()
        val clients by r.hub.clientInfo.collectAsState()

        Text("http://127.0.0.1:${r.port}  (ws://127.0.0.1:${r.port}/ws)", color = Color(0xFF80CBC4), fontSize = 14.sp)
        r.serverError?.let { Text(it, color = Color(0xFFFF8A80)) }
        Text(state.status, color = Color(0xFF9FB3C8))
        state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        if (state.models.isNotEmpty()) Text(state.models, fontSize = 12.sp, color = Color(0xFF6F8296))
        state.error?.let { Text(it, color = Color(0xFFFF8A80)) }
        Text(
            "WS 接続: ${clients.wsClients}  受信 PCM: ${clients.pcmBytes / 1024} KB  " +
                "最終経路: ${clients.lastTransport ?: "-"}\n最終 Origin: ${clients.lastOrigin ?: "(未接続)"}",
            fontSize = 12.sp, color = Color(0xFFB0BEC5)
        )
        Text(if (state.listening) "聞き取り中… ${state.partial}" else "", color = Color(0xFFFFD54F))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), label = { Text("テキストで質問（動作確認）") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button(enabled = state.ready && input.isNotBlank(), onClick = { r.assistant.ask(input); input = "" }) { Text("送信") }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.log.reversed()) { u ->
                Column(Modifier.fillMaxWidth().background(Color(0xFF1B232B), RoundedCornerShape(10.dp)).padding(10.dp)) {
                    Text("あなた: ${u.user}", color = Color(0xFFE3F2FD))
                    if (u.reply.isNotEmpty() || u.replying) Text("AI: ${u.reply}${if (u.replying) " ▍" else ""}", color = Color(0xFFA5D6A7))
                }
            }
        }
    }
}
