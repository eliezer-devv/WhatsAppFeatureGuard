
package com.example.whatsappfeatureguard

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock

class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WhatsAppFeatureGuard"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val BLOCK_DURATION_MS = 10_000L

        const val PREFS_NAME = "feature_guard_settings"
        const val BLOCK_STATUS = "block_status"
        const val BLOCK_CHANNELS = "block_channels"
    }

    private var blockedUntil = 0L
    private var lastBlockTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        if (event.packageName?.toString() != WHATSAPP_PACKAGE) {
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val blockUpdates = prefs.getBoolean(BLOCK_STATUS, true)
        val blockChannels = prefs.getBoolean(BLOCK_CHANNELS, true)

        val now = SystemClock.elapsedRealtime()

        if (now < blockedUntil) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        val root = rootInActiveWindow ?: event.source ?: return

        val clicked = event.eventType ==
            AccessibilityEvent.TYPE_VIEW_CLICKED

        val matched = findBlockedFeature(
            root = root,
            blockUpdates = blockUpdates,
            blockChannels = blockChannels,
            clicked = clicked,
            selectedAncestor = false
        )

        if (matched) {
            blockedUntil = now + BLOCK_DURATION_MS
            lastBlockTime = now

            Log.i(TAG, "Blocked a configured WhatsApp section")
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun findBlockedFeature(
        root: AccessibilityNodeInfo,
        blockUpdates: Boolean,
        blockChannels: Boolean,
        clicked: Boolean,
        selectedAncestor: Boolean
    ): Boolean {
        val text = root.text?.toString().orEmpty()
        val description = root.contentDescription?.toString().orEmpty()

        val label = normalize("$text $description")

        val isUpdates = label.contains("updates") ||
            label.contains("status")

        val isChannels = label.contains("channels")

        val selectedHere = root.isSelected || root.isChecked
        val selected = selectedHere || selectedAncestor

        // A click on the named section is an entry attempt.
        // Otherwise, require the section or its parent to be selected.
        val entryAttempt = clicked && root.isClickable

        if (blockUpdates && isUpdates &&
            (entryAttempt || selected)
        ) {
            Log.i(TAG, "Updates/Status entry detected")
            return true
        }

        if (blockChannels && isChannels &&
            (entryAttempt || selected)
        ) {
            Log.i(TAG, "Channels entry detected")
            return true
        }

        val childSelectedAncestor = selectedAncestor || selectedHere

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue

            if (findBlockedFeature(
                    child,
                    blockUpdates,
                    blockChannels,
                    clicked,
                    childSelectedAncestor
                )
            ) {
                return true
            }
        }

        return false
    }

    private fun normalize(value: String): String {
        return value.trim().lowercase()
            .replace(Regex("\\s+"), " ")
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility service interrupted")
    }
}
