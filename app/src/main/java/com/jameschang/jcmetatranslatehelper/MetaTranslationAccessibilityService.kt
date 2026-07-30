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
     * Meta View exposes each outgoing entry in this order:
     * "Play translation for You speak", the original sentence, then its translation.
     * Use that semantic marker instead of guessing from left/right screen position.
     */
    private fun inspectTranslationScreen(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int
    ): InspectionResult {
        val nodes = mutableListOf<NodeRecord>()

        fun walk(node: AccessibilityNodeInfo) {
            val nodeText = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val text = normalize(nodeText.ifBlank { description })
            if (text.isNotEmpty()) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val isControl = node.isClickable || node.isCheckable ||
                    node.className?.toString()?.contains("Button", ignoreCase = true) == true
                nodes += NodeRecord(
                    order = nodes.size,
                    bounds = bounds,
                    text = text,
                    isControl = isControl,
                    fromDescription = nodeText.isBlank() && description.isNotBlank()
                )
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::walk)
            }
        }

        walk(root)

        val marker = nodes.lastOrNull { isYouSpeakMarker(it.text) }
        val semanticTexts = marker?.let { newestMarker ->
            nodes.asSequence()
                .filter { it.order > newestMarker.order }
                .filterNot { it.isControl }
                .map { it.text }
                .filter(::looksSpeakable)
                .filterNot(::isYouSpeakMarker)
                .distinct()
                .take(2)
                .toList()
        }.orEmpty()

        // The first sentence after the marker is what James said; the second is
        // Meta's translated sentence. Do not speak when both are not available.
        val semanticSelection = semanticTexts.getOrNull(1)
        val fallbackSelection = if (marker == null) {
            nodes.asSequence()
                .filter { it.bounds.centerX() > screenWidth * OUTGOING_CENTER_THRESHOLD }
                .filter { it.bounds.top <= screenHeight * BOTTOM_NAVIGATION_THRESHOLD }
                .filterNot { it.isControl }
                .filter { looksSpeakable(it.text) }
                .maxByOrNull { it.bounds.bottom }
                ?.text
        } else {
            null
        }
        val selected = semanticSelection ?: fallbackSelection

        val diagnostics = buildString {
            appendLine("Version 0.3.0")
            appendLine("You-speak marker: ${marker?.text ?: "none"}")
            appendLine("After marker: ${semanticTexts.joinToString(" | ").ifBlank { "none" }}")
            appendLine("Selected: ${selected ?: "none"}")
            appendLine("Screen: ${screenWidth}x${screenHeight}")
            nodes.takeLast(MAX_DIAGNOSTIC_NODES).forEach { item ->
                append(if (isYouSpeakMarker(item.text)) "MARKER" else "NODE")
                append(" [${item.bounds.left},${item.bounds.top},${item.bounds.right},${item.bounds.bottom}] ")
                append(item.text.take(160))
                if (item.fromDescription) append(" desc")
                if (item.isControl) append(" control")
                appendLine()
            }
        }.trim()

        return InspectionResult(selected, diagnostics)
    }

    private fun normalize(text: String): String =
        text.trim().replace(Regex("\\s+"), " ")

    private fun isYouSpeakMarker(text: String): Boolean {
        val lower = normalize(text).lowercase()
        return lower.contains(YOU_SPEAK_MARKER) ||
            (lower.contains("play translation") && lower.contains("you speak"))
    }

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
        private const val YOU_SPEAK_MARKER = "play translation for you speak"
        private const val OUTGOING_CENTER_THRESHOLD = 0.58
        private const val BOTTOM_NAVIGATION_THRESHOLD = 0.88
        private const val DUPLICATE_WINDOW_MS = 30_000L
        private const val MAX_DIAGNOSTIC_NODES = 50
        private val UI_LABELS = setOf(
            "live translation", "history", "settings", "pause", "resume", "end",
            "microphone", "english", "spanish", "copy", "share", "back", "close",
            "start", "stop", "cancel", "done", "more options", "you speak",
            "they speak", "play translation"
        )
    }

    private data class NodeRecord(
        val order: Int,
        val bounds: Rect,
        val text: String,
        val isControl: Boolean,
        val fromDescription: Boolean
    )

    private data class InspectionResult(val candidate: String?, val diagnostics: String)
}
