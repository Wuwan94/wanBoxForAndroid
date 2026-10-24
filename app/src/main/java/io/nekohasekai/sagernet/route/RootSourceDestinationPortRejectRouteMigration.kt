// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_SOURCE_DESTINATION_PORT_REJECT_ROUTE_MARKER = "_wanbox_root_route_source_destination_port_reject_v38"
internal const val ROOT_SOURCE_DESTINATION_PORT_REJECT_MAX_RULE_ID = 9_007_199_254_740_991L

internal data class RootSourceDestinationPortRejectRouteInput(val ruleId: Long, val sourcePortText: String, val portText: String)

/** Module owns only a fully specified source-port AND destination-port-only reject rule. */
internal fun rootSourceDestinationPortRejectRouteInput(rule: RuleEntity): RootSourceDestinationPortRejectRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_SOURCE_DESTINATION_PORT_REJECT_MAX_RULE_ID || rule.outbound != -2L ||
        rule.config.isNotBlank() || rule.sourcePort.isBlank() || rule.port.isBlank() || rule.domains.isNotBlank() ||
        rule.ip.isNotBlank() || rule.network.isNotBlank() || rule.source.isNotBlank() || rule.protocol.isNotBlank() ||
        rule.ruleset.isNotBlank() || rule.packages.isNotEmpty()) return null
    if (parseRootPortRejectPorts(rule.sourcePort).isNullOrEmpty() || parseRootPortRejectPorts(rule.port).isNullOrEmpty()) return null
    return RootSourceDestinationPortRejectRouteInput(rule.id, rule.sourcePort, rule.port)
}
