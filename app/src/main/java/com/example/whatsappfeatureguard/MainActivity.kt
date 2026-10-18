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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {

    companion object {
        private const val PREFS_NAME = "feature_guard_settings"
        private const val DIAGNOSTIC_MODE = "diagnostic_mode"
        private const val LOG_FILE = "whatsapp_accessibility_log.txt"
        private const val REQUEST_NOTIFICATIONS = 1001
    }

    private lateinit var statusText: TextView
    private lateinit var diagnosticText: TextView

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
            text = "Diagnostic build\n\nAutomatic blocking is disabled in this version. " +
                "Use diagnostic mode while navigating WhatsApp to collect UI information."
            textSize = 16f
            setTextColor(0xFF333333.toInt())
            setPadding(0, dp(12), 0, dp(16))
        }
        outer.addView(explanation, matchWrap())

        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF222222.toInt())
            setPadding(0, dp(8), 0, dp(12))
        }
        outer.addView(statusText, matchWrap())

        diagnosticText = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF333333.toInt())
            setPadding(0, dp(4), 0, dp(12))
        }
        outer.addView(diagnosticText, matchWrap())

        addButton(outer, "Enable diagnostic mode") {
            setDiagnosticMode(true)
        }

        addButton(outer, "Disable diagnostic mode") {
            setDiagnosticMode(false)
        }

        addButton(outer, "Refresh status") {
            refreshStatus()
        }

        addButton(outer, "Open WhatsApp") {
            val launchIntent = packageManager.getLaunchIntentForPackage("com.whatsapp")
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
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        setContentView(scroll)
    }

    private fun setDiagnosticMode(enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(DIAGNOSTIC_MODE, enabled)
            .apply()

        toast(if (enabled) {
            "Diagnostic mode enabled. Open WhatsApp and navigate normally."
        } else {
            "Diagnostic mode disabled."
        })

        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(DIAGNOSTIC_MODE, false)

        statusText.text = "Diagnostic mode: " +
            if (enabled) "ON" else "OFF"

        val serviceEnabled = isAccessibilityServiceEnabled()

        diagnosticText.text = buildString {
            appendLine("Accessibility service: " +
                if (serviceEnabled) "enabled in system settings" else "not enabled")
            appendLine()
            appendLine("Latest capture status:")
            appendLine(WhatsAppAccessibilityService.lastCaptureStatus)
            appendLine()
            append("Saved log size: ${logFile().length()} bytes")
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${WhatsAppAccessibilityService::class.java.name}"
        val enabledServices = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(':').any {
            it.equals(expected, ignoreCase = true)
        }
    }

    private fun logFile(): File = File(filesDir, LOG_FILE)

    private fun shareLog() {
        val file = logFile()

        if (!file.exists() || file.length() == 0L) {
            toast("No log yet. Enable diagnostic mode and navigate WhatsApp first.")
            return
        }

        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, "WhatsApp UI log", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "Share diagnostic log"))
        } catch (e: Exception) {
            toast("Could not share log: ${e.javaClass.simpleName}")
        }
    }

    private fun copyLog() {
        val file = logFile()

        if (!file.exists() || file.length() == 0L) {
            toast("No log yet. Enable diagnostic mode and navigate WhatsApp first.")
            return
        }

        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("WhatsApp accessibility log", file.readText())
            )
            toast("Log copied. Paste it into a private note or chat.")
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
