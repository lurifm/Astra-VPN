package com.astra.vpn

import io.nekohasekai.libbox.PlatformInterface

abstract class AstraPlatform : PlatformInterface {

    override fun usePlatformAutoDetectInterfaceControl(): Boolean {
        return true
    }

    override fun autoDetectInterfaceControl(fd: Int) {
    }
}
