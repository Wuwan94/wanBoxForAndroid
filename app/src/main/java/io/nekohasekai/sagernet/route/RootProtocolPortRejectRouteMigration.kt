// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_PROTOCOL_PORT_REJECT_ROUTE_MARKER = "_wanbox_root_route_protocol_port_reject_v39"
internal const val ROOT_PROTOCOL_PORT_REJECT_MAX_RULE_ID = 9_007_199_254_740_991L
internal data class RootProtocolPortRejectRouteInput(val ruleId: Long, val protocolText: String, val portText: String)

/** Module owns only enabled reject rules matching exactly protocol AND destination-port lists. */
internal fun rootProtocolPortRejectRouteInput(rule: RuleEntity): RootProtocolPortRejectRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_PROTOCOL_PORT_REJECT_MAX_RULE_ID || rule.outbound != -2L ||
        rule.config.isNotBlank() || rule.protocol.isBlank() || rule.port.isBlank() || rule.domains.isNotBlank() ||
        rule.ip.isNotBlank() || rule.sourcePort.isNotBlank() || rule.network.isNotBlank() || rule.source.isNotBlank() ||
        rule.ruleset.isNotBlank() || rule.packages.isNotEmpty()) return null
    if (parseRootProtocolRejectProtocols(rule.protocol).isNullOrEmpty() || parseRootPortRejectPorts(rule.port).isNullOrEmpty()) return null
    return RootProtocolPortRejectRouteInput(rule.id, rule.protocol, rule.port)
}
