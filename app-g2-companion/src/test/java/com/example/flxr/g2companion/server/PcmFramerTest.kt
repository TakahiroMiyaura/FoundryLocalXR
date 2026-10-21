// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.g2companion.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcmFramerTest {
    @Test
    fun cutsIntoFixedFrames_keepingByteOrderAcrossCalls() {
        val f = PcmFramer(4)
        val src = ByteArray(10) { it.toByte() }
        val a = f.push(src.copyOfRange(0, 3))
        val b = f.push(src.copyOfRange(3, 10))
        assertEquals(0, a.size)
        assertEquals(2, b.size)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), b[0])
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), b[1])
        assertArrayEquals(byteArrayOf(8, 9), f.drain())
        assertNull(f.drain())
    }

    @Test
    fun drainDropsDanglingHalfSample() {
        val f = PcmFramer(4)
        f.push(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2), f.drain())
    }

    @Test
    fun g2FramesPassThroughUnchanged() {
        val f = PcmFramer()
        val frames = f.push(ByteArray(3200) { 7 })
        assertEquals(1, frames.size)
        assertEquals(3200, frames[0].size)
    }
}
