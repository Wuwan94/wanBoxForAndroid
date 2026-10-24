// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_SOURCE_PORT_REJECT_ROUTE_MARKER = "_wanbox_root_route_source_port_reject_v33"
internal const val ROOT_SOURCE_PORT_REJECT_MAX_RULE_ID = 9_007_199_254_740_991L

internal data class RootSourcePortRejectRouteInput(val ruleId: Long, val sourcePortText: String)

/** Module owns only an enabled source-port-only reject rule with fully valid decimal ports. */
internal fun rootSourcePortRejectRouteInput(rule: RuleEntity): RootSourcePortRejectRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_SOURCE_PORT_REJECT_MAX_RULE_ID || rule.outbound != -2L ||
        rule.config.isNotBlank() || rule.sourcePort.isBlank() || rule.domains.isNotBlank() ||
        rule.ip.isNotBlank() || rule.port.isNotBlank() || rule.network.isNotBlank() ||
        rule.source.isNotBlank() || rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() ||
        rule.packages.isNotEmpty()) return null
    if (parseRootPortRejectPorts(rule.sourcePort).isNullOrEmpty()) return null
    return RootSourcePortRejectRouteInput(rule.id, rule.sourcePort)
}
