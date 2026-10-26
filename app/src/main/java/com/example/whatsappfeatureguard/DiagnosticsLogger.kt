package com.example.whatsappfeatureguard

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Local-only diagnostics.
 *
 * diagnostic_mode: routine event logging.
 * detailed_capture_mode: full UI-tree snapshots.
 * include_ui_text: explicitly permits UI text in snapshots.
 *
 * Text is redacted by default because accessibility trees can
 * contain private messages and contact information.
 */
object DiagnosticsLogger {

    private const val PREFS = "feature_guard_settings"
    const val ROUTINE_KEY = "diagnostic_mode"
    const val DETAILED_KEY = "detailed_capture_mode"
    const val INCLUDE_TEXT_KEY = "include_ui_text"

    private const val LOG_FILE = "whatsapp_accessibility_log.txt"
    private const val PREVIOUS_FILE = "whatsapp_accessibility_log.previous.txt"

    private const val MAX_LOG_BYTES = 2 * 1024 * 1024L
    private const val MAX_NODES = 800
    private const val MAX_DEPTH = 40
    private const val MAX_FIELD_LENGTH = 400

    private val sequence = AtomicLong(0)

    fun routineEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ROUTINE_KEY, false)

    fun detailedEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(DETAILED_KEY, false)

    fun includeTextEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(INCLUDE_TEXT_KEY, false)

    fun event(context: Context, name: String, details: String = "") {
        if (!routineEnabled(context)) return
        write(context, buildString {
            appendLine()
            appendLine("========== EVENT ==========")
            appendLine("time=${timestamp()}")
            appendLine("sequence=${sequence.incrementAndGet()}")
            appendLine("event=${clean(name)}")
            if (details.isNotBlank()) appendLine(clean(details))
            appendLine("===========================")
        })
    }

    /**
     * Important errors are retained even when routine logging is off.
     * This deliberately records only diagnostic metadata, not UI text.
     */
    fun failure(context: Context, name: String, details: String = "") {
        write(context, buildString {
            appendLine()
            appendLine("======= IMPORTANT FAILURE =======")
            appendLine("time=${timestamp()}")
            appendLine("sequence=${sequence.incrementAndGet()}")
            appendLine("event=${clean(name)}")
            if (details.isNotBlank()) appendLine(clean(details))
            appendLine("=================================")
        })
    }

    fun deviceHeader(context: Context, force: Boolean = false) {
        if (!force && !routineEnabled(context)) return

        val metrics = context.resources.displayMetrics
        val whatsapp = try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    "com.whatsapp",
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo("com.whatsapp", 0)
            }
            val code = if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName ?: "unknown"} (code=$code)"
        } catch (e: Exception) {
            "unavailable (${e.javaClass.simpleName})"
        }

        val appVersion = try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName ?: "unknown"} (code=$code)"
        } catch (_: Exception) {
            "unknown"
        }

        write(context, buildString {
            appendLine()
            appendLine("======= DEVICE / BUILD INFO =======")
            appendLine("time=${timestamp()}")
            appendLine("appPackage=${context.packageName}")
            appendLine("appVersion=$appVersion")
            appendLine("buildVariant=${BuildConfig.FLAVOR}")
            appendLine("blockStatus=${BuildConfig.BLOCK_STATUS}")
            appendLine("blockChannels=${BuildConfig.BLOCK_CHANNELS}")
            appendLine("manufacturer=${clean(Build.MANUFACTURER)}")
            appendLine("brand=${clean(Build.BRAND)}")
            appendLine("model=${clean(Build.MODEL)}")
            appendLine("device=${clean(Build.DEVICE)}")
            appendLine("androidRelease=${Build.VERSION.RELEASE}")
            appendLine("sdkInt=${Build.VERSION.SDK_INT}")
            appendLine("display=${metrics.widthPixels}x${metrics.heightPixels}")
            appendLine("density=${metrics.density}")
            appendLine("densityDpi=${metrics.densityDpi}")
            appendLine("whatsappVersion=$whatsapp")
            appendLine("routineLogging=${routineEnabled(context)}")
            appendLine("detailedCapture=${detailedEnabled(context)}")
            appendLine("includeUiText=${includeTextEnabled(context)}")
            appendLine("===================================")
        })
    }

    fun tree(
        context: Context,
        event: AccessibilityEvent?,
        root: AccessibilityNodeInfo,
        detectionSummary: String,
        force: Boolean = false
    ) {
        if (!force &&
            (!routineEnabled(context) || !detailedEnabled(context))
        ) return

        val includeText = includeTextEnabled(context)
        val count = intArrayOf(0)
        val output = StringBuilder()

        output.appendLine()
        output.appendLine("========== UI TREE SNAPSHOT ==========")
        output.appendLine("time=${timestamp()}")
        output.appendLine("sequence=${sequence.incrementAndGet()}")
        output.appendLine("eventType=${event?.let { eventType(it.eventType) } ?: "RETRY_OR_MANUAL"}")
        output.appendLine("eventPackage=${clean(event?.packageName?.toString())}")
        output.appendLine("eventClass=${clean(event?.className?.toString())}")
        output.appendLine("windowId=${event?.windowId ?: -1}")
        output.appendLine("rootPackage=${clean(root.packageName?.toString())}")
        output.appendLine("rootWindowId=${root.windowId}")
        output.appendLine("contentChangeTypes=${event?.contentChangeTypes ?: 0}")
        output.appendLine("eventText=${textValue(event?.text?.joinToString(" | "), includeText)}")
        output.appendLine("includeUiText=$includeText")
        output.appendLine("detectionSummary=${clean(detectionSummary)}")

        appendNode(root, output, 0, count, includeText)

        output.appendLine("totalNodes=${count[0]}")
        output.appendLine("nodeLimit=$MAX_NODES")
        output.appendLine("depthLimit=$MAX_DEPTH")
        output.appendLine("======================================")

        write(context, output.toString())
    }

    private fun appendNode(
        node: AccessibilityNodeInfo,
        out: StringBuilder,
        depth: Int,
        count: IntArray,
        includeText: Boolean
    ) {
        if (count[0] >= MAX_NODES || depth > MAX_DEPTH) return
        count[0]++

        val bounds = Rect()
        try {
            node.getBoundsInScreen(bounds)
        } catch (_: Exception) {
            bounds.setEmpty()
        }

        val id = try {
            node.viewIdResourceName
        } catch (_: Exception) {
            null
        }

        val hint = if (Build.VERSION.SDK_INT >= 26) {
            try { node.hintText?.toString() } catch (_: Exception) { null }
        } else null

        val indent = "  ".repeat(depth)
        out.appendLine("${indent}NODE #${count[0]}")
        out.appendLine("${indent} class=${clean(node.className?.toString())}")
        out.appendLine("${indent} package=${clean(node.packageName?.toString())}")
        out.appendLine("${indent} viewId=${clean(id)}")
        out.appendLine("${indent} text=${textValue(node.text?.toString(), includeText)}")
        out.appendLine("${indent} description=${textValue(node.contentDescription?.toString(), includeText)}")
        out.appendLine("${indent} hint=${textValue(hint, includeText)}")
        out.appendLine("${indent} bounds=$bounds")
        out.appendLine("${indent} visibleToUser=${node.isVisibleToUser}")
        out.appendLine("${indent} clickable=${node.isClickable}")
        out.appendLine("${indent} longClickable=${node.isLongClickable}")
        out.appendLine("${indent} selected=${node.isSelected}")
        out.appendLine("${indent} checkable=${node.isCheckable}")
        out.appendLine("${indent} checked=${node.isChecked}")
        out.appendLine("${indent} enabled=${node.isEnabled}")
        out.appendLine("${indent} focusable=${node.isFocusable}")
        out.appendLine("${indent} focused=${node.isFocused}")
        out.appendLine("${indent} accessibilityFocused=${node.isAccessibilityFocused}")
        out.appendLine("${indent} scrollable=${node.isScrollable}")
        out.appendLine("${indent} editable=${node.isEditable}")
        out.appendLine("${indent} password=${node.isPassword}")
        out.appendLine("${indent} childCount=${node.childCount}")

        for (i in 0 until node.childCount) {
            if (count[0] >= MAX_NODES) {
                out.appendLine("${indent} [NODE LIMIT REACHED]")
                break
            }

            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                out.appendLine("${indent} child[$i] error=${e.javaClass.simpleName}")
                null
            } ?: continue

            try {
                appendNode(child, out, depth + 1, count, includeText)
            } catch (e: Exception) {
                out.appendLine(
                    "${indent} child[$i] traversalError=${e.javaClass.simpleName}"
                )
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }
    }

    private fun write(context: Context, content: String) {
        synchronized(this) {
            val file = File(context.filesDir, LOG_FILE)
            val previous = File(context.filesDir, PREVIOUS_FILE)

            try {
                val incomingBytes = content.toByteArray(Charsets.UTF_8).size
                if (file.exists() && file.length() + incomingBytes > MAX_LOG_BYTES) {
                    if (previous.exists()) previous.delete()
                    if (file.exists()) file.copyTo(previous, overwrite = true)
                    file.writeText(
                        "Log rotated. Previous segment: $PREVIOUS_FILE\n",
                        Charsets.UTF_8
                    )
                }
                file.appendText(content + "\n", Charsets.UTF_8)
            } catch (e: Exception) {
                try {
                    file.appendText(
                        "\nLOG_WRITE_FAILURE ${e.javaClass.simpleName}\n"
                    )
                } catch (_: Exception) {}
            }
        }
    }

    private fun textValue(value: String?, includeText: Boolean): String {
        if (value.isNullOrBlank()) return "(empty)"
        if (!includeText) return "[REDACTED length=${value.length}]"
        return clean(value)
    }

    private fun clean(value: String?): String {
        if (value == null) return "(null)"
        return value
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .take(MAX_FIELD_LENGTH)
    }

    private fun timestamp(): String =
        SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS Z",
            Locale.US
        ).format(Date())

    private fun eventType(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "VIEW_SELECTED"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
        else -> "OTHER($type)"
    }
}
