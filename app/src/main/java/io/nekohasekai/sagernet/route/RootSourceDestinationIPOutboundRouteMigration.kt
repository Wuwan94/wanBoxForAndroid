// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_SOURCE_DESTINATION_IP_OUTBOUND_ROUTE_MARKER = "_wanbox_root_route_source_destination_ip_outbound_v60"
internal const val ROOT_SOURCE_DESTINATION_IP_OUTBOUND_MAX_RULE_ID = 9_007_199_254_740_991L

internal data class RootSourceDestinationIPOutboundRouteInput(
    val ruleId: Long,
    val sourceText: String,
    val ipText: String,
    val outbound: String
)

/** Move only the exact source-CIDR AND destination-CIDR outbound predicate; richer rules stay legacy. */
internal fun rootSourceDestinationIPOutboundRouteInput(
    rule: RuleEntity,
    outbound: String
): RootSourceDestinationIPOutboundRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_SOURCE_DESTINATION_IP_OUTBOUND_MAX_RULE_ID ||
        rule.source.isBlank() || rule.ip.isBlank() || rule.domains.isNotBlank() || rule.port.isNotBlank() ||
        rule.sourcePort.isNotBlank() || rule.network.isNotBlank() || rule.protocol.isNotBlank() ||
        rule.ruleset.isNotBlank() || rule.packages.isNotEmpty() || rule.config.isNotBlank() ||
        outbound.isBlank() || outbound == "block") return null
    if (parseRootSourceIPRejectCIDRs(rule.source).isNullOrEmpty() || parseRootIPRejectCIDRs(rule.ip).isNullOrEmpty()) return null
    return RootSourceDestinationIPOutboundRouteInput(rule.id, rule.source, rule.ip, outbound)
}
