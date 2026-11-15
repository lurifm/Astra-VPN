package com.astra.vpn

import android.content.Intent
import android.net.VpnService

class AstraVpnService : VpnService() {

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        return START_NOT_STICKY
    }
}
