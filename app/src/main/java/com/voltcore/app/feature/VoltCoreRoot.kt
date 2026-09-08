package com.voltcore.app.feature

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.voltcore.app.core.AppGraph
import com.voltcore.app.core.data.VoltSettings
import com.voltcore.app.feature.boost.BoostRoute
import com.voltcore.app.feature.dashboard.DashboardRoute
import com.voltcore.app.feature.freezer.FreezerRoute
import com.voltcore.app.feature.history.HistoryRoute
import com.voltcore.app.feature.settings.SettingsRoute
import com.voltcore.app.ui.components.VoltIcons
import com.voltcore.app.ui.theme.VoltText
import com.voltcore.app.ui.theme.VoltTheme

enum class VoltTab(val label: String, val icon: ImageVector) {
    POWER("Power", VoltIcons.Bolt),
    HISTORY("History", VoltIcons.Pulse),
    BOOST("Boost", VoltIcons.Boost),
    APPS("Apps", VoltIcons.Snowflake),
    SETTINGS("Settings", VoltIcons.Sliders),
}

/**
 * The app shell.
 *
 * Five flat destinations with no nesting, so a saved tab index carries the whole
 * navigation state and a NavHost would only add indirection.
 */
@Composable
fun VoltCoreRoot(
    graph: AppGraph,
    settings: VoltSettings,
    initialTab: VoltTab = VoltTab.POWER,
    autoBoost: Boolean = false,
    /** Bumped on every incoming launch intent so a repeat tap still navigates. */
    navRequest: Int = 0,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }

    // rememberSaveable only seeds from initialTab once, so a later intent needs its own
    // signal to move the selection.
    LaunchedEffect(navRequest) {
        if (navRequest > 0) tab = initialTab
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (tab) {
            VoltTab.POWER -> DashboardRoute(graph, settings)
            VoltTab.HISTORY -> HistoryRoute(graph)
            VoltTab.BOOST -> BoostRoute(graph, autoStart = autoBoost)
            VoltTab.APPS -> FreezerRoute(graph)
            VoltTab.SETTINGS -> SettingsRoute(graph)
        }

        VoltBottomBar(
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun VoltBottomBar(
    selected: VoltTab,
    onSelect: (VoltTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VoltTheme.colors

    Box(
        modifier = modifier
            .fillMaxWidth()
            // A gradient scrim rather than a solid bar, so content scrolls away under it
            // instead of stopping at a hard edge. The gradient fades to the background
            // colour, so the strip behind the system gesture bar stays fully opaque.
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.glass)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoltTab.entries.forEach { entry ->
                TabItem(
                    tab = entry,
                    selected = entry == selected,
                    onClick = { onSelect(entry) },
                )
            }
        }
    }
}

@Composable
private fun TabItem(tab: VoltTab, selected: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.9f,
        animationSpec = spring(dampingRatio = 0.55f),
        label = "tabScale",
    )
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val interaction = remember { MutableInteractionSource() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier
                .size(22.dp)
                .scale(scale)
                .alpha(if (selected) 1f else 0.75f),
        )
        Spacer(Modifier.height(3.dp))
        AnimatedVisibility(visible = selected, enter = fadeIn(), exit = fadeOut()) {
            Text(tab.label, style = VoltText.caption, color = tint)
        }
    }
}
