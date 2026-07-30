package com.jameschang.jcmetatranslatehelper

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

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
     * Meta exposes a translation entry as:
     * 1. A content-description button: "Play translation for <original sentence>"
     * 2. The visible original sentence
     * 3. The visible translated sentence
     *
     * The button is not labelled "You speak", so pair the marker with its two
     * nearby visible sentences and accept only English-original/Spanish-result
     * pairs. This prevents the other person's Spanish-original entries speaking.
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

        val markers = nodes.filter { isTranslationMarker(it) }
        val pairs = markers.mapNotNull { marker -> pairForMarker(marker, nodes) }
        val selectedPair = pairs.lastOrNull { pair ->
            looksLikeEnglish(pair.original) && looksLikeSpanish(pair.translation)
        }
        val selected = selectedPair?.translation

        val diagnostics = buildString {
            appendLine("Version 0.4.0")
            appendLine("Translation markers: ${markers.size}")
            appendLine(
                "Newest pair: " +
                    (pairs.lastOrNull()?.let { "${it.original} -> ${it.translation}" } ?: "none")
            )
            appendLine("Selected: ${selected ?: "none"}")
            appendLine("Screen: ${screenWidth}x${screenHeight}")
            nodes.takeLast(MAX_DIAGNOSTIC_NODES).forEach { item ->
                append(if (isTranslationMarker(item)) "MARKER" else "NODE")
                append(" [${item.bounds.left},${item.bounds.top},${item.bounds.right},${item.bounds.bottom}] ")
                append(item.text.take(160))
                if (item.fromDescription) append(" desc")
                if (item.isControl) append(" control")
                appendLine()
            }
        }.trim()

        return InspectionResult(selected, diagnostics)
    }

    private fun isTranslationMarker(node: NodeRecord): Boolean =
        node.fromDescription &&
            node.text.lowercase(Locale.ROOT).startsWith(PLAY_TRANSLATION_PREFIX)

    private fun pairForMarker(
        marker: NodeRecord,
        nodes: List<NodeRecord>
    ): TranslationPair? {
        val originalFromMarker = normalize(
            marker.text.substringAfter(PLAY_TRANSLATION_PREFIX, "")
        )
        if (!looksSpeakable(originalFromMarker)) return null

        val nextMarkerOrder = nodes.asSequence()
            .filter { it.order > marker.order && isTranslationMarker(it) }
            .minOfOrNull { it.order } ?: Int.MAX_VALUE

        val visible = nodes.asSequence()
            .filter { it.order > marker.order && it.order < nextMarkerOrder }
            .filterNot { it.isControl || it.fromDescription }
            .filter { looksSpeakable(it.text) }
            .toList()

        val originalIndex = visible.indexOfFirst {
            normalizeForMatch(it.text) == normalizeForMatch(originalFromMarker)
        }
        if (originalIndex < 0) return null

        val translation = visible.drop(originalIndex + 1)
            .firstOrNull { normalizeForMatch(it.text) != normalizeForMatch(originalFromMarker) }
            ?.text ?: return null

        return TranslationPair(originalFromMarker, translation)
    }

    private fun normalize(text: String): String =
        text.trim().replace(Regex("\\s+"), " ")

    private fun normalizeForMatch(text: String): String =
        normalize(text)
            .lowercase(Locale.ROOT)
            .trimEnd('.', '!', '?', '¿', '¡')

    private fun looksSpeakable(text: String): Boolean {
        if (text.length < 2 || text.length > 500) return false
        if (text.none { it.isLetter() }) return false

        val lower = text.lowercase(Locale.ROOT)
        return UI_LABELS.none { label ->
            lower == label || lower.startsWith("$label ") || lower.endsWith(" $label")
        }
    }

    private fun looksLikeEnglish(text: String): Boolean {
        val lower = " ${normalizeForMatch(text)} "
        if (SPANISH_STRONG_CHARS.any(lower::contains)) return false
        val englishHits = ENGLISH_WORDS.count { lower.contains(" $it ") }
        val spanishHits = SPANISH_WORDS.count { lower.contains(" $it ") }
        return englishHits > spanishHits || spanishHits == 0
    }

    private fun looksLikeSpanish(text: String): Boolean {
        val lower = " ${normalizeForMatch(text)} "
        if (SPANISH_STRONG_CHARS.any(lower::contains)) return true
        val spanishHits = SPANISH_WORDS.count { lower.contains(" $it ") }
        val englishHits = ENGLISH_WORDS.count { lower.contains(" $it ") }
        return spanishHits > englishHits
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
        private const val PLAY_TRANSLATION_PREFIX = "play translation for "
        private const val DUPLICATE_WINDOW_MS = 30_000L
        private const val MAX_DIAGNOSTIC_NODES = 60

        private val UI_LABELS = setOf(
            "live translation", "history", "settings", "pause", "resume", "end",
            "microphone", "english", "spanish", "copy", "share", "back", "close",
            "start", "stop", "cancel", "done", "more options", "you speak",
            "they speak", "play translation", "good translation", "bad translation",
            "delete language", "using downloaded languages"
        )

        private val SPANISH_STRONG_CHARS = listOf("¿", "¡", "ñ", "á", "é", "í", "ó", "ú")
        private val SPANISH_WORDS = setOf(
            "a", "al", "aquí", "como", "con", "donde", "dónde", "el", "ella", "en",
            "es", "esta", "está", "estoy", "gracias", "hola", "la", "las", "lo",
            "los", "me", "mi", "no", "para", "por", "que", "qué", "se", "sí",
            "soy", "su", "te", "tengo", "tu", "un", "una", "y", "yo"
        )
        private val ENGLISH_WORDS = setOf(
            "a", "again", "am", "are", "can", "do", "for", "from", "hello", "here",
            "how", "i", "in", "is", "it", "me", "my", "of", "please", "thank",
            "that", "the", "this", "to", "we", "what", "where", "with", "you"
        )
    }

    private data class NodeRecord(
        val order: Int,
        val bounds: Rect,
        val text: String,
        val isControl: Boolean,
        val fromDescription: Boolean
    )

    private data class TranslationPair(val original: String, val translation: String)
    private data class InspectionResult(val candidate: String?, val diagnostics: String)
}
