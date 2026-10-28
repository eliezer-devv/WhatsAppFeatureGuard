package com.example.whatsappfeatureguard

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Local-only diagnostic logging.
 *
 * Basic Log records concise events and important failures.
 * Full Log additionally captures UI trees and may include UI text.
 *
 * Routine event patterns are limited to three samples per process run.
 * UI trees are limited to three distinct detection outcomes per category,
 * shared across event types and window IDs. Repeated identical outcomes
 * do not consume additional capture slots.
 */
object DiagnosticsLogger {

    private const val PREFS = "feature_guard_settings"

    const val ROUTINE_KEY = "diagnostic_mode"
    const val DETAILED_KEY = "detailed_capture_mode"
    const val INCLUDE_TEXT_KEY = "include_ui_text"

    private const val LOG_FILE = "whatsapp_accessibility_log.txt"
    private const val PREVIOUS_FILE =
        "whatsapp_accessibility_log.previous.txt"

    private const val MAX_LOG_BYTES = 2 * 1024 * 1024L
    private const val MAX_SAMPLES_PER_PATTERN = 3
    private const val MAX_NODES = 500
    private const val MAX_DEPTH = 30
    private const val MAX_FIELD_LENGTH = 240

    private val sequence = AtomicLong(0)
    private val eventSamples = mutableMapOf<String, Int>()

    // Each category stores the distinct detection summaries already captured.
    private val treeSamples = mutableMapOf<String, MutableSet<String>>()

    fun routineEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ROUTINE_KEY, false)

    fun detailedEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(DETAILED_KEY, false)

    fun includeTextEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(INCLUDE_TEXT_KEY, false)

    /**
     * Reset in-memory sample limits when the user changes logging mode
     * or clears the log. Existing log files are not changed by this method.
     */
    @Synchronized
    fun resetCaptureLimits() {
        eventSamples.clear()
        treeSamples.clear()
    }

    /**
     * Writes concise routine events only while logging is enabled.
     * Each normalized event name/detail pattern is limited to three samples.
     */
    fun event(
        context: Context,
        name: String,
        details: String = ""
    ) {
        if (!routineEnabled(context)) return

        val signature = eventSignature(name, details)
        if (!allowEventSample(signature)) return

        write(
            context,
            buildString {
                appendLine()
                appendLine("========== EVENT ==========")
                appendLine("time=${timestamp()}")
                appendLine("sequence=${sequence.incrementAndGet()}")
                appendLine("event=${clean(name)}")
                if (details.isNotBlank()) {
                    appendLine(clean(details))
                }
                appendLine("===========================")
            }
        )
    }

    /**
     * Important failures are logged even when routine logging is disabled.
     * Repeated failures of the same normalized pattern are capped at three.
     * Do not pass private UI text to this method.
     */
    fun failure(
        context: Context,
        name: String,
        details: String = ""
    ) {
        val signature = eventSignature("FAILURE:$name", details)
        if (!allowEventSample(signature)) return

        write(
            context,
            buildString {
                appendLine()
                appendLine("======= IMPORTANT FAILURE =======")
                appendLine("time=${timestamp()}")
                appendLine("sequence=${sequence.incrementAndGet()}")
                appendLine("event=${clean(name)}")
                if (details.isNotBlank()) {
                    appendLine(clean(details))
                }
                appendLine("=================================")
            }
        )
    }

    /**
     * Records app, device, build variant, display and WhatsApp version.
     */
    fun deviceHeader(
        context: Context,
        force: Boolean = false
    ) {
        if (!force && !routineEnabled(context)) return

        val metrics = context.resources.displayMetrics
        val whatsappVersion = packageVersion(context, "com.whatsapp")
        val appVersion = packageVersion(context, context.packageName)

        write(
            context,
            buildString {
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
                appendLine(
                    "display=${metrics.widthPixels}x${metrics.heightPixels}"
                )
                appendLine("density=${metrics.density}")
                appendLine("densityDpi=${metrics.densityDpi}")
                appendLine("whatsappVersion=$whatsappVersion")
                appendLine("routineLogging=${routineEnabled(context)}")
                appendLine("detailedCapture=${detailedEnabled(context)}")
                appendLine("includeUiText=${includeTextEnabled(context)}")
                appendLine("maxSamplesPerPattern=$MAX_SAMPLES_PER_PATTERN")
                appendLine("maxDistinctTreeOutcomesPerCategory=$MAX_SAMPLES_PER_PATTERN")
                appendLine("===================================")
            }
        )
    }

    /**
     * Captures a UI tree only in Full Log mode.
     *
     * The cap is shared across event types and window IDs. Each category
     * retains up to three distinct detection summaries. Duplicate summaries
     * are ignored and do not consume slots.
     *
     * UI text is redacted unless the user explicitly enabled it.
     */
    fun tree(
        context: Context,
        event: AccessibilityEvent?,
        root: AccessibilityNodeInfo,
        detectionSummary: String,
        force: Boolean = false
    ) {
        if (
            !force &&
            (!routineEnabled(context) || !detailedEnabled(context))
        ) {
            return
        }

        val category = treeCategory(detectionSummary)
        val outcome = normalizePattern(detectionSummary)

        if (!allowTreeSample(category, outcome)) return

        val includeText = includeTextEnabled(context)
        val count = intArrayOf(0)
        val output = StringBuilder()

        output.appendLine()
        output.appendLine("========== UI TREE SNAPSHOT ==========")
        output.appendLine("time=${timestamp()}")
        output.appendLine("sequence=${sequence.incrementAndGet()}")
        output.appendLine(
            "eventType=${event?.let { eventType(it.eventType) } ?: "RETRY_OR_MANUAL"}"
        )
        output.appendLine(
            "eventPackage=${clean(event?.packageName?.toString())}"
        )
        output.appendLine(
            "eventClass=${clean(event?.className?.toString())}"
        )
        output.appendLine("windowId=${event?.windowId ?: -1}")
        output.appendLine(
            "rootPackage=${clean(root.packageName?.toString())}"
        )
        output.appendLine("rootWindowId=${root.windowId}")
        output.appendLine(
            "contentChangeTypes=${event?.contentChangeTypes ?: 0}"
        )
        output.appendLine(
            "eventText=${textValue(event?.text?.joinToString(" | "), includeText)}"
        )
        output.appendLine("includeUiText=$includeText")
        output.appendLine("sampleLimit=$MAX_SAMPLES_PER_PATTERN")
        output.appendLine("treeCategory=$category")
        output.appendLine("treeOutcome=${clean(outcome)}")
        output.appendLine("detectionSummary=${clean(detectionSummary)}")

        try {
            appendNode(root, output, 0, count, includeText)
        } catch (e: Exception) {
            output.appendLine(
                "TREE_TRAVERSAL_FAILURE=${e.javaClass.simpleName}: " +
                    clean(e.message)
            )
        }

        output.appendLine("totalNodes=${count[0]}")
        output.appendLine("nodeLimit=$MAX_NODES")
        output.appendLine("depthLimit=$MAX_DEPTH")
        output.appendLine("======================================")

        write(context, output.toString())
    }

    /**
     * Groups snapshots by the screen/detection category being investigated.
     *
     * A detected viewer or discovery screen gets its own category. Otherwise,
     * snapshots are grouped by whether the Updates list or Channels heading
     * bounds were found. The fallback category preserves other states.
     */
    private fun treeCategory(summary: String): String {
        return when {
            summary.contains(
                "Status viewer: MATCH",
                ignoreCase = true
            ) -> "STATUS_VIEWER"

            summary.contains(
                "Channels discovery: MATCH",
                ignoreCase = true
            ) -> "CHANNELS_DISCOVERY"

            summary.contains(
                "Channels screen: MATCH",
                ignoreCase = true
            ) -> "CHANNELS_SCREEN"

            summary.contains(
                "Updates list bounds: found",
                ignoreCase = true
            ) -> "UPDATES_LIST_FOUND"

            summary.contains(
                "Channels heading bounds: found",
                ignoreCase = true
            ) -> "CHANNELS_HEADING_FOUND"

            summary.contains(
                "Updates list bounds: not found",
                ignoreCase = true
            ) -> "UPDATES_LIST_NOT_FOUND"

            summary.contains(
                "Channels heading bounds: not found",
                ignoreCase = true
            ) -> "CHANNELS_HEADING_NOT_FOUND"

            else -> "OTHER_OR_UNDETECTED"
        }
    }

    /**
     * Allows up to three distinct outcomes per category.
     * Identical summaries do not consume additional slots.
     */
    @Synchronized
    private fun allowTreeSample(
        category: String,
        outcome: String
    ): Boolean {
        val outcomes = treeSamples.getOrPut(category) { linkedSetOf() }

        if (outcome in outcomes) return false
        if (outcomes.size >= MAX_SAMPLES_PER_PATTERN) return false

        outcomes.add(outcome)
        return true
    }

    /**
     * Atomically grants at most three samples for a routine event signature.
     */
    @Synchronized
    private fun allowEventSample(signature: String): Boolean {
        val count = eventSamples[signature] ?: 0
        if (count >= MAX_SAMPLES_PER_PATTERN) return false

        eventSamples[signature] = count + 1
        return true
    }

    /**
     * Normalize changing numbers so coordinates, counters and timestamps
     * do not create endless unique patterns.
     */
    private fun eventSignature(name: String, details: String): String =
        "$name|${normalizePattern(details).take(MAX_FIELD_LENGTH)}"

    private fun normalizePattern(value: String): String =
        value
            .replace(Regex("\\d+"), "#")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_FIELD_LENGTH)

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

        val viewId = try {
            node.viewIdResourceName
        } catch (_: Exception) {
            null
        }

        val hint = if (Build.VERSION.SDK_INT >= 26) {
            try {
                node.hintText?.toString()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        val indent = "  ".repeat(depth)

        out.appendLine("${indent}NODE #${count[0]}")
        out.appendLine(
            "${indent} class=${clean(node.className?.toString())}"
        )
        out.appendLine(
            "${indent} package=${clean(node.packageName?.toString())}"
        )
        out.appendLine("${indent} viewId=${clean(viewId)}")
        out.appendLine(
            "${indent} text=${textValue(node.text?.toString(), includeText)}"
        )
        out.appendLine(
            "${indent} description=${textValue(node.contentDescription?.toString(), includeText)}"
        )
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
        out.appendLine(
            "${indent} accessibilityFocused=${node.isAccessibilityFocused}"
        )
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
                out.appendLine(
                    "${indent} child[$i] error=${e.javaClass.simpleName}"
                )
                null
            } ?: continue

            try {
                appendNode(child, out, depth + 1, count, includeText)
            } catch (e: Exception) {
                out.appendLine(
                    "${indent} child[$i] traversalError=" +
                        e.javaClass.simpleName
                )
            } finally {
                try {
                    child.recycle()
                } catch (_: Exception) {
                    // Ignore recycled-node cleanup errors.
                }
            }
        }
    }

    private fun packageVersion(
        context: Context,
        packageName: String
    ): String {
        return try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }

            val versionCode = if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }

            "${info.versionName ?: "unknown"} (code=$versionCode)"
        } catch (e: Exception) {
            "unavailable (${e.javaClass.simpleName})"
        }
    }

    /**
     * Writes to the current log and retains one previous segment on rotation.
     */
    private fun write(
        context: Context,
        content: String
    ) {
        synchronized(this) {
            val current = File(context.filesDir, LOG_FILE)
            val previous = File(context.filesDir, PREVIOUS_FILE)

            try {
                val incomingBytes = content.toByteArray(Charsets.UTF_8).size

                if (
                    current.exists() &&
                    current.length() + incomingBytes > MAX_LOG_BYTES
                ) {
                    if (previous.exists()) previous.delete()

                    if (current.exists()) {
                        current.copyTo(previous, overwrite = true)
                    }

                    current.writeText(
                        "Log rotated at ${timestamp()}. " +
                            "Previous segment: $PREVIOUS_FILE\n",
                        Charsets.UTF_8
                    )
                }

                current.appendText(content + "\n", Charsets.UTF_8)
            } catch (e: Exception) {
                try {
                    current.appendText(
                        "\nLOG_WRITE_FAILURE " +
                            "${e.javaClass.simpleName}: ${clean(e.message)}\n"
                    )
                } catch (_: Exception) {
                    // No further recovery is possible if storage is unavailable.
                }
            }
        }
    }

    private fun textValue(
        value: String?,
        includeText: Boolean
    ): String {
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
