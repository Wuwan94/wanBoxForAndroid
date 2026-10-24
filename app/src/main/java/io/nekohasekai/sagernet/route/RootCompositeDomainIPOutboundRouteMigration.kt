// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity
import java.net.Inet6Address
import java.net.InetAddress

internal const val ROOT_COMPOSITE_DOMAIN_IP_OUTBOUND_MARKER = "_wanbox_root_composite_domain_ip_outbound_v51"
internal data class RootCompositeDomainIPOutboundRouteInput(
    val ruleId: Long,
    val domainText: String,
    val ipText: String,
    val outbound: String,
    val invert: Boolean
)

/**
 * Root constructs the OR(domain, IP) matcher and applies NOT once to that whole
 * predicate. Domain full/suffix/keyword forms are accepted only where the shared
 * ConfigBuilder DNS parser proves their emitted field mapping; regex/geosite remain legacy.
 * DNS side effects are emitted separately as a typed domain-only rule.
 */
internal fun rootCompositeDomainIPOutboundRouteInput(
    rule: RuleEntity,
    outbound: String
): RootCompositeDomainIPOutboundRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_DOMAIN_REJECT_MAX_RULE_ID ||
        outbound.isBlank() || outbound == "block" || rule.domains.isBlank() || rule.ip.isBlank() ||
        rule.port.isNotBlank() || rule.sourcePort.isNotBlank() || rule.network.isNotBlank() ||
        rule.source.isNotBlank() || rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() || rule.packages.isNotEmpty()) return null
    val custom = RouteRuleEditor.json(rule.config)
    if (custom.keys().asSequence().any { it != "invert" }) return null
    if (!parseRootDNSDomainMatchers(rule.domains)) return null
    if (!parseRootIpCidrs(rule.ip)) return null
    return RootCompositeDomainIPOutboundRouteInput(rule.id, rule.domains, rule.ip, outbound, custom.optBoolean("invert"))
}

private fun parseRootIpCidrs(raw: String): Boolean {
    if (raw.length > 4096) return false
    val tokens = raw.split(',', '\n').map(String::trim).filter(String::isNotEmpty)
    if (tokens.isEmpty() || tokens.size > 256) return false
    return tokens.all { token ->
        val parts = token.split('/')
        if (parts.size != 2 || parts[0].contains('%')) return@all false
        val prefix = parts[1].toIntOrNull() ?: return@all false
        if (':' in parts[0]) {
            prefix in 0..128 && runCatching { InetAddress.getByName(parts[0]) is Inet6Address }.getOrDefault(false)
        } else {
            val octets = parts[0].split('.')
            octets.size == 4 && prefix in 0..32 && octets.all { octet ->
                octet.isNotEmpty() && (octet.length == 1 || octet[0] != '0') && octet.all { it in '0'..'9' } && (octet.toIntOrNull()?.let { it in 0..255 } == true)
            }
        }
    }
}
