package com.enki.netrix.vpn

/** Domain matching helper for bypass allowlists.
 *
 * A whitelist entry matches only the exact host or a real subdomain. Avoid using
 * substring matching because `evilgoogle.com` must not match `google.com`.
 */
object DomainMatcher {
    fun isWhitelisted(host: String?, whitelist: Set<String>): Boolean {
        val normalizedHost = host
            ?.trim()
            ?.trimEnd('.')
            ?.lowercase()
            ?: return false
        if (normalizedHost.isEmpty()) return false

        return whitelist.any { domain ->
            val normalizedDomain = domain.trim().trimEnd('.').lowercase()
            normalizedDomain.isNotEmpty() &&
                (normalizedHost == normalizedDomain || normalizedHost.endsWith(".$normalizedDomain"))
        }
    }
}
