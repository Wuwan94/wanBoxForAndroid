// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_NETWORK_PORT_REJECT_ROUTE_MARKER = "_wanbox_root_route_network_port_reject_v32"
internal const val ROOT_NETWORK_PORT_REJECT_MAX_RULE_ID = 9_007_199_254_740_991L

internal data class RootNetworkPortRejectRouteInput(val ruleId: Long, val networkText: String, val portText: String)

/** Only the complete two-predicate network+destination-port reject conjunction is module-owned. */
internal fun rootNetworkPortRejectRouteInput(rule: RuleEntity): RootNetworkPortRejectRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_NETWORK_PORT_REJECT_MAX_RULE_ID || rule.outbound != -2L ||
        rule.config.isNotBlank() || rule.network.isBlank() || rule.port.isBlank() ||
        rule.domains.isNotBlank() || rule.ip.isNotBlank() || rule.sourcePort.isNotBlank() ||
        rule.source.isNotBlank() || rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() ||
        rule.packages.isNotEmpty()) return null
    if (parseRootNetworkRejectNetworks(rule.network).isNullOrEmpty() ||
        parseRootPortRejectPorts(rule.port).isNullOrEmpty()) return null
    return RootNetworkPortRejectRouteInput(rule.id, rule.network, rule.port)
}
