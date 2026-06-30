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
        assertEquals(DesyncMethod.FAKE, DesyncMethod.fromVpnPreference("GECIT_FAKE"))
    }

    @Test
    fun rootModeDesyncPreferencePreservesPacketLevelMethods() {
        assertEquals(DesyncMethod.SPLIT_REVERSE, DesyncMethod.fromRootPreference("SPLIT_REVERSE"))
        assertEquals(DesyncMethod.DISORDER, DesyncMethod.fromRootPreference("DISORDER"))
        assertEquals(DesyncMethod.DISORDER_REVERSE, DesyncMethod.fromRootPreference("DISORDER_REVERSE"))
        assertEquals(DesyncMethod.GECIT_FAKE, DesyncMethod.fromRootPreference("GECIT_FAKE"))
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

    @Test
    fun defaultWhitelistIncludesModernCdnAndSocialCriticalServices() {
        val defaults = DpiSettings.DEFAULT_WHITELIST

        assertTrue("youtube.com" in defaults)
        assertTrue("googlevideo.com" in defaults)
        assertTrue("ytimg.com" in defaults)
        assertTrue("ggpht.com" in defaults)
        assertTrue("cloudflare.com" in defaults)
        assertTrue("facebook.com" in defaults)
        assertTrue("fbcdn.net" in defaults)
        assertTrue("instagram.com" in defaults)
        assertTrue("cdninstagram.com" in defaults)
        assertTrue("whatsapp.net" in defaults)
    }

    @Test
    fun normalVpnModeDefaultsToCompatibilitySafeHttpsDesyncOff() {
        assertEquals(false, DpiSettings().desyncHttps)
    }

    @Test
    fun defaultVpnDisallowedPackagesKeepCriticalBackgroundAppsOutOfNormalModeTunnel() {
        val defaults = DpiSettings.DEFAULT_DISALLOWED_VPN_PACKAGES

        assertTrue("com.google.android.gms" in defaults)
        assertTrue("com.google.android.googlequicksearchbox" in defaults)
        assertTrue("com.google.android.youtube" in defaults)
        assertTrue("com.instagram.android" in defaults)
        assertTrue("com.whatsapp" in defaults)
    }
}
