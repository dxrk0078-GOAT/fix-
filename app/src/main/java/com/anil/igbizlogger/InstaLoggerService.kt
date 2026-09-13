package com.anil.igbizlogger

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class InstaLoggerService : AccessibilityService() {

    private lateinit var store: MessageStore
    private lateinit var archiver: HistoryArchiver
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRun: Runnable? = null

    private val DEBOUNCE_MS = 700L
    private var signatureOk = true

    private var lastKnownUsername: String? = null
    // Guards the live-vs-archiver race: while HistoryArchiver is scrolling a given
    // conversation's history, the live path must NOT also append to that same file.
    private var archivingUsername: String? = null

    // "Every time Instagram opens" export trigger: treats a long gap since the last
    // processed event as a fresh open, with a cooldown so reopening repeatedly in a
    // short window doesn't re-export every time.
    private var lastEventAtMs = 0L
    private val OPEN_GAP_MS = 120_000L
    private val ON_OPEN_EXPORT_COOLDOWN_MS = 30 * 60_000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        signatureOk = SignatureGuard.isGenuine(applicationContext)
        if (!signatureOk) {
            AppLog.log(applicationContext, "Signature mismatch — refusing to activate logging.")
            return
        }
        store = MessageStore(applicationContext)
        archiver = HistoryArchiver(this, store) { username, active ->
            archivingUsername = if (active) username else null
        }
        AppLog.log(applicationContext, "Service connected, signature OK")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !signatureOk) return
        if (event.packageName?.toString() != "com.instagram.android") return

        val now = System.currentTimeMillis()
        val freshOpen = now - lastEventAtMs > OPEN_GAP_MS
        lastEventAtMs = now
        if (freshOpen) maybeTriggerOnOpenExport()

        pendingRun?.let { mainHandler.removeCallbacks(it) }
        val run = Runnable { processCurrentScreen() }
        pendingRun = run
        mainHandler.postDelayed(run, DEBOUNCE_MS)
    }

    private fun maybeTriggerOnOpenExport() {
        if (SecurePrefs.getExportMode(applicationContext) != "ON_OPEN") return
        val last = SecurePrefs.getLastExportTriggerMillis(applicationContext)
        if (System.currentTimeMillis() - last < ON_OPEN_EXPORT_COOLDOWN_MS) return
        SecurePrefs.setLastExportTriggerMillis(applicationContext, System.currentTimeMillis())
        WorkManager.getInstance(applicationContext).enqueue(OneTimeWorkRequestBuilder<DailyExportWorker>().build())
        AppLog.log(applicationContext, "Export triggered by Instagram opening")
    }

    private fun processCurrentScreen() {
        val root = rootInActiveWindow ?: return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getMetrics(metrics)
        val screenWidth = metrics.widthPixels.takeIf { it > 0 } ?: 1080

        val result = try {
            ConversationParser.parse(root, screenWidth)
        } finally {
            root.recycle()
        }

        val username = result.username ?: run {
            if (result.messages.isEmpty() && result.unsendEvents.isEmpty()) {
                lastKnownUsername = null
                return
            }
            lastKnownUsername
        } ?: return

        val conversationSwitched = username != lastKnownUsername
        lastKnownUsername = username

        // While HistoryArchiver owns this conversation, the live path steps aside
        // entirely — the archiver's own prependBatch call is the only writer.
        if (archivingUsername == username) return

        if (result.messages.isNotEmpty()) {
            store.appendBatch(username, result.messages)
        }
        for (direction in result.unsendEvents) {
            store.markLastAsDeleted(username, direction)
        }

        if (conversationSwitched && !archiver.isDone(username)) {
            archiver.maybeStart(username, screenWidth)
        }
    }

    private val windowManager
        get() = getSystemService(WINDOW_SERVICE) as? android.view.WindowManager

    override fun onInterrupt() {}
}
