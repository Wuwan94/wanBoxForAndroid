// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_DOMAIN_NETWORK_OUTBOUND_ROUTE_MARKER = "_wanbox_root_domain_network_outbound_v58"
internal const val ROOT_DOMAIN_NETWORK_OUTBOUND_MAX_RULE_ID = 9_007_199_254_740_991L

internal data class RootDomainNetworkOutboundRouteInput(
    val ruleId: Long,
    val domainText: String,
    val networkText: String,
    val outbound: String,
    val invert: Boolean
)

/** Root generates a domain AND TCP/UDP network route; richer predicates stay legacy. */
internal fun rootDomainNetworkOutboundRouteInput(rule: RuleEntity, outbound: String): RootDomainNetworkOutboundRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_DOMAIN_NETWORK_OUTBOUND_MAX_RULE_ID ||
        rule.domains.isBlank() || rule.network.isBlank() || rule.ip.isNotBlank() || rule.ruleset.isNotBlank() ||
        rule.port.isNotBlank() || rule.sourcePort.isNotBlank() || rule.source.isNotBlank() ||
        rule.protocol.isNotBlank() || rule.packages.isNotEmpty() || outbound.isBlank() || outbound == "block") return null
    if (parseRootNetworkRejectNetworks(rule.network).isNullOrEmpty() || !parseRootDNSDomainMatchers(rule.domains)) return null
    val custom = RouteRuleEditor.json(rule.config)
    if (custom.keys().asSequence().any { it != "invert" }) return null
    return RootDomainNetworkOutboundRouteInput(rule.id, rule.domains, rule.network, outbound, custom.optBoolean("invert"))
}
