package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnSurfaceStatusPolicyTest {
    @Test
    fun tileExplainsConnectingStateInsteadOfClaimingDisconnected() {
        assertEquals("Подключение…", VpnSurfaceStatusPolicy.tileStateDescription(AstraVpnService.STATE_CONNECTING))
        assertEquals(
            "VPN подключается. Нажмите, чтобы открыть Astra VPN",
            VpnSurfaceStatusPolicy.tileContentDescription(AstraVpnService.STATE_CONNECTING)
        )
    }

    @Test
    fun tileDistinguishesConnectedErrorAndDisconnectedStates() {
        assertEquals("Подключено", VpnSurfaceStatusPolicy.tileStateDescription(AstraVpnService.STATE_CONNECTED))
        assertEquals("Ошибка", VpnSurfaceStatusPolicy.tileStateDescription(AstraVpnService.STATE_ERROR))
        assertEquals("Отключено", VpnSurfaceStatusPolicy.tileStateDescription(AstraVpnService.STATE_DISCONNECTED))
    }

    @Test
    fun notificationUsesSpecificErrorAndFallsBackForBlankMessage() {
        assertEquals("Не удалось запустить VPN", VpnSurfaceStatusPolicy.notificationErrorDetail(" Не удалось запустить VPN "))
        assertEquals("Не удалось установить соединение", VpnSurfaceStatusPolicy.notificationErrorDetail("  "))
        assertEquals("Не удалось установить соединение", VpnSurfaceStatusPolicy.notificationErrorDetail(null))
    }

    @Test
    fun notificationErrorDetailIsBounded() {
        val detail = VpnSurfaceStatusPolicy.notificationErrorDetail("x".repeat(200))
        assertEquals(160, detail.length)
        assertEquals("…", detail.takeLast(1))
    }
}
