package com.voltcore.app.core.freezer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.voltcore.app.core.data.VoltPrefs
import com.voltcore.app.core.privileged.PrivilegeManager
import com.voltcore.app.core.privileged.PrivilegeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Freezes the chosen apps a while after the screen goes off, and brings them back when
 * the user unlocks.
 *
 * The delay matters. Freezing the instant the screen blanks catches apps mid-sync and
 * makes the phone feel broken when you pick it up ten seconds later; waiting a few
 * minutes means only genuinely idle time is affected.
 *
 * Screen state cannot be observed from a manifest receiver — ACTION_SCREEN_OFF is
 * explicitly excluded — so this has to be registered at runtime by a running service.
 */
class ScreenStateAutomation(
    private val context: Context,
    private val prefs: VoltPrefs,
    private val privilege: PrivilegeManager,
    private val scope: CoroutineScope,
) {

    private var pendingFreeze: Job? = null
    private var frozenByAutomation = mutableSetOf<String>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> onUserBack()
            }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching { context.registerReceiver(receiver, filter) }
            .onSuccess { registered = true }
    }

    fun stop() {
        pendingFreeze?.cancel()
        pendingFreeze = null
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
    }

    private fun onScreenOff() {
        pendingFreeze?.cancel()
        pendingFreeze = scope.launch {
            val settings = prefs.settings.first()
            if (!settings.freezeOnScreenOff) return@launch
            if (settings.frozenPackages.isEmpty()) return@launch

            delay(settings.freezeDelayMinutes.toLong() * 60_000L)

            for (pkg in settings.frozenPackages) {
                if (privilege.freeze(pkg, settings.freezeMethod) is PrivilegeResult.Success) {
                    frozenByAutomation += pkg
                }
            }
        }
    }

    private fun onUserBack() {
        // Cancel a freeze that has not fired yet — the user came back before the timer.
        pendingFreeze?.cancel()
        pendingFreeze = null

        if (frozenByAutomation.isEmpty()) return
        scope.launch {
            val settings = prefs.settings.first()
            if (!settings.unfreezeOnUnlock) return@launch
            // Only undo what this automation did; apps the user froze by hand stay frozen.
            for (pkg in frozenByAutomation.toList()) {
                privilege.unfreeze(pkg, settings.freezeMethod)
            }
            frozenByAutomation.clear()
        }
    }
}
