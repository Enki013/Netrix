package com.enki.netrix.vpn

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomainMatcherTest {
    @Test
    fun matchesExactDomainAndSubdomainsOnly() {
        val whitelist = setOf("google.com", "play.google.com")

        assertTrue(DomainMatcher.isWhitelisted("google.com", whitelist))
        assertTrue(DomainMatcher.isWhitelisted("www.google.com", whitelist))
        assertTrue(DomainMatcher.isWhitelisted("play.google.com", whitelist))
        assertTrue(DomainMatcher.isWhitelisted("sub.play.google.com", whitelist))

        assertFalse(DomainMatcher.isWhitelisted("evilgoogle.com", whitelist))
        assertFalse(DomainMatcher.isWhitelisted("google.com.evil.test", whitelist))
        assertFalse(DomainMatcher.isWhitelisted("", whitelist))
    }
}
