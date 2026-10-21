// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.rokid

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** Connection state of [LanGlassesLink]. */
enum class LinkState { IDLE, SEARCHING, CONNECTED, UNAVAILABLE }

/** Connection state of [LanGlassesLink] with a human-readable detail. */
data class LinkStatus(val state: LinkState = LinkState.IDLE, val detail: String = "未接続")

/**
 * Sends [GlassesView] snapshots to the glasses receiver app over Wi-Fi UDP,
 * finding it by broadcast discovery or a manually entered IP address.
 */
class LanGlassesLink(context: Context, private val log: (String) -> Unit) {

    private val _status = MutableStateFlow(LinkStatus())
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var recvJob: Job? = null
    private var discJob: Job? = null

    @Volatile
    var target: InetAddress? = null
        private set

    /** Sets the glasses address manually, bypassing discovery. */
    fun setManualTarget(ip: String) {
        val addr = runCatching { InetAddress.getByName(ip.trim()) }.getOrNull()
        if (addr == null) {
            log("IP を解釈できません: $ip")
            return
        }
        ensureSocket() ?: return
        target = addr
        _status.value = LinkStatus(LinkState.CONNECTED, "送信先 ${addr.hostAddress}（手動）")
        log("LAN: 送信先を ${addr.hostAddress} に設定")
    }

    /** Starts broadcast discovery; can be called again to retry. */
    fun connect() {
        val sock = ensureSocket() ?: return
        discJob?.cancel()
        if (target == null) _status.value = LinkStatus(LinkState.SEARCHING, "グラスを探索中（ブロードキャスト）")
        discJob = scope.launch {
            val bytes = GlassesProtocol.DISC.toByteArray()
            repeat(10) {
                if (target != null) return@launch
                for (addr in broadcastAddresses()) {
                    runCatching { sock.send(DatagramPacket(bytes, bytes.size, addr, GlassesProtocol.PORT)) }
                }
                delay(1000)
            }
            if (target == null) {
                _status.value = LinkStatus(
                    LinkState.UNAVAILABLE,
                    "見つかりません。グラスの Wi-Fi とアプリ起動を確認するか IP を手入力",
                )
            }
        }
    }

    private fun ensureSocket(): DatagramSocket? {
        socket?.let { return it }
        return try {
            multicastLock = wifi?.createMulticastLock("flxr-rokid")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            val s = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 1000
                bind(InetSocketAddress(0))
            }
            socket = s
            recvJob = scope.launch { receiveLoop(s) }
            s
        } catch (e: Exception) {
            log("LAN: UDP ソケットを開けません: ${e.message}")
            _status.value = LinkStatus(LinkState.UNAVAILABLE, "UDP ソケット失敗: ${e.message}")
            null
        }
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(1024)
        while (scope.isActive && !s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (!s.isClosed) Log.w(TAG, "receive", e)
                break
            }
            val text = String(p.data, p.offset, p.length, Charsets.UTF_8)
            if (text.startsWith(GlassesProtocol.HERE)) {
                val name = text.removePrefix(GlassesProtocol.HERE).trim()
                if (target != p.address) log("LAN: グラスを発見 ${p.address.hostAddress} ($name)")
                target = p.address
                _status.value = LinkStatus(LinkState.CONNECTED, "${p.address.hostAddress} ($name)")
            }
        }
    }

    /** Sends a full snapshot without blocking. */
    fun send(view: GlassesView) {
        val s = socket ?: return
        val to = target ?: return
        val bytes = (GlassesProtocol.VIEW + "\n" + view.toJson()).toByteArray(Charsets.UTF_8)
        try {
            s.send(DatagramPacket(bytes, bytes.size, to, GlassesProtocol.PORT))
        } catch (e: Exception) {
            Log.v(TAG, "send failed", e)
        }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) ia.broadcast?.let { out.add(it) }
            }
        }
        runCatching { out.add(InetAddress.getByName("255.255.255.255")) }
        return out.toList()
    }

    /** Closes the socket and releases the multicast lock. */
    fun close() {
        scope.cancel()
        runCatching { socket?.close() }
        socket = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        target = null
        _status.value = LinkStatus()
    }

    private companion object {
        const val TAG = "FLXR-Rokid-LAN"
    }
}
