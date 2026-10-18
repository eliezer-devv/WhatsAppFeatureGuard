package com.example.whatsappfeatureguard

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock

class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val BLOCK_DURATION_MS = 10_000L

        const val PREFS_NAME = "feature_guard_settings"
        const val BLOCK_STATUS = "block_status"
        const val BLOCK_CHANNELS = "block_channels"
    }

    private var blockedUntil = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        if (event.packageName?.toString() != WHATSAPP_PACKAGE) {
            return
        }

        val now = SystemClock.elapsedRealtime()

        // Keep WhatsApp from reopening immediately after a blocked attempt.
        if (now < blockedUntil) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        val preferences = getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        )

        val blockStatus = preferences.getBoolean(BLOCK_STATUS, true)
        val blockChannels = preferences.getBoolean(BLOCK_CHANNELS, true)

        val root = rootInActiveWindow ?: event.source

        if (root == null) {
            return
        }

        val blockedFeature = findBlockedFeature(
            root,
            blockStatus,
            blockChannels
        )

        if (blockedFeature) {
            blockedUntil = now + BLOCK_DURATION_MS
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun findBlockedFeature(
        root: AccessibilityNodeInfo,
        blockStatus: Boolean,
        blockChannels: Boolean
    ): Boolean {
        val label = (
            root.text?.toString()
                ?: root.contentDescription?.toString()
                ?: ""
            ).trim().lowercase()

        // Only act on nodes marked as selected.
        val selected = root.isSelected

        if (selected) {
            if (blockStatus && label == "status") {
                return true
            }

            if (blockChannels && label == "channels") {
                return true
            }
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue

            if (findBlockedFeature(
                    child,
                    blockStatus,
                    blockChannels
                )
            ) {
                return true
            }
        }

        return false
    }

    override fun onInterrupt() {
        // Android interrupted the accessibility service.
    }
}
