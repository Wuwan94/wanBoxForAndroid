// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_NETWORK_REJECT_ROUTE_MARKER = "_wanbox_root_route_network_reject_v31"
internal const val ROOT_NETWORK_REJECT_MAX_RULE_ID = 9_007_199_254_740_991L

internal data class RootNetworkRejectRouteInput(val ruleId: Long, val networkText: String)

/** Only a single-matcher, standard TCP/UDP reject rule has module parity in this slice. */
internal fun rootNetworkRejectRouteInput(rule: RuleEntity): RootNetworkRejectRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_NETWORK_REJECT_MAX_RULE_ID || rule.outbound != -2L ||
        rule.config.isNotBlank() || rule.network.isBlank() || rule.domains.isNotBlank() ||
        rule.ip.isNotBlank() || rule.port.isNotBlank() || rule.sourcePort.isNotBlank() ||
        rule.source.isNotBlank() || rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() ||
        rule.packages.isNotEmpty()) return null
    if (parseRootNetworkRejectNetworks(rule.network).isNullOrEmpty()) return null
    return RootNetworkRejectRouteInput(rule.id, rule.network)
}

/** Mirrors listByLineOrComma and preserves token order and duplicates. */
internal fun parseRootNetworkRejectNetworks(raw: String): List<String>? {
    if (raw.length > 4096) return null
    val tokens = raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (tokens.isEmpty() || tokens.size > 256 || tokens.any { it !in setOf("tcp", "udp") }) return null
    return tokens
}
