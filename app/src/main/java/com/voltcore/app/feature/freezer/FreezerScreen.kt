package com.voltcore.app.feature.freezer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voltcore.app.core.AppGraph
import com.voltcore.app.core.data.InstalledApp
import com.voltcore.app.core.privileged.FreezeMethod
import com.voltcore.app.core.privileged.PrivilegeState
import com.voltcore.app.ui.components.AppIcon
import com.voltcore.app.ui.components.Badge
import com.voltcore.app.ui.components.GlassCard
import com.voltcore.app.ui.components.SectionLabel
import com.voltcore.app.ui.components.VoltIcons
import com.voltcore.app.ui.theme.VoltText
import com.voltcore.app.ui.theme.VoltTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun FreezerRoute(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    val state = remember { FreezerState(graph, scope) }

    LaunchedEffect(Unit) { state.refresh() }

    FreezerScreen(
        state = state,
        onToggle = { pkg -> scope.launch { state.toggleSelection(pkg) } },
        onFreeze = { scope.launch { state.freezeSelected() } },
        onUnfreeze = { scope.launch { state.unfreezeSelected() } },
        onMethodChange = { m -> scope.launch { state.setMethod(m) } },
        onSetupShizuku = { scope.launch { state.setupShizuku() } },
    )
}

@Composable
private fun FreezerScreen(
    state: FreezerState,
    onToggle: (String) -> Unit,
    onFreeze: () -> Unit,
    onUnfreeze: () -> Unit,
    onMethodChange: (FreezeMethod) -> Unit,
    onSetupShizuku: () -> Unit,
) {
    val colors = VoltTheme.colors
    val visible = state.visibleApps()

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "App freezer",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Pick the apps that drain the battery in the background. " +
                        "VoltCore keeps them stopped until you resume them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
            }

            item { PrivilegeCard(state.privilege, onSetupShizuku) }

            item { MethodPicker(state.method, state.privilege, onMethodChange) }

            item {
                ActionBar(
                    selectedCount = state.selected.size,
                    busy = state.busy,
                    canAct = state.privilege.tier.rank >= 2,
                    onFreeze = onFreeze,
                    onUnfreeze = onUnfreeze,
                )
            }

            state.message?.let { msg ->
                item {
                    GlassCard(accent = colors.warn) {
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { state.query = it },
                    placeholder = { Text("Search apps") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel("${visible.size} apps")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Show system",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Switch(
                            checked = state.showSystem,
                            onCheckedChange = { state.onShowSystemChanged(it) },
                        )
                    }
                }
            }

            if (state.loading) {
                item {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            items(visible, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    checked = app.packageName in state.selected,
                    onToggle = { onToggle(app.packageName) },
                )
            }

            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

// ------------------------------------------------------------------------- privilege

@Composable
private fun PrivilegeCard(state: PrivilegeState, onSetup: () -> Unit) {
    val colors = VoltTheme.colors
    val (accent, title, body, action) = when {
        state.shizukuPermission -> Quad(
            colors.good,
            "Shizuku connected",
            "VoltCore can pause and resume apps instantly, with no root and no effect on " +
                "banking apps or Play Integrity.",
            null,
        )

        state.shizukuRunning && !state.shizukuPermission -> Quad(
            colors.warn,
            "Shizuku needs permission",
            "Shizuku is running. Grant VoltCore access to let it pause apps.",
            "Grant permission",
        )

        state.shizukuInstalled -> Quad(
            colors.warn,
            "Start Shizuku",
            "Shizuku is installed but not running. Open it and start the service using " +
                "Wireless debugging — this does not root your phone.",
            "Open Shizuku",
        )

        state.accessibilityEnabled -> Quad(
            colors.warn,
            "Limited mode",
            "Without Shizuku, VoltCore can only force stop apps by driving the Settings " +
                "screen. It is slow and apps can restart themselves.",
            "Set up Shizuku",
        )

        else -> Quad(
            colors.danger,
            "Setup needed",
            "Android gives no app the right to stop another app. Shizuku grants that " +
                "safely over Wireless debugging — no root, no unlocked bootloader, and " +
                "nothing that trips banking apps.",
            "Set up Shizuku",
        )
    }

    GlassCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                VoltIcons.Shield,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSetup, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                Text(action, color = accent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private data class Quad(
    val accent: Color,
    val title: String,
    val body: String,
    val action: String?,
)

// ---------------------------------------------------------------------------- method

@Composable
private fun MethodPicker(
    method: FreezeMethod,
    privilege: PrivilegeState,
    onChange: (FreezeMethod) -> Unit,
) {
    GlassCard {
        SectionLabel("What should happen")
        Spacer(Modifier.height(8.dp))
        FreezeMethod.entries.forEach { entry ->
            val enabled = entry == FreezeMethod.FORCE_STOP || privilege.canPause
            MethodRow(
                method = entry,
                selected = entry == method,
                enabled = enabled,
                onClick = { if (enabled) onChange(entry) },
            )
        }
    }
}

@Composable
private fun MethodRow(
    method: FreezeMethod,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .background(if (selected) accent.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) accent else MaterialTheme.colorScheme.outline),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                method.label,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
            )
            Text(
                if (enabled) method.description else "Needs Shizuku",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------- actions

@Composable
private fun ActionBar(
    selectedCount: Int,
    busy: Boolean,
    canAct: Boolean,
    onFreeze: () -> Unit,
    onUnfreeze: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onFreeze,
            enabled = !busy && canAct && selectedCount > 0,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.weight(1f),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(if (selectedCount > 0) "Freeze $selectedCount" else "Freeze")
        }
        TextButton(
            onClick = onUnfreeze,
            enabled = !busy && canAct,
            modifier = Modifier.weight(1f),
        ) {
            Text("Resume all")
        }
    }
}

// ------------------------------------------------------------------------- list rows

@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, onToggle: () -> Unit) {
    val colors = VoltTheme.colors
    GlassCard(cornerRadius = 16.dp, contentPadding = 10.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !app.isProtected, onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app.packageName, app.label)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        app.packageName,
                        style = VoltText.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                app.isProtected -> Badge("Protected", colors.trickle)
                app.isSuspended -> Badge("Paused", colors.slow)
                !app.isEnabled -> Badge("Disabled", colors.danger)
                else -> Checkbox(
                    checked = checked,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }
    }
}
