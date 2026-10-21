// Copyright (c) 2026 Takahiro Miyaura
// SPDX-License-Identifier: BSL-1.0
// Distributed under the Boost Software License, Version 1.0.
// See the LICENSE file in the project root.

package com.example.flxr.rokid.glasses

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Glasses HUD that shows the latest question and answer received from the phone app.
 */
class GlassesActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var userView: TextView
    private lateinit var replyView: TextView
    private lateinit var footerView: TextView
    private lateinit var scroll: ScrollView

    private var lastSeq = -1L
    private var udpInfo = ""
    private var peer = ""

    private lateinit var udp: UdpReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildLayout())
        udp = UdpReceiver(this, onView = ::show, onPeer = { p -> if (p != peer) runOnUiThread { peer = p; renderFooter() } })
        statusView.text = "スマホの「FLXR Rokid」を待っています"
    }

    override fun onStart() {
        super.onStart()
        udpInfo = udp.start() ?: "UDP :${GlassesProtocol.PORT}"
        renderFooter()
    }

    override fun onStop() {
        udp.stop()
        super.onStop()
    }

    private fun show(v: GlassesView) = runOnUiThread {
        if (v.seq <= lastSeq && lastSeq - v.seq < 50) return@runOnUiThread
        lastSeq = v.seq
        statusView.text = v.status
        userView.text = if (v.user.isBlank()) "" else "Q: ${v.user}"
        replyView.text = if (v.reply.isBlank() && !v.replying) "" else "A: ${v.reply}${if (v.replying) " ▍" else ""}"
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun renderFooter() {
        footerView.text = "IP ${localIpv4()}  $udpInfo  ${if (peer.isNotEmpty()) "← $peer" else ""}"
    }

    private fun buildLayout(): View {
        fun tv(sp: Float, alpha: Float = 1f, bold: Boolean = false) = TextView(this).apply {
            setTextColor(Color.WHITE)
            this.alpha = alpha
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        statusView = tv(14f, 0.7f)
        userView = tv(18f, 0.85f)
        replyView = tv(22f, bold = true)
        footerView = tv(11f, 0.5f)
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(LinearLayout(this@GlassesActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(userView)
                addView(replyView)
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            val pad = (resources.displayMetrics.widthPixels * 0.06f).toInt()
            setPadding(pad, pad, pad, pad)
            addView(statusView)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(footerView)
        }
    }
}
