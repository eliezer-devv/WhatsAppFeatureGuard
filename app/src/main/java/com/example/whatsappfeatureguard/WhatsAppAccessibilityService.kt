package com.example.whatsappfeatureguard

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
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
    private const val COOLDOWN_UNTIL = "cooldown_until"
    private const val LOG_FILE = "whatsapp_accessibility_log.txt"

    private const val NOTIFICATION_CHANNEL_ID =
        "whatsapp_feature_guard_lockout"
    private const val NOTIFICATION_ID = 7401

    private const val MAX_LOG_BYTES = 2 * 1024 * 1024
    private const val MAX_NODES = 500
    private const val MAX_DEPTH = 30
    private const val COOLDOWN_MS = 10_000L
    private const val ROOT_RETRY_MS = 200L
    private const val MAX_ROOT_RETRIES = 5

    private const val BACK_PRESS_INTERVAL_MS = 300L
    private const val MAX_BACK_PRESSES = 6

    @Volatile
    var lastCaptureStatus: String = "Waiting for WhatsApp"
        private set

    @Volatile
    var isServiceConnected: Boolean = false
        private set

    @Volatile
    var lastDetectionSummary: String = "No WhatsApp screen analyzed yet"
        private set
}

private val handler = Handler(Looper.getMainLooper())
private val overlayViews = mutableMapOf<String, TextView>()
private val overlayRects = mutableMapOf<String, Rect>()

private var lastSnapshot = ""
private var lastLoggedDetectionSummary = ""
private var lastCaptureTime = 0L
private var rootRetryCount = 0
private var serviceActive = false

private var pendingLockoutReason: String? = null
private var backPressCount = 0

private val rootRetryRunnable = Runnable {
    if (serviceActive && rootRetryCount < MAX_ROOT_RETRIES) {
        rootRetryCount++
        processCurrentWindow()
    }
}

private lateinit var backOutRunnable: Runnable

private val lockoutCountdownRunnable = Runnable {
    if (serviceActive) {
        val prefs =
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val until = prefs.getLong(COOLDOWN_UNTIL, 0L)

        if (until > System.currentTimeMillis()) {
            updateLockoutNotification(until)
        } else {
            finishLockout()
        }
    }
}

override fun onCreate() {
    super.onCreate()

    backOutRunnable = Runnable {
        if (!serviceActive) return@Runnable

        val reason = pendingLockoutReason ?: return@Runnable

        val root = rootInActiveWindow
        val foregroundPackage = try {
            root?.packageName?.toString()
        } catch (_: Exception) {
            null
        } finally {
            root?.recycle()
        }

        if (foregroundPackage != null &&
            foregroundPackage != "com.whatsapp"
        ) {
            pendingLockoutReason = null
            beginLockoutCountdown(reason)
            return@Runnable
        }

        if (backPressCount < MAX_BACK_PRESSES) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            backPressCount++

            handler.postDelayed(
                backOutRunnable,
                BACK_PRESS_INTERVAL_MS
            )
        } else {
            pendingLockoutReason = null
            beginLockoutCountdown(reason)
        }
    }
}

override fun onServiceConnected() {
    super.onServiceConnected()
    serviceActive = true
    isServiceConnected = true
    lastCaptureStatus = "Accessibility service connected"

    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    val until = prefs.getLong(COOLDOWN_UNTIL, 0L)

    if (until > System.currentTimeMillis()) {
        updateLockoutNotification(until)
    } else if (until != 0L) {
        finishLockout()
    }
}

override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (!serviceActive || event == null) return

    val eventPackage = event.packageName?.toString()

    // Observe Android Settings for diagnostics only.
    // Settings controls are not covered or modified.
    if (eventPackage != "com.whatsapp") {
        if (eventPackage == "com.android.settings") {
            val diagnosticMode = getSharedPreferences(
                PREFS_NAME,
                MODE_PRIVATE
            ).getBoolean(DIAGNOSTIC_MODE, false)

            if (diagnosticMode) {
                val root = rootInActiveWindow

                if (root != null) {
                    try {
                        captureScreen(event, root)
                    } catch (e: Exception) {
                        lastCaptureStatus =
                            "Settings diagnostic error: " +
                                e.javaClass.simpleName
                    } finally {
                        root.recycle()
                    }
                } else {
                    lastCaptureStatus =
                        "Settings event received, but root is null"
                }
            }
        }

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
    // Feature selection is fixed by the build flavor, independent of saved preferences.
    val blockStatus = BuildConfig.BLOCK_STATUS
    val blockChannels = BuildConfig.BLOCK_CHANNELS
    val overlayStatus = BuildConfig.BLOCK_STATUS
    val overlayChannels = BuildConfig.BLOCK_CHANNELS
    val diagnosticMode = prefs.getBoolean(DIAGNOSTIC_MODE, false)

    val now = System.currentTimeMillis()
    val cooldownUntil = prefs.getLong(COOLDOWN_UNTIL, 0L)

    if (now < cooldownUntil) {
        removeAllOverlays()
        updateLockoutNotification(cooldownUntil)
        performGlobalAction(GLOBAL_ACTION_HOME)
        return
    } else if (cooldownUntil != 0L) {
        finishLockout()
    }

    if (pendingLockoutReason != null) {
        removeAllOverlays()
        return
    }

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

        val statusViewerDetected = isStatusViewer(root)
        val channelsScreenDetected = isChannelsScreen(root)
        val updatesListBounds = findUpdatesListBounds(root)
        val channelsHeadingBounds = findChannelsHeadingBounds(root)

        updateOverlays(root, overlayStatus, overlayChannels)

        lastDetectionSummary = buildString {
            append("Status viewer: ").append(if (statusViewerDetected) "MATCH" else "no match")
            append("; Channels screen: ").append(if (channelsScreenDetected) "MATCH" else "no match")
            append("; Updates list bounds: ").append(if (updatesListBounds != null) "found" else "not found")
            append("; Channels heading bounds: ").append(if (channelsHeadingBounds != null) "found" else "not found")
            append("; Status overlay shown: ").append(overlayRects.containsKey("status"))
            append("; Channels overlay shown: ").append(overlayRects.containsKey("channels"))
            append("; blocking enabled: Status=").append(blockStatus)
            append(", Channels=").append(blockChannels)
        }
        if (diagnosticMode && lastDetectionSummary != lastLoggedDetectionSummary) {
            appendToLog("\n[DETECTION ${Date()}] $lastDetectionSummary\n")
            lastLoggedDetectionSummary = lastDetectionSummary
        }
        if (diagnosticMode && event != null) captureScreen(event, root)

        val statusViewer = blockStatus && statusViewerDetected
        val channelsScreen = blockChannels && channelsScreenDetected

        if (statusViewer || channelsScreen) {
            val reason = if (statusViewer) "Status" else "Channels"
            startLockout(reason)
            return
        }
    } catch (e: Exception) {
        lastCaptureStatus =
            "Service error: ${e.javaClass.simpleName}"
    } finally {
        root.recycle()
    }
}

// ---------------------------------------------------------------
// REPEATED BACK PRESSES AND TEN-SECOND LOCKOUT
// ---------------------------------------------------------------

private fun startLockout(reason: String) {
    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    val existingUntil = prefs.getLong(COOLDOWN_UNTIL, 0L)

    if (existingUntil > System.currentTimeMillis()) {
        removeAllOverlays()
        updateLockoutNotification(existingUntil)
        performGlobalAction(GLOBAL_ACTION_HOME)
        return
    }

    if (pendingLockoutReason != null) return

    pendingLockoutReason = reason
    backPressCount = 0
    removeAllOverlays()

    lastCaptureStatus = "$reason opened; backing out of WhatsApp"

    performGlobalAction(GLOBAL_ACTION_BACK)
    backPressCount++

    handler.removeCallbacks(backOutRunnable)
    handler.postDelayed(
        backOutRunnable,
        BACK_PRESS_INTERVAL_MS
    )
}

private fun beginLockoutCountdown(reason: String) {
    if (!serviceActive) return

    handler.removeCallbacks(backOutRunnable)
    pendingLockoutReason = null
    removeAllOverlays()

    val until = System.currentTimeMillis() + COOLDOWN_MS

    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        .edit()
        .putLong(COOLDOWN_UNTIL, until)
        .apply()

    lastCaptureStatus =
        "$reason opened; WhatsApp locked for 10 seconds"

    updateLockoutNotification(until)
    performGlobalAction(GLOBAL_ACTION_HOME)
}

private fun createLockoutNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager =
            getSystemService(NOTIFICATION_SERVICE)
                as NotificationManager

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "WhatsApp lockout countdown",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description =
                "Shows the remaining time in a temporary WhatsApp lockout"
            setShowBadge(false)
        }

        manager.createNotificationChannel(channel)
    }
}

private fun updateLockoutNotification(until: Long) {
    handler.removeCallbacks(lockoutCountdownRunnable)

    val remainingMs = until - System.currentTimeMillis()

    if (remainingMs <= 0L) {
        finishLockout()
        return
    }

    val secondsRemaining =
        ((remainingMs + 999L) / 1000L).toInt()

    try {
        createLockoutNotificationChannel()

        val manager =
            getSystemService(NOTIFICATION_SERVICE)
                as NotificationManager

        val builder = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setPriority(Notification.PRIORITY_LOW)
        }

        val notification = builder
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("WhatsApp temporarily locked")
            .setContentText(
                "WhatsApp will unlock in $secondsRemaining " +
                    if (secondsRemaining == 1) "second" else "seconds"
            )
            .setSubText("Restricted feature opened")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setProgress(
                10,
                (10 - secondsRemaining).coerceIn(0, 10),
                false
            )
            .build()

        manager.notify(NOTIFICATION_ID, notification)

        lastCaptureStatus =
            "Lockout active: $secondsRemaining seconds remaining"
    } catch (_: SecurityException) {
        lastCaptureStatus =
            "Lockout active, but notification permission is missing"
    } catch (e: Exception) {
        lastCaptureStatus =
            "Notification error: ${e.javaClass.simpleName}"
    }

    handler.postDelayed(
        lockoutCountdownRunnable,
        minOf(1000L, remainingMs)
    )
}

private fun finishLockout() {
    handler.removeCallbacks(lockoutCountdownRunnable)

    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        .edit()
        .putLong(COOLDOWN_UNTIL, 0L)
        .apply()

    try {
        val manager =
            getSystemService(NOTIFICATION_SERVICE)
                as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    } catch (_: Exception) {
        // Ignore notification cleanup errors.
    }

    lastCaptureStatus = "10-second lockout finished"
}

// ---------------------------------------------------------------
// WHATSAPP OVERLAYS
// ---------------------------------------------------------------

private fun updateOverlays(
    root: AccessibilityNodeInfo,
    overlayStatus: Boolean,
    overlayChannels: Boolean
) {
    val updatesList = findUpdatesListBounds(root)
    val channelsStart = findChannelsHeadingBounds(root)

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
            // Android may already have removed the view.
        }
    }
}

private fun removeAllOverlays() {
    removeOverlay("status")
    removeOverlay("channels")
}

// ---------------------------------------------------------------
// ACCESSIBILITY NODE SEARCH
// ---------------------------------------------------------------

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
// WHATSAPP FEATURE DETECTION
// ---------------------------------------------------------------

private fun isStatusViewer(root: AccessibilityNodeInfo): Boolean {
    // Use stable IDs when available, plus a flexible counter matcher. The
    // counter no longer assumes WhatsApp only displays Status 1 through 4.
    if (containsViewId(root, "playback_progress") ||
        containsViewId(root, "status_playback_image") ||
        containsTextMatching(root, Regex("\\bstatus\\s+\\d+\\s+of\\s+\\d+\\b", RegexOption.IGNORE_CASE))
    ) return true

    // Fallback for UI revisions that remove the counter but expose status-viewer
    // actions. Require multiple clues to reduce false positives in normal chats.
    val replyAction = containsText(root, "Reply privately") ||
        containsText(root, "Reply to status")
    val statusAction = containsText(root, "Mute status") ||
        containsText(root, "Pause status") ||
        containsText(root, "View status")
    return replyAction && statusAction
}

private fun isChannelsScreen(root: AccessibilityNodeInfo): Boolean {
    if (findUpdatesListBounds(root) != null) return false

    val directoryIds = containsViewId(root, "newsletter_directory_row_container") ||
        containsViewId(root, "newsletter_name") ||
        containsViewId(root, "directory_category_list")
    val channelActions = containsText(root, "FOLLOW CHANNEL") ||
        containsText(root, "Follow channel") ||
        containsText(root, "Find channels") ||
        containsText(root, "Discover channels") ||
        containsText(root, "Search channels")
    return directoryIds || channelActions || isChannelsDiscoveryScreen(root)
}

private fun isChannelsDiscoveryScreen(root: AccessibilityNodeInfo): Boolean {
    if (findUpdatesListBounds(root) != null) return false
    if (containsViewId(root, "directory_category_list")) return true

    val exploreHeading = containsText(root, "Explore channels") ||
        containsText(root, "Explore")
    val discoveryContent = containsText(root, "Recommended") ||
        containsText(root, "Popular") ||
        containsText(root, "Verified") ||
        containsText(root, "Search channels") ||
        containsText(root, "Find channels") ||
        containsText(root, "Discover channels")
    return exploreHeading && discoveryContent
}

private fun findUpdatesListBounds(root: AccessibilityNodeInfo): Rect? {
    val ids = listOf("updates_list", "updates_list_container", "status_updates_list")
    for (id in ids) {
        val bounds = findViewIdBounds(root, id)
        if (bounds != null) return bounds
    }
    return null
}

private fun findChannelsHeadingBounds(root: AccessibilityNodeInfo): Rect? {
    val labels = listOf(
        "Channels", "FOLLOW CHANNEL", "Follow channel",
        "Find channels", "Discover channels", "Explore channels"
    )
    for (label in labels) {
        val bounds = findLabelBounds(root, label)
        if (bounds != null) return bounds
    }
    return null
}

private fun containsTextMatching(
    node: AccessibilityNodeInfo,
    regex: Regex
): Boolean {
    if (regex.containsMatchIn(node.text?.toString().orEmpty()) ||
        regex.containsMatchIn(node.contentDescription?.toString().orEmpty())
    ) return true
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        try {
            if (containsTextMatching(child, regex)) return true
        } finally {
            child.recycle()
        }
    }
    return false
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

// ---------------------------------------------------------------
// DIAGNOSTIC LOGGING
// ---------------------------------------------------------------

private fun captureScreen(
    event: AccessibilityEvent,
    root: AccessibilityNodeInfo
) {
    val now = System.currentTimeMillis()

    // Keep the original rate limit for repeated events.
    if (now - lastCaptureTime < 400) return

    val output = StringBuilder()
    val timestamp = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss.SSS",
        Locale.US
    ).format(Date(now))

    val packageName = event.packageName?.toString() ?: "(null)"
    val rootPackage = root.packageName?.toString() ?: "(null)"
    val nodeCount = intArrayOf(0)
    
    output.appendLine()
    output.appendLine("==================================================")
    output.appendLine("DETAILED CAPTURE: $timestamp")
    output.appendLine("PACKAGE: $packageName")
    output.appendLine("ROOT_PACKAGE: $rootPackage")
    output.appendLine("CLASS: ${clean(event.className)}")
    output.appendLine("EVENT_TYPE: ${eventTypeName(event.eventType)}")
    output.appendLine("WINDOW_ID: ${event.windowId}")
    output.appendLine("ROOT_WINDOW_ID: ${root.windowId}")
    output.appendLine("CONTENT_CHANGE_TYPES: ${event.contentChangeTypes}")
    output.appendLine("SOURCE_TEXT: ${clean(event.text.joinToString(" | "))}")
    output.appendLine("ROOT_AVAILABLE: true")
    output.appendLine("==================================================")

    appendNodeTree(
        node = root,
        output = output,
        depth = 0,
        counter = nodeCount
    )

    output.appendLine()
    output.appendLine("========== CAPTURE SUMMARY ==========")
    output.appendLine("TOTAL_NODES: ${nodeCount[0]}")
    output.appendLine("DETECTION_SUMMARY: $lastDetectionSummary")
    output.appendLine("PACKAGE_IS_SETTINGS: ${packageName == "com.android.settings"}")
    output.appendLine("=====================================")

    val snapshot = output.toString()

    // Avoid writing identical consecutive trees, but allow the
    // summary and event details to be refreshed when they change.
    if (snapshot == lastSnapshot) return

    appendToLog(snapshot)
    lastSnapshot = snapshot
    lastCaptureTime = now

    lastCaptureStatus =
        "Captured ${nodeCount[0]} UI elements"
}

private fun appendNodeTree(
    node: AccessibilityNodeInfo,
    output: StringBuilder,
    depth: Int,
    counter: IntArray
) {
    if (counter[0] >= MAX_NODES || depth > MAX_DEPTH) return

    counter[0]++

    val text = node.text?.toString()
    val description = node.contentDescription?.toString()
    val viewId = try {
        node.viewIdResourceName
    } catch (_: Exception) {
        null
    }

    val indent = "  ".repeat(depth.coerceAtMost(MAX_DEPTH))
    val bounds = Rect()
    node.getBoundsInScreen(bounds)

    output.append(indent).append("NODE ")
        .append(counter[0]).appendLine()
    output.append(indent).appendLine("  class=${clean(node.className)}")
    output.append(indent).appendLine("  text=${clean(text)}")
    output.append(indent).appendLine(
        "  contentDescription=${clean(description)}"
    )
    output.append(indent).appendLine("  viewId=${clean(viewId)}")
    output.append(indent).appendLine("  clickable=${node.isClickable}")
    output.append(indent).appendLine("  selected=${node.isSelected}")
    output.append(indent).appendLine("  checkable=${node.isCheckable}")
    output.append(indent).appendLine("  checked=${node.isChecked}")
    output.append(indent).appendLine("  enabled=${node.isEnabled}")
    output.append(indent).appendLine("  focusable=${node.isFocusable}")
    output.append(indent).appendLine("  focused=${node.isFocused}")
    output.append(indent).appendLine("  scrollable=${node.isScrollable}")
    output.append(indent).appendLine("  childCount=${node.childCount}")
    output.append(indent).appendLine("  bounds=$bounds")

    for (i in 0 until node.childCount) {
        if (counter[0] >= MAX_NODES) {
            output.appendLine("${indent}  [MAX_NODES reached]")
            break
        }

        val child = try {
            node.getChild(i)
        } catch (e: Exception) {
            output.appendLine(
                "${indent}  CHILD $i ERROR: ${e.javaClass.simpleName}"
            )
            null
        } ?: continue

        try {
            appendNodeTree(
                node = child,
                output = output,
                depth = depth + 1,
                counter = counter
            )
        } catch (e: Exception) {
            output.appendLine(
                "${indent}  CHILD $i ERROR: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        } finally {
            child.recycle()
        }
    }
}

private fun appendToLog(content: String) {
    val file = File(filesDir, LOG_FILE)

    try {
        if (file.exists() && file.length() > MAX_LOG_BYTES) {
            file.writeText(
                "Older diagnostic captures were cleared because " +
                    "the log reached its size limit.\n"
            )
        }

        file.appendText(content + "\n")
    } catch (e: Exception) {
        lastCaptureStatus =
            "Log write failed: ${e.javaClass.simpleName}: ${e.message}"
    }
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

        AccessibilityEvent.TYPE_VIEW_SELECTED ->
            "VIEW_SELECTED"

        AccessibilityEvent.TYPE_VIEW_CLICKED ->
            "VIEW_CLICKED"

        AccessibilityEvent.TYPE_VIEW_FOCUSED ->
            "VIEW_FOCUSED"

        AccessibilityEvent.TYPE_VIEW_SCROLLED ->
            "VIEW_SCROLLED"

        AccessibilityEvent.TYPE_WINDOWS_CHANGED ->
            "WINDOWS_CHANGED"

        else -> "OTHER($type)"
    }
}

override fun onInterrupt() {
    isServiceConnected = false
    removeAllOverlays()
    lastCaptureStatus = "Accessibility service interrupted"
}

override fun onDestroy() {
    serviceActive = false
    isServiceConnected = false
    handler.removeCallbacks(rootRetryRunnable)
    handler.removeCallbacks(backOutRunnable)
    handler.removeCallbacks(lockoutCountdownRunnable)
    removeAllOverlays()
    super.onDestroy()
}

}
