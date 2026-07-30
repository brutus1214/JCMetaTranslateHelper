package com.jameschang.jcmetatranslatehelper

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MetaTranslationAccessibilityService : AccessibilityService() {
    private lateinit var speaker: Speaker
    private var lastSpoken = ""
    private var lastSpokenAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        speaker = Speaker(this)
        lastSpoken = Preferences.lastSpoken(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != META_VIEW_PACKAGE) return

        val root = rootInActiveWindow ?: return
        val result = inspectTranslationScreen(
            root = root,
            screenWidth = resources.displayMetrics.widthPixels,
            screenHeight = resources.displayMetrics.heightPixels
        )
        Preferences.setDiagnostics(this, result.diagnostics)

        if (!Preferences.isSpeakEnabled(this)) return
        val candidate = result.candidate ?: return

        val normalized = normalize(candidate)
        if (isDuplicate(normalized)) return

        lastSpoken = normalized
        lastSpokenAt = System.currentTimeMillis()
        Preferences.setLastSpoken(this, normalized)
        speaker.speak(normalized)
    }

    /**
     * Finds the newest speakable text on James's outgoing (right-hand) side.
     * Filtering happens before choosing the lowest node so a bottom navigation label
     * such as "History" cannot hide the real translated sentence above it.
     */
    private fun inspectTranslationScreen(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int
    ): InspectionResult {
        val candidates = mutableListOf<Candidate>()
        val visibleNodes = mutableListOf<String>()

        fun walk(node: AccessibilityNodeInfo) {
            val nodeText = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val text = normalize(nodeText.ifBlank { description })
            if (text.isNotEmpty()) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val centerX = bounds.centerX()
                val isOutgoing = centerX > screenWidth * OUTGOING_CENTER_THRESHOLD
                val isBottomNavigation = bounds.top > screenHeight * BOTTOM_NAVIGATION_THRESHOLD
                val isControl = node.isClickable || node.isCheckable ||
                    node.className?.toString()?.contains("Button", ignoreCase = true) == true

                visibleNodes += buildString {
                    append(if (isOutgoing) "R" else "L")
                    append(" [${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}] ")
                    append(text.take(160))
                    node.viewIdResourceName?.let { append(" id=").append(it) }
                    if (description.isNotBlank()) append(" desc")
                    if (isControl) append(" control")
                }

                if (isOutgoing && !isBottomNavigation && !isControl && looksSpeakable(text)) {
                    candidates += Candidate(bounds.bottom, text)
                }
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::walk)
            }
        }

        walk(root)
        val selected = candidates.maxByOrNull { it.bottom }?.text
        val diagnostics = buildString {
            appendLine("Version 0.2.0")
            appendLine("Selected: ${selected ?: "none"}")
            appendLine("Screen: ${screenWidth}x$screenHeight")
            visibleNodes.takeLast(MAX_DIAGNOSTIC_NODES).forEach(::appendLine)
        }.trim()
        return InspectionResult(selected, diagnostics)
    }

    private fun normalize(text: String): String =
        text.trim().replace(Regex("\\s+"), " ")

    private fun looksSpeakable(text: String): Boolean {
        if (text.length < 2 || text.length > 500) return false
        if (text.none { it.isLetter() }) return false

        val lower = text.lowercase()
        return UI_LABELS.none { label ->
            lower == label || lower.startsWith("$label ") || lower.endsWith(" $label")
        }
    }

    private fun isDuplicate(text: String): Boolean {
        val age = System.currentTimeMillis() - lastSpokenAt
        return text == lastSpoken && age < DUPLICATE_WINDOW_MS
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (::speaker.isInitialized) speaker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val META_VIEW_PACKAGE = "com.facebook.stella"
        private const val OUTGOING_CENTER_THRESHOLD = 0.58
        private const val BOTTOM_NAVIGATION_THRESHOLD = 0.88
        private const val DUPLICATE_WINDOW_MS = 30_000L
        private const val MAX_DIAGNOSTIC_NODES = 40
        private val UI_LABELS = setOf(
            "live translation", "history", "settings", "pause", "resume", "end",
            "microphone", "english", "spanish", "copy", "share", "back", "close",
            "start", "stop", "cancel", "done", "more options"
        )
    }

    private data class Candidate(val bottom: Int, val text: String)
    private data class InspectionResult(val candidate: String?, val diagnostics: String)
}
