// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.route.ROOT_SOURCE_DESTINATION_PORT_REJECT_MAX_RULE_ID
import io.nekohasekai.sagernet.route.ROOT_SOURCE_DESTINATION_PORT_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.parseRootPortRejectPorts
import java.io.IOException

/** Extract raw source+destination port reject rules from private builder markers. */
internal fun projectRootRouteSourceDestinationPortRejectRules(config: JsonObject): JsonObject? {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return null
    val projected = JsonArray()
    val ids = hashSetOf<Long>()
    for (element in rules) {
        if (!element.isJsonObject) continue
        val rule = element.asJsonObject
        if (!rule.has(ROOT_SOURCE_DESTINATION_PORT_REJECT_ROUTE_MARKER)) continue
        if (rule.keySet() != setOf(ROOT_SOURCE_DESTINATION_PORT_REJECT_ROUTE_MARKER)) throw IOException("root_route_source_destination_port_reject_invalid")
        val marker = rule.get(ROOT_SOURCE_DESTINATION_PORT_REJECT_ROUTE_MARKER)
        if (!marker.isJsonObject || marker.asJsonObject.keySet() != setOf("ruleId", "sourcePortText", "portText")) throw IOException("root_route_source_destination_port_reject_invalid")
        val payload = marker.asJsonObject
        val idValue = payload.get("ruleId")
        val id = if (idValue != null && idValue.isJsonPrimitive && idValue.asJsonPrimitive.isNumber) idValue.asString.toLongOrNull() else null
        val source = payload.get("sourcePortText")
        val destination = payload.get("portText")
        if (id == null || id !in 1..ROOT_SOURCE_DESTINATION_PORT_REJECT_MAX_RULE_ID || !ids.add(id) ||
            source == null || !source.isJsonPrimitive || !source.asJsonPrimitive.isString || parseRootPortRejectPorts(source.asString).isNullOrEmpty() ||
            destination == null || !destination.isJsonPrimitive || !destination.asJsonPrimitive.isString || parseRootPortRejectPorts(destination.asString).isNullOrEmpty()) {
            throw IOException("root_route_source_destination_port_reject_invalid")
        }
        projected.add(JsonObject().apply { addProperty("ruleId", id); addProperty("sourcePortText", source.asString); addProperty("portText", destination.asString) })
    }
    if (projected.size() == 0) return null
    return JsonObject().apply { add("rules", projected) }
}
