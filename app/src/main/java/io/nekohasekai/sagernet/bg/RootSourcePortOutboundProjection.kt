// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.route.ROOT_SOURCE_PORT_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_SOURCE_PORT_OUTBOUND_MAX_RULE_ID
import io.nekohasekai.sagernet.route.parseRootPortRejectPorts
import java.io.IOException

/** Rebase source-port-only markers after earlier Root projections remove route slots. */
internal fun rebaseRootSourcePortOutboundMarkers(config: JsonObject): Boolean {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return false
    val ids = hashSetOf<Long>()
    var found = false
    rules.forEachIndexed { index, element ->
        if (!element.isJsonObject) return@forEachIndexed
        val rule = element.asJsonObject
        if (!rule.has(ROOT_SOURCE_PORT_OUTBOUND_ROUTE_MARKER)) return@forEachIndexed
        if (rule.keySet() != setOf(ROOT_SOURCE_PORT_OUTBOUND_ROUTE_MARKER)) throw IOException("root_source_port_outbound_invalid")
        val marker = rule.getAsJsonObject(ROOT_SOURCE_PORT_OUTBOUND_ROUTE_MARKER)
            ?: throw IOException("root_source_port_outbound_invalid")
        if (marker.keySet() != setOf("index", "ruleId", "sourcePortText", "outbound")) throw IOException("root_source_port_outbound_invalid")
        val idValue = marker.get("ruleId")
        val id = if (idValue != null && idValue.isJsonPrimitive && idValue.asJsonPrimitive.isNumber) idValue.asString.toLongOrNull() else null
        val sourcePort = marker.get("sourcePortText")
        val outbound = marker.get("outbound")
        if (id == null || id !in 1..ROOT_SOURCE_PORT_OUTBOUND_MAX_RULE_ID || !ids.add(id) ||
            sourcePort == null || !sourcePort.isJsonPrimitive || !sourcePort.asJsonPrimitive.isString || parseRootPortRejectPorts(sourcePort.asString).isNullOrEmpty() ||
            outbound == null || !outbound.isJsonPrimitive || !outbound.asJsonPrimitive.isString || outbound.asString.isBlank() || outbound.asString == "block") {
            throw IOException("root_source_port_outbound_invalid")
        }
        marker.addProperty("index", index)
        found = true
    }
    return found
}
