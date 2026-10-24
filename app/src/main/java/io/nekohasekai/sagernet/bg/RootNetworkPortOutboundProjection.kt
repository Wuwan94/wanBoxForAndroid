// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.route.ROOT_NETWORK_PORT_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_NETWORK_PORT_OUTBOUND_MAX_RULE_ID
import io.nekohasekai.sagernet.route.parseRootNetworkPortOutboundPorts
import io.nekohasekai.sagernet.route.parseRootNetworkRejectNetworks
import java.io.IOException

/** Validate the typed network/port route marker without changing its original rule slot. */
internal fun rebaseRootNetworkPortOutboundRouteMarkers(config: JsonObject): Boolean {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return false
    val seen = hashSetOf<Long>()
    var found = false
    for (index in 0 until rules.size()) {
        val item = rules[index]
        if (!item.isJsonObject || !item.asJsonObject.has(ROOT_NETWORK_PORT_OUTBOUND_ROUTE_MARKER)) continue
        val rule = item.asJsonObject
        if (rule.keySet() != setOf(ROOT_NETWORK_PORT_OUTBOUND_ROUTE_MARKER)) throw IOException("root_network_port_outbound_invalid")
        val value = rule.get(ROOT_NETWORK_PORT_OUTBOUND_ROUTE_MARKER)
        if (!value.isJsonObject || value.asJsonObject.keySet() != setOf("index", "ruleId", "networkText", "portText", "outbound")) {
            throw IOException("root_network_port_outbound_invalid")
        }
        val marker = value.asJsonObject
        val sourceIndex = marker.get("index")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        val ruleId = marker.get("ruleId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toLongOrNull()
        val networkText = marker.get("networkText")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val portText = marker.get("portText")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val outbound = marker.get("outbound")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        if (ruleId == null || ruleId !in 1..ROOT_NETWORK_PORT_OUTBOUND_MAX_RULE_ID || !seen.add(ruleId) ||
            networkText == null || parseRootNetworkRejectNetworks(networkText).isNullOrEmpty() ||
            portText == null || parseRootNetworkPortOutboundPorts(portText).isNullOrEmpty() || outbound.isNullOrBlank()) {
            throw IOException("root_network_port_outbound_invalid")
        }
        // Earlier projections may replace/remove generated rules. Rebase at this final
        // App→Root boundary so Root validates the marker against the current route slot.
        marker.addProperty("index", index)
        found = true
    }
    return found
}
