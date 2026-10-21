// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.rokid

import org.json.JSONObject

/**
 * Message constants shared with the glasses receiver app (:app-rokid-glasses).
 */
object GlassesProtocol {
    const val PORT = 47110
    const val MAGIC = "FLXR1"
    const val DISC = "$MAGIC DISC"
    const val HERE = "$MAGIC HERE"
    const val VIEW = "$MAGIC VIEW"
    const val KEEPALIVE_MS = 3_000L
    const val MAX_CHARS = 400
}

/** Snapshot of what the glasses should display. */
data class GlassesView(
    val seq: Long = 0,
    val status: String = "",
    val user: String = "",
    val reply: String = "",
    val replying: Boolean = false,
) {
    /** Serializes this snapshot to JSON, truncating long fields. */
    fun toJson(): String = JSONObject()
        .put("seq", seq)
        .put("status", status.take(80))
        .put("user", user.takeLast(GlassesProtocol.MAX_CHARS))
        .put("reply", reply.takeLast(GlassesProtocol.MAX_CHARS))
        .put("replying", replying)
        .toString()
}
