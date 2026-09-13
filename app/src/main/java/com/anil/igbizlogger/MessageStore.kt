package com.anil.igbizlogger

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ParsedMessage(
    val text: String,
    val direction: String,      // "IN" or "OUT"
    val isMedia: Boolean,
    val mediaLabel: String? = null,   // e.g. "Photo", "Voice message"
    val visibleTimestamp: String? = null,
    val unknownDesc: String? = null   // set for [Unknown/Unsupported] fallback entries
)

/**
 * Handles all disk I/O: one plain-text file per conversation.
 *
 * v1.2 identity model: a screen scan gives us an ORDERED list of visible messages.
 * We keep the committed log's fingerprints in the same order (.idx, one per line) and
 * align the tail of that order against the new scan (see appendBatch). This preserves
 * legitimate repeats like two separate "okay" messages, because they're told apart by
 * their position in the sequence, not just their content.
 *
 * Known limitation (Android/Accessibility constraint, not fixable in this layer): if the
 * app is reopened and the first scan has zero overlap with the last committed anchor
 * window (e.g. a big time gap, or the conversation was scrolled far away), we fall back
 * to plain content-based dedup for that one scan, which *can* re-collapse genuine
 * back-to-back duplicates in that edge case. A proper fix would need a persistent
 * per-message ID from Instagram itself, which the Accessibility tree does not expose.
 */
class MessageStore(context: Context) {

    private val rootDir: File = File(context.getExternalFilesDir(null), "IGBizLogs").apply { mkdirs() }
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val ANCHOR = 6

    // username -> ordered list of committed fingerprints (mirrors the .idx file on disk)
    private val committedCache = HashMap<String, MutableList<String>>()
    // username -> full ever-seen set, fallback for when anchor alignment fails
    private val everSeenCache = HashMap<String, MutableSet<String>>()

    init {
        // Crash recovery: any leftover .tmp file means a rewrite was interrupted before
        // its atomic rename completed. The original file was never touched in that
        // pattern, so it's always safe to just discard the stray temp file.
        rootDir.listFiles { f -> f.extension == "tmp" }?.forEach { it.delete() }
    }

    private fun sanitize(username: String): String =
        username.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80).ifBlank { "unknown_user" }

    private fun fingerprintOf(msg: ParsedMessage): String {
        val basis = "${msg.direction}|${msg.text}|${msg.mediaLabel ?: ""}|${msg.unknownDesc ?: ""}"
        val bytes = MessageDigest.getInstance("SHA-256").digest(basis.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun idxFile(username: String) = File(rootDir, "${sanitize(username)}.idx")
    private fun logFile(username: String) = File(rootDir, "${sanitize(username)}.txt")

    private fun loadCommitted(username: String): MutableList<String> =
        committedCache.getOrPut(username) {
            val f = idxFile(username)
            if (f.exists()) f.readLines().filter { it.isNotBlank() }.toMutableList() else mutableListOf()
        }

    private fun loadEverSeen(username: String): MutableSet<String> =
        everSeenCache.getOrPut(username) { loadCommitted(username).toMutableSet() }

    /** fsync'd append — not fully atomic (that needs a DB), but ensures a written line survives a crash. */
    private fun appendDurable(file: File, text: String) {
        FileOutputStream(file, true).use { fos ->
            fos.write(text.toByteArray())
            fos.fd.sync()
        }
    }

    /** Atomic full-file rewrite via temp file + rename. */
    private fun writeAtomic(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        tmp.renameTo(file) // rename() is atomic on the same filesystem
    }

    private fun formatLine(msg: ParsedMessage): String {
        val loggedAt = timeFmt.format(Date())
        return buildString {
            append("[$loggedAt]")
            if (msg.visibleTimestamp != null) append(" (shown: ${msg.visibleTimestamp})")
            append(" ${msg.direction}: ")
            when {
                msg.isMedia -> append("[${msg.mediaLabel ?: "Media"}]")
                msg.unknownDesc != null -> append("[Unknown/Unsupported: ${msg.unknownDesc}]")
                else -> append(msg.text)
            }
        }
    }

    /**
     * Aligns an ordered scan of currently-visible messages against the committed log and
     * appends only the genuinely new tail. Returns how many new lines were written.
     */
    @Synchronized
    fun appendBatch(username: String, scan: List<ParsedMessage>): Int {
        if (scan.isEmpty()) return 0
        val committed = loadCommitted(username)
        val scanFp = scan.map { fingerprintOf(it) }

        val newStartIndex: Int = if (committed.isEmpty()) {
            0
        } else {
            var found = -1
            var tailLen = minOf(ANCHOR, committed.size)
            while (tailLen > 0 && found == -1) {
                val tail = committed.takeLast(tailLen)
                for (i in 0..(scanFp.size - tailLen)) {
                    if (scanFp.subList(i, i + tailLen) == tail) { found = i + tailLen; break }
                }
                tailLen--
            }
            found // -1 if no overlap at all
        }

        val newLines = StringBuilder()
        val newFingerprints = mutableListOf<String>()
        var written = 0

        if (newStartIndex >= 0) {
            // Clean case: we know exactly where the log left off.
            for (i in newStartIndex until scan.size) {
                newLines.append(formatLine(scan[i])).append("\n")
                newFingerprints.add(scanFp[i])
                written++
            }
        } else {
            // Fallback case (documented limitation above): no overlap found, so dedup
            // against the full ever-seen set instead of the ordered tail.
            val everSeen = loadEverSeen(username)
            for (i in scan.indices) {
                if (everSeen.contains(scanFp[i])) continue
                newLines.append(formatLine(scan[i])).append("\n")
                newFingerprints.add(scanFp[i])
                written++
            }
        }

        if (written > 0) {
            appendDurable(logFile(username), newLines.toString())
            appendDurable(idxFile(username), newFingerprints.joinToString("\n") + "\n")
            committed.addAll(newFingerprints)
            loadEverSeen(username).addAll(newFingerprints)
            trimIndexIfNeeded(username, committed)
        }
        return written
    }

    private fun trimIndexIfNeeded(username: String, committed: MutableList<String>) {
        if (committed.size <= 6000) return
        val trimmed = committed.takeLast(4000).toMutableList()
        committedCache[username] = trimmed
        writeAtomic(idxFile(username), trimmed.joinToString("\n") + "\n")
        // everSeenCache intentionally keeps the full history so old messages don't
        // get re-logged after trimming — only the ordered anchor index is trimmed.
    }

    /**
     * Called when we detect an "unsent"/"removed" placeholder on screen in place of a
     * real message. direction is the placeholder's own left/right position: OUT -> we
     * unsent our own message (SELF), IN -> the other party unsent theirs (OTHER).
     */
    @Synchronized
    fun markLastAsDeleted(username: String, direction: String) {
        val file = logFile(username)
        if (!file.exists()) return
        val lines = file.readLines().toMutableList()
        val marker = " $direction: "
        val tag = if (direction == "OUT") "DELETED-SELF" else "DELETED-OTHER"
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            if (line.contains(marker) && !line.contains("[DELETED-")) {
                lines[i] = line.replaceFirst(marker, " $direction [$tag]: ")
                writeAtomic(file, lines.joinToString("\n") + "\n")
                return
            }
        }
    }

    /** Full wipe, used by the "Stop logging & delete all data" control. */
    @Synchronized
    fun deleteAllData() {
        rootDir.listFiles()?.forEach { it.delete() }
        committedCache.clear()
        everSeenCache.clear()
    }

    /** Shared root, so HistoryArchiver's scroll-checkpoint files live alongside the logs. */
    fun conversationsDir(): File = rootDir

    /**
     * Mirror of appendBatch, but for scroll-back history archiving: `scan` is ordered
     * oldest-to-newest (same as appendBatch expects) and represents messages ABOVE what
     * we've already committed. We align the *tail* of the scan against the *head* of the
     * committed log and prepend whatever comes before that overlap.
     *
     * Note: unlike appendBatch, a full-file rewrite is required here since we're adding to
     * the front of the file. Fine at personal-business conversation scale; for a very large
     * multi-thousand-message thread this becomes O(n) per scroll step — a real fix would
     * move storage to SQLite, which is a bigger change intentionally left for a future pass.
     */
    @Synchronized
    fun prependBatch(username: String, scan: List<ParsedMessage>): Int {
        if (scan.isEmpty()) return 0
        val committed = loadCommitted(username)
        val scanFp = scan.map { fingerprintOf(it) }

        val olderCount: Int = if (committed.isEmpty()) {
            scan.size
        } else {
            var matchStart = -1
            var windowSize = minOf(ANCHOR, committed.size)
            while (windowSize > 0 && matchStart == -1) {
                val head = committed.take(windowSize)
                for (i in 0..(scanFp.size - windowSize)) {
                    if (scanFp.subList(i, i + windowSize) == head) { matchStart = i; break }
                }
                windowSize--
            }
            matchStart // -1 if we can't align this scroll step at all — skip it, documented limitation above
        }

        if (olderCount <= 0) return 0
        val olderMessages = scan.subList(0, olderCount)
        val olderLines = olderMessages.joinToString("\n") { formatLine(it) }
        val olderFingerprints = olderMessages.map { fingerprintOf(it) }

        val txt = logFile(username)
        val idx = idxFile(username)
        val existingTxt = if (txt.exists()) txt.readText() else ""
        val existingIdx = if (idx.exists()) idx.readText() else ""
        writeAtomic(txt, olderLines + "\n" + existingTxt)
        writeAtomic(idx, olderFingerprints.joinToString("\n") + "\n" + existingIdx)

        committed.addAll(0, olderFingerprints)
        loadEverSeen(username).addAll(olderFingerprints)
        return olderMessages.size
    }
}
