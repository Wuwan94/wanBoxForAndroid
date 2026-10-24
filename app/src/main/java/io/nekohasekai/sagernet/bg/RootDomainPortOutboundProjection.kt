// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.route.ROOT_DOMAIN_PORT_OUTBOUND_MAX_RULE_ID
import io.nekohasekai.sagernet.route.ROOT_DOMAIN_PORT_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.parseRootDNSDomainMatchers
import io.nekohasekai.sagernet.route.parseRootNetworkPortOutboundPorts
import java.io.IOException

/** Rebase and validate the typed marker after earlier projections change route positions. */
internal fun rebaseRootDomainPortOutboundMarkers(config: JsonObject): Boolean {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return false
    val seen = hashSetOf<Long>()
    var found = false
    for (index in 0 until rules.size()) {
        val item = rules[index]
        if (!item.isJsonObject || !item.asJsonObject.has(ROOT_DOMAIN_PORT_OUTBOUND_ROUTE_MARKER)) continue
        val rule = item.asJsonObject
        if (rule.keySet() != setOf(ROOT_DOMAIN_PORT_OUTBOUND_ROUTE_MARKER)) throw IOException("root_domain_port_outbound_invalid")
        val value = rule.get(ROOT_DOMAIN_PORT_OUTBOUND_ROUTE_MARKER)
        if (!value.isJsonObject || value.asJsonObject.keySet() != setOf("index", "ruleId", "domainText", "portText", "outbound", "invert")) {
            throw IOException("root_domain_port_outbound_invalid")
        }
        val marker = value.asJsonObject
        val id = marker.get("ruleId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toLongOrNull()
        val domainText = marker.get("domainText")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val portText = marker.get("portText")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val outbound = marker.get("outbound")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val invert = marker.get("invert")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        if (id == null || id !in 1..ROOT_DOMAIN_PORT_OUTBOUND_MAX_RULE_ID || !seen.add(id) ||
            domainText == null || !parseRootDNSDomainMatchers(domainText) ||
            portText == null || parseRootNetworkPortOutboundPorts(portText).isNullOrEmpty() || outbound.isNullOrBlank() || invert == null) {
            throw IOException("root_domain_port_outbound_invalid")
        }
        marker.addProperty("index", index)
        found = true
    }
    return found
}
