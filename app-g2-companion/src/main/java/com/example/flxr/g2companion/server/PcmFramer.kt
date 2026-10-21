// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.g2companion.server

/** Re-cuts arbitrary PCM byte chunks into fixed-size frames (default 3200 bytes = 100 ms of 16 kHz s16le). */
class PcmFramer(private val frameBytes: Int = 3200) {
    init {
        require(frameBytes > 0 && frameBytes % 2 == 0) { "frameBytes must be a positive even number" }
    }

    private val buffer = ByteArray(frameBytes)
    private var filled = 0

    /** Returns the complete frames contained in [bytes]; the remainder is kept for the next call. */
    fun push(bytes: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(bytes.size / frameBytes + 1)
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(frameBytes - filled, bytes.size - offset)
            System.arraycopy(bytes, offset, buffer, filled, n)
            filled += n
            offset += n
            if (filled == frameBytes) {
                out += buffer.copyOf()
                filled = 0
            }
        }
        return out
    }

    /** Returns the buffered remainder trimmed to an even length, or null if empty. */
    fun drain(): ByteArray? {
        val even = filled - (filled % 2)
        filled = 0
        return if (even > 0) buffer.copyOf(even) else null
    }

    /** Discards any buffered bytes. */
    fun reset() {
        filled = 0
    }
}
