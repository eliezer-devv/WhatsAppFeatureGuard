package com.example.whatsappfeatureguard

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {

    companion object {
        private const val PREFS_NAME = "feature_guard_settings"
        private const val DIAGNOSTIC_MODE = "diagnostic_mode"
        private const val COOLDOWN_UNTIL = "cooldown_until"
        private const val LOG_FILE = "whatsapp_accessibility_log.txt"
        private const val PREVIOUS_LOG_FILE =
            "whatsapp_accessibility_log.previous.txt"
        private const val EXPORT_LOG_FILE =
            "whatsapp_diagnostic_export.txt"
        private const val REQUEST_NOTIFICATIONS = 1001

        private const val TEST_BLOCK_STATUS = "test_block_status"
        private const val TEST_BLOCK_CHANNELS = "test_block_channels"
        private const val TEST_BLOCK_CHANNELS_DISCOVERY =
            "test_block_channels_discovery"
        private const val TEST_OVERLAY_STATUS = "test_overlay_status"
        private const val TEST_OVERLAY_CHANNELS = "test_overlay_channels"
        private const val TEST_LOCKOUT_ENABLED = "test_lockout_enabled"
    }

    private lateinit var statusText: TextView
    private lateinit var diagnosticText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }

        buildScreen()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) refreshStatus()
    }

    private fun buildScreen() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(0xFFF7F7F7.toInt())
        }

        val scroll = ScrollView(this)
        scroll.addView(outer)

        val title = TextView(this).apply {
            text = "WhatsApp Feature Guard"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF202020.toInt())
        }
        outer.addView(title, matchWrap())

        val explanation = TextView(this).apply {
            text = if (BuildConfig.CUSTOM_TESTING) {
                "Custom Testing mode: use the independent switches below to " +
                    "test each restriction. All six switches start OFF. " +
                    "Changes are saved on this device. Use this screen to " +
                    "enable Accessibility and reduce battery restrictions."
            } else {
                "This build has a fixed feature configuration. " +
                    "Use this screen to enable Accessibility and reduce battery " +
                    "restrictions. Android does not allow an ordinary app to " +
                    "silently re-enable its own Accessibility service."
            }
            textSize = 16f
            setTextColor(0xFF333333.toInt())
            setPadding(0, dp(12), 0, dp(16))
        }
        outer.addView(explanation, matchWrap())

        val featureMode = TextView(this).apply {
            text = when {
                BuildConfig.CUSTOM_TESTING ->
                    "Build mode: Custom Testing — configure each feature below."
                BuildConfig.BLOCK_STATUS ->
                    "Build mode: Status and Channels blocked, with both overlays."
                else ->
                    "Build mode: Channels blocked, with the Channels overlay. Status remains accessible."
            }
            textSize = 16f
            setTextColor(0xFF333333.toInt())
            setPadding(0, dp(8), 0, dp(12))
        }
        outer.addView(featureMode, matchWrap())

        if (BuildConfig.CUSTOM_TESTING) {
            val settingsHeading = TextView(this).apply {
                text = "Custom feature controls"
                textSize = 19f
                setTypeface(null, Typeface.BOLD)
                setTextColor(0xFF202020.toInt())
                setPadding(0, dp(8), 0, dp(8))
            }
            outer.addView(settingsHeading, matchWrap())

            addFeatureCheckbox(
                outer,
                "Block Status viewer",
                TEST_BLOCK_STATUS
            )
            addFeatureCheckbox(
                outer,
                "Block opened Channels",
                TEST_BLOCK_CHANNELS
            )
            addFeatureCheckbox(
                outer,
                "Block Channels discovery page (Explore / Find channels)",
                TEST_BLOCK_CHANNELS_DISCOVERY
            )
            addFeatureCheckbox(
                outer,
                "Show Status overlay on Updates tab",
                TEST_OVERLAY_STATUS
            )
            addFeatureCheckbox(
                outer,
                "Show Channels overlay on Updates tab",
                TEST_OVERLAY_CHANNELS
            )
            addFeatureCheckbox(
                outer,
                "Enable 10-second lockout",
                TEST_LOCKOUT_ENABLED
            )

            val note = TextView(this).apply {
                text = "Note: these switches configure the requested behavior. " +
                    "The Accessibility service must also be updated to apply " +
                    "each setting independently."
                textSize = 13f
                setTextColor(0xFF555555.toInt())
                setPadding(0, dp(4), 0, dp(12))
            }
            outer.addView(note, matchWrap())
        }

        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF222222.toInt())
            setPadding(0, dp(12), 0, dp(12))
        }
        outer.addView(statusText, matchWrap())

        diagnosticText = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF333333.toInt())
            setPadding(0, dp(4), 0, dp(12))
        }
        outer.addView(diagnosticText, matchWrap())

        addButton(outer, "Set up / enable Accessibility") {
            openAccessibilitySettings()
        }

        addButton(outer, "Request battery-optimization exemption") {
            requestBatteryOptimizationExemption()
        }

        addButton(outer, "Open app battery settings (choose Unrestricted)") {
            openAppDetailsSettings()
        }

        addButton(outer, "Basic Log — enable") {
            setLoggingMode(full = false)
        }

        addButton(outer, "Full Log — enable detailed capture") {
            setLoggingMode(full = true)
        }

        addButton(outer, "Share Complete Log") {
            shareLog()
        }

        addButton(outer, "Clear diagnostic log") {
            clearLog()
        }

        addButton(outer, "Refresh status") {
            refreshStatus()
        }

        addButton(outer, "Open WhatsApp") {
            openWhatsApp()
        }

        setContentView(scroll)
    }

    private fun addFeatureCheckbox(
        parent: LinearLayout,
        label: String,
        preferenceKey: String
    ) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val checkBox = CheckBox(this).apply {
            text = label
            textSize = 15f
            setTextColor(0xFF222222.toInt())
            isChecked = prefs.getBoolean(preferenceKey, false)
            setPadding(0, dp(4), 0, dp(4))

            setOnCheckedChangeListener { _, checked ->
                prefs.edit()
                    .putBoolean(preferenceKey, checked)
                    .apply()
                refreshStatus()
            }
        }

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(2)
            bottomMargin = dp(2)
        }

        parent.addView(checkBox, params)
    }

    private fun setLoggingMode(full: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        prefs.edit()
            .putBoolean(DIAGNOSTIC_MODE, true)
            .putBoolean(DiagnosticsLogger.DETAILED_KEY, full)
            .putBoolean(DiagnosticsLogger.INCLUDE_TEXT_KEY, full)
            .apply()

        DiagnosticsLogger.resetCaptureLimits()
        DiagnosticsLogger.deviceHeader(this, force = true)

        if (full) {
            toast(
                "Full Log enabled. UI text may contain private messages, names, and contact information."
            )
        } else {
            toast(
                "Basic Log enabled. UI-tree dumps and UI text are disabled."
            )
        }

        refreshStatus()
    }

    private fun openWhatsApp() {
        val whatsappPackage = "com.whatsapp"

        try {
            val launchIntent =
                packageManager.getLaunchIntentForPackage(whatsappPackage)

            if (launchIntent != null) {
                startActivity(launchIntent)
                return
            }
        } catch (_: Exception) {
            // Try resolving the launcher activity explicitly below.
        }

        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(whatsappPackage)
            }

            val resolved = packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
            ).firstOrNull()

            if (resolved != null) {
                intent.component = android.content.ComponentName(
                    resolved.activityInfo.packageName,
                    resolved.activityInfo.name
                )
                startActivity(intent)
                return
            }
        } catch (_: Exception) {
            // Show a useful error instead of crashing the setup screen.
        }

        toast(
            "Couldn't open WhatsApp. Check that the official WhatsApp app is installed and enabled."
        )
    }

    private fun refreshStatus() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val diagnosticEnabled =
            prefs.getBoolean(DIAGNOSTIC_MODE, false)
        val detailedEnabled =
            DiagnosticsLogger.detailedEnabled(this)
        val includeText =
            DiagnosticsLogger.includeTextEnabled(this)
        val cooldownUntil =
            prefs.getLong(COOLDOWN_UNTIL, 0L)

        val remainingSeconds = (
            (cooldownUntil - System.currentTimeMillis())
                .coerceAtLeast(0L) / 1000L
            )

        val loggingMode = when {
            !diagnosticEnabled -> "OFF"
            detailedEnabled && includeText -> "FULL"
            else -> "BASIC"
        }

        val blockStatus = if (BuildConfig.CUSTOM_TESTING) {
            prefs.getBoolean(TEST_BLOCK_STATUS, false)
        } else {
            BuildConfig.BLOCK_STATUS
        }

        val blockChannels = if (BuildConfig.CUSTOM_TESTING) {
            prefs.getBoolean(TEST_BLOCK_CHANNELS, false)
        } else {
            BuildConfig.BLOCK_CHANNELS
        }

        val blockDiscovery = if (BuildConfig.CUSTOM_TESTING) {
            prefs.getBoolean(TEST_BLOCK_CHANNELS_DISCOVERY, false)
        } else {
            BuildConfig.BLOCK_CHANNELS
        }

        val overlayStatus = if (BuildConfig.CUSTOM_TESTING) {
            prefs.getBoolean(TEST_OVERLAY_STATUS, false)
        } else {
            BuildConfig.BLOCK_STATUS
        }

        val overlayChannels = if (BuildConfig.CUSTOM_TESTING) {
            prefs.getBoolean(TEST_OVERLAY_CHANNELS, false)
        } else {
            BuildConfig.BLOCK_CHANNELS
        }

        val lockoutEnabled = if (BuildConfig.CUSTOM_TESTING) {
            prefs.getBoolean(TEST_LOCKOUT_ENABLED, false)
        } else {
            true
        }

        statusText.text = buildString {
            appendLine(
                "Block Status: " + if (blockStatus) "ON" else "OFF"
            )
            appendLine(
                "Block opened Channels: " +
                    if (blockChannels) "ON" else "OFF"
            )
            appendLine(
                "Block Channels discovery: " +
                    if (blockDiscovery) "ON" else "OFF"
            )
            appendLine(
                "Overlay Status: " +
                    if (overlayStatus) "ON" else "OFF"
            )
            appendLine(
                "Overlay Channels: " +
                    if (overlayChannels) "ON" else "OFF"
            )
            appendLine(
                "10-second lockout: " +
                    if (lockoutEnabled) "ON" else "OFF"
            )
            appendLine("Logging mode: $loggingMode")
            appendLine(
                "UI text: " +
                    if (includeText) {
                        "INCLUDED — CHECK PRIVACY"
                    } else {
                        "NOT INCLUDED"
                    }
            )
            appendLine("Cooldown remaining: ${remainingSeconds}s")
        }

        val serviceEnabled = isAccessibilityServiceEnabled()
        val serviceConnected =
            WhatsAppAccessibilityService.isServiceConnected
        val batteryExempt = isIgnoringBatteryOptimizations()

        diagnosticText.text = buildString {
            appendLine(
                "Accessibility enabled in Settings: " +
                    if (serviceEnabled) {
                        "YES"
                    } else {
                        "NO — tap Set up / enable Accessibility"
                    }
            )
            appendLine(
                "Accessibility service connected now: " +
                    if (serviceConnected) {
                        "YES"
                    } else {
                        "NO / not currently connected"
                    }
            )
            appendLine(
                "Ignoring battery optimizations: " +
                    if (batteryExempt) {
                        "YES"
                    } else {
                        "NO — request exemption below"
                    }
            )
            appendLine(
                "Samsung battery setting: also set this app to Unrestricted in App info → Battery."
            )
            appendLine()
            appendLine("Latest service status:")
            appendLine(WhatsAppAccessibilityService.lastCaptureStatus)
            appendLine()
            appendLine("Latest detection report:")
            appendLine(WhatsAppAccessibilityService.lastDetectionSummary)
            appendLine()
            appendLine("Current log: ${logFile().length()} bytes")
            appendLine("Previous log: ${previousLogFile().length()} bytes")
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            toast(
                "Could not open Accessibility settings. Open Settings manually."
            )
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast(
                "This Android version does not use this battery exemption setting."
            )
            return
        }

        if (isIgnoringBatteryOptimizations()) {
            toast(
                "Battery-optimization exemption is already enabled. Also check App info → Battery → Unrestricted."
            )
            openAppDetailsSettings()
            return
        }

        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Exception) {
            toast(
                "Could not open the exemption request. Opening app settings instead."
            )
            openAppDetailsSettings()
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true

        val powerManager =
            getSystemService(Context.POWER_SERVICE) as PowerManager

        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openAppDetailsSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
            toast(
                "In App info, open Battery and select Unrestricted if available."
            )
        } catch (_: Exception) {
            toast(
                "Could not open app settings. Find WhatsApp Feature Guard under Settings → Apps."
            )
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected =
            "$packageName/${WhatsAppAccessibilityService::class.java.name}"

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(':').any {
            it.equals(expected, ignoreCase = true)
        }
    }

    private fun logFile(): File = File(filesDir, LOG_FILE)

    private fun previousLogFile(): File =
        File(filesDir, PREVIOUS_LOG_FILE)

    /**
     * Combines both retained log segments into one shareable export file.
     */
    private fun exportLogFile(): File {
        val current = logFile()
        val previous = previousLogFile()
        val export = File(filesDir, EXPORT_LOG_FILE)

        export.writeText(
            buildString {
                if (previous.exists() && previous.length() > 0L) {
                    appendLine("========== PREVIOUS LOG SEGMENT ==========")
                    appendLine(previous.readText())
                    appendLine()
                }

                if (current.exists() && current.length() > 0L) {
                    appendLine("========== CURRENT LOG SEGMENT ==========")
                    appendLine(current.readText())
                }
            }
        )

        return export
    }

    private fun shareLog() {
        val current = logFile()
        val previous = previousLogFile()

        if (
            (!current.exists() || current.length() == 0L) &&
            (!previous.exists() || previous.length() == 0L)
        ) {
            toast("No log yet. Enable Basic Log or Full Log and navigate WhatsApp first.")
            return
        }

        try {
            val file = exportLogFile()
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "WhatsApp Feature Guard diagnostic log")
                clipData = android.content.ClipData.newUri(
                    contentResolver,
                    "WhatsApp diagnostic log",
                    uri
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(
                Intent.createChooser(intent, "Share Complete Log")
            )
        } catch (e: Exception) {
            toast("Could not share log: ${e.javaClass.simpleName}")
        }
    }

    private fun clearLog() {
        logFile().delete()
        previousLogFile().delete()
        File(filesDir, EXPORT_LOG_FILE).delete()
        DiagnosticsLogger.resetCaptureLimits()

        toast("Diagnostic logs cleared.")
        refreshStatus()
    }

    private fun addButton(
        parent: LinearLayout,
        label: String,
        action: () -> Unit
    ) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            gravity = Gravity.CENTER
            setOnClickListener { action() }
        }

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(5)
            bottomMargin = dp(5)
        }

        parent.addView(button, params)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
