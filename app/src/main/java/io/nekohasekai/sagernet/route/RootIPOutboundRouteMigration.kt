// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity
import java.net.Inet6Address
import java.net.InetAddress

internal const val ROOT_IP_OUTBOUND_ROUTE_MARKER = "_wanbox_root_ip_outbound_v52"
internal data class RootIPOutboundRouteInput(val ruleId: Long, val ipText: String, val outbound: String, val invert: Boolean)

/** Transfer only an IP-CIDR-only outbound rule. It has no DNS matcher/side effects. */
internal fun rootIPOutboundRouteInput(rule: RuleEntity, outbound: String): RootIPOutboundRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_DOMAIN_REJECT_MAX_RULE_ID || outbound.isBlank() || outbound == "block" ||
        rule.domains.isNotBlank() || rule.ip.isBlank() || rule.port.isNotBlank() || rule.sourcePort.isNotBlank() ||
        rule.network.isNotBlank() || rule.source.isNotBlank() || rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() || rule.packages.isNotEmpty()) return null
    val custom = RouteRuleEditor.json(rule.config)
    if (custom.keys().asSequence().any { it != "invert" } || parseRootIPOutboundCidrs(rule.ip) == null) return null
    return RootIPOutboundRouteInput(rule.id, rule.ip, outbound, custom.optBoolean("invert"))
}

internal fun parseRootIPOutboundCidrs(raw: String): List<String>? {
    if (raw.length > 4096) return null
    val values = raw.split(',', '\n').map(String::trim).filter(String::isNotEmpty)
    if (values.isEmpty() || values.size > 256) return null
    for (value in values) {
        val parts = value.split('/')
        if (parts.size != 2) return null
        val address = parts[0]
        val prefix = parts[1].toIntOrNull() ?: return null
        if (address.contains(':')) {
            // A colon guarantees a numeric IPv6 literal parse attempt; reject zone IDs so the
            // stored matcher remains portable and cannot depend on a device interface name.
            if ('%' in address || prefix !in 0..128) return null
            val parsed = try { InetAddress.getByName(address) } catch (_: Exception) { return null }
            if (parsed !is Inet6Address) return null
        } else {
            if (prefix !in 0..32) return null
            val octets = address.split('.')
            if (octets.size != 4 || octets.any { octet ->
                    octet.isEmpty() || (octet.length > 1 && octet[0] == '0') || !octet.all { it in '0'..'9' } ||
                        octet.toIntOrNull()?.let { it in 0..255 } != true
                }) return null
        }
    }
    return values
}
