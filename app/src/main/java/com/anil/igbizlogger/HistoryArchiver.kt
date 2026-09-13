package com.anil.igbizlogger

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

/**
 * Instagram's Accessibility tree only ever exposes what's currently rendered on screen —
 * there is no API to fetch "the whole conversation" directly. The only way to reach older
 * messages is the same way a human would: scroll up, wait for it to render, read again.
 * This class automates exactly that, in small bounded bursts, with a checkpoint file so a
 * kill/restart resumes instead of re-scrolling from scratch or looping forever.
 */
class HistoryArchiver(
    private val service: AccessibilityService,
    private val store: MessageStore,
    private val onStateChanged: (username: String, active: Boolean) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val MAX_ITERATIONS_PER_SESSION = 40
    private val NO_PROGRESS_LIMIT = 3
    private val SCROLL_SETTLE_MS = 900L

    private var running = false

    private fun stateFile(username: String): File =
        File(store.conversationsDir(), sanitize(username) + ".scrollstate")

    private fun sanitize(username: String) = username.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)

    fun isDone(username: String): Boolean = stateFile(username).exists() && stateFile(username).readText() == "DONE"

    fun maybeStart(username: String, screenWidthPx: Int) {
        if (running || isDone(username)) return
        running = true
        onStateChanged(username, true)
        runIteration(username, screenWidthPx, iterationsLeft = MAX_ITERATIONS_PER_SESSION, noProgressStreak = 0)
    }

    private fun runIteration(username: String, screenWidthPx: Int, iterationsLeft: Int, noProgressStreak: Int) {
        if (iterationsLeft <= 0) { finish(username, done = false); return }

        val root = service.rootInActiveWindow
        if (root == null) { finish(username, done = false); return }
        val result = try { ConversationParser.parse(root, screenWidthPx) } finally { root.recycle() }

        // If the header disappeared, the user navigated away mid-archive — stop cleanly,
        // resume next time this conversation is opened (checkpoint not marked DONE).
        if (result.username == null || sanitize(result.username) != sanitize(username)) {
            finish(username, done = false); return
        }

        val added = store.prependBatch(username, result.messages)
        val streak = if (added == 0) noProgressStreak + 1 else 0

        if (streak >= NO_PROGRESS_LIMIT) {
            // Two-plus scrolls with nothing new above -> we've reached the top of the thread.
            finish(username, done = true)
            return
        }

        val scrolled = performScrollUp(service.rootInActiveWindow)
        if (!scrolled) { finish(username, done = true); return } // no scrollable container found -> top of a short thread

        handler.postDelayed(
            { runIteration(username, screenWidthPx, iterationsLeft - 1, streak) },
            SCROLL_SETTLE_MS
        )
    }

    private fun performScrollUp(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val scrollable = findScrollableList(root, HashSet())
        val ok = scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ?: false
        root.recycle()
        return ok
    }

    private fun findScrollableList(node: AccessibilityNodeInfo, visited: HashSet<Int>): AccessibilityNodeInfo? {
        val id = System.identityHashCode(node)
        if (!visited.add(id)) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findScrollableList(child, visited)?.let { return it }
        }
        return null
    }

    private fun finish(username: String, done: Boolean) {
        running = false
        onStateChanged(username, false)
        if (done) stateFile(username).writeText("DONE")
        // else: leave no/partial state so the next time this conversation opens, we try again.
    }
}
