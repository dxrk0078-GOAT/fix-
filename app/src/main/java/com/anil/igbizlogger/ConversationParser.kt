package com.anil.igbizlogger

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * IMPORTANT: Instagram does not expose a stable public API for its DM UI, and its
 * internal view IDs shift between app updates/regions. So instead of hardcoding
 * resource-ids (which would break on the next IG update), this parser reads
 * *structural* signals that are much more stable: left/right position, content-
 * description keywords IG sets for TalkBack/accessibility, and text patterns.
 *
 * You WILL likely need to tweak the keyword lists below after watching a few real
 * conversations flow through — expected for any screen-reader-style tool, not a bug.
 */
object ConversationParser {

    private val mediaKeywords = listOf(
        "photo" to "Photo", "video" to "Video", "voice message" to "Voice message",
        "audio" to "Voice message", "sticker" to "Sticker", "gif" to "GIF",
        "reel" to "Reel share", "post" to "Post share", "attachment" to "Attachment",
        "sent an attachment" to "Attachment"
    )

    // Refused entirely, even as a placeholder — stays on the "logger not spyware" side.
    private val ephemeralKeywords = listOf(
        "view once", "disappearing", "expired", "opened photo", "opened video", "vanish mode"
    )

    private val unsendKeywords = listOf(
        "unsent a message", "this message was deleted", "message was removed",
        "you unsent", "unsent this message"
    )

    // Reactions/system notices are real events worth keeping, but are NOT messages —
    // logging them as if they were plain text would corrupt the anchor-window dedup
    // in MessageStore (which assumes "messages" are the actual conversation content).
    private val reactionKeywords = listOf("reacted", "liked a message", "reaction")
    private val systemKeywords = listOf(
        "messages disappear", "say hi", "you're now connected", "started following",
        "changed the theme", "named the group", "added you", "left the conversation"
    )

    // Header text that is UI chrome, not a username — excluded so it can never win findUsername().
    private val headerBlocklist = setOf(
        "instagram", "direct", "search", "camera", "call", "video call", "back",
        "more options", "info", "requests", "primary", "general"
    )

    private val timestampSeparatorRegex = Regex(
        "(?i)(today|yesterday|mon|tue|wed|thu|fri|sat|sun)[^0-9]*\\d{1,2}:\\d{2}|\\d{1,2}:\\d{2}\\s*(am|pm)"
    )

    data class ScreenResult(
        val username: String?,
        val messages: List<ParsedMessage>,   // guaranteed sorted top-to-bottom by screen position
        val unsendEvents: List<String> = emptyList()
    )

    private data class Positioned(val top: Int, val msg: ParsedMessage)

    fun parse(root: AccessibilityNodeInfo, screenWidthPx: Int): ScreenResult {
        val username = findUsername(root)
        val positioned = mutableListOf<Positioned>()
        val unsendEvents = mutableListOf<String>()
        val visited = HashSet<Int>()

        // Local to this single parse() call — a previous/unrelated accessibility event
        // can no longer leak its timestamp state into this one (v1.1 bug fix).
        var pendingTimestamp: String? = null

        walk(root, visited) { node ->
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()
            val lowerText = text?.lowercase() ?: ""
            val bounds = Rect().also { node.getBoundsInScreen(it) }

            if (ephemeralKeywords.any { desc.contains(it) }) return@walk

            if (unsendKeywords.any { lowerText.contains(it) }) {
                unsendEvents.add(directionOf(bounds, screenWidthPx))
                return@walk
            }

            if (reactionKeywords.any { desc.contains(it) || lowerText.contains(it) }) {
                positioned.add(Positioned(bounds.top, ParsedMessage(
                    text = "", direction = directionOf(bounds, screenWidthPx),
                    isMedia = false, unknownDesc = "Reaction"
                )))
                return@walk
            }

            if (systemKeywords.any { lowerText.contains(it) }) {
                positioned.add(Positioned(bounds.top, ParsedMessage(
                    text = "", direction = "IN", isMedia = false, unknownDesc = "System notice: ${text?.take(60)}"
                )))
                return@walk
            }

            val mediaMatch = mediaKeywords.firstOrNull { desc.contains(it.first) }
            if (mediaMatch != null) {
                positioned.add(Positioned(bounds.top, ParsedMessage(
                    text = "", direction = directionOf(bounds, screenWidthPx),
                    isMedia = true, mediaLabel = mediaMatch.second
                )))
                return@walk
            }

            if (!text.isNullOrBlank() && timestampSeparatorRegex.containsMatchIn(text) && text.length < 40) {
                pendingTimestamp = text
                return@walk
            }

            if (!text.isNullOrBlank() && node.className?.contains("TextView") == true && text.length in 1..2000) {
                positioned.add(Positioned(bounds.top, ParsedMessage(
                    text = text, direction = directionOf(bounds, screenWidthPx),
                    isMedia = false, visibleTimestamp = pendingTimestamp
                )))
                pendingTimestamp = null
                return@walk
            }

            // Fallback: something bubble-shaped with a meaningful content description that
            // matched none of the above. Recorded rather than silently dropped, per spec —
            // but this is the heuristic most likely to need tuning against a real device,
            // since "bubble-shaped" here is only a size/position guess, not a real UI id.
            if (desc.isNotBlank() && desc.length in 3..300 && bounds.height() in 20..300) {
                positioned.add(Positioned(bounds.top, ParsedMessage(
                    text = "", direction = directionOf(bounds, screenWidthPx),
                    isMedia = false, unknownDesc = desc.take(80)
                )))
            }
        }

        // Enforce true visual (top-to-bottom) order regardless of the accessibility tree's
        // internal traversal order — required for MessageStore's anchor-window alignment.
        val messages = positioned.sortedBy { it.top }.map { it.msg }
        return ScreenResult(username, messages, unsendEvents)
    }

    private fun directionOf(bounds: Rect, screenWidthPx: Int): String =
        if (bounds.centerX() > screenWidthPx / 2) "OUT" else "IN"

    private fun findUsername(root: AccessibilityNodeInfo): String? {
        var found: String? = null
        val visited = HashSet<Int>()
        walk(root, visited) { node ->
            if (found != null) return@walk
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val text = node.text?.toString()
            if (!text.isNullOrBlank() && bounds.top in 0..250 &&
                text.length in 2..60 &&
                node.className?.contains("TextView") == true &&
                text.lowercase() !in headerBlocklist &&
                !text.matches(Regex("\\d+"))
            ) {
                found = text
            }
        }
        return found
    }

    private fun walk(node: AccessibilityNodeInfo?, visited: HashSet<Int>, action: (AccessibilityNodeInfo) -> Unit) {
        if (node == null) return
        val id = System.identityHashCode(node)
        if (!visited.add(id)) return
        action(node)
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), visited, action)
        }
    }
}
