package com.astra.vpn

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build

/** Adds an app-icon shortcut that opens Astra's main screen. */
internal object AstraQuickShortcut {
    fun publish(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val openShortcut = ShortcutInfo.Builder(context, "astra_open")
            .setShortLabel(context.getString(R.string.shortcut_open))
            .setLongLabel(context.getString(R.string.shortcut_open_long))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_vpn))
            .setIntent(intent)
            .build()
        val connectIntent = Intent(intent).setAction(MainActivity.ACTION_TILE_TOGGLE)
        val connectShortcut = ShortcutInfo.Builder(context, "astra_connect")
            .setShortLabel(context.getString(R.string.shortcut_connect))
            .setLongLabel(context.getString(R.string.shortcut_connect_long))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_vpn))
            .setIntent(connectIntent)
            .build()
        runCatching { manager.dynamicShortcuts = listOf(openShortcut, connectShortcut) }
    }
}
