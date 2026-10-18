package com.example.whatsappfeatureguard

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup
import android.graphics.Color
import android.view.Gravity

class MainActivity : android.app.Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "WhatsApp Feature Guard"
            textSize = 24f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }

        val enableButton = Button(this).apply {
            text = "Enable Accessibility Service"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            }
        }

        val refreshButton = Button(this).apply {
            text = "Refresh Status"
            setOnClickListener {
                updateStatus()
            }
        }

        layout.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        layout.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        layout.addView(
            enableButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        layout.addView(
            refreshButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(layout)
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) {
            updateStatus()
        }
    }

    private fun updateStatus() {
        val manager = getSystemService(
            ACCESSIBILITY_SERVICE
        ) as AccessibilityManager

        val expectedService = ComponentName(
            this,
            WhatsAppAccessibilityService::class.java
        )

        val enabled = manager
            .getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            .any { info ->
                info.resolveInfo.serviceInfo.let { service ->
                    service.packageName == packageName &&
                    service.name == expectedService.className
                }
            }

        statusText.text = if (enabled) {
            "Accessibility service: ENABLED\n\n" +
            "Feature blocking will depend on the service implementation."
        } else {
            "Accessibility service: DISABLED\n\n" +
            "Enable the service in Android Settings to continue."
        }
    }
}
