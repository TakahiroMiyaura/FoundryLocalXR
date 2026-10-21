// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.rokid

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Holds the current [GlassesView] and sends it through [link] when it changes,
 * resending periodically as a keep-alive.
 */
class GlassesDisplay(private val scope: CoroutineScope, private val link: LanGlassesLink) {

    private val _view = MutableStateFlow(GlassesView())
    val view: StateFlow<GlassesView> = _view.asStateFlow()

    private var job: Job? = null
    private var lastSentSeq = -1L
    private var lastSentAt = 0L

    /** Applies [transform] to the current view and bumps its sequence number. */
    fun update(transform: (GlassesView) -> GlassesView) =
        _view.update { old -> transform(old).copy(seq = old.seq + 1) }

    /** Starts the send loop. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val v = _view.value
                val now = SystemClock.elapsedRealtime()
                if (v.seq != lastSentSeq || now - lastSentAt >= GlassesProtocol.KEEPALIVE_MS) {
                    link.send(v)
                    lastSentSeq = v.seq
                    lastSentAt = now
                }
                delay(INTERVAL_MS)
            }
        }
    }

    /** Stops the send loop. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val INTERVAL_MS = 300L
    }
}
