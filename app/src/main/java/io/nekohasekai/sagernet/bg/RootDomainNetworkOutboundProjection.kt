// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.route.ROOT_DOMAIN_NETWORK_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_DOMAIN_NETWORK_OUTBOUND_MAX_RULE_ID
import io.nekohasekai.sagernet.route.parseRootDNSDomainMatchers
import io.nekohasekai.sagernet.route.parseRootNetworkRejectNetworks
import java.io.IOException

/** Rebase the typed route marker after earlier projections modify preceding rules. */
internal fun rebaseRootDomainNetworkOutboundMarkers(config: JsonObject): Boolean {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return false
    val seen = hashSetOf<Long>()
    var found = false
    rules.forEachIndexed { index, element ->
        if (!element.isJsonObject || !element.asJsonObject.has(ROOT_DOMAIN_NETWORK_OUTBOUND_ROUTE_MARKER)) return@forEachIndexed
        val rule = element.asJsonObject
        if (rule.keySet() != setOf(ROOT_DOMAIN_NETWORK_OUTBOUND_ROUTE_MARKER)) throw IOException("root_domain_network_outbound_invalid")
        val marker = rule.getAsJsonObject(ROOT_DOMAIN_NETWORK_OUTBOUND_ROUTE_MARKER)
        if (marker.keySet() != setOf("index", "ruleId", "domainText", "networkText", "outbound", "invert")) {
            throw IOException("root_domain_network_outbound_invalid")
        }
        val id = marker.get("ruleId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toLongOrNull()
        val domainText = marker.get("domainText")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val networkText = marker.get("networkText")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val outbound = marker.get("outbound")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val invert = marker.get("invert")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        if (id == null || id !in 1..ROOT_DOMAIN_NETWORK_OUTBOUND_MAX_RULE_ID || !seen.add(id) ||
            domainText == null || !parseRootDNSDomainMatchers(domainText) ||
            networkText == null || parseRootNetworkRejectNetworks(networkText).isNullOrEmpty() || outbound.isNullOrBlank() || invert == null) {
            throw IOException("root_domain_network_outbound_invalid")
        }
        marker.addProperty("index", index)
        found = true
    }
    return found
}
