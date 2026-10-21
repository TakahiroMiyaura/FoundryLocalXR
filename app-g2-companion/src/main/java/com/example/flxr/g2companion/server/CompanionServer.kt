// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.g2companion.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/** Loopback HTTP and WebSocket server that connects the Even Hub web app to a [SessionHub]. */
class CompanionServer(
    private val hub: SessionHub,
    private val port: Int = DEFAULT_PORT,
    private val host: String = LOOPBACK,
    private val version: String = "dev",
    private val originAllowed: (String?) -> Boolean = { true }
) {
    private var server: EmbeddedServer<*, *>? = null

    /** Starts the server without blocking. */
    fun start() {
        check(server == null) { "already started" }
        server = embeddedServer(CIO, port = port, host = host) {
            companionModule(hub, version, port, originAllowed)
        }.start(wait = false)
    }

    /** Stops the server. */
    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        server = null
    }

    companion object {
        const val DEFAULT_PORT = 18765
        const val LOOPBACK = "127.0.0.1"
    }
}

/** CORS plugin that echoes the request Origin and answers preflight requests. */
private fun loopbackCors(originAllowed: (String?) -> Boolean) = createApplicationPlugin("LoopbackCors") {
    onCall { call ->
        val origin = call.request.headers[HttpHeaders.Origin]
        if (!originAllowed(origin)) {
            call.respond(HttpStatusCode.Forbidden, "origin not allowed: $origin")
            return@onCall
        }
        if (call.request.path() == "/ws") return@onCall
        call.response.header(HttpHeaders.AccessControlAllowOrigin, origin ?: "*")
        call.response.header(HttpHeaders.Vary, HttpHeaders.Origin)
        if (call.request.httpMethod == HttpMethod.Options) {
            call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, OPTIONS")
            call.response.header(
                HttpHeaders.AccessControlAllowHeaders,
                call.request.headers[HttpHeaders.AccessControlRequestHeaders] ?: "Content-Type"
            )
            call.response.header("Access-Control-Allow-Private-Network", "true")
            call.response.header(HttpHeaders.AccessControlMaxAge, "600")
        }
    }
}

/** Installs WebSockets, CORS and the companion routes. */
fun Application.companionModule(
    hub: SessionHub,
    version: String,
    port: Int,
    originAllowed: (String?) -> Boolean = { true }
) {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = 1L shl 20
        masking = false
    }
    install(loopbackCors(originAllowed))

    routing {
        options("{...}") { call.respond(HttpStatusCode.NoContent) }

        get("/") {
            call.respondText(
                "Foundry Local XR - Even G2 companion $version\nGET /health, WS /ws, POST /api/pcm, POST /api/control, GET /api/events\n",
                ContentType.Text.Plain
            )
        }

        get("/health") {
            val origin = call.request.headers[HttpHeaders.Origin]
            hub.clientSeen(origin, "http")
            val body = hub.statusJson {
                put("ok", true)
                put("service", SessionHub.SERVICE_NAME)
                put("version", version)
                put("port", port)
                put("origin", origin)
            }
            call.respondText(body.toString(), ContentType.Application.Json)
        }

        post("/api/pcm") {
            hub.clientSeen(call.request.headers[HttpHeaders.Origin], "http")
            hub.pushPcm(call.receive<ByteArray>())
            call.respond(HttpStatusCode.NoContent)
        }

        post("/api/control") {
            hub.clientSeen(call.request.headers[HttpHeaders.Origin], "http")
            val reply = hub.handleControl(call.receiveText()) ?: "{}"
            call.respondText(reply, ContentType.Application.Json)
        }

        get("/api/events") {
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
            val wait = (call.request.queryParameters["wait"]?.toLongOrNull() ?: 20_000L).coerceIn(0L, 25_000L)
            val events = hub.eventsAfter(after, wait)
            val body = buildJsonObject {
                put("next", events.lastOrNull()?.seq ?: after)
                put("events", buildJsonArray { events.forEach { add(Json.parseToJsonElement(it.json)) } })
            }
            call.respondText(body.toString(), ContentType.Application.Json)
        }

        webSocket("/ws") {
            hub.wsOpened(call.request.headers[HttpHeaders.Origin])
            val forward = launch {
                hub.events
                    .onSubscription { send(Frame.Text(hub.helloJson())) }
                    .collect { send(Frame.Text(it.json)) }
            }
            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Binary -> hub.pushPcm(frame.readBytes())
                        is Frame.Text -> hub.handleControl(frame.readText())?.let { send(Frame.Text(it)) }
                        else -> Unit
                    }
                }
            } finally {
                forward.cancel()
                hub.wsClosed()
            }
        }
    }
}
