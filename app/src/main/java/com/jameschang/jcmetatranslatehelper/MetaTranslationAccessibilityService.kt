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
        if (!Preferences.isSpeakEnabled(this)) return
        if (event?.packageName?.toString() != META_VIEW_PACKAGE) return

        val root = rootInActiveWindow ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val candidate = findNewestOutgoingTranslation(root, screenWidth) ?: return
        val normalized = candidate.trim().replace(Regex("\\s+"), " ")

        if (!looksSpeakable(normalized) || isDuplicate(normalized)) return

        lastSpoken = normalized
        lastSpokenAt = System.currentTimeMillis()
        Preferences.setLastSpoken(this, normalized)
        speaker.speak(normalized)
    }

    /**
     * Meta renders live translation like a message conversation. Version 0.1 treats
     * text whose node is centered in the right-hand 58% of the screen as the user's
     * outgoing side. This intentionally excludes left-aligned incoming translations.
     * The debug/field test on the Fold 7 will confirm Meta's exact node geometry.
     */
    private fun findNewestOutgoingTranslation(
        root: AccessibilityNodeInfo,
        screenWidth: Int
    ): String? {
        val candidates = mutableListOf<Pair<Int, String>>()

        fun walk(node: AccessibilityNodeInfo) {
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val centerX = bounds.centerX()
                if (centerX > screenWidth * OUTGOING_CENTER_THRESHOLD) {
                    candidates += bounds.bottom to text
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::walk)
            }
        }

        walk(root)
        return candidates.maxByOrNull { it.first }?.second
    }

    private fun looksSpeakable(text: String): Boolean {
        if (text.length < 2 || text.length > 500) return false
        val lower = text.lowercase()
        return UI_LABELS.none { lower == it || lower.startsWith("$it ") }
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
        private const val DUPLICATE_WINDOW_MS = 30_000L
        private val UI_LABELS = setOf(
            "live translation", "settings", "pause", "resume", "end",
            "microphone", "english", "spanish"
        )
    }
}
