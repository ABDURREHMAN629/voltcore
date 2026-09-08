package com.voltcore.app.core.privileged

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Force-stops apps by driving the system Settings UI.
 *
 * Android has never offered an API for stopping another app, so with no Shizuku and no
 * root the only route left is the one a person would take by hand: open App info, press
 * Force stop, confirm. That is what this does, one package at a time.
 *
 * It is deliberately the last resort. It is slow, it steals the foreground while it runs,
 * and it depends on button labels that OEMs rename, so it is offered as best-effort and
 * the UI pushes people towards Shizuku instead.
 */
class ForceStopAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private var currentPackage: String? = null
    private var stage = Stage.IDLE

    private enum class Stage { IDLE, OPENING, FIND_BUTTON, CONFIRM, DONE }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        pump()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (currentPackage == null) return
        if (event?.packageName != SETTINGS_PACKAGE) return

        when (stage) {
            Stage.OPENING, Stage.FIND_BUTTON -> tryClickForceStop()
            Stage.CONFIRM -> tryConfirm()
            else -> Unit
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ state machine

    private fun pump() {
        if (busy.get()) return
        val next = queue.poll() ?: run {
            onBatchComplete?.invoke()
            return
        }
        busy.set(true)
        currentPackage = next
        stage = Stage.OPENING
        openAppInfo(next)
        // Give up on a package rather than wedging the whole queue.
        handler.postDelayed({ finish(success = false) }, PER_APP_TIMEOUT_MS)
    }

    private fun openAppInfo(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        runCatching { startActivity(intent) }
            .onFailure { finish(success = false) }
    }

    private fun tryClickForceStop() {
        val root = rootInActiveWindow ?: return
        val button = findByText(root, FORCE_STOP_LABELS) ?: return

        // A greyed-out button means the app is already stopped: that counts as done.
        if (!button.isEnabled) {
            finish(success = true)
            return
        }
        if (clickable(button)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
            stage = Stage.CONFIRM
        }
    }

    private fun tryConfirm() {
        val root = rootInActiveWindow ?: return
        val confirm = findByText(root, CONFIRM_LABELS) ?: return
        if (clickable(confirm)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
            finish(success = true)
        }
    }

    private fun finish(success: Boolean) {
        val pkg = currentPackage ?: return
        handler.removeCallbacksAndMessages(null)
        currentPackage = null
        stage = Stage.IDLE
        busy.set(false)
        onPackageDone?.invoke(pkg, success)

        // Close Settings and move on.
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ pump() }, BETWEEN_APPS_MS)
    }

    // ------------------------------------------------------------------ node search

    /**
     * Depth-first search for a node whose text or content description matches, compared
     * case-insensitively against a list of known labels. Matching on text rather than a
     * view id is the lesser evil: OEMs renumber ids far more often than they rename the
     * button, and this way one code path covers stock, One UI and MIUI.
     */
    private fun findByText(root: AccessibilityNodeInfo, labels: Set<String>): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++
            val text = node.text?.toString()?.trim()?.lowercase()
            val desc = node.contentDescription?.toString()?.trim()?.lowercase()
            if ((text != null && text in labels) || (desc != null && desc in labels)) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    /** Walks up until something actually accepts a click. */
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cursor: AccessibilityNodeInfo? = node
        var hops = 0
        while (cursor != null && hops < 6) {
            if (cursor.isClickable) return cursor
            cursor = cursor.parent
            hops++
        }
        return null
    }

    companion object {
        @Volatile
        var instance: ForceStopAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null

        private val queue = ConcurrentLinkedQueue<String>()

        /** Called after each package, with whether the stop appeared to succeed. */
        @Volatile
        var onPackageDone: ((String, Boolean) -> Unit)? = null

        @Volatile
        var onBatchComplete: (() -> Unit)? = null

        fun enqueue(packages: List<String>) {
            queue.addAll(packages)
            instance?.handler?.post { instance?.pump() }
        }

        fun clear() = queue.clear()

        /** Opens the system page where the user turns this service on. */
        fun openSettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val PER_APP_TIMEOUT_MS = 6_000L
        private const val BETWEEN_APPS_MS = 600L
        private const val MAX_NODES = 900

        private val FORCE_STOP_LABELS = setOf(
            "force stop", "force close", "forcestop", "stop", "force-stop",
        )
        private val CONFIRM_LABELS = setOf(
            "ok", "force stop", "stop", "yes", "confirm",
        )
    }
}
