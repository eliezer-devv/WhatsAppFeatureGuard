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
 * Privacy-first, local-only diagnostic logging.
 *
 * WhatsApp UI text, descriptions, hints, names, messages and notification
 * contents are NEVER written to the log.
 *
 * Detection summaries are rebuilt from a fixed allowlist of technical fields.
 * Event details are filtered to a small allowlist of technical values.
 * Exception messages and arbitrary caller-supplied text are never recorded.
 *
 * Routine event patterns are limited to three samples per process run.
 * UI trees are limited to three distinct detection outcomes per category.
 */
object DiagnosticsLogger {

    private const val PREFS = "feature_guard_settings"

    const val ROUTINE_KEY = "diagnostic_mode"
    const val DETAILED_KEY = "detailed_capture_mode"

    private const val LOG_FILE = "whatsapp_accessibility_log.txt"
    private const val PREVIOUS_FILE =
        "whatsapp_accessibility_log.previous.txt"

    private const val MAX_LOG_BYTES = 2 * 1024 * 1024L
    private const val MAX_SAMPLES_PER_PATTERN = 3
    private const val MAX_NODES = 120
    private const val MAX_DEPTH = 18
    private const val MAX_FIELD_LENGTH = 240

    private val sequence = AtomicLong(0)
    private val eventSamples = mutableMapOf<String, Int>()
    private val treeSamples = mutableMapOf<String, MutableSet<String>>()

    private val safeDetailKeys = setOf(
        "retry",
        "retryCount",
        "maxRetries",
        "backPresses",
        "attempt",
        "androidAcceptedAction",
        "homeActionAccepted",
        "remainingMs",
        "durationMs",
        "eventType",
        "windowId",
        "contentChangeTypes",
        "statusViewerDetected",
        "channelsScreenDetected",
        "statusBlocking",
        "channelsBlocking",
        "blockStatus",
        "blockChannels",
        "viewTracked",
        "removeViewReturned",
        "screenWidth",
        "action",
        "accepted",
        "exception",
        "key",
        "channelPublicLabel",
"channelContactHeaderId",
"channelForwardingId",
"channelHeaderMatch",
"channelPostsMatch"
    )

    /**
     * Only these exact, generic WhatsApp labels may appear in the log.
     * Never log arbitrary text that merely contains one of these words.
     */
    private val approvedUiLabels = mapOf(
        "UPDATES" to "Updates",
        "CHANNELS" to "Channels",
        "EXPLORE" to "Explore",
        "CHATS" to "Chats",
        "FOLLOW CHANNEL" to "FOLLOW CHANNEL",
        "FIND CHANNELS" to "Find channels",
        "STATUS" to "Status",
        "SEARCH" to "Search"
    )

    private fun safeUiText(value: CharSequence?): String {
        if (value.isNullOrBlank()) {
            return "[OMITTED_FOR_PRIVACY]"
        }

        val normalized = value
            .toString()
            .trim()
            .replace(Regex("\\s+"), " ")
            .uppercase(Locale.ROOT)

        return approvedUiLabels[normalized]
            ?: "[OMITTED_FOR_PRIVACY]"
    }
    
    fun routineEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ROUTINE_KEY, false)

    fun detailedEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(DETAILED_KEY, false)

    @Synchronized
    fun resetCaptureLimits() {
        eventSamples.clear()
        treeSamples.clear()
    }

    fun event(
        context: Context,
        name: String,
        details: String = ""
    ) {
        if (!routineEnabled(context)) return

        val safeName = safeEventName(name)
        val safeDetails = safeDetails(name, details)
        // Limit each event name to three samples.
// Changing window IDs or details cannot create unlimited samples.
if (!allowEventSample(safeName)) return

        write(
            context,
            buildString {
                appendLine()
                appendLine("========== EVENT ==========")
                appendLine("time=${timestamp()}")
                appendLine("sequence=${sequence.incrementAndGet()}")
                appendLine("event=$safeName")

                if (safeDetails.isNotBlank()) {
                    appendLine("details=$safeDetails")
                }

                appendLine("===========================")
            }
        )
    }

    fun failure(
        context: Context,
        name: String,
        details: String = ""
    ) {
        val safeName = safeEventName(name)
        val safeDetails = safeDetails(name, details)
        if (!allowEventSample("FAILURE:$safeName")) return

        write(
            context,
            buildString {
                appendLine()
                appendLine("======= IMPORTANT FAILURE =======")
                appendLine("time=${timestamp()}")
                appendLine("sequence=${sequence.incrementAndGet()}")
                appendLine("event=$safeName")

                if (safeDetails.isNotBlank()) {
                    appendLine("details=$safeDetails")
                }

                appendLine("=================================")
            }
        )
    }

    /**
     * Records technical build information without device identifiers.
     * No device serial number, account name or WhatsApp UI content is logged.
     */
    fun deviceHeader(
        context: Context,
        force: Boolean = false
    ) {
        if (!force && !routineEnabled(context)) return

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
                appendLine("androidRelease=${safeVersion(Build.VERSION.RELEASE)}")
appendLine("sdkInt=${Build.VERSION.SDK_INT}")

appendLine("manufacturer=${safeBuildValue(Build.MANUFACTURER)}")
appendLine("model=${safeBuildValue(Build.MODEL)}")
appendLine("device=${safeBuildValue(Build.DEVICE)}")
appendLine("product=${safeBuildValue(Build.PRODUCT)}")
appendLine("buildId=${safeBuildValue(Build.ID)}")

appendLine(
    "securityPatch=" +
        safeBuildValue(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Build.VERSION.SECURITY_PATCH
            } else {
                "unavailable"
            }
        )
)

val metrics = context.resources.displayMetrics
appendLine("screenWidthPx=${metrics.widthPixels}")
appendLine("screenHeightPx=${metrics.heightPixels}")
appendLine("screenDensityDpi=${metrics.densityDpi}")
appendLine(
    "screenDensity=${"%.2f".format(Locale.US, metrics.density)}"
)

appendLine("whatsappVersion=$whatsappVersion")
                appendLine("routineLogging=${routineEnabled(context)}")
                appendLine("detailedCapture=${detailedEnabled(context)}")
                appendLine("uiTextLogging=APPROVED_LABELS_ONLY")
                appendLine("maxSamplesPerPattern=$MAX_SAMPLES_PER_PATTERN")
                appendLine(
                    "maxDistinctTreeOutcomesPerCategory=" +
                        MAX_SAMPLES_PER_PATTERN
                )
                appendLine("===================================")
            }
        )
    }

    /**
     * Captures technical accessibility-tree information.
     * Only exact, approved generic UI labels may be recorded.
     * Descriptions, hints and all other UI text remain omitted.
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

        val safeSummary = safeDetectionSummary(detectionSummary)
        val category = treeCategory(safeSummary)
        val outcome = normalizePattern(safeSummary)

        if (!allowTreeSample(category, outcome)) return

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
            "eventPackageIsWhatsApp=" +
                (event?.packageName?.toString() == "com.whatsapp")
        )
        output.appendLine(
            "eventClass=${safeClassName(event?.className?.toString())}"
        )
        output.appendLine("windowId=${event?.windowId ?: -1}")
        output.appendLine(
            "rootPackageIsWhatsApp=" +
                (root.packageName?.toString() == "com.whatsapp")
        )
        output.appendLine("rootWindowId=${root.windowId}")
        output.appendLine(
            "contentChangeTypes=${event?.contentChangeTypes ?: 0}"
        )
        output.appendLine("eventText=[OMITTED_FOR_PRIVACY]")
        output.appendLine("uiTextLogging=DISABLED_BY_PRIVACY_POLICY")
        output.appendLine("sampleLimit=$MAX_SAMPLES_PER_PATTERN")
        output.appendLine("treeCategory=$category")
        output.appendLine("treeOutcome=${clean(outcome)}")
        output.appendLine("detectionSummary=${clean(safeSummary)}")

        try {
            appendNode(root, output, 0, count)
        } catch (e: Exception) {
            output.appendLine(
                "TREE_TRAVERSAL_FAILURE=${e.javaClass.simpleName}"
            )
        }

        output.appendLine("totalNodes=${count[0]}")
        output.appendLine("nodeLimit=$MAX_NODES")
        output.appendLine("depthLimit=$MAX_DEPTH")
        output.appendLine("======================================")

        write(context, output.toString())
    }

    /**
     * Rebuilds the detection summary from recognized technical fields only.
     * Unknown text is discarded rather than copied into the log.
     */
    private fun safeDetectionSummary(summary: String): String {
        fun capture(pattern: String): String? {
            return Regex(pattern, RegexOption.IGNORE_CASE)
                .find(summary)
                ?.groupValues
                ?.getOrNull(1)
                ?.uppercase(Locale.ROOT)
        }

        val statusViewer = capture(
            """Status viewer:\s*(MATCH|no match)"""
        )?.let {
            if (it == "MATCH") "MATCH" else "no match"
        }

        val channelsScreen = capture(
            """Channels screen:\s*(MATCH|no match)"""
        )?.let {
            if (it == "MATCH") "MATCH" else "no match"
        }

        val channelsDiscovery = capture(
            """Channels discovery:\s*(MATCH|no match)"""
        )?.let {
            if (it == "MATCH") "MATCH" else "no match"
        }

        val updatesBounds = capture(
            """Updates list bounds:\s*(found|not found)"""
        )?.lowercase(Locale.ROOT)

        val channelsBounds = capture(
            """Channels heading bounds:\s*(found|not found)"""
        )?.lowercase(Locale.ROOT)

        val statusOverlay = capture(
            """Status overlay shown:\s*(TRUE|FALSE)"""
        )?.lowercase(Locale.ROOT)

        val channelsOverlay = capture(
            """Channels overlay shown:\s*(TRUE|FALSE)"""
        )?.lowercase(Locale.ROOT)

        val blockingStatus = capture(
            """blocking enabled:\s*Status=(true|false)"""
        )?.lowercase(Locale.ROOT)

        val blockingChannels = capture(
            """Channels=(true|false)"""
        )?.lowercase(Locale.ROOT)

        return buildList {
            statusViewer?.let { add("Status viewer: $it") }
            channelsScreen?.let { add("Channels screen: $it") }
            channelsDiscovery?.let { add("Channels discovery: $it") }
            updatesBounds?.let { add("Updates list bounds: $it") }
            channelsBounds?.let { add("Channels heading bounds: $it") }
            statusOverlay?.let { add("Status overlay shown: $it") }
            channelsOverlay?.let { add("Channels overlay shown: $it") }

            if (blockingStatus != null && blockingChannels != null) {
                add(
                    "blocking enabled: Status=$blockingStatus, " +
                        "Channels=$blockingChannels"
                )
            }
        }.joinToString("; ").ifBlank {
            "No recognized detection fields"
        }
    }

    private fun treeCategory(summary: String): String {
        return when {
            summary.contains("Status viewer: MATCH", ignoreCase = true) ->
                "STATUS_VIEWER"

            summary.contains(
                "Channels discovery: MATCH",
                ignoreCase = true
            ) -> "CHANNELS_DISCOVERY"

            summary.contains("Channels screen: MATCH", ignoreCase = true) ->
                "CHANNELS_SCREEN"

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

    @Synchronized
    private fun allowEventSample(signature: String): Boolean {
        val count = eventSamples[signature] ?: 0
        if (count >= MAX_SAMPLES_PER_PATTERN) return false

        eventSamples[signature] = count + 1
        return true
    }

    private fun eventSignature(name: String, details: String): String =
        "$name|${normalizePattern(details).take(MAX_FIELD_LENGTH)}"

    private fun normalizePattern(value: String): String =
        value
            .replace(Regex("\\d+"), "#")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_FIELD_LENGTH)

    /**
     * Event names in the service are technical constant identifiers.
     * Arbitrary text is never allowed into the event-name field.
     */
    private fun safeEventName(name: String): String {
        return if (
            name.length <= 80 &&
            Regex("^[A-Z0-9_]+$").matches(name)
        ) {
            name
        } else {
            "UNCLASSIFIED_EVENT"
        }
    }

    /**
     * Keeps only explicitly approved technical key/value fields.
     * Free-form strings, UI text, reasons, labels and exception messages
     * are discarded. SCREEN_DETECTION uses its dedicated allowlist parser.
     */
    private fun safeDetails(name: String, details: String): String {
        if (details.isBlank()) return ""

        if (name == "SCREEN_DETECTION") {
            return safeDetectionSummary(details)
        }

        val kept = mutableListOf<String>()
        val pairPattern = Regex("""([A-Za-z][A-Za-z0-9_]*)\s*=\s*([^;,]+)""")

        for (match in pairPattern.findAll(details)) {
            val key = match.groupValues[1]
            val rawValue = match.groupValues[2].trim()

            if (key !in safeDetailKeys) continue

            val safeValue = when (key) {
                "exception" -> {
                    if (
                        rawValue.length <= 120 &&
                        Regex("^[A-Za-z0-9_.$]+$").matches(rawValue)
                    ) rawValue else null
                }

                "key" -> {
                    when (rawValue.lowercase(Locale.ROOT)) {
                        "status", "channels" ->
                            rawValue.lowercase(Locale.ROOT)
                        else -> null
                    }
                }

                "action" -> {
                    when (rawValue.uppercase(Locale.ROOT)) {
                        "ADD", "UPDATE", "REMOVE" ->
                            rawValue.uppercase(Locale.ROOT)
                        else -> null
                    }
                }

                "eventType" -> {
                    if (
                        rawValue.length <= 40 &&
                        Regex("^[A-Z0-9_]+$").matches(rawValue)
                    ) rawValue else null
                }

                else -> {
                    if (
                        Regex("^-?[0-9]+(?:\\.[0-9]+)?$").matches(rawValue) ||
                        rawValue.equals("true", true) ||
                        rawValue.equals("false", true)
                    ) {
                        rawValue
                    } else {
                        null
                    }
                }
            }

            if (safeValue != null) {
                kept.add("$key=$safeValue")
            }
        }

        return kept.distinct().joinToString("; ")
    }

    private fun appendNode(
        node: AccessibilityNodeInfo,
        out: StringBuilder,
        depth: Int,
        count: IntArray
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

        val indent = "  ".repeat(depth)

        out.appendLine("${indent}NODE #${count[0]}")
        out.appendLine(
            "${indent} class=${safeClassName(node.className?.toString())}"
        )
        out.appendLine(
            "${indent} packageIsWhatsApp=" +
                (node.packageName?.toString() == "com.whatsapp")
        )
        out.appendLine("${indent} viewId=${safeViewId(viewId)}")

        // Never log text, content descriptions, hints or their lengths.
        out.appendLine("${indent} text=${safeUiText(node.text)}")
        out.appendLine("${indent} description=[OMITTED_FOR_PRIVACY]")
        out.appendLine("${indent} hint=[OMITTED_FOR_PRIVACY]")

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
                appendNode(child, out, depth + 1, count)
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

    private fun safeClassName(value: String?): String {
        if (value.isNullOrBlank()) return "(unknown)"

        return if (
            value.length <= MAX_FIELD_LENGTH &&
            Regex("^[A-Za-z0-9_.$]+$").matches(value)
        ) {
            value
        } else {
            "[OMITTED]"
        }
    }

    private fun safeViewId(value: String?): String {
        if (value.isNullOrBlank()) return "(none)"

        return if (
            value.length <= MAX_FIELD_LENGTH &&
            Regex("^[A-Za-z0-9_.$:/-]+$").matches(value)
        ) {
            value
        } else {
            "[OMITTED]"
        }
    }

    private fun safeVersion(value: String?): String {
        if (value.isNullOrBlank()) return "unknown"

        return if (
            value.length <= 40 &&
            Regex("^[A-Za-z0-9._-]+$").matches(value)
        ) {
            value
        } else {
            "unknown"
        }
    }
    
private fun safeBuildValue(value: String?): String {
    if (value.isNullOrBlank()) return "unknown"

    return if (
        value.length <= 80 &&
        Regex("^[A-Za-z0-9 ._:/-]+$").matches(value)
    ) {
        value
    } else {
        "unknown"
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

            "${safeVersion(info.versionName)} (code=$versionCode)"
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
                val incomingBytes =
                    content.toByteArray(Charsets.UTF_8).size

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
                    // Do not include the exception message; it may contain
                    // arbitrary text from the system.
                    current.appendText(
                        "\nLOG_WRITE_FAILURE " +
                            "${e.javaClass.simpleName}\n",
                        Charsets.UTF_8
                    )
                } catch (_: Exception) {
                    // No further recovery is possible if storage is unavailable.
                }
            }
        }
    }

    private fun clean(value: String?): String {
        if (value == null) return "(null)"

        return value
            .replace("\n", " ")
            .replace("\r", " ")
            .replace("\t", " ")
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
