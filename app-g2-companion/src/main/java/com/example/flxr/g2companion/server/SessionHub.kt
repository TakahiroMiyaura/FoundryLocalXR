// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.g2companion.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Transport-independent session shared by the WebSocket and HTTP fallback: routes PCM to the
 * [AssistantBackend] and publishes sequenced JSON events to all clients.
 */
class SessionHub(
    private val backend: AssistantBackend,
    private val scope: CoroutineScope,
    private val idleStopMs: Long = 20_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** Outgoing JSON event with its sequence number. */
    data class Event(val seq: Long, val json: String)

    private val seq = AtomicLong(0)
    private val ring = ArrayDeque<Event>()
    private val latestSeq = MutableStateFlow(0L)
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Live event stream for WebSocket clients. */
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val pcmBytes = AtomicLong(0)
    private val wsClients = AtomicInteger(0)
    @Volatile private var lastPcmAt = 0L
    @Volatile var lastOrigin: String? = null
        private set
    @Volatile var lastTransport: String? = null
        private set

    private val _clientInfo = MutableStateFlow(ClientInfo())
    val clientInfo: StateFlow<ClientInfo> = _clientInfo.asStateFlow()

    /** Client connection counters shown on the phone UI. */
    data class ClientInfo(val wsClients: Int = 0, val lastOrigin: String? = null, val lastTransport: String? = null, val pcmBytes: Long = 0)

    private val audioLock = Any()
    private var channel: Channel<ByteArray>? = null
    private val framer = PcmFramer()

    init {
        backend.setListener(object : BackendListener {
            override fun onFinalTranscript(text: String) { publish("transcript") { put("text", text) } }
            override fun onReplyDelta(delta: String) { publish("reply_delta") { put("text", delta) } }
            override fun onReply(text: String) { publish("reply") { put("text", text) } }
        })
        scope.launch {
            backend.state
                .map { StatusKey(it.ready, it.listening, it.status, it.error, it.models) }
                .distinctUntilChanged()
                .collect { publish("status") { putStatus() } }
        }
        scope.launch {
            backend.state.map { it.partial }.distinctUntilChanged().collect { p ->
                if (seq.get() > 0) publish("partial") { put("text", p) }
            }
        }
        scope.launch {
            while (isActive) {
                delay(1_000)
                val open = synchronized(audioLock) { channel != null }
                if (open && wsClients.get() == 0 && clock() - lastPcmAt > idleStopMs) stopAudio("idle")
            }
        }
    }

    private data class StatusKey(val ready: Boolean, val listening: Boolean, val status: String, val error: String?, val models: String)

    private fun JsonObjectBuilder.putStatus() {
        val s = backend.state.value
        put("ready", s.ready)
        put("listening", s.listening)
        put("status", s.status)
        put("error", s.error)
        put("models", s.models)
        put("audioOpen", synchronized(audioLock) { channel != null })
    }

    /** Publishes an event of [type] to all clients and the long-poll buffer. */
    fun publish(type: String, body: JsonObjectBuilder.() -> Unit = {}): Event {
        val event: Event
        synchronized(ring) {
            val n = seq.incrementAndGet()
            event = Event(n, buildJsonObject { put("type", type); put("seq", n); body() }.toString())
            ring.addLast(event)
            while (ring.size > RING_SIZE) ring.removeFirst()
        }
        latestSeq.value = event.seq
        _events.tryEmit(event)
        return event
    }

    /** Builds the greeting sent to a newly connected WebSocket client. */
    fun helloJson(): String = buildJsonObject {
        put("type", "hello")
        put("seq", seq.get())
        put("service", SERVICE_NAME)
        putStatus()
    }.toString()

    /** Builds the status and diagnostics object returned by `/health`. */
    fun statusJson(extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        putStatus()
        put("wsClients", wsClients.get())
        put("pcmBytes", pcmBytes.get())
        put("lastOrigin", lastOrigin)
        put("lastTransport", lastTransport)
        put("seq", seq.get())
        extra()
    }

    /** Returns events with seq > [after], waiting up to [waitMs] for the first one. */
    suspend fun eventsAfter(after: Long, waitMs: Long): List<Event> {
        snapshotAfter(after).let { if (it.isNotEmpty() || waitMs <= 0) return it }
        withTimeoutOrNull(waitMs) { latestSeq.first { it > after } }
        return snapshotAfter(after)
    }

    private fun snapshotAfter(after: Long): List<Event> = synchronized(ring) { ring.filter { it.seq > after } }

    /** Records the Origin and transport of the latest client request. */
    fun clientSeen(origin: String?, transport: String) {
        lastOrigin = origin ?: "(none)"
        lastTransport = transport
        updateClientInfo()
    }

    /** Called when a WebSocket client connects. */
    fun wsOpened(origin: String?) {
        wsClients.incrementAndGet()
        clientSeen(origin, "ws")
    }

    /** Called when a WebSocket client disconnects; closes the audio stream when none remain. */
    fun wsClosed() {
        if (wsClients.decrementAndGet() <= 0) {
            wsClients.set(0)
            stopAudio("client closed")
        }
        updateClientInfo()
    }

    private fun updateClientInfo() {
        _clientInfo.value = ClientInfo(wsClients.get(), lastOrigin, lastTransport, pcmBytes.get())
    }

    /** Feeds raw PCM from any transport, opening the listening session on the first chunk. */
    fun pushPcm(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        lastPcmAt = clock()
        pcmBytes.addAndGet(bytes.size.toLong())
        synchronized(audioLock) {
            val ch = channel ?: openChannelLocked()
            for (frame in framer.push(bytes)) ch.trySend(frame)
        }
        if (pcmBytes.get() % (BYTES_PER_UI_UPDATE) < bytes.size) updateClientInfo()
    }

    /** Opens the audio session without sending audio. */
    fun startAudio() {
        lastPcmAt = clock()
        synchronized(audioLock) { if (channel == null) openChannelLocked() }
        publish("status") { putStatus() }
    }

    /** Closes the audio stream so the backend finalizes the current utterance. */
    fun stopAudio(reason: String) {
        val closed = synchronized(audioLock) {
            val ch = channel ?: return@synchronized false
            channel = null
            framer.drain()?.let { ch.trySend(it) }
            ch.close()
            true
        }
        if (closed) publish("status") { putStatus(); put("reason", reason) }
    }

    private fun openChannelLocked(): Channel<ByteArray> {
        val ch = Channel<ByteArray>(capacity = 600, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        channel = ch
        framer.reset()
        scope.launch {
            backend.state.first { it.ready && !it.listening }
            backend.startListening(ch.consumeAsFlow())
        }
        return ch
    }

    /** Handles a JSON control message and returns the direct reply for the sender. */
    fun handleControl(text: String): String? {
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return errorJson("invalid JSON control message")
        return when (val type = obj["type"]?.jsonPrimitive?.contentOrNull) {
            "start" -> { startAudio(); ackJson(type) }
            "stop" -> { stopAudio("client stop"); ackJson(type) }
            "ask" -> {
                val q = obj["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (q.isEmpty()) errorJson("ask: text is empty")
                else if (!backend.state.value.ready) errorJson("model not ready: ${backend.state.value.status}")
                else { backend.ask(q); ackJson(type) }
            }
            "ping" -> buildJsonObject { put("type", "pong"); put("t", obj["t"]?.jsonPrimitive?.contentOrNull) }.toString()
            "status" -> buildJsonObject { put("type", "status"); put("seq", seq.get()); putStatus() }.toString()
            else -> errorJson("unknown control type: $type")
        }
    }

    private fun ackJson(type: String) = buildJsonObject { put("type", "ack"); put("of", type) }.toString()
    private fun errorJson(message: String) = buildJsonObject { put("type", "error"); put("message", message) }.toString()

    companion object {
        const val SERVICE_NAME = "flxr-g2-companion"
        private const val RING_SIZE = 300
        private const val BYTES_PER_UI_UPDATE = 32_000L
    }
}
