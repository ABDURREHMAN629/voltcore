package com.voltcore.app.feature.freezer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.voltcore.app.core.AppGraph
import com.voltcore.app.core.data.InstalledApp
import com.voltcore.app.core.privileged.FreezeMethod
import com.voltcore.app.core.privileged.PrivilegeResult
import com.voltcore.app.core.privileged.PrivilegeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Screen state for the app freezer. */
class FreezerState(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    var apps by mutableStateOf<List<InstalledApp>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
        private set
    var privilege by mutableStateOf(PrivilegeState())
        private set
    var method by mutableStateOf(FreezeMethod.SUSPEND)
        private set
    var showSystem by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    var query by mutableStateOf("")

    /** Packages the user has ticked. Mirrors the persisted set. */
    val selected = mutableStateListOf<String>()

    suspend fun refresh() {
        privilege = graph.privilege.refresh()

        val settings = graph.prefs.settings.first()
        method = settings.freezeMethod
        selected.clear()
        selected.addAll(settings.frozenPackages)

        loading = true
        apps = graph.installedApps.load(includeSystem = showSystem)
        loading = false
    }

    fun visibleApps(): List<InstalledApp> {
        val q = query.trim().lowercase()
        val base = if (q.isEmpty()) {
            apps
        } else {
            apps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        }
        // Selected apps float to the top so a long list stays manageable.
        return base.sortedWith(
            compareByDescending<InstalledApp> { it.packageName in selected }
                .thenBy { it.label.lowercase() },
        )
    }

    fun onShowSystemChanged(value: Boolean) {
        showSystem = value
        scope.launch {
            loading = true
            apps = graph.installedApps.load(includeSystem = value)
            loading = false
        }
    }

    suspend fun toggleSelection(packageName: String) {
        if (packageName in selected) selected.remove(packageName) else selected.add(packageName)
        graph.prefs.setFrozenPackages(selected.toSet())
    }

    suspend fun setMethod(value: FreezeMethod) {
        method = value
        graph.prefs.setFreezeMethod(value)
    }

    suspend fun freezeSelected() {
        if (selected.isEmpty()) return
        busy = true
        message = null

        val failures = mutableListOf<String>()
        var lastReason: String? = null

        for (pkg in selected.toList()) {
            when (val result = graph.privilege.freeze(pkg, method)) {
                is PrivilegeResult.Success -> Unit
                is PrivilegeResult.Failed -> {
                    failures += pkg; lastReason = result.reason
                }
                is PrivilegeResult.Unsupported -> {
                    failures += pkg; lastReason = result.reason
                }
            }
        }

        message = when {
            failures.isEmpty() -> "Froze ${selected.size} app${plural(selected.size)}."
            failures.size == selected.size ->
                lastReason ?: "Could not freeze anything. Check the setup above."
            else -> "Froze ${selected.size - failures.size}, " +
                "${failures.size} failed. ${lastReason.orEmpty()}".trim()
        }

        refreshStates()
        busy = false
    }

    suspend fun unfreezeSelected() {
        busy = true
        message = null
        var restored = 0
        for (pkg in selected.toList()) {
            if (graph.privilege.unfreeze(pkg, method) is PrivilegeResult.Success) restored++
        }
        message = if (restored > 0) {
            "Resumed $restored app${plural(restored)}."
        } else {
            "Nothing to resume."
        }
        refreshStates()
        busy = false
    }

    suspend fun setupShizuku() {
        val state = graph.privilege.refresh()
        privilege = state
        when {
            // Running but unauthorised: the prompt is the whole job.
            state.shizukuRunning && !state.shizukuPermission -> {
                graph.privilege.requestShizukuPermission()
                privilege = graph.privilege.refresh()
            }
            // Installed or not, sending them to the app (or the store) is the next step.
            else -> graph.privilege.openShizuku()
        }
    }

    /** Re-reads suspended/enabled flags so the badges reflect what actually happened. */
    private suspend fun refreshStates() {
        val updated = apps.map { app ->
            val state = graph.installedApps.stateOf(app.packageName)
            if (state == null) app else app.copy(isEnabled = state.first, isSuspended = state.second)
        }
        apps = updated
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
