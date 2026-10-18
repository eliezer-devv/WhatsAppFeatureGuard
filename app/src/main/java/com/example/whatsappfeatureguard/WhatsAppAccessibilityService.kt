package com.example.whatsappfeatureguard

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        private const val PREFS_NAME = "feature_guard_settings"
        private const val DIAGNOSTIC_MODE = "diagnostic_mode"
        private const val BLOCK_STATUS = "block_status"
        private const val BLOCK_CHANNELS = "block_channels"
        private const val OVERLAY_STATUS = "overlay_status"
        private const val OVERLAY_CHANNELS = "overlay_channels"
        private const val COOLDOWN_UNTIL = "cooldown_until"
        private const val LOG_FILE = "whatsapp_accessibility_log.txt"

        private const val MAX_LOG_BYTES = 2 * 1024 * 1024
        private const val MAX_NODES = 500
        private const val MAX_DEPTH = 30
        private const val COOLDOWN_MS = 10_000L
        private const val REDIRECT_THROTTLE_MS = 250L
        private const val ROOT_RETRY_MS = 200L
        private const val MAX_ROOT_RETRIES = 5

        @Volatile
        var lastCaptureStatus: String = "Waiting for WhatsApp"
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val overlayViews = mutableMapOf<String, TextView>()
    private val overlayRects = mutableMapOf<String, Rect>()

    private var lastSnapshot = ""
    private var lastCaptureTime = 0L
    private var lastRedirectTime = 0L
    private var rootRetryCount = 0
    private var serviceActive = false

    private val rootRetryRunnable = Runnable {
        if (serviceActive && rootRetryCount < MAX_ROOT_RETRIES) {
            rootRetryCount++
            processCurrentWindow()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceActive = true
        lastCaptureStatus = "Accessibility service connected"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!serviceActive || event == null) return

        if (event.packageName?.toString() != "com.whatsapp") {
            if (event.eventType ==
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            ) {
                removeAllOverlays()
            }
            return
        }

        rootRetryCount = 0
        handler.removeCallbacks(rootRetryRunnable)
        processCurrentWindow(event)
    }

    private fun processCurrentWindow(
        event: AccessibilityEvent? = null
    ) {
        if (!serviceActive) return

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val blockStatus = prefs.getBoolean(BLOCK_STATUS, false)
        val blockChannels = prefs.getBoolean(BLOCK_CHANNELS, false)
        val overlayStatus = prefs.getBoolean(OVERLAY_STATUS, false)
        val overlayChannels = prefs.getBoolean(OVERLAY_CHANNELS, false)
        val diagnosticMode = prefs.getBoolean(DIAGNOSTIC_MODE, false)

        if (!blockStatus && !blockChannels &&
            !overlayStatus && !overlayChannels && !diagnosticMode
        ) {
            removeAllOverlays()
            return
        }

        val root = rootInActiveWindow

        if (root == null) {
            if (diagnosticMode) {
                lastCaptureStatus =
                    "WhatsApp event received, but no accessible root"
            }

            if (rootRetryCount < MAX_ROOT_RETRIES) {
                handler.removeCallbacks(rootRetryRunnable)
                handler.postDelayed(rootRetryRunnable, ROOT_RETRY_MS)
            }
            return
        }

        try {
            if (root.packageName?.toString() != "com.whatsapp") {
                removeAllOverlays()
                return
            }

            if (diagnosticMode && event != null) {
                captureScreen(event, root)
            }

            updateOverlays(root, overlayStatus, overlayChannels)

            // Existing cooldown and blocking flow.
            val now = System.currentTimeMillis()
            val cooldownUntil = prefs.getLong(COOLDOWN_UNTIL, 0L)

            if (now < cooldownUntil && !isChatsScreen(root)) {
                redirectToChats()
                return
            }

            if (!blockStatus && !blockChannels) return

            val statusViewer =
                blockStatus && isStatusViewer(root)

            val statusRowClicked =
                blockStatus &&
                    event?.let { isStatusRowClick(it) } == true

            val channelsScreen =
                blockChannels && isChannelsScreen(root)

            if (statusViewer || statusRowClicked || channelsScreen) {
                startCooldown()
                redirectToChats()
            }
        } catch (e: Exception) {
            lastCaptureStatus =
                "Service error: ${e.javaClass.simpleName}"
        } finally {
            root.recycle()
        }
    }

    // ---------------------------------------------------------------
    // OVERLAYS
    // ---------------------------------------------------------------

    private fun updateOverlays(
        root: AccessibilityNodeInfo,
        overlayStatus: Boolean,
        overlayChannels: Boolean
    ) {
        val updatesList = findViewIdBounds(root, "updates_list")
        val channelsHeading = findLabelBounds(root, "Channels")
        val followChannel =
            findLabelBounds(root, "FOLLOW CHANNEL")
                ?: findLabelBounds(root, "Follow channel")

        val channelsStart = channelsHeading ?: followChannel

        // Detect Updates even if the Channels heading is not exposed.
        val onUpdatesList =
            updatesList != null &&
                !isStatusViewer(root) &&
                !isChannelsDiscoveryScreen(root)

        if (!onUpdatesList) {
            removeAllOverlays()
            return
        }

        val list = updatesList ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val left = list.left.coerceAtLeast(0)
        val right = list.right.coerceAtMost(screenWidth)

        if (right <= left) {
            removeAllOverlays()
            return
        }

        // Fallback: cover the visible Updates list when the Channels
        // heading cannot be found. This may temporarily cover channel
        // content until the heading becomes available.
        val sectionBottom = channelsStart?.top
            ?.minus(dp(4))
            ?: list.bottom.coerceAtMost(screenHeight)

        if (overlayStatus) {
            val top = list.top.coerceAtLeast(0)
            val bottom = sectionBottom
                .coerceAtMost(list.bottom)
                .coerceAtMost(screenHeight)

            if (bottom > top) {
                setOverlay(
                    "status",
                    "Status hidden",
                    Rect(left, top, right, bottom)
                )
            } else {
                removeOverlay("status")
            }
        } else {
            removeOverlay("status")
        }

        // Keep Channels as a separate overlay.
        if (overlayChannels && channelsStart != null) {
            val top = (channelsStart.top - dp(4))
                .coerceAtLeast(list.top)
                .coerceAtLeast(0)

            val bottom = list.bottom.coerceAtMost(screenHeight)

            if (bottom > top) {
                setOverlay(
                    "channels",
                    "Channels hidden",
                    Rect(left, top, right, bottom)
                )
            } else {
                removeOverlay("channels")
            }
        } else {
            removeOverlay("channels")
        }
    }

    private fun setOverlay(
        key: String,
        label: String,
        bounds: Rect
    ) {
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            removeOverlay(key)
            return
        }

        val oldBounds = overlayRects[key]
        val existing = overlayViews[key]

        if (existing != null && oldBounds == bounds) return

        val windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        val view = existing ?: TextView(this).apply {
            textSize = 15f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = ColorDrawable(Color.rgb(247, 247, 247))

            // Consume touch events so they do not pass through to WhatsApp.
            isClickable = true
            isFocusable = false
            importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_NO

            setOnTouchListener { _, _ -> true }
        }

        view.text = label

        val params = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
        }

        try {
            if (existing == null) {
                windowManager.addView(view, params)
                overlayViews[key] = view
            } else {
                windowManager.updateViewLayout(view, params)
            }

            overlayRects[key] = Rect(bounds)
        } catch (e: Exception) {
            lastCaptureStatus =
                "Overlay error: ${e.javaClass.simpleName}"
            removeOverlay(key)
        }
    }

    private fun removeOverlay(key: String) {
        val view = overlayViews.remove(key)
        overlayRects.remove(key)

        if (view != null) {
            try {
                val windowManager =
                    getSystemService(WINDOW_SERVICE) as WindowManager
                windowManager.removeView(view)
            } catch (_: Exception) {
                // Android may already have removed this view.
            }
        }
    }

    private fun removeAllOverlays() {
        removeOverlay("status")
        removeOverlay("channels")
    }

    private fun findViewIdBounds(
        node: AccessibilityNodeInfo,
        expected: String
    ): Rect? {
        if (node.viewIdResourceName?.contains(
                expected,
                ignoreCase = true
            ) == true
        ) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) return bounds
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val result = findViewIdBounds(child, expected)
                if (result != null) return result
            } finally {
                child.recycle()
            }
        }

        return null
    }

    private fun findLabelBounds(
        node: AccessibilityNodeInfo,
        expected: String
    ): Rect? {
        val text = node.text?.toString()?.trim().orEmpty()
        val description =
            node.contentDescription?.toString()?.trim().orEmpty()

        if (text.equals(expected, ignoreCase = true) ||
            description.equals(expected, ignoreCase = true)
        ) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) return bounds
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val result = findLabelBounds(child, expected)
                if (result != null) return result
            } finally {
                child.recycle()
            }
        }

        return null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------
    // EXISTING BLOCKING LOGIC
    // ---------------------------------------------------------------

    private fun startCooldown() {
        val until = System.currentTimeMillis() + COOLDOWN_MS

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putLong(COOLDOWN_UNTIL, until)
            .apply()

        lastCaptureStatus =
            "Restricted feature detected; cooldown started"
    }

    private fun isStatusRowClick(
        event: AccessibilityEvent
    ): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) {
            return false
        }

        val source = event.source ?: return false

        return try {
            val id = source.viewIdResourceName.orEmpty()
            val description =
                source.contentDescription?.toString().orEmpty()
            val text = source.text?.toString().orEmpty()

            id.contains("status_row_container", ignoreCase = true) ||
                id.contains("my_status_row_container", ignoreCase = true) ||
                description.contains("My status", ignoreCase = true) ||
                description.contains("unread statuses", ignoreCase = true) ||
                text.equals("My status", ignoreCase = true)
        } finally {
            source.recycle()
        }
    }

    private fun isStatusViewer(
        root: AccessibilityNodeInfo
    ): Boolean {
        return containsViewId(root, "playback_progress") ||
            containsViewId(root, "status_playback_image") ||
            containsText(root, "Status 1 of") ||
            containsText(root, "Status 2 of") ||
            containsText(root, "Status 3 of") ||
            containsText(root, "Status 4 of")
    }

    private fun isChannelsScreen(
        root: AccessibilityNodeInfo
    ): Boolean {
        val hasUpdatesList = containsViewId(root, "updates_list")

        val existingChannelIndicators =
            containsText(root, "FOLLOW CHANNEL") ||
                containsText(root, "Follow channel") ||
                containsText(root, "Find channels") ||
                containsText(root, "Discover channels")

        return existingChannelIndicators ||
            (!hasUpdatesList && isChannelsDiscoveryScreen(root))
    }

    private fun isChannelsDiscoveryScreen(
        root: AccessibilityNodeInfo
    ): Boolean {
        if (containsViewId(root, "updates_list")) return false

        if (containsViewId(root, "directory_category_list")) {
            return true
        }

        val exploreHeading =
            containsText(root, "Explore channels") ||
                containsText(root, "Explore")

        val discoveryContent =
            containsText(root, "Recommended") ||
                containsText(root, "Popular") ||
                containsText(root, "Verified") ||
                containsText(root, "Search channels") ||
                containsText(root, "Find channels") ||
                containsText(root, "Discover channels")

        return exploreHeading && discoveryContent
    }

    private fun isChatsScreen(
        root: AccessibilityNodeInfo
    ): Boolean {
        val hasSearchBar =
            containsViewId(root, "search_bar_inner_layout")
        val hasSearchText =
            containsViewId(root, "search_text")
        val hasConversationList =
            containsViewId(root, "contact_row_container") ||
                containsViewId(
                    root,
                    "conversations_coordinator_layout"
                )

        return hasSearchBar && (hasSearchText || hasConversationList)
    }

    private fun containsText(
        node: AccessibilityNodeInfo,
        expected: String
    ): Boolean {
        if (node.text?.toString()?.contains(
                expected,
                ignoreCase = true
            ) == true
        ) return true

        if (node.contentDescription?.toString()?.contains(
                expected,
                ignoreCase = true
            ) == true
        ) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (containsText(child, expected)) return true
            } finally {
                child.recycle()
            }
        }

        return false
    }

    private fun containsViewId(
        node: AccessibilityNodeInfo,
        expected: String
    ): Boolean {
        if (node.viewIdResourceName?.contains(
                expected,
                ignoreCase = true
            ) == true
        ) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (containsViewId(child, expected)) return true
            } finally {
                child.recycle()
            }
        }

        return false
    }

    private fun redirectToChats() {
        val now = System.currentTimeMillis()

        if (now - lastRedirectTime < REDIRECT_THROTTLE_MS) return
        lastRedirectTime = now

        val root = rootInActiveWindow

        if (root == null) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            lastCaptureStatus =
                "Restricted feature detected; attempted Back"
            return
        }

        try {
            if (root.packageName?.toString() != "com.whatsapp") return

            if (clickLabel(root, "Chats")) {
                lastCaptureStatus =
                    "Restricted feature blocked; returned to Chats"
                return
            }
        } finally {
            root.recycle()
        }

        performGlobalAction(GLOBAL_ACTION_BACK)
        lastCaptureStatus =
            "Restricted feature detected; attempted to leave screen"
    }

    private fun clickLabel(
        node: AccessibilityNodeInfo,
        label: String
    ): Boolean {
        val text = node.text?.toString().orEmpty()
        val description =
            node.contentDescription?.toString().orEmpty()

        if (text.equals(label, ignoreCase = true) ||
            description.equals(label, ignoreCase = true)
        ) {
            if (node.isClickable &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) return true

            val parent = node.parent
            if (parent != null) {
                try {
                    if (parent.isClickable &&
                        parent.performAction(
                            AccessibilityNodeInfo.ACTION_CLICK
                        )
                    ) return true
                } finally {
                    parent.recycle()
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (clickLabel(child, label)) return true
            } finally {
                child.recycle()
            }
        }

        return false
    }

    // ---------------------------------------------------------------
    // EXISTING DIAGNOSTIC LOGGING
    // ---------------------------------------------------------------

    private fun captureScreen(
        event: AccessibilityEvent,
        root: AccessibilityNodeInfo
    ) {
        val now = System.currentTimeMillis()
        if (now - lastCaptureTime < 400) return

        val output = StringBuilder()
        val timestamp = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS",
            Locale.US
        ).format(Date(now))

        output.appendLine()
        output.appendLine("==================================================")
        output.appendLine("CAPTURE: $timestamp")
        output.appendLine("PACKAGE: ${event.packageName}")
        output.appendLine("EVENT_TYPE: ${eventTypeName(event.eventType)}")
        output.appendLine("WINDOW_ID: ${root.windowId}")
        output.appendLine("==================================================")

        val counter = intArrayOf(0)
        appendNodeTree(root, output, 0, counter)

        val snapshot = output.toString()
        if (snapshot == lastSnapshot) return

        appendToLog(snapshot)
        lastSnapshot = snapshot
        lastCaptureTime = now
        lastCaptureStatus =
            "Captured ${counter[0]} UI elements at $timestamp"
    }

    private fun appendNodeTree(
        node: AccessibilityNodeInfo,
        output: StringBuilder,
        depth: Int,
        counter: IntArray
    ) {
        if (counter[0] >= MAX_NODES || depth > MAX_DEPTH) return

        counter[0]++

        val indent = "  ".repeat(depth.coerceAtMost(MAX_DEPTH))
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        output.append(indent).append("NODE ")
            .append(counter[0]).appendLine()
        output.append(indent).appendLine("  class=${clean(node.className)}")
        output.append(indent).appendLine("  text=${clean(node.text)}")
        output.append(indent).appendLine(
            "  contentDescription=${clean(node.contentDescription)}"
        )
        output.append(indent).appendLine(
            "  viewId=${clean(node.viewIdResourceName)}"
        )
        output.append(indent).appendLine("  clickable=${node.isClickable}")
        output.append(indent).appendLine("  selected=${node.isSelected}")
        output.append(indent).appendLine("  checkable=${node.isCheckable}")
        output.append(indent).appendLine("  checked=${node.isChecked}")
        output.append(indent).appendLine("  enabled=${node.isEnabled}")
        output.append(indent).appendLine("  focusable=${node.isFocusable}")
        output.append(indent).appendLine("  focused=${node.isFocused}")
        output.append(indent).appendLine("  scrollable=${node.isScrollable}")
        output.append(indent).appendLine("  bounds=$bounds")

        for (i in 0 until node.childCount) {
            if (counter[0] >= MAX_NODES) break

            val child = node.getChild(i) ?: continue
            try {
                appendNodeTree(child, output, depth + 1, counter)
            } finally {
                child.recycle()
            }
        }
    }

    private fun appendToLog(content: String) {
        val file = File(filesDir, LOG_FILE)

        if (file.exists() && file.length() > MAX_LOG_BYTES) {
            file.writeText(
                "Older diagnostic captures were cleared because the log reached its size limit.\n"
            )
        }

        file.appendText(content + "\n")
    }

    private fun clean(value: CharSequence?): String {
        if (value == null) return "(null)"

        return value.toString()
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .take(500)
    }

    private fun eventTypeName(type: Int): String {
        return when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                "WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                "WINDOW_CONTENT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_SELECTED -> "VIEW_SELECTED"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
            else -> "OTHER($type)"
        }
    }

    override fun onInterrupt() {
        removeAllOverlays()
        lastCaptureStatus = "Accessibility service interrupted"
    }

    override fun onDestroy() {
        serviceActive = false
        handler.removeCallbacks(rootRetryRunnable)
        removeAllOverlays()
        super.onDestroy()
    }
}
