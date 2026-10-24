// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_SOURCE_DESTINATION_IP_REJECT_ROUTE_MARKER = "_wanbox_root_route_source_destination_ip_reject_v37"
internal const val ROOT_SOURCE_DESTINATION_IP_REJECT_MAX_RULE_ID = 9_007_199_254_740_991L

internal data class RootSourceDestinationIPRejectRouteInput(val ruleId: Long, val sourceText: String, val ipText: String)

/** Move only enabled reject rules whose sole match is the AND of explicit source/destination CIDRs. */
internal fun rootSourceDestinationIPRejectRouteInput(rule: RuleEntity): RootSourceDestinationIPRejectRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_SOURCE_DESTINATION_IP_REJECT_MAX_RULE_ID || rule.outbound != -2L ||
        rule.config.isNotBlank() || rule.source.isBlank() || rule.ip.isBlank() || rule.domains.isNotBlank() ||
        rule.port.isNotBlank() || rule.sourcePort.isNotBlank() || rule.network.isNotBlank() ||
        rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() || rule.packages.isNotEmpty()) return null
    if (parseRootSourceIPRejectCIDRs(rule.source).isNullOrEmpty() || parseRootIPRejectCIDRs(rule.ip).isNullOrEmpty()) return null
    return RootSourceDestinationIPRejectRouteInput(rule.id, rule.source, rule.ip)
}
