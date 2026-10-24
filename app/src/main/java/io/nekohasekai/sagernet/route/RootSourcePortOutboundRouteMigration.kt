// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_SOURCE_PORT_OUTBOUND_ROUTE_MARKER = "_wanbox_root_route_source_port_outbound_v63"
internal const val ROOT_SOURCE_PORT_OUTBOUND_MAX_RULE_ID = 9_007_199_254_740_991L

internal data class RootSourcePortOutboundRouteInput(val ruleId: Long, val sourcePortText: String, val outbound: String)

/** Move only exact source-port-only outbound rules; all richer/custom semantics remain in ConfigBuilder. */
internal fun rootSourcePortOutboundRouteInput(rule: RuleEntity, outbound: String): RootSourcePortOutboundRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_SOURCE_PORT_OUTBOUND_MAX_RULE_ID || rule.sourcePort.isBlank() ||
        rule.domains.isNotBlank() || rule.ip.isNotBlank() || rule.port.isNotBlank() || rule.network.isNotBlank() ||
        rule.source.isNotBlank() || rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() ||
        rule.packages.isNotEmpty() || rule.config.isNotBlank() || outbound.isBlank() || outbound == "block") return null
    if (parseRootPortRejectPorts(rule.sourcePort).isNullOrEmpty()) return null
    return RootSourcePortOutboundRouteInput(rule.id, rule.sourcePort, outbound)
}
