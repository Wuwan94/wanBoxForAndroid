// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity
import org.json.JSONObject

internal const val ROOT_DOMAIN_IP_VERSION_OUTBOUND_ROUTE_MARKER = "_wanbox_root_domain_ip_version_outbound_v67"
internal const val ROOT_DOMAIN_IP_VERSION_OUTBOUND_MAX_RULE_ID = 9_007_199_254_740_991L

internal data class RootDomainIPVersionOutboundRouteInput(
    val ruleId: Long,
    val domainText: String,
    val ipVersion: Int,
    val outbound: String,
    val invert: Boolean
)

/** Root generates the supported domain AND IP-version conjunction; richer rules stay legacy. */
internal fun rootDomainIPVersionOutboundRouteInput(rule: RuleEntity, outbound: String): RootDomainIPVersionOutboundRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_DOMAIN_IP_VERSION_OUTBOUND_MAX_RULE_ID ||
        rule.domains.isBlank() || rule.ip.isNotBlank() || rule.port.isNotBlank() || rule.sourcePort.isNotBlank() ||
        rule.network.isNotBlank() || rule.source.isNotBlank() || rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() ||
        rule.packages.isNotEmpty() || outbound.isBlank() || outbound == "block") return null
    if (!parseRootDNSDomainMatchers(rule.domains)) return null
    val custom = try { JSONObject(rule.config) } catch (_: Exception) { return null }
    if (!custom.has("ip_version") || custom.keys().asSequence().any { it !in setOf("ip_version", "invert") }) return null
    val version = custom.optInt("ip_version", -1)
    if (version !in setOf(4, 6) || (custom.has("invert") && custom.opt("invert") !is Boolean)) return null
    return RootDomainIPVersionOutboundRouteInput(rule.id, rule.domains, version, outbound, custom.optBoolean("invert"))
}
