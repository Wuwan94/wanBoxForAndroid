// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_SOURCE_IP_OUTBOUND_ROUTE_MARKER = "_wanbox_root_route_source_ip_outbound_v61"
internal const val ROOT_SOURCE_IP_OUTBOUND_MAX_RULE_ID = 9_007_199_254_740_991L

internal data class RootSourceIPOutboundRouteInput(val ruleId: Long, val sourceText: String, val outbound: String)

/** Move only source-CIDR-only outbound rules; all richer/custom semantics remain in ConfigBuilder. */
internal fun rootSourceIPOutboundRouteInput(rule: RuleEntity, outbound: String): RootSourceIPOutboundRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_SOURCE_IP_OUTBOUND_MAX_RULE_ID || rule.source.isBlank() ||
        rule.domains.isNotBlank() || rule.ip.isNotBlank() || rule.port.isNotBlank() || rule.sourcePort.isNotBlank() ||
        rule.network.isNotBlank() || rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() ||
        rule.packages.isNotEmpty() || rule.config.isNotBlank() || outbound.isBlank() || outbound == "block") return null
    if (parseRootSourceIPRejectCIDRs(rule.source).isNullOrEmpty()) return null
    return RootSourceIPOutboundRouteInput(rule.id, rule.source, outbound)
}
