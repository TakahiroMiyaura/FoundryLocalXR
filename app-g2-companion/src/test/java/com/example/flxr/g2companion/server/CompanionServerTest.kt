// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.g2companion.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/** End-to-end tests of [CompanionServer] over real loopback sockets with a fake backend. */
class CompanionServerTest {

    /** Fake backend that replies with the number of PCM bytes received. */
    private class FakeBackend(private val scope: CoroutineScope) : AssistantBackend {
        val frames = Collections.synchronizedList(mutableListOf<Int>())
        private val _state = MutableStateFlow(BackendState(ready = true, status = "準備完了", models = "fake"))
        override val state: StateFlow<BackendState> = _state
        private var listener: BackendListener? = null
        override fun setListener(listener: BackendListener) { this.listener = listener }

        override fun startListening(pcm: Flow<ByteArray>) {
            _state.update { it.copy(listening = true) }
            scope.launch {
                var total = 0
                pcm.collect { frames += it.size; total += it.size; _state.update { s -> s.copy(partial = "…$total") } }
                _state.update { it.copy(listening = false, partial = "") }
                respond("heard $total bytes")
            }
        }

        override fun ask(text: String) { scope.launch { respond(text) } }

        private fun respond(user: String) {
            val l = listener ?: return
            l.onFinalTranscript(user)
            l.onReplyDelta("re: ")
            l.onReplyDelta(user)
            l.onReply("re: $user")
        }
    }

    private lateinit var scope: CoroutineScope
    private lateinit var backend: FakeBackend
    private lateinit var hub: SessionHub
    private lateinit var server: CompanionServer
    private lateinit var client: HttpClient
    private var port = 0
    private val base get() = "http://127.0.0.1:$port"

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        backend = FakeBackend(scope)
        hub = SessionHub(backend, scope, idleStopMs = 1_500)
        port = ServerSocket(0).use { it.localPort }
        server = CompanionServer(hub, port, version = "test")
        server.start()
        client = HttpClient(CIO) { install(WebSockets) }
    }

    @After
    fun tearDown() {
        client.close()
        server.stop()
        scope.cancel()
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content

    @Test
    fun health_isReachable_withCorsEchoingOrigin() = runBlocking {
        val origin = "http://127.0.0.1:36673"
        val r = client.get("$base/health") { header("Origin", origin) }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(origin, r.headers["Access-Control-Allow-Origin"])
        val body = json(r.bodyAsText())
        assertTrue(body["ok"]!!.jsonPrimitive.boolean)
        assertEquals(SessionHub.SERVICE_NAME, body.str("service"))
        assertEquals(origin, body.str("origin"))
        assertTrue(body["ready"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun preflight_allowsMethodsHeadersAndPrivateNetwork() = runBlocking {
        val r = client.options("$base/api/pcm") {
            header("Origin", "http://192.168.0.10:5173")
            header("Access-Control-Request-Method", "POST")
            header("Access-Control-Request-Headers", "content-type")
            header("Access-Control-Request-Private-Network", "true")
        }
        assertEquals(HttpStatusCode.NoContent, r.status)
        assertEquals("http://192.168.0.10:5173", r.headers["Access-Control-Allow-Origin"])
        assertTrue(r.headers["Access-Control-Allow-Methods"]!!.contains("POST"))
        assertEquals("content-type", r.headers["Access-Control-Allow-Headers"])
        assertEquals("true", r.headers["Access-Control-Allow-Private-Network"])
    }

    @Test
    fun websocket_pcmIsReframedAndRepliesStreamBack() = runBlocking {
        withTimeout(10_000) {
            client.webSocket(host = "127.0.0.1", port = port, path = "/ws") {
                val hello = json((incoming.receive() as Frame.Text).readText())
                assertEquals("hello", hello.str("type"))

                send(Frame.Binary(true, ByteArray(4000)))
                send(Frame.Binary(true, ByteArray(2401)))
                send(Frame.Text("""{"type":"stop"}"""))

                val types = mutableListOf<String>()
                var reply: String? = null
                while (reply == null) {
                    val msg = json((incoming.receive() as Frame.Text).readText())
                    val type = msg.str("type")!!
                    types += type
                    if (type == "reply") reply = msg.str("text")
                }
                assertEquals("re: heard 6400 bytes", reply)
                assertTrue(types.containsAll(listOf("ack", "transcript", "reply_delta", "reply")))
            }
        }
        assertEquals(listOf(3200, 3200), backend.frames.toList())
    }

    @Test
    fun websocket_askReturnsReply_andBadJsonIsAnError() = runBlocking {
        withTimeout(10_000) {
            client.webSocket(host = "127.0.0.1", port = port, path = "/ws") {
                incoming.receive()
                send(Frame.Text("not json"))
                val err = json((incoming.receive() as Frame.Text).readText())
                assertEquals("error", err.str("type"))

                send(Frame.Text("""{"type":"ask","text":"こんにちは"}"""))
                var reply: String? = null
                while (reply == null) {
                    val msg = json((incoming.receive() as Frame.Text).readText())
                    if (msg.str("type") == "reply") reply = msg.str("text")
                }
                assertEquals("re: こんにちは", reply)
            }
        }
    }

    @Test
    fun httpFallback_pcmControlAndLongPoll() = runBlocking {
        withTimeout(15_000) {
            val origin = "http://127.0.0.1:40000"
            val p = client.post("$base/api/pcm") {
                header("Origin", origin)
                contentType(ContentType.Application.OctetStream)
                setBody(ByteArray(9600))
            }
            assertEquals(HttpStatusCode.NoContent, p.status)
            assertEquals(origin, p.headers["Access-Control-Allow-Origin"])

            val c = client.post("$base/api/control") {
                contentType(ContentType.Application.Json)
                setBody("""{"type":"stop"}""")
            }
            assertEquals("ack", json(c.bodyAsText()).str("type"))

            var after = 0L
            var reply: String? = null
            while (reply == null) {
                val r = json(client.get("$base/api/events?after=$after&wait=5000").bodyAsText())
                after = r["next"]!!.jsonPrimitive.long
                for (e in r["events"]!!.jsonArray) {
                    val o = e.jsonObject
                    if (o.str("type") == "reply") reply = o.str("text")
                }
            }
            assertEquals("re: heard 9600 bytes", reply)
        }
    }

    @Test
    fun httpFallback_idleAudioIsClosedAutomatically() = runBlocking {
        withTimeout(10_000) {
            client.post("$base/api/pcm") { setBody(ByteArray(3200)) }
            var reply: String? = null
            var after = 0L
            while (reply == null) {
                val r = json(client.get("$base/api/events?after=$after&wait=5000").bodyAsText())
                after = r["next"]!!.jsonPrimitive.long
                r["events"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it.str("type") == "reply" }?.let { reply = it.str("text") }
            }
            assertEquals("re: heard 3200 bytes", reply)
        }
    }

    @Test
    fun longPoll_returnsEmptyAfterWait() = runBlocking {
        val now = json(client.get("$base/health").bodyAsText())["seq"]!!.jsonPrimitive.long
        val t0 = System.currentTimeMillis()
        val r = json(client.get("$base/api/events?after=$now&wait=300").bodyAsText())
        assertTrue(System.currentTimeMillis() - t0 >= 250)
        assertEquals(0, r["events"]!!.jsonArray.size)
        assertEquals(now, r["next"]!!.jsonPrimitive.long)
    }

    @Test
    fun listensOnLoopbackOnly() {
        val lan = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
            ?: return
        try {
            Socket().use { it.connect(InetSocketAddress(lan, port), 1_000) }
            fail("server must not accept connections on $lan")
        } catch (_: java.io.IOException) {
        }
    }
}
