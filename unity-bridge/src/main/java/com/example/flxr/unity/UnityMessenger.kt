// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.unity

import android.util.Log
import java.lang.reflect.Method

/** Receiver for bridge messages in hosts other than Unity. */
fun interface BridgeListener {
    /** Called with each message [method] and its JSON payload. */
    fun onMessage(method: String, json: String)
}

/** Sends messages to a Unity GameObject through UnityPlayer.UnitySendMessage, resolved by reflection. */
internal class UnityMessenger(private val gameObject: String) {

    @Volatile var listener: BridgeListener? = null

    private val sendMessage: Method? by lazy {
        runCatching {
            Class.forName("com.unity3d.player.UnityPlayer").getMethod(
                "UnitySendMessage", String::class.java, String::class.java, String::class.java
            )
        }.onFailure {
            Log.w(TAG, "UnityPlayer not found; messages go to BridgeListener / logcat only")
        }.getOrNull()
    }

    /** Sends [json] to [method] on the target GameObject, or to the listener if one is set. */
    fun send(method: String, json: String) {
        listener?.let { return it.onMessage(method, json) }
        val m = sendMessage
        if (m == null) {
            Log.i(TAG, "$method $json")
            return
        }
        runCatching { m.invoke(null, gameObject, method, json) }
            .onFailure { Log.e(TAG, "UnitySendMessage($gameObject.$method) failed", it) }
    }

    companion object {
        const val TAG = "FLXR-Unity"
    }
}
