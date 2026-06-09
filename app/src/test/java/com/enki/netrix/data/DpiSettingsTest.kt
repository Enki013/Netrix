package com.enki.netrix.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DpiSettingsTest {
    @Test
    fun vpnModeDesyncPreferenceNormalizesStreamUnsafeMethods() {
        assertEquals(DesyncMethod.SPLIT, DesyncMethod.fromVpnPreference("SPLIT_REVERSE"))
        assertEquals(DesyncMethod.SPLIT, DesyncMethod.fromVpnPreference("DISORDER"))
        assertEquals(DesyncMethod.SPLIT, DesyncMethod.fromVpnPreference("DISORDER_REVERSE"))
        assertEquals(DesyncMethod.FAKE, DesyncMethod.fromVpnPreference("FAKE"))
    }

    @Test
    fun rootModeDesyncPreferencePreservesPacketLevelMethods() {
        assertEquals(DesyncMethod.SPLIT_REVERSE, DesyncMethod.fromRootPreference("SPLIT_REVERSE"))
        assertEquals(DesyncMethod.DISORDER, DesyncMethod.fromRootPreference("DISORDER"))
        assertEquals(DesyncMethod.DISORDER_REVERSE, DesyncMethod.fromRootPreference("DISORDER_REVERSE"))
    }

    @Test
    fun defaultWhitelistIncludesAndroidGoogleCriticalServices() {
        val defaults = DpiSettings.DEFAULT_WHITELIST
        assertTrue("googleapis.com" in defaults)
        assertTrue("gstatic.com" in defaults)
        assertTrue("gvt1.com" in defaults)
        assertTrue("play.google.com" in defaults)
        assertTrue("android.com" in defaults)
    }
}
