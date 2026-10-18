package com.example.whatsappfeatureguard

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
        if (event.packageName?.toString() != "com.whatsapp") return

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
        val diagnosticMode = prefs.getBoolean(DIAGNOSTIC_MODE, false)

        if (!blockStatus && !blockChannels && !diagnosticMode) return

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
            // A retry must not act on another app's accessibility window.
            if (root.packageName?.toString() != "com.whatsapp") {
                return
            }

            if (diagnosticMode && event != null) {
                captureScreen(event, root)
            }

            val now = System.currentTimeMillis()
            val cooldownUntil = prefs.getLong(COOLDOWN_UNTIL, 0L)

            // During cooldown, attempt to keep WhatsApp on its Chats screen.
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

    private fun startCooldown() {
        val until = System.currentTimeMillis() + COOLDOWN_MS

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putLong(COOLDOWN_UNTIL, until)
            .apply()

        lastCaptureStatus = "Restricted feature detected; cooldown started"
    }

    private fun isStatusRowClick(event: AccessibilityEvent): Boolean {
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

    private fun isStatusViewer(root: AccessibilityNodeInfo): Boolean {
        return containsViewId(root, "playback_progress") ||
            containsText(root, "Status 1 of") ||
            containsText(root, "Status 2 of") ||
            containsText(root, "Status 3 of") ||
            containsText(root, "Status 4 of")
    }

    private fun isChannelsScreen(root: AccessibilityNodeInfo): Boolean {
        return containsText(root, "FOLLOW CHANNEL") ||
            containsText(root, "Follow channel") ||
            containsText(root, "Find channels") ||
            containsText(root, "Discover channels")
    }

    private fun isChatsScreen(root: AccessibilityNodeInfo): Boolean {
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
        ) {
            return true
        }

        if (node.contentDescription?.toString()?.contains(
                expected,
                ignoreCase = true
            ) == true
        ) {
            return true
        }

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
        ) {
            return true
        }

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
            if (root.packageName?.toString() != "com.whatsapp") {
                return
            }

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
        val nodeText = node.text?.toString().orEmpty()
        val description =
            node.contentDescription?.toString().orEmpty()

        if (nodeText.equals(label, ignoreCase = true) ||
            description.equals(label, ignoreCase = true)
        ) {
            if (node.isClickable &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }

            val parent = node.parent
            if (parent != null) {
                try {
                    if (parent.isClickable &&
                        parent.performAction(
                            AccessibilityNodeInfo.ACTION_CLICK
                        )
                    ) {
                        return true
                    }
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
        lastCaptureStatus = "Accessibility service interrupted"
    }

    override fun onDestroy() {
        serviceActive = false
        handler.removeCallbacks(rootRetryRunnable)
        super.onDestroy()
    }
}
