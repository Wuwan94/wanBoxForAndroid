// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.route.ROOT_PROTOCOL_PORT_REJECT_MAX_RULE_ID
import io.nekohasekai.sagernet.route.ROOT_PROTOCOL_PORT_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.parseRootProtocolRejectProtocols
import io.nekohasekai.sagernet.route.parseRootPortRejectPorts
import java.io.IOException

/** Transfer only module-supported raw protocol AND destination-port reject input. */
internal fun projectRootRouteProtocolPortRejectRules(config: JsonObject): JsonObject? {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return null
    val projected = JsonArray(); val ids = hashSetOf<Long>()
    for (element in rules) {
        if (!element.isJsonObject) continue
        val rule = element.asJsonObject
        if (!rule.has(ROOT_PROTOCOL_PORT_REJECT_ROUTE_MARKER)) continue
        if (rule.keySet() != setOf(ROOT_PROTOCOL_PORT_REJECT_ROUTE_MARKER)) throw IOException("root_route_protocol_port_reject_invalid")
        val marker = rule.get(ROOT_PROTOCOL_PORT_REJECT_ROUTE_MARKER)
        if (!marker.isJsonObject || marker.asJsonObject.keySet() != setOf("ruleId", "protocolText", "portText")) throw IOException("root_route_protocol_port_reject_invalid")
        val payload = marker.asJsonObject; val idValue = payload.get("ruleId")
        val id = if (idValue != null && idValue.isJsonPrimitive && idValue.asJsonPrimitive.isNumber) idValue.asString.toLongOrNull() else null
        val protocol = payload.get("protocolText"); val port = payload.get("portText")
        if (id == null || id !in 1..ROOT_PROTOCOL_PORT_REJECT_MAX_RULE_ID || !ids.add(id) || protocol == null || !protocol.isJsonPrimitive || !protocol.asJsonPrimitive.isString || parseRootProtocolRejectProtocols(protocol.asString).isNullOrEmpty() || port == null || !port.isJsonPrimitive || !port.asJsonPrimitive.isString || parseRootPortRejectPorts(port.asString).isNullOrEmpty()) throw IOException("root_route_protocol_port_reject_invalid")
        projected.add(JsonObject().apply { addProperty("ruleId", id); addProperty("protocolText", protocol.asString); addProperty("portText", port.asString) })
    }
    if (projected.size() == 0) return null
    return JsonObject().apply { add("rules", projected) }
}
