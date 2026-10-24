// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_EXACT_DOMAIN_OUTBOUND_ROUTE_MARKER = "_wanbox_root_exact_domain_outbound_v50"
internal data class RootExactDomainOutboundRouteInput(val ruleId: Long, val domainText: String, val outbound: String)

/** Only exact full:-domain routing with no additional matcher/action semantics. */
internal fun rootExactDomainOutboundRouteInput(rule: RuleEntity, outbound: String): RootExactDomainOutboundRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_DOMAIN_REJECT_MAX_RULE_ID || outbound.isBlank() ||
        (rule.outbound == -2L || outbound == "block") || rule.config.isNotBlank() || rule.domains.isBlank() || rule.ip.isNotBlank() ||
        rule.port.isNotBlank() || rule.sourcePort.isNotBlank() || rule.network.isNotBlank() ||
        rule.source.isNotBlank() || rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() || rule.packages.isNotEmpty()) return null
    if (parseRootDomainRejectDomains(rule.domains).isNullOrEmpty()) return null
    return RootExactDomainOutboundRouteInput(rule.id, rule.domains, outbound)
}
