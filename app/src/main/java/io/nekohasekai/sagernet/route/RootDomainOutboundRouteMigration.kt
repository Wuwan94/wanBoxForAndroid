// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_DOMAIN_OUTBOUND_ROUTE_MARKER = "_wanbox_root_domain_outbound_v55"
internal data class RootDomainOutboundRouteInput(val ruleId: Long, val domainText: String, val outbound: String, val invert: Boolean)

/** Transfer only domain full/suffix/keyword route matchers; regex/geosite and extra filters stay legacy. */
internal fun rootDomainOutboundRouteInput(rule: RuleEntity, outbound: String): RootDomainOutboundRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_DOMAIN_REJECT_MAX_RULE_ID || outbound.isBlank() || outbound == "block" ||
        rule.domains.isBlank() || rule.ip.isNotBlank() || rule.ruleset.isNotBlank() || rule.port.isNotBlank() ||
        rule.sourcePort.isNotBlank() || rule.network.isNotBlank() || rule.source.isNotBlank() || rule.protocol.isNotBlank() ||
        rule.packages.isNotEmpty()) return null
    val custom = RouteRuleEditor.json(rule.config)
    if (custom.keys().asSequence().any { it != "invert" } || !parseRootDNSDomainMatchers(rule.domains)) return null
    return RootDomainOutboundRouteInput(rule.id, rule.domains, outbound, custom.optBoolean("invert"))
}
