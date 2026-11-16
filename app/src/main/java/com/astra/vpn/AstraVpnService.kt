package com.astra.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Build

class AstraVpnService : VpnService() {

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(
                1,
                AstraNotification.create(this)
            )
        }

        return START_STICKY
    }
}
