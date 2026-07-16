package com.example.barky

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import java.util.Locale
import java.util.regex.Pattern

class BarkyService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var currentDelay = 20 * 1000L
    private var lastScanAt = 0L

    // Conversion factors
    private val cmToInch = 0.393701
    private val inchToCm = 2.54
    private val mToFt = 3.280839895
    private val ftToM = 0.3048

    // Number: integer or decimal
    private val num = "(\\d+(?:\\.\\d+)?)"

    // cm → in
    private val cmPattern = Pattern.compile("$num\\s*cm\\b", Pattern.CASE_INSENSITIVE)

    // inches: 12 in / inch / inches, or 12" / 12″
    private val inchWordPattern =
        Pattern.compile("$num\\s*(?:inches|inch|in)\\b", Pattern.CASE_INSENSITIVE)
    private val inchQuotePattern =
        Pattern.compile("$num\\s*[\"\u2033\u201D\u201C]")

    // feet: 12 ft / foot / feet, or 12' / 12′
    private val feetWordPattern =
        Pattern.compile("$num\\s*(?:feet|foot|ft)\\b", Pattern.CASE_INSENSITIVE)
    private val feetQuotePattern =
        Pattern.compile("$num\\s*['\u2032\u2019\u2018]")

    // meters: 12 m / meter / meters / metre / metres
    private val meterPattern =
        Pattern.compile("$num\\s*(?:meters|metres|meter|metre|m)\\b", Pattern.CASE_INSENSITIVE)

    override fun onServiceConnected() {
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 250
        }
        serviceInfo = info

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        loadDelay()
        Log.i(TAG, "BarkyService connected")
    }

    private fun loadDelay() {
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        currentDelay = prefs.getInt("hide_delay", 20) * 1000L
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Throttle: accessibility fires very often; avoid main-thread jank / ANR risk.
        val now = System.currentTimeMillis()
        if (now - lastScanAt < SCAN_THROTTLE_MS) return
        lastScanAt = now

        val root = rootInActiveWindow ?: return
        try {
            val texts = mutableListOf<String>()
            collectTexts(root, texts, depth = 0)
            val conversions = parseConversions(texts).distinct().take(MAX_LINES)
            if (conversions.isNotEmpty()) {
                showOverlay(conversions)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onAccessibilityEvent failed", t)
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private fun collectTexts(
        node: AccessibilityNodeInfo?,
        list: MutableList<String>,
        depth: Int
    ) {
        if (node == null || depth > MAX_TREE_DEPTH || list.size >= MAX_TEXT_NODES) return

        node.text?.let { if (it.isNotBlank()) list.add(it.toString()) }
        node.contentDescription?.let { if (it.isNotBlank()) list.add(it.toString()) }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (list.size >= MAX_TEXT_NODES) break
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            try {
                collectTexts(child, list, depth + 1)
            } finally {
                try {
                    child.recycle()
                } catch (_: Exception) {
                    // ignore
                }
            }
        }
    }

    private fun parseConversions(texts: List<String>): List<String> {
        val results = mutableListOf<String>()
        for (text in texts) {
            // cm → in
            matchAll(cmPattern, text) { n ->
                results.add(String.format(Locale.US, "%.2f cm ≈ %.2f in", n, n * cmToInch))
            }

            // in / " → cm
            matchAll(inchWordPattern, text) { n ->
                results.add(String.format(Locale.US, "%.2f in ≈ %.2f cm", n, n * inchToCm))
            }
            matchAll(inchQuotePattern, text) { n ->
                results.add(String.format(Locale.US, "%.2f in ≈ %.2f cm", n, n * inchToCm))
            }

            // ft / ' → m
            matchAll(feetWordPattern, text) { n ->
                results.add(String.format(Locale.US, "%.2f ft ≈ %.2f m", n, n * ftToM))
            }
            matchAll(feetQuotePattern, text) { n ->
                results.add(String.format(Locale.US, "%.2f ft ≈ %.2f m", n, n * ftToM))
            }

            // m → ft
            matchAll(meterPattern, text) { n ->
                results.add(String.format(Locale.US, "%.2f m ≈ %.2f ft", n, n * mToFt))
            }
        }
        return results
    }

    private inline fun matchAll(
        pattern: Pattern,
        text: String,
        onMatch: (Double) -> Unit
    ) {
        val matcher = pattern.matcher(text)
        while (matcher.find()) {
            val n = matcher.group(1)?.toDoubleOrNull() ?: continue
            onMatch(n)
        }
    }

    private fun showOverlay(conversions: List<String>) {
        handler.post {
            try {
                loadDelay()
                val joined = conversions.joinToString("\n")

                if (overlayView != null) {
                    overlayView?.findViewById<TextView>(R.id.overlay_text)?.text = joined
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({ hideOverlay() }, currentDelay)
                    return@post
                }

                // Theme the inflater: service context is not a Material themed Activity.
                val themed = ContextThemeWrapper(this, R.style.Theme_Barky)
                val inflater = LayoutInflater.from(themed)
                val view = inflater.inflate(R.layout.overlay_layout, null)
                overlayView = view

                view.findViewById<TextView>(R.id.overlay_text).text = joined
                view.findViewById<Button>(R.id.close_btn).setOnClickListener { hideOverlay() }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 50
                    y = 200
                }

                windowManager?.addView(view, params)
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ hideOverlay() }, currentDelay)
                Log.i(TAG, "Overlay shown (${conversions.size} lines)")
            } catch (t: Throwable) {
                Log.e(TAG, "showOverlay failed", t)
                overlayView = null
            }
        }
    }

    private fun hideOverlay() {
        handler.post {
            overlayView?.let { view ->
                try {
                    windowManager?.removeView(view)
                } catch (_: Exception) {
                    // View may already be detached
                }
                overlayView = null
            }
        }
    }

    override fun onInterrupt() {
        hideOverlay()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BarkyService"
        private const val SCAN_THROTTLE_MS = 400L
        private const val MAX_TREE_DEPTH = 25
        private const val MAX_TEXT_NODES = 200
        private const val MAX_LINES = 8
    }
}
