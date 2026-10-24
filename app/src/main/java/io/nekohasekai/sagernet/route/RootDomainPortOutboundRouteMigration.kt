// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_DOMAIN_PORT_OUTBOUND_ROUTE_MARKER = "_wanbox_root_domain_port_outbound_v59"
internal const val ROOT_DOMAIN_PORT_OUTBOUND_MAX_RULE_ID = 9_007_199_254_740_991L

internal data class RootDomainPortOutboundRouteInput(
    val ruleId: Long,
    val domainText: String,
    val portText: String,
    val outbound: String,
    val invert: Boolean
)

/** Transfer only supported domain AND numeric destination-port routes; richer predicates stay legacy. */
internal fun rootDomainPortOutboundRouteInput(rule: RuleEntity, outbound: String): RootDomainPortOutboundRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_DOMAIN_PORT_OUTBOUND_MAX_RULE_ID ||
        rule.domains.isBlank() || rule.port.isBlank() || rule.ip.isNotBlank() ||
        rule.sourcePort.isNotBlank() || rule.network.isNotBlank() || rule.source.isNotBlank() ||
        rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() || rule.packages.isNotEmpty() ||
        outbound.isBlank() || outbound == "block") return null
    val custom = RouteRuleEditor.json(rule.config)
    if (custom.keys().asSequence().any { it != "invert" } ||
        !parseRootDNSDomainMatchers(rule.domains) || parseRootNetworkPortOutboundPorts(rule.port).isNullOrEmpty()) return null
    return RootDomainPortOutboundRouteInput(rule.id, rule.domains, rule.port, outbound, custom.optBoolean("invert"))
}
