// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.route.ROOT_SOURCE_DESTINATION_IP_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_SOURCE_DESTINATION_IP_OUTBOUND_MAX_RULE_ID
import io.nekohasekai.sagernet.route.parseRootIPRejectCIDRs
import io.nekohasekai.sagernet.route.parseRootSourceIPRejectCIDRs
import java.io.IOException

/** Rebase and validate source/destination CIDR outbound markers after earlier projections. */
internal fun rebaseRootSourceDestinationIPOutboundMarkers(config: JsonObject): Boolean {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return false
    val ids = hashSetOf<Long>()
    var found = false
    rules.forEachIndexed { index, element ->
        if (!element.isJsonObject) return@forEachIndexed
        val rule = element.asJsonObject
        if (!rule.has(ROOT_SOURCE_DESTINATION_IP_OUTBOUND_ROUTE_MARKER)) return@forEachIndexed
        if (rule.keySet() != setOf(ROOT_SOURCE_DESTINATION_IP_OUTBOUND_ROUTE_MARKER)) throw IOException("root_route_source_destination_ip_outbound_invalid")
        val marker = rule.getAsJsonObject(ROOT_SOURCE_DESTINATION_IP_OUTBOUND_ROUTE_MARKER)
            ?: throw IOException("root_route_source_destination_ip_outbound_invalid")
        if (marker.keySet() != setOf("index", "ruleId", "sourceText", "ipText", "outbound")) throw IOException("root_route_source_destination_ip_outbound_invalid")
        val idValue = marker.get("ruleId")
        val id = if (idValue != null && idValue.isJsonPrimitive && idValue.asJsonPrimitive.isNumber) idValue.asString.toLongOrNull() else null
        val source = marker.get("sourceText")
        val destination = marker.get("ipText")
        val outbound = marker.get("outbound")
        if (id == null || id !in 1..ROOT_SOURCE_DESTINATION_IP_OUTBOUND_MAX_RULE_ID || !ids.add(id) ||
            source == null || !source.isJsonPrimitive || !source.asJsonPrimitive.isString || parseRootSourceIPRejectCIDRs(source.asString).isNullOrEmpty() ||
            destination == null || !destination.isJsonPrimitive || !destination.asJsonPrimitive.isString || parseRootIPRejectCIDRs(destination.asString).isNullOrEmpty() ||
            outbound == null || !outbound.isJsonPrimitive || !outbound.asJsonPrimitive.isString || outbound.asString.isBlank() || outbound.asString == "block") {
            throw IOException("root_route_source_destination_ip_outbound_invalid")
        }
        marker.addProperty("index", index)
        found = true
    }
    return found
}
