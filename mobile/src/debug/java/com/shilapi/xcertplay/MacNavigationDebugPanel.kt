package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.diagnostics.NavigationDebugDecoder
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/** Movable in-Activity window; no system overlay permission and no car outputs. */
internal class MacNavigationDebugPanel(private val activity: Activity, root: FrameLayout) {
    private val main = Handler(Looper.getMainLooper())
    private val started = SystemClock.elapsedRealtime()
    private val rawFile = File(activity.filesDir, "mac-usb-navigation.log").apply { writeText("") }
    private val history = ArrayDeque<String>()
    private val counts = intArrayOf(0, 0, 0)
    private var lastPacket = 0L
    private var closed = false
    private var capped = false
    private var expanded = true
    private var subscribed = false
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private val panel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xe618202b.toInt())
        elevation = dp(8).toFloat()
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    private val title = TextView(activity).apply { textSize = 12f; setTextColor(0xff87e8c0.toInt()) }
    private val body = TextView(activity).apply {
        textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(Color.WHITE)
        text = "Waiting for iAP2 RouteGuidance.\nStart a route on the iPhone.\nOnly 0x5201 / 0x5202 / 0x5204 appear here.\nDrag the title to move; − to collapse."
    }
    private val scroll = ScrollView(activity).apply { addView(body) }
    private val panelWidth = minOf(dp(460), (activity.resources.displayMetrics.widthPixels * 0.48).toInt())
    private val panelHeight = minOf(dp(300), (activity.resources.displayMetrics.heightPixels * 0.78).toInt())
    private val panelParams = FrameLayout.LayoutParams(panelWidth, panelHeight).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        leftMargin = (activity.resources.displayMetrics.widthPixels - panelWidth - dp(12)).coerceAtLeast(0)
        topMargin = dp(40)
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (closed) return
            val age = if (lastPacket == 0L) "no packets" else "${(SystemClock.elapsedRealtime() - lastPacket) / 1000}s ago"
            title.text = "NAV  route:${counts[0]}  turn:${counts[1]}  lanes:${counts[2]}\n${if (subscribed) "0x5200 sent" else "Awaiting subscription"} · $age"
            main.postDelayed(this, 1000)
        }
    }

    init {
        val header = LinearLayout(activity).apply { isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val collapse = Button(activity).apply {
            text = "−"; contentDescription = "Collapse or expand navigation log"
            minimumWidth = 0; minWidth = 0; setPadding(0, 0, 0, 0)
            setOnClickListener {
                expanded = !expanded
                scroll.visibility = if (expanded) View.VISIBLE else View.GONE
                panelParams.height = if (expanded) panelHeight else FrameLayout.LayoutParams.WRAP_CONTENT
                text = if (expanded) "−" else "+"
                panel.layoutParams = panelParams
            }
        }
        header.addView(collapse, LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(header, LinearLayout.LayoutParams(-1, -2))
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(panel, panelParams)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        title.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = panelParams.leftMargin; startY = panelParams.topMargin }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.leftMargin = (startX + event.rawX - downX).toInt().coerceIn(0, (root.width - panel.width).coerceAtLeast(0))
                    panelParams.topMargin = (startY + event.rawY - downY).toInt().coerceIn(0, (root.height - panel.height).coerceAtLeast(0))
                    panel.layoutParams = panelParams
                }
                MotionEvent.ACTION_UP -> view.performClick()
            }
            true
        }
        main.post(ticker)
    }

    fun subscriptionSent() { main.post { subscribed = true } }

    /** Called by the existing sole iAP2 reader; never reads or sends on the session itself. */
    fun accept(frame: Iap2Frame) {
        val decoded = NavigationDebugDecoder.decode(frame) ?: return
        val elapsed = (SystemClock.elapsedRealtime() - started) / 1000.0
        val entry = String.format(Locale.US, "+%.1fs %s", elapsed, decoded)
        // Navigation may contain street names. Keep it local and bounded; do not use Android logcat.
        runCatching {
            if (!capped) {
                val record = "epochMs=${System.currentTimeMillis()} $entry\nPAYLOAD_HEX=${NavigationDebugDecoder.hex(frame.payload)}\n\n"
                if (rawFile.length() + record.toByteArray().size <= 2 * 1024 * 1024) rawFile.appendText(record)
                else { rawFile.appendText("LOG LIMIT REACHED (2 MiB)\n"); capped = true }
            }
        }.onFailure { main.post { body.text = "Local navigation log write failed: ${it.javaClass.simpleName}" } }
        main.post {
            if (closed) return@post
            counts[when (frame.messageId) { 0x5201 -> 0; 0x5202 -> 1; else -> 2 }]++
            lastPacket = SystemClock.elapsedRealtime()
            history.addLast(entry)
            while (history.size > 12) history.removeFirst()
            body.text = history.joinToString("\n\n").takeLast(16000)
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    fun close() { closed = true; main.removeCallbacksAndMessages(null) }
}
