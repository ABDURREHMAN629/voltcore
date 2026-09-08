package com.voltcore.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.voltcore.app.core.data.VoltSettings
import com.voltcore.app.feature.VoltCoreRoot
import com.voltcore.app.feature.VoltTab
import com.voltcore.app.service.MonitorService
import com.voltcore.app.ui.theme.VoltCoreTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* advisory */ }

    /** Set from the launching intent; the Quick Settings tile uses it to jump to Boost. */
    private var launchTab by mutableStateOf(VoltTab.POWER)
    private var autoBoost by mutableStateOf(false)
    private var navRequest by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val graph = (application as VoltCoreApp).graph
        applyLaunchIntent(intent)

        // The live readout is a notification, so without this permission the app's main
        // background feature silently does nothing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            if (graph.prefs.settings.first().monitorEnabled) {
                MonitorService.start(this@MainActivity)
            }
        }

        setContent {
            val settings by graph.prefs.settings
                .collectAsStateWithLifecycle(initialValue = VoltSettings())

            // Handled here rather than per screen: it is a property of the window, and
            // scoping it to the activity guarantees the flag is released.
            DisposableEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            VoltCoreTheme(dynamicColor = settings.dynamicColor) {
                VoltCoreRoot(
                    graph = graph,
                    settings = settings,
                    initialTab = launchTab,
                    autoBoost = autoBoost,
                    navRequest = navRequest,
                )
            }
        }
    }

    /** singleTask means a second launch arrives here rather than in onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunchIntent(intent)
    }

    private fun applyLaunchIntent(intent: Intent?) {
        val requested = intent?.getStringExtra(EXTRA_TAB) ?: return
        launchTab = runCatching { VoltTab.valueOf(requested) }.getOrDefault(VoltTab.POWER)
        autoBoost = intent.getBooleanExtra(EXTRA_AUTO_BOOST, false)
        navRequest++
    }

    companion object {
        const val EXTRA_TAB = "com.voltcore.app.extra.TAB"
        const val EXTRA_AUTO_BOOST = "com.voltcore.app.extra.AUTO_BOOST"
        const val TAB_BOOST = "BOOST"
    }
}
