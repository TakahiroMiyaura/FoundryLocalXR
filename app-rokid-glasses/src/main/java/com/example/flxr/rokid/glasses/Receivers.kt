// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.rokid.glasses

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface

/** Message constants shared with the phone app (:app-rokid). */
object GlassesProtocol {
    const val PORT = 47110
    const val MAGIC = "FLXR1"
    const val DISC = "$MAGIC DISC"
    const val HERE = "$MAGIC HERE"
    const val VIEW = "$MAGIC VIEW"
}

/** Snapshot of what the glasses should display. */
data class GlassesView(
    val seq: Long,
    val status: String,
    val user: String,
    val reply: String,
    val replying: Boolean,
) {
    companion object {
        /** Parses a JSON snapshot, or returns null if it is malformed. */
        fun parse(json: String): GlassesView? = runCatching {
            val o = JSONObject(json)
            GlassesView(
                seq = o.optLong("seq"),
                status = o.optString("status"),
                user = o.optString("user"),
                reply = o.optString("reply"),
                replying = o.optBoolean("replying"),
            )
        }.getOrNull()
    }
}

/** Wi-Fi UDP receiver that answers discovery requests and delivers view snapshots. */
class UdpReceiver(
    context: Context,
    private val onView: (GlassesView) -> Unit,
    private val onPeer: (String) -> Unit,
) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    /** Opens the UDP socket; returns an error message on failure, otherwise null. */
    fun start(): String? {
        if (socket != null) return null
        return try {
            lock = wifi?.createMulticastLock("flxr-glasses")?.apply { setReferenceCounted(false); acquire() }
            val s = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(GlassesProtocol.PORT))
            }
            socket = s
            thread = Thread({ loop(s) }, "flxr-udp").apply { isDaemon = true; start() }
            null
        } catch (e: Exception) {
            Log.e(TAG, "cannot open UDP ${GlassesProtocol.PORT}", e)
            "UDP 失敗: ${e.message}"
        }
    }

    private fun loop(s: DatagramSocket) {
        val buf = ByteArray(8192)
        val here = "${GlassesProtocol.HERE} ${Build.MODEL}".toByteArray()
        while (!s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
            } catch (_: Exception) {
                break
            }
            val text = String(p.data, p.offset, p.length, Charsets.UTF_8)
            when {
                text.startsWith(GlassesProtocol.DISC) -> {
                    runCatching { s.send(DatagramPacket(here, here.size, p.socketAddress)) }
                    onPeer(p.address.hostAddress ?: "?")
                }
                text.startsWith(GlassesProtocol.VIEW) -> {
                    GlassesView.parse(text.substringAfter('\n'))?.let(onView)
                    onPeer(p.address.hostAddress ?: "?")
                }
            }
        }
    }

    /** Closes the socket and releases the multicast lock. */
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        runCatching { lock?.release() }
        lock = null
    }

    private companion object { const val TAG = "FLXR-Glasses-UDP" }
}

/** Returns the first non-loopback IPv4 address of this device, or a placeholder. */
fun localIpv4(): String = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address }?.hostAddress
}.getOrNull() ?: "Wi-Fi 未接続"
