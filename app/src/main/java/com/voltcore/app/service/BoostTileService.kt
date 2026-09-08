package com.voltcore.app.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.voltcore.app.MainActivity
import com.voltcore.app.VoltCoreApp
import com.voltcore.app.core.util.Fmt

/**
 * Quick Settings tile: shows the live charging rate and starts a boost.
 *
 * Tapping opens the app rather than boosting silently in the background. Boost is a
 * measurement — it samples current, acts, then samples again — and a number the user
 * never sees would be pointless. It also means stopping apps always happens somewhere
 * visible, never invisibly from the shade.
 */
class BoostTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()

        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_BOOST)
            .putExtra(MainActivity.EXTRA_AUTO_BOOST, true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 replaced the Intent overload with a PendingIntent one.
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val snapshot = runCatching {
            (application as VoltCoreApp).graph.telemetry.read()
        }.getOrNull()

        tile.state = if (snapshot?.status?.isCharging == true) {
            Tile.STATE_ACTIVE
        } else {
            Tile.STATE_INACTIVE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                snapshot == null -> "Tap to open"
                snapshot.status.isCharging -> {
                    val current = Fmt.current(snapshot.currentMa)
                    if (current != null) {
                        "$current ${Fmt.currentUnit(snapshot.currentMa)}"
                    } else {
                        snapshot.speed.label
                    }
                }
                else -> "${snapshot.levelPercent}%"
            }
        }
        runCatching { tile.updateTile() }
    }
}
