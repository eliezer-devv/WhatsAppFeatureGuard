package com.example.whatsappfeatureguard

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.graphics.Typeface
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
        private const val BLOCK_STATUS = "block_status"
        private const val BLOCK_CHANNELS = "block_channels"
        private const val COOLDOWN_UNTIL = "cooldown_until"
        private const val LOG_FILE = "whatsapp_accessibility_log.txt"
        private const val REQUEST_NOTIFICATIONS = 1001
    }

    private lateinit var statusText: TextView
    private lateinit var diagnosticText: TextView
    private lateinit var statusCheckBox: CheckBox
    private lateinit var channelsCheckBox: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
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
            text = "Choose which WhatsApp features to block. " +
                "The accessibility service must be enabled in Android Settings."
            textSize = 16f
            setTextColor(0xFF333333.toInt())
            setPadding(0, dp(12), 0, dp(16))
        }
        outer.addView(explanation, matchWrap())

        statusCheckBox = CheckBox(this).apply {
            text = "Block Status"
            textSize = 17f
            isChecked = getSetting(BLOCK_STATUS)
            setOnCheckedChangeListener { _, checked ->
                saveSetting(BLOCK_STATUS, checked)
                refreshStatus()
            }
        }
        outer.addView(statusCheckBox, matchWrap())

        channelsCheckBox = CheckBox(this).apply {
            text = "Block Channels"
            textSize = 17f
            isChecked = getSetting(BLOCK_CHANNELS)
            setOnCheckedChangeListener { _, checked ->
                saveSetting(BLOCK_CHANNELS, checked)
                refreshStatus()
            }
        }
        outer.addView(channelsCheckBox, matchWrap())

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

        addButton(outer, "Enable diagnostic logging") {
            saveSetting(DIAGNOSTIC_MODE, true)
            toast("Diagnostic logging enabled.")
            refreshStatus()
        }

        addButton(outer, "Disable diagnostic logging") {
            saveSetting(DIAGNOSTIC_MODE, false)
            toast("Diagnostic logging disabled.")
            refreshStatus()
        }

        addButton(outer, "Refresh status") {
            refreshStatus()
        }

        addButton(outer, "Open WhatsApp") {
            val launchIntent =
                packageManager.getLaunchIntentForPackage("com.whatsapp")

            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                toast("WhatsApp was not found.")
            }
        }

        addButton(outer, "Share diagnostic log") {
            shareLog()
        }

        addButton(outer, "Copy diagnostic log") {
            copyLog()
        }

        addButton(outer, "Clear diagnostic log") {
            clearLog()
        }

        addButton(outer, "Open Accessibility settings") {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
        }

        setContentView(scroll)
    }

    private fun getSetting(key: String): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(key, false)

    private fun saveSetting(key: String, value: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(key, value)
            .apply()
    }

    private fun refreshStatus() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val diagnosticEnabled =
            prefs.getBoolean(DIAGNOSTIC_MODE, false)
        val cooldownUntil = prefs.getLong(COOLDOWN_UNTIL, 0L)
        val remainingSeconds = (
            (cooldownUntil - System.currentTimeMillis()).coerceAtLeast(0L)
                / 1000L
            )

        statusText.text = buildString {
            appendLine("Block Status: " +
                if (prefs.getBoolean(BLOCK_STATUS, false)) "ON" else "OFF")
            appendLine("Block Channels: " +
                if (prefs.getBoolean(BLOCK_CHANNELS, false)) "ON" else "OFF")
            appendLine("Diagnostic logging: " +
                if (diagnosticEnabled) "ON" else "OFF")
            appendLine("Cooldown remaining: ${remainingSeconds}s")
        }

        val serviceEnabled = isAccessibilityServiceEnabled()

        diagnosticText.text = buildString {
            appendLine("Accessibility service: " +
                if (serviceEnabled) "enabled in system settings" else "NOT ENABLED")
            appendLine()
            appendLine("Latest service status:")
            appendLine(WhatsAppAccessibilityService.lastCaptureStatus)
            appendLine()
            append("Saved log size: ${logFile().length()} bytes")
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

    private fun shareLog() {
        val file = logFile()

        if (!file.exists() || file.length() == 0L) {
            toast("No log yet. Enable logging and navigate WhatsApp first.")
            return
        }

        try {
            val uri: Uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(
                    contentResolver, "WhatsApp UI log", uri
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(
                Intent.createChooser(intent, "Share diagnostic log")
            )
        } catch (e: Exception) {
            toast("Could not share log: ${e.javaClass.simpleName}")
        }
    }

    private fun copyLog() {
        val file = logFile()

        if (!file.exists() || file.length() == 0L) {
            toast("No log yet. Enable logging and navigate WhatsApp first.")
            return
        }

        try {
            val clipboard =
                getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            clipboard.setPrimaryClip(
                ClipData.newPlainText(
                    "WhatsApp accessibility log", file.readText()
                )
            )
            toast("Diagnostic log copied.")
        } catch (e: Exception) {
            toast("Could not copy log: ${e.javaClass.simpleName}")
        }
    }

    private fun clearLog() {
        logFile().delete()
        toast("Diagnostic log cleared.")
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
