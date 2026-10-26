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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        private const val PREFS_NAME = "feature_guard_settings"
        private const val DIAGNOSTIC_MODE = "diagnostic_mode"
        private const val COOLDOWN_UNTIL = "cooldown_until"

        private const val NOTIFICATION_CHANNEL_ID =
            "whatsapp_feature_guard_lockout"
        private const val NOTIFICATION_ID = 7401

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
        var lastDetectionSummary: String =
            "No WhatsApp screen analyzed yet"
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val overlayViews = mutableMapOf<String, TextView>()
    private val overlayRects = mutableMapOf<String, Rect>()

    private var rootRetryCount = 0
    private var serviceActive = false
    private var pendingLockoutReason: String? = null
    private var backPressCount = 0
    private var lastLoggedDetectionSummary = ""

    private val rootRetryRunnable = Runnable {
        if (serviceActive && rootRetryCount < MAX_ROOT_RETRIES) {
            rootRetryCount++
            DiagnosticsLogger.event(
                this,
                "ACCESSIBILITY_ROOT_RETRY",
                "retry=$rootRetryCount; maxRetries=$MAX_ROOT_RETRIES"
            )
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

            val reason =
                pendingLockoutReason ?: return@Runnable

            val root = rootInActiveWindow
            val foregroundPackage = try {
                root?.packageName?.toString()
            } catch (e: Exception) {
                DiagnosticsLogger.failure(
                    this,
                    "BACKOUT_PACKAGE_READ_FAILURE",
                    "exception=${e.javaClass.name}; message=${e.message}"
                )
                null
            } finally {
                try {
                    root?.recycle()
                } catch (_: Exception) {
                }
            }

            if (
                foregroundPackage != null &&
                foregroundPackage != "com.whatsapp"
            ) {
                DiagnosticsLogger.event(
                    this,
                    "BACKOUT_LEFT_WHATSAPP",
                    "reason=$reason; foregroundPackage=$foregroundPackage; " +
                        "backPresses=$backPressCount"
                )
                pendingLockoutReason = null
                beginLockoutCountdown(reason)
                return@Runnable
            }

            if (backPressCount < MAX_BACK_PRESSES) {
                val accepted =
                    performGlobalAction(GLOBAL_ACTION_BACK)
                backPressCount++

                DiagnosticsLogger.event(
                    this,
                    "BACK_ACTION",
                    "reason=$reason; attempt=$backPressCount; " +
                        "androidAcceptedAction=$accepted; " +
                        "foregroundPackage=$foregroundPackage"
                )

                handler.postDelayed(
                    backOutRunnable,
                    BACK_PRESS_INTERVAL_MS
                )
            } else {
                DiagnosticsLogger.event(
                    this,
                    "BACKOUT_MAX_ATTEMPTS",
                    "reason=$reason; attempts=$backPressCount; " +
                        "starting cooldown"
                )
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

        DiagnosticsLogger.deviceHeader(this, force = true)
        DiagnosticsLogger.event(
            this,
            "SERVICE_CONNECTED",
            "Accessibility service connected successfully"
        )

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val until = prefs.getLong(COOLDOWN_UNTIL, 0L)

        if (until > System.currentTimeMillis()) {
            DiagnosticsLogger.event(
                this,
                "COOLDOWN_RESTORED",
                "remainingMs=${until - System.currentTimeMillis()}"
            )
            updateLockoutNotification(until)
        } else if (until != 0L) {
            finishLockout()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!serviceActive || event == null) return

        val eventPackage = event.packageName?.toString()

        if (eventPackage != "com.whatsapp") {
            if (
                eventPackage == "com.android.settings" &&
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(DIAGNOSTIC_MODE, false)
            ) {
                DiagnosticsLogger.event(
                    this,
                    "NON_WHATSAPP_SETTINGS_EVENT",
                    "eventType=${eventTypeName(event.eventType)}; " +
                        "class=${event.className}; " +
                        "windowId=${event.windowId}; " +
                        "Settings UI tree not captured"
                )
            }

            if (
                event.eventType ==
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            ) {
                DiagnosticsLogger.event(
                    this,
                    "LEFT_WHATSAPP_WINDOW",
                    "newPackage=$eventPackage; overlays will be removed"
                )
                removeAllOverlays()
            }

            return
        }

        rootRetryCount = 0
        handler.removeCallbacks(rootRetryRunnable)

        DiagnosticsLogger.event(
            this,
            "WHATSAPP_ACCESSIBILITY_EVENT",
            "eventType=${eventTypeName(event.eventType)}; " +
                "class=${event.className}; windowId=${event.windowId}; " +
                "contentChangeTypes=${event.contentChangeTypes}"
        )

        processCurrentWindow(event)
    }

    private fun processCurrentWindow(
        event: AccessibilityEvent? = null
    ) {
        if (!serviceActive) return

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val blockStatus = BuildConfig.BLOCK_STATUS
        val blockChannels = BuildConfig.BLOCK_CHANNELS
        val overlayStatus = BuildConfig.BLOCK_STATUS
        val overlayChannels = BuildConfig.BLOCK_CHANNELS
        val diagnosticMode =
            prefs.getBoolean(DIAGNOSTIC_MODE, false)

        val now = System.currentTimeMillis()
        val cooldownUntil = prefs.getLong(COOLDOWN_UNTIL, 0L)

        if (now < cooldownUntil) {
            removeAllOverlays()
            updateLockoutNotification(cooldownUntil)
            val accepted = performGlobalAction(GLOBAL_ACTION_HOME)

            DiagnosticsLogger.event(
                this,
                "COOLDOWN_ENFORCEMENT",
                "remainingMs=${cooldownUntil - now}; " +
                    "homeActionAccepted=$accepted"
            )
            return
        } else if (cooldownUntil != 0L) {
            finishLockout()
        }

        if (pendingLockoutReason != null) {
            removeAllOverlays()
            return
        }

        if (
            !blockStatus &&
            !blockChannels &&
            !overlayStatus &&
            !overlayChannels &&
            !diagnosticMode
        ) {
            removeAllOverlays()
            return
        }

        val root = rootInActiveWindow

        if (root == null) {
            DiagnosticsLogger.event(
                this,
                "ACCESSIBILITY_ROOT_MISSING",
                "WhatsApp event received but rootInActiveWindow was null; " +
                    "retryCount=$rootRetryCount"
            )

            if (diagnosticMode) {
                lastCaptureStatus =
                    "WhatsApp event received, but no accessible root"
            }

            if (rootRetryCount < MAX_ROOT_RETRIES) {
                handler.removeCallbacks(rootRetryRunnable)
                handler.postDelayed(
                    rootRetryRunnable,
                    ROOT_RETRY_MS
                )
            } else {
                DiagnosticsLogger.event(
                    this,
                    "ACCESSIBILITY_ROOT_RETRIES_EXHAUSTED",
                    "maxRetries=$MAX_ROOT_RETRIES"
                )
            }
            return
        }

        try {
            val rootPackage = root.packageName?.toString()

            if (rootPackage != "com.whatsapp") {
                DiagnosticsLogger.event(
                    this,
                    "ROOT_PACKAGE_MISMATCH",
                    "expected=com.whatsapp; actual=$rootPackage"
                )
                removeAllOverlays()
                return
            }

            val statusViewerDetected = isStatusViewer(root)
            val channelsScreenDetected = isChannelsScreen(root)
            val updatesListBounds = findUpdatesListBounds(root)
            val channelsHeadingBounds =
                findChannelsHeadingBounds(root)

            updateOverlays(
                root,
                overlayStatus,
                overlayChannels
            )

            lastDetectionSummary = buildString {
                append("Status viewer: ")
                    .append(if (statusViewerDetected) "MATCH" else "no match")
                append("; Channels screen: ")
                    .append(if (channelsScreenDetected) "MATCH" else "no match")
                append("; Updates list bounds: ")
                    .append(if (updatesListBounds != null) "found" else "not found")
                append("; Channels heading bounds: ")
                    .append(if (channelsHeadingBounds != null) "found" else "not found")
                append("; Status overlay shown: ")
                    .append(overlayRects.containsKey("status"))
                append("; Channels overlay shown: ")
                    .append(overlayRects.containsKey("channels"))
                append("; blocking enabled: Status=")
                    .append(blockStatus)
                append(", Channels=")
                    .append(blockChannels)
            }

            if (
                lastDetectionSummary != lastLoggedDetectionSummary
            ) {
                DiagnosticsLogger.event(
                    this,
                    "SCREEN_DETECTION",
                    lastDetectionSummary
                )

                if (diagnosticMode) {
                    lastCaptureStatus = lastDetectionSummary
                }

                lastLoggedDetectionSummary =
                    lastDetectionSummary
            }

            if (diagnosticMode && event != null) {
                DiagnosticsLogger.tree(
                    context = this,
                    event = event,
                    root = root,
                    detectionSummary = lastDetectionSummary
                )
            }

            val statusViewer =
                blockStatus && statusViewerDetected
            val channelsScreen =
                blockChannels && channelsScreenDetected

            if (statusViewer || channelsScreen) {
                val reason =
                    if (statusViewer) "Status" else "Channels"

                DiagnosticsLogger.event(
                    this,
                    "RESTRICTED_SCREEN_MATCH",
                    "reason=$reason; statusViewerDetected=$statusViewerDetected; " +
                        "channelsScreenDetected=$channelsScreenDetected"
                )

                startLockout(reason)
                return
            }
        } catch (e: Exception) {
            lastCaptureStatus =
                "Service error: ${e.javaClass.simpleName}"

            DiagnosticsLogger.failure(
                this,
                "PROCESS_WINDOW_FAILURE",
                "exception=${e.javaClass.name}; message=${e.message}"
            )
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
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
            val accepted = performGlobalAction(GLOBAL_ACTION_HOME)

            DiagnosticsLogger.event(
                this,
                "LOCKOUT_ALREADY_ACTIVE",
                "reason=$reason; until=$existingUntil; " +
                    "homeActionAccepted=$accepted"
            )
            return
        }

        if (pendingLockoutReason != null) return

        pendingLockoutReason = reason
        backPressCount = 0

        DiagnosticsLogger.event(
            this,
            "RESTRICTED_SCREEN_DETECTED",
            "reason=$reason; backPressCount=$backPressCount; " +
                "statusBlocking=${BuildConfig.BLOCK_STATUS}; " +
                "channelsBlocking=${BuildConfig.BLOCK_CHANNELS}"
        )

        removeAllOverlays()
        lastCaptureStatus =
            "$reason opened; backing out of WhatsApp"

        val accepted = performGlobalAction(GLOBAL_ACTION_BACK)

        DiagnosticsLogger.event(
            this,
            "BACK_ACTION",
            "reason=$reason; attempt=1; " +
                "androidAcceptedAction=$accepted"
        )

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

        DiagnosticsLogger.event(
            this,
            "LOCKOUT_STARTED",
            "reason=$reason; durationMs=$COOLDOWN_MS; until=$until"
        )

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putLong(COOLDOWN_UNTIL, until)
            .apply()

        lastCaptureStatus =
            "$reason opened; WhatsApp locked for 10 seconds"

        updateLockoutNotification(until)
        val homeAccepted = performGlobalAction(GLOBAL_ACTION_HOME)

        DiagnosticsLogger.event(
            this,
            "LOCKOUT_HOME_ACTION",
            "reason=$reason; homeActionAccepted=$homeAccepted"
        )
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
                Notification.Builder(
                    this,
                    NOTIFICATION_CHANNEL_ID
                )
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
        } catch (e: SecurityException) {
            lastCaptureStatus =
                "Lockout active, but notification permission is missing"

            DiagnosticsLogger.failure(
                this,
                "LOCKOUT_NOTIFICATION_PERMISSION_FAILURE",
                "exception=${e.javaClass.name}; message=${e.message}"
            )
        } catch (e: Exception) {
            lastCaptureStatus =
                "Notification error: ${e.javaClass.simpleName}"

            DiagnosticsLogger.failure(
                this,
                "LOCKOUT_NOTIFICATION_FAILURE",
                "exception=${e.javaClass.name}; message=${e.message}"
            )
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
        } catch (e: Exception) {
            DiagnosticsLogger.failure(
                this,
                "LOCKOUT_NOTIFICATION_CANCEL_FAILURE",
                "exception=${e.javaClass.name}; message=${e.message}"
            )
        }

        lastCaptureStatus = "10-second lockout finished"

        DiagnosticsLogger.event(
            this,
            "LOCKOUT_FINISHED",
            "Cooldown finished and saved cooldown timestamp cleared"
        )
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
            DiagnosticsLogger.event(
                this,
                "OVERLAY_INVALID_LIST_BOUNDS",
                "listBounds=$list; screenWidth=$screenWidth"
            )
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
            DiagnosticsLogger.event(
                this,
                "OVERLAY_INVALID_BOUNDS",
                "key=$key; label=$label; bounds=$bounds"
            )
            removeOverlay(key)
            return
        }

        val oldBounds = overlayRects[key]
        val existing = overlayViews[key]

        if (existing != null && oldBounds == bounds) {
            DiagnosticsLogger.event(
                this,
                "OVERLAY_UNCHANGED",
                "key=$key; label=$label; bounds=$bounds; tracked=true"
            )
            return
        }

        DiagnosticsLogger.event(
            this,
            "OVERLAY_REQUEST",
            "key=$key; label=$label; bounds=$bounds; " +
                "action=${if (existing == null) "ADD" else "UPDATE"}; " +
                "oldBounds=$oldBounds"
        )

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

            setOnTouchListener { _, motionEvent: MotionEvent ->
                DiagnosticsLogger.event(
                    this@WhatsAppAccessibilityService,
                    "OVERLAY_TOUCH_INTERCEPTED",
                    "key=$key; action=${motionEvent.action}; " +
                        "x=${motionEvent.x}; y=${motionEvent.y}"
                )
                true
            }
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

            DiagnosticsLogger.event(
                this,
                "OVERLAY_SUCCESS",
                "key=$key; label=$label; bounds=$bounds; " +
                    "viewTracked=${overlayViews.containsKey(key)}; " +
                    "rectTracked=${overlayRects.containsKey(key)}; " +
                    "viewWidth=${view.width}; viewHeight=${view.height}"
            )
        } catch (e: Exception) {
            DiagnosticsLogger.failure(
                this,
                "OVERLAY_FAILURE",
                "key=$key; label=$label; requestedBounds=$bounds; " +
                    "exception=${e.javaClass.name}; message=${e.message}"
            )

            lastCaptureStatus =
                "Overlay error: ${e.javaClass.simpleName}"
        }
    }

    private fun removeOverlay(key: String) {
        val previousBounds = overlayRects[key]
        val view = overlayViews.remove(key)
        overlayRects.remove(key)

        if (view == null) return

        DiagnosticsLogger.event(
            this,
            "OVERLAY_REMOVE_REQUEST",
            "key=$key; previousBounds=$previousBounds; viewTracked=true"
        )

        try {
            val windowManager =
                getSystemService(WINDOW_SERVICE) as WindowManager

            windowManager.removeView(view)

            DiagnosticsLogger.event(
                this,
                "OVERLAY_REMOVED",
                "key=$key; previousBounds=$previousBounds; " +
                    "removeViewReturned=true"
            )
        } catch (e: Exception) {
            DiagnosticsLogger.failure(
                this,
                "OVERLAY_REMOVE_FAILURE",
                "key=$key; previousBounds=$previousBounds; " +
                    "exception=${e.javaClass.name}; message=${e.message}"
            )
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
        if (
            node.viewIdResourceName?.contains(
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
                try {
                    child.recycle()
                } catch (_: Exception) {
                }
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

        if (
            text.equals(expected, ignoreCase = true) ||
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
                try {
                    child.recycle()
                } catch (_: Exception) {
                }
            }
        }

        return null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------
    // WHATSAPP FEATURE DETECTION
    // ---------------------------------------------------------------

    private fun isStatusViewer(
        root: AccessibilityNodeInfo
    ): Boolean {
        if (
            containsViewId(root, "playback_progress") ||
            containsViewId(root, "status_playback_image") ||
            containsTextMatching(
                root,
                Regex(
                    "\\bstatus\\s+\\d+\\s+of\\s+\\d+\\b",
                    RegexOption.IGNORE_CASE
                )
            )
        ) {
            return true
        }

        val replyAction =
            containsText(root, "Reply privately") ||
                containsText(root, "Reply to status")

        val statusAction =
            containsText(root, "Mute status") ||
                containsText(root, "Pause status") ||
                containsText(root, "View status")

        return replyAction && statusAction
    }

    private fun isChannelsScreen(
        root: AccessibilityNodeInfo
    ): Boolean {
        if (findUpdatesListBounds(root) != null) return false

        val directoryIds =
            containsViewId(root, "newsletter_directory_row_container") ||
                containsViewId(root, "newsletter_name") ||
                containsViewId(root, "directory_category_list")

        val channelActions =
            containsText(root, "FOLLOW CHANNEL") ||
                containsText(root, "Follow channel") ||
                containsText(root, "Find channels") ||
                containsText(root, "Discover channels") ||
                containsText(root, "Search channels")

        return directoryIds ||
            channelActions ||
            isChannelsDiscoveryScreen(root)
    }

    private fun isChannelsDiscoveryScreen(
        root: AccessibilityNodeInfo
    ): Boolean {
        if (findUpdatesListBounds(root) != null) return false
        if (containsViewId(root, "directory_category_list")) return true

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

    private fun findUpdatesListBounds(
        root: AccessibilityNodeInfo
    ): Rect? {
        val ids = listOf(
            "updates_list",
            "updates_list_container",
            "status_updates_list"
        )

        for (id in ids) {
            val bounds = findViewIdBounds(root, id)
            if (bounds != null) return bounds
        }

        return null
    }

    private fun findChannelsHeadingBounds(
        root: AccessibilityNodeInfo
    ): Rect? {
        val labels = listOf(
            "Channels",
            "FOLLOW CHANNEL",
            "Follow channel",
            "Find channels",
            "Discover channels",
            "Explore channels"
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
        if (
            regex.containsMatchIn(node.text?.toString().orEmpty()) ||
            regex.containsMatchIn(
                node.contentDescription?.toString().orEmpty()
            )
        ) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (containsTextMatching(child, regex)) return true
            } finally {
                try {
                    child.recycle()
                } catch (_: Exception) {
                }
            }
        }

        return false
    }

    private fun containsText(
        node: AccessibilityNodeInfo,
        expected: String
    ): Boolean {
        if (
            node.text?.toString()?.contains(
                expected,
                ignoreCase = true
            ) == true
        ) {
            return true
        }

        if (
            node.contentDescription?.toString()?.contains(
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
                try {
                    child.recycle()
                } catch (_: Exception) {
                }
            }
        }

        return false
    }

    private fun containsViewId(
        node: AccessibilityNodeInfo,
        expected: String
    ): Boolean {
        if (
            node.viewIdResourceName?.contains(
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
                try {
                    child.recycle()
                } catch (_: Exception) {
                }
            }
        }

        return false
    }

    private fun eventTypeName(type: Int): String = when (type) {
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

    override fun onInterrupt() {
        serviceActive = false
        isServiceConnected = false

        handler.removeCallbacks(rootRetryRunnable)
        handler.removeCallbacks(backOutRunnable)
        handler.removeCallbacks(lockoutCountdownRunnable)

        removeAllOverlays()
        lastCaptureStatus = "Accessibility service interrupted"

        DiagnosticsLogger.event(
            this,
            "SERVICE_INTERRUPTED",
            "Accessibility service received onInterrupt"
        )
    }

    override fun onDestroy() {
        serviceActive = false
        isServiceConnected = false

        handler.removeCallbacks(rootRetryRunnable)
        handler.removeCallbacks(backOutRunnable)
        handler.removeCallbacks(lockoutCountdownRunnable)

        removeAllOverlays()

        DiagnosticsLogger.event(
            this,
            "SERVICE_DESTROYED",
            "Accessibility service destroyed"
        )

        super.onDestroy()
    }
}
