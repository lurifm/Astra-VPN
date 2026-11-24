package com.astra.vpn

import android.content.Intent
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings shortcut. The Activity owns permission prompts and VPN state transitions. */
class AstraQuickSettingsTileService : TileService() {
    companion object {
        /** Ask Android to refresh the tile from the latest persisted service state. */
        fun requestSync(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            runCatching {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, AstraQuickSettingsTileService::class.java)
                )
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val launch = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_TILE_TOGGLE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launch)
        }
    }

    private fun syncTile() {
        val state = getSharedPreferences(AstraVpnService.STATE_PREFS, MODE_PRIVATE)
            .getString(AstraVpnService.PREF_STATE, AstraVpnService.STATE_DISCONNECTED)
        qsTile?.apply {
            this.state = if (state == AstraVpnService.STATE_CONNECTED) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Astra VPN"
            contentDescription = VpnSurfaceStatusPolicy.tileContentDescription(state)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                stateDescription = VpnSurfaceStatusPolicy.tileStateDescription(state)
            }
            updateTile()
        }
    }
}
