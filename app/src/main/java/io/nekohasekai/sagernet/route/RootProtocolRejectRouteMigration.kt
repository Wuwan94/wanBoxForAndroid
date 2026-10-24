// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_PROTOCOL_REJECT_ROUTE_MARKER = "_wanbox_root_route_protocol_reject_v34"
internal const val ROOT_PROTOCOL_REJECT_MAX_RULE_ID = 9_007_199_254_740_991L
internal val ROOT_PROTOCOL_REJECT_PROTOCOLS = setOf("tls", "http", "quic", "dns", "stun", "bittorrent", "dtls", "ssh", "rdp", "ntp")

internal data class RootProtocolRejectRouteInput(val ruleId: Long, val protocolText: String)

/** Only an enabled, protocol-only reject rule with protocols understood by both clients moves. */
internal fun rootProtocolRejectRouteInput(rule: RuleEntity): RootProtocolRejectRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_PROTOCOL_REJECT_MAX_RULE_ID || rule.outbound != -2L ||
        rule.config.isNotBlank() || rule.protocol.isBlank() || rule.domains.isNotBlank() ||
        rule.ip.isNotBlank() || rule.port.isNotBlank() || rule.sourcePort.isNotBlank() ||
        rule.network.isNotBlank() || rule.source.isNotBlank() || rule.ruleset.isNotBlank() ||
        rule.packages.isNotEmpty()) return null
    if (parseRootProtocolRejectProtocols(rule.protocol).isNullOrEmpty()) return null
    return RootProtocolRejectRouteInput(rule.id, rule.protocol)
}

/** Same tokenization as listByLineOrComma, restricted to the protocol enum accepted by RouteRuleEditor. */
internal fun parseRootProtocolRejectProtocols(raw: String): List<String>? {
    if (raw.length > 4096) return null
    val tokens = raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (tokens.isEmpty() || tokens.size > 256 || tokens.any { it !in ROOT_PROTOCOL_REJECT_PROTOCOLS }) return null
    return tokens
}
