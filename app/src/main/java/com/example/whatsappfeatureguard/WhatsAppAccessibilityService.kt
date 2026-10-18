package com.example.whatsappfeatureguard

import android.accessibilityservice.AccessibilityService
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
        private const val LOG_FILE = "whatsapp_accessibility_log.txt"

        private const val MAX_LOG_BYTES = 2 * 1024 * 1024
        private const val MAX_NODES = 500
        private const val MAX_DEPTH = 30

        @Volatile
        var lastCaptureStatus: String = "Waiting for WhatsApp"
            private set
    }

    private var lastSnapshot = ""
    private var lastCaptureTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        lastCaptureStatus = "Accessibility service connected"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != "com.whatsapp") return

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Diagnostic mode only: never block or redirect the user.
        if (!prefs.getBoolean(DIAGNOSTIC_MODE, false)) return

        val now = System.currentTimeMillis()

        // Avoid capturing the same screen repeatedly at high frequency.
        if (now - lastCaptureTime < 400) return

        val root = rootInActiveWindow ?: run {
            lastCaptureStatus = "WhatsApp detected, but no accessible root"
            return
        }

        try {
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
            appendNodeTree(
                node = root,
                output = output,
                depth = 0,
                counter = counter
            )

            val snapshot = output.toString()

            // Skip identical consecutive trees.
            if (snapshot == lastSnapshot) return

            appendToLog(snapshot)

            lastSnapshot = snapshot
            lastCaptureTime = now
            lastCaptureStatus = "Captured ${counter[0]} UI elements at $timestamp"
        } catch (e: Exception) {
            lastCaptureStatus = "Capture error: ${e.javaClass.simpleName}"
        } finally {
            root.recycle()
        }
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
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        output.append(indent)
            .append("NODE ")
            .append(counter[0])
            .appendLine()

        output.append(indent).appendLine("  class=${clean(node.className)}")
        output.append(indent).appendLine("  text=${clean(node.text)}")
        output.append(indent).appendLine("  contentDescription=${clean(node.contentDescription)}")
        output.append(indent).appendLine("  viewId=${clean(node.viewIdResourceName)}")
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
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
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
}
