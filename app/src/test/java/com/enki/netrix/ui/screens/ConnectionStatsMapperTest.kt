package com.enki.netrix.ui.screens

import com.enki.netrix.native.RootStats
import com.enki.netrix.vpn.BypassVpnService
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionStatsMapperTest {
    @Test
    fun selectsVpnStatsInNormalMode() {
        val uiStats = selectConnectionStats(
            useRootMode = false,
            vpnStats = BypassVpnService.Stats(packetsIn = 3, packetsOut = 4, bytesIn = 100, bytesOut = 50),
            rootStats = RootStats(packetsTotal = 99, packetsBypassed = 7)
        )

        assertEquals(7, uiStats.packetsProcessed)
        assertEquals(100, uiStats.bytesIn)
        assertEquals(50, uiStats.bytesOut)
    }

    @Test
    fun selectsRootDaemonStatsInRootMode() {
        val uiStats = selectConnectionStats(
            useRootMode = true,
            vpnStats = BypassVpnService.Stats(packetsIn = 3, packetsOut = 4, bytesIn = 100, bytesOut = 50),
            rootStats = RootStats(packetsTotal = 24, packetsBypassed = 3)
        )

        assertEquals(24, uiStats.packetsProcessed)
        assertEquals(0, uiStats.bytesIn)
        assertEquals(3, uiStats.bytesOut)
    }
}
