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
        val shortcut = ShortcutInfo.Builder(context, "astra_open")
            .setShortLabel(context.getString(R.string.shortcut_open))
            .setLongLabel(context.getString(R.string.shortcut_open_long))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_vpn))
            .setIntent(intent)
            .build()
        runCatching { manager.dynamicShortcuts = listOf(shortcut) }
    }
}
