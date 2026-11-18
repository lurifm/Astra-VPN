package com.astra.vpn

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings shortcut. The Activity owns permission prompts and VPN state transitions. */
class AstraQuickSettingsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        val launch = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_TILE_TOGGLE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivityAndCollapse(launch)
    }

    private fun syncTile() {
        val state = getSharedPreferences(AstraVpnService.STATE_PREFS, MODE_PRIVATE)
            .getString(AstraVpnService.PREF_STATE, AstraVpnService.STATE_DISCONNECTED)
        qsTile?.apply {
            this.state = if (state == AstraVpnService.STATE_CONNECTED) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Astra VPN"
            contentDescription = if (this.state == Tile.STATE_ACTIVE) "VPN подключён. Нажмите для отключения" else "VPN отключён. Нажмите для подключения"
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                stateDescription = if (this.state == Tile.STATE_ACTIVE) "Подключено" else "Отключено"
            }
            updateTile()
        }
    }
}
