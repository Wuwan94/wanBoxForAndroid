// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_PORT_OUTBOUND_ROUTE_MARKER = "_wanbox_root_route_port_outbound_v82"
internal const val ROOT_PORT_OUTBOUND_MAX_RULE_ID = 9_007_199_254_740_991L

internal data class RootPortOutboundRouteInput(val ruleId: Long, val portText: String, val outbound: String)

/** Move only exact numeric-port-only outbound rules; richer/custom semantics remain in ConfigBuilder. */
internal fun rootPortOutboundRouteInput(rule: RuleEntity, outbound: String): RootPortOutboundRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_PORT_OUTBOUND_MAX_RULE_ID || rule.port.isBlank() ||
        rule.domains.isNotBlank() || rule.ip.isNotBlank() || rule.sourcePort.isNotBlank() || rule.network.isNotBlank() ||
        rule.source.isNotBlank() || rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() ||
        rule.packages.isNotEmpty() || rule.config.isNotBlank() || outbound.isBlank() || outbound == "block") return null
    if (parseRootPortRejectPorts(rule.port).isNullOrEmpty()) return null
    return RootPortOutboundRouteInput(rule.id, rule.port, outbound)
}
