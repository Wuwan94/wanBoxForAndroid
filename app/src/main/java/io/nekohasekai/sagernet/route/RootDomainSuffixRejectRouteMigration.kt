// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_DOMAIN_SUFFIX_REJECT_ROUTE_MARKER = "_wanbox_root_route_domain_suffix_reject_v41"
internal const val ROOT_DOMAIN_SUFFIX_REJECT_MAX_RULE_ID = 9_007_199_254_740_991L
internal data class RootDomainSuffixRejectRouteInput(val ruleId: Long, val domainText: String)

/** Module owns only enabled reject rules using explicit domain-suffix (domain:) matchers. */
internal fun rootDomainSuffixRejectRouteInput(rule: RuleEntity): RootDomainSuffixRejectRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_DOMAIN_SUFFIX_REJECT_MAX_RULE_ID || rule.outbound != -2L ||
        rule.config.isNotBlank() || rule.domains.isBlank() || rule.ip.isNotBlank() || rule.port.isNotBlank() ||
        rule.sourcePort.isNotBlank() || rule.network.isNotBlank() || rule.source.isNotBlank() ||
        rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() || rule.packages.isNotEmpty()) return null
    if (parseRootDomainSuffixRejectDomains(rule.domains).isNullOrEmpty()) return null
    return RootDomainSuffixRejectRouteInput(rule.id, rule.domains)
}

/** Accept only explicit domain: DNS suffixes only; implicit, keyword, regex and geosite forms stay in App. */
internal fun parseRootDomainSuffixRejectDomains(raw: String): List<String>? {
    if (raw.length > 4096) return null
    val tokens = raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (tokens.isEmpty() || tokens.size > 256) return null
    val domains = ArrayList<String>(tokens.size)
    for (token in tokens) {
        if (!token.startsWith("domain:")) return null
        val domain = token.removePrefix("domain:")
        if (domain.length !in 1..253 || domain.split('.').any { label ->
                label.length !in 1..63 ||
                    !label.first().isAsciiAlphaNumeric() || !label.last().isAsciiAlphaNumeric() ||
                    label.any { !it.isAsciiAlphaNumeric() && it != '-' }
            }) return null
        domains.add(domain.lowercase())
    }
    return domains
}

private fun Char.isAsciiAlphaNumeric(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
