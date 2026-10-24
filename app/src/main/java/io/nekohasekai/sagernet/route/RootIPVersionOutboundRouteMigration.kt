// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity
import org.json.JSONObject

internal const val ROOT_IP_VERSION_OUTBOUND_ROUTE_MARKER = "_wanbox_root_ip_version_outbound_v62"
internal const val ROOT_IP_VERSION_OUTBOUND_MAX_RULE_ID = 9_007_199_254_740_991L

internal data class RootIPVersionOutboundRouteInput(val ruleId: Long, val ipVersion: Int, val outbound: String)

/** Move only a standalone IPv4/IPv6 version route; mixed/custom rules stay on ConfigBuilder. */
internal fun rootIPVersionOutboundRouteInput(rule: RuleEntity, outbound: String): RootIPVersionOutboundRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_IP_VERSION_OUTBOUND_MAX_RULE_ID ||
        rule.domains.isNotBlank() || rule.ip.isNotBlank() || rule.port.isNotBlank() || rule.sourcePort.isNotBlank() ||
        rule.network.isNotBlank() || rule.source.isNotBlank() || rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() ||
        rule.packages.isNotEmpty() || outbound.isBlank() || outbound == "block") return null
    val custom = try { JSONObject(rule.config) } catch (_: Exception) { return null }
    if (custom.length() != 1 || !custom.has("ip_version")) return null
    val version = custom.optInt("ip_version", -1)
    if (version !in setOf(4, 6)) return null
    return RootIPVersionOutboundRouteInput(rule.id, version, outbound)
}
