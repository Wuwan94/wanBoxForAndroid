// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_EXACT_DOMAIN_DNS_RULE_MARKER = "_wanbox_root_exact_domain_dns_rule_v53"
internal data class RootExactDomainDNSRuleInput(
    val ruleId: Long,
    val domainText: String,
    val kind: String,
    val fakeDns: Boolean
)

/**
 * Root can rebuild uncomplicated domain DNS effects emitted by ConfigBuilder. A
 * route rule may also have destination-IP matchers: DNS selection intentionally
 * matches its domain portion only, while the route projection preserves OR/NOT.
 * DNS domain effects are independent of the route rule's ruleset effect: ConfigBuilder
 * emits them as separate ordered DNS rules, and Root can reconstruct each in its own slot.
 * Other predicates and non-routing actions stay App-generated.
 */
internal fun rootExactDomainDNSRuleInput(
    rule: RuleEntity,
    routingAction: String,
    useFakeDns: Boolean
): RootExactDomainDNSRuleInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_DOMAIN_REJECT_MAX_RULE_ID ||
        routingAction !in setOf("route", "reject") || rule.domains.isBlank() ||
        rule.port.isNotBlank() || rule.sourcePort.isNotBlank() || rule.network.isNotBlank() ||
        rule.source.isNotBlank() || rule.protocol.isNotBlank() ||
        rule.packages.isNotEmpty()) return null
    // Route inversion applies to the route predicate, not to DNS lookup selection.
    // Permit only that known route option so a composite route can still hand its
    // domain-only DNS side effect to Root without changing the DNS matcher.
    if (RouteRuleEditor.json(rule.config).keys().asSequence().any { it != "invert" }) return null
    if (!parseRootDNSDomainMatchers(rule.domains)) return null
    val kind = when {
        routingAction == "reject" || rule.outbound == -2L -> "reject"
        rule.outbound == -1L -> "direct"
        else -> "proxy"
    }
    return RootExactDomainDNSRuleInput(rule.id, rule.domains, kind, useFakeDns && kind == "proxy")
}

/**
 * The Root DNS marker can safely reconstruct the App's supported DNS matcher fields.
 * Exact domains, suffixes and keywords are accepted; regex/geosite and malformed inputs
 * remain in ConfigBuilder's legacy DNS JSON path.
 */
internal fun parseRootDNSDomainMatchers(raw: String): Boolean {
    if (raw.length > 4096) return false
    val tokens = raw.split(',', '\n').map(String::trim).filter(String::isNotEmpty)
    if (tokens.isEmpty() || tokens.size > 256) return false
    return tokens.all { token ->
        val domain = when {
            token.startsWith("full:") -> token.removePrefix("full:")
            token.startsWith("domain:") -> token.removePrefix("domain:")
            token.startsWith("keyword:") -> token.removePrefix("keyword:")
            token.startsWith("regexp:") || token.startsWith("geosite:") || token.startsWith("geosite-") -> return@all false
            else -> token
        }
        domain.length in 1..253 && domain.split('.').all { label ->
            label.length in 1..63 && label.first().isAsciiAlphaNumeric() && label.last().isAsciiAlphaNumeric() &&
                label.all { it.isAsciiAlphaNumeric() || it == '-' }
        }
    }
}

private fun Char.isAsciiAlphaNumeric(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
