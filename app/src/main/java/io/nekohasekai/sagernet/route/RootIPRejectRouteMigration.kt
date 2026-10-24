// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_IP_REJECT_ROUTE_MARKER = "_wanbox_root_route_ip_reject_v36"
internal const val ROOT_IP_REJECT_MAX_RULE_ID = 9_007_199_254_740_991L

internal data class RootIPRejectRouteInput(val ruleId: Long, val ipText: String)

/** Move only enabled, destination-CIDR-only reject rules whose exact matcher has module parity. */
internal fun rootIPRejectRouteInput(rule: RuleEntity): RootIPRejectRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_IP_REJECT_MAX_RULE_ID || rule.outbound != -2L ||
        rule.config.isNotBlank() || rule.ip.isBlank() || rule.domains.isNotBlank() || rule.source.isNotBlank() ||
        rule.port.isNotBlank() || rule.sourcePort.isNotBlank() || rule.network.isNotBlank() ||
        rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() || rule.packages.isNotEmpty()) return null
    if (parseRootIPRejectCIDRs(rule.ip).isNullOrEmpty()) return null
    return RootIPRejectRouteInput(rule.id, rule.ip)
}

/** Mirrors listByLineOrComma; intentionally accepts only explicit CIDRs, never host/IP aliases. */
internal fun parseRootIPRejectCIDRs(raw: String): List<String>? {
    if (raw.length > 4096) return null
    val tokens = raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (tokens.isEmpty() || tokens.size > 256 || tokens.any { token ->
            if ('/' !in token || token.count { it == '/' } != 1 || !RouteRuleEditor.address(token)) return@any true
            val parts = token.split('/')
            val host = parts[0]
            // net.ParseCIDR (Root) rejects non-canonical dotted IPv4 octets and signed prefixes;
            // keep these legacy-only rather than letting Kotlin accept inputs Go would reject.
            if (parts[1].any { it !in '0'..'9' }) return@any true
            val dotted = if (':' in host) host.substringAfterLast(':', "") else host
            if ('.' in dotted) {
                val octets = dotted.split('.')
                if (octets.size != 4 || octets.any { it.isEmpty() || (it.length > 1 && it.startsWith('0')) || it.any { c -> c !in '0'..'9' } }) return@any true
            }
            false
        }) return null
    return tokens
}
