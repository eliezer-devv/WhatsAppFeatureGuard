
package com.example.whatsappfeatureguard

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WhatsAppFeatureGuard"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val BLOCK_DURATION_MS = 10_000L
        private const val CHANNEL_ID = "whatsapp_block_countdown"
        private const val NOTIFICATION_ID = 1001

        const val PREFS_NAME = "feature_guard_settings"
        const val BLOCK_STATUS = "block_status"
        const val BLOCK_CHANNELS = "block_channels"
    }

    private var blockedUntil = 0L

    private val handler = Handler(Looper.getMainLooper())

    private val cancelNotification = Runnable {
        getSystemService(NotificationManager::class.java)
            .cancel(NOTIFICATION_ID)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        createNotificationChannel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        if (event.packageName?.toString() != WHATSAPP_PACKAGE) return

        val now = SystemClock.elapsedRealtime()

        // Keep enforcing the original cooldown without restarting it.
        if (now < blockedUntil) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val blockUpdates = prefs.getBoolean(BLOCK_STATUS, true)
        val blockChannels = prefs.getBoolean(BLOCK_CHANNELS, true)

        val root = rootInActiveWindow ?: event.source ?: return

        val clicked = event.eventType ==
            AccessibilityEvent.TYPE_VIEW_CLICKED

        if (findBlockedFeature(
                root,
                blockUpdates,
                blockChannels,
                clicked,
                false
            )
        ) {
            blockedUntil = now + BLOCK_DURATION_MS

            showCountdownNotification()

            handler.removeCallbacks(cancelNotification)
            handler.postDelayed(
                cancelNotification,
                BLOCK_DURATION_MS
            )

            Log.i(TAG, "Restricted WhatsApp section detected")
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun showCountdownNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Notification permission has not been granted")
            return
        }

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("WhatsApp temporarily blocked")
            .setContentText("Available in 10 seconds")
            .setWhen(System.currentTimeMillis() + BLOCK_DURATION_MS)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WhatsApp block countdown",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when WhatsApp is available again"
                setShowBadge(false)
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun findBlockedFeature(
        root: AccessibilityNodeInfo,
        blockUpdates: Boolean,
        blockChannels: Boolean,
        clicked: Boolean,
        selectedAncestor: Boolean
    ): Boolean {
        val label = normalize(
            "${root.text?.toString().orEmpty()} " +
            root.contentDescription?.toString().orEmpty()
        )

        val selectedHere = root.isSelected || root.isChecked
        val selected = selectedHere || selectedAncestor

        val isUpdates = label.contains("updates") ||
            label.contains("status")
        val isChannels = label.contains("channels")
        val entryAttempt = clicked && root.isClickable

        if (blockUpdates && isUpdates && (entryAttempt || selected)) {
            return true
        }

        if (blockChannels && isChannels && (entryAttempt || selected)) {
            return true
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue

            if (findBlockedFeature(
                    child,
                    blockUpdates,
                    blockChannels,
                    clicked,
                    selected || selectedHere
                )
            ) {
                return true
            }
        }

        return false
    }

    private fun normalize(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        handler.removeCallbacks(cancelNotification)
        super.onDestroy()
    }
}
