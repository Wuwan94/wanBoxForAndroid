// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_NETWORK_PORT_OUTBOUND_ROUTE_MARKER = "_wanbox_root_network_port_outbound_v57"
internal const val ROOT_NETWORK_PORT_OUTBOUND_MAX_RULE_ID = 9_007_199_254_740_991L

internal data class RootNetworkPortOutboundRouteInput(
    val ruleId: Long,
    val networkText: String,
    val portText: String,
    val outbound: String
)

/** A single network AND destination-port route with exact TCP/UDP and numeric-port semantics. */
internal fun rootNetworkPortOutboundRouteInput(rule: RuleEntity, outbound: String): RootNetworkPortOutboundRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_NETWORK_PORT_OUTBOUND_MAX_RULE_ID ||
        rule.config.isNotBlank() || rule.network.isBlank() || rule.port.isBlank() ||
        rule.domains.isNotBlank() || rule.ip.isNotBlank() || rule.sourcePort.isNotBlank() ||
        rule.source.isNotBlank() || rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() ||
        rule.packages.isNotEmpty() || outbound.isBlank() || outbound == "block") return null
    if (parseRootNetworkRejectNetworks(rule.network).isNullOrEmpty() ||
        parseRootNetworkPortOutboundPorts(rule.port).isNullOrEmpty()) return null
    return RootNetworkPortOutboundRouteInput(rule.id, rule.network, rule.port, outbound)
}

/** ConfigBuilder accepts integer tokens for port; ranges and malformed tokens stay legacy. */
internal fun parseRootNetworkPortOutboundPorts(raw: String): List<Int>? {
    if (raw.length > 4096) return null
    val tokens = raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (tokens.isEmpty() || tokens.size > 256) return null
    val result = ArrayList<Int>(tokens.size)
    for (token in tokens) {
        if (token.any { it !in '0'..'9' }) return null
        val port = token.toIntOrNull() ?: return null
        if (port !in 0..65535) return null
        result.add(port)
    }
    return result
}
