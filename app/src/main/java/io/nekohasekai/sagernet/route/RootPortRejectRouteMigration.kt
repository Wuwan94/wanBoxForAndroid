// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_PORT_REJECT_ROUTE_MARKER = "_wanbox_root_route_port_reject_v30"
internal const val ROOT_PORT_REJECT_MAX_RULE_ID = 9_007_199_254_740_991L

internal data class RootPortRejectRouteInput(val ruleId: Long, val portText: String)

/**
 * The initial module-owned route slice is intentionally narrow: a saved, enabled,
 * port-only reject rule with no custom action or other matcher.
 */
internal fun rootPortRejectRouteInput(rule: RuleEntity): RootPortRejectRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_PORT_REJECT_MAX_RULE_ID || rule.outbound != -2L ||
        rule.config.isNotBlank() || rule.port.isBlank() || rule.domains.isNotBlank() ||
        rule.ip.isNotBlank() || rule.sourcePort.isNotBlank() || rule.network.isNotBlank() ||
        rule.source.isNotBlank() || rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() ||
        rule.packages.isNotEmpty()) return null
    val ports = parseRootPortRejectPorts(rule.port) ?: return null
    if (ports.isEmpty()) return null
    return RootPortRejectRouteInput(rule.id, rule.port)
}

/** Mirrors ConfigBuilder.listByLineOrComma, then accepts only ordinary TCP/UDP port numbers. */
internal fun parseRootPortRejectPorts(raw: String): List<Int>? {
    if (raw.length > 4096) return null
    val tokens = raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (tokens.isEmpty() || tokens.size > 256) return null
    val ports = ArrayList<Int>(tokens.size)
    for (token in tokens) {
        if (token.any { it !in '0'..'9' }) return null
        val port = token.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        ports.add(port)
    }
    return ports
}
