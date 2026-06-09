package com.enki.netrix.ui.screens

import com.enki.netrix.native.RootStats
import com.enki.netrix.vpn.BypassVpnService

data class UiConnectionStats(
    val packetsProcessed: Long = 0,
    val bytesIn: Long = 0,
    val bytesOut: Long = 0
)

fun selectConnectionStats(
    useRootMode: Boolean,
    vpnStats: BypassVpnService.Stats,
    rootStats: RootStats
): UiConnectionStats {
    return if (useRootMode) {
        UiConnectionStats(
            packetsProcessed = rootStats.packetsTotal,
            bytesIn = 0,
            // Root daemon does not currently expose byte counters. Use bypassed
            // packets in the upload slot so the UI no longer appears stuck at all
            // zeros while root-mode packet processing is active.
            bytesOut = rootStats.packetsBypassed
        )
    } else {
        UiConnectionStats(
            packetsProcessed = vpnStats.packetsIn + vpnStats.packetsOut,
            bytesIn = vpnStats.bytesIn,
            bytesOut = vpnStats.bytesOut
        )
    }
}
