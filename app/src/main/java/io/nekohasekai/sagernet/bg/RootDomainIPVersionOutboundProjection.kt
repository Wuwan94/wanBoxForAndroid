// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.route.ROOT_DOMAIN_IP_VERSION_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_DOMAIN_IP_VERSION_OUTBOUND_MAX_RULE_ID
import java.io.IOException

/** Rebase v67 route markers after earlier Root projections remove route slots. */
internal fun rebaseRootDomainIPVersionOutboundMarkers(config: JsonObject): Boolean {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return false
    val ids = hashSetOf<Long>()
    var found = false
    rules.forEachIndexed { index, element ->
        if (!element.isJsonObject) return@forEachIndexed
        val rule = element.asJsonObject
        if (!rule.has(ROOT_DOMAIN_IP_VERSION_OUTBOUND_ROUTE_MARKER)) return@forEachIndexed
        if (rule.keySet() != setOf(ROOT_DOMAIN_IP_VERSION_OUTBOUND_ROUTE_MARKER)) throw IOException("root_domain_ip_version_outbound_invalid")
        val marker = rule.getAsJsonObject(ROOT_DOMAIN_IP_VERSION_OUTBOUND_ROUTE_MARKER)
            ?: throw IOException("root_domain_ip_version_outbound_invalid")
        if (marker.keySet() != setOf("index", "ruleId", "domainText", "ipVersion", "outbound", "invert")) {
            throw IOException("root_domain_ip_version_outbound_invalid")
        }
        val idElement = marker.get("ruleId")
        val id = if (idElement != null && idElement.isJsonPrimitive && idElement.asJsonPrimitive.isNumber) idElement.asString.toLongOrNull() else null
        val domain = marker.get("domainText")
        val version = marker.get("ipVersion")
        val outbound = marker.get("outbound")
        val invert = marker.get("invert")
        if (id == null || id !in 1..ROOT_DOMAIN_IP_VERSION_OUTBOUND_MAX_RULE_ID || !ids.add(id) ||
            domain == null || !domain.isJsonPrimitive || !domain.asJsonPrimitive.isString || domain.asString.isBlank() ||
            version == null || !version.isJsonPrimitive || !version.asJsonPrimitive.isNumber || version.asInt !in setOf(4, 6) ||
            outbound == null || !outbound.isJsonPrimitive || !outbound.asJsonPrimitive.isString || outbound.asString.isBlank() || outbound.asString == "block" ||
            invert == null || !invert.isJsonPrimitive || !invert.asJsonPrimitive.isBoolean) {
            throw IOException("root_domain_ip_version_outbound_invalid")
        }
        marker.addProperty("index", index)
        found = true
    }
    return found
}
