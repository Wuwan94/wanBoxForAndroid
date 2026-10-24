// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.route.ROOT_PORT_RANGE_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_SOURCE_PORT_RANGE_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_PORT_RANGE_OUTBOUND_MAX_RULE_ID
import io.nekohasekai.sagernet.route.parseRootClosedPortRanges
import java.io.IOException

/** Rebase v85 range-only markers after earlier Root projections remove route slots. */
internal fun rebaseRootPortRangeOutboundMarkers(config: JsonObject): Boolean =
    rebaseRootPortRangeOutboundMarkers(config, ROOT_PORT_RANGE_OUTBOUND_ROUTE_MARKER)

internal fun rebaseRootSourcePortRangeOutboundMarkers(config: JsonObject): Boolean =
    rebaseRootPortRangeOutboundMarkers(config, ROOT_SOURCE_PORT_RANGE_OUTBOUND_ROUTE_MARKER)

private fun rebaseRootPortRangeOutboundMarkers(config: JsonObject, key: String): Boolean {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return false
    val ids = hashSetOf<Long>()
    var found = false
    rules.forEachIndexed { index, element ->
        if (!element.isJsonObject) return@forEachIndexed
        val rule = element.asJsonObject
        if (!rule.has(key)) return@forEachIndexed
        if (rule.keySet() != setOf(key)) throw IOException("root_port_range_outbound_invalid")
        val markerElement = rule.get(key)
        if (markerElement == null || !markerElement.isJsonObject) throw IOException("root_port_range_outbound_invalid")
        val marker = markerElement.asJsonObject
        if (marker.keySet() != setOf("index", "ruleId", "ranges", "outbound")) throw IOException("root_port_range_outbound_invalid")
        val idElement = marker.get("ruleId")
        val id = if (idElement != null && idElement.isJsonPrimitive && idElement.asJsonPrimitive.isNumber) idElement.asString.toLongOrNull() else null
        val rangesElement = marker.get("ranges")
        val ranges = if (rangesElement != null && rangesElement.isJsonArray) {
            rangesElement.asJsonArray.map { if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else "" }
        } else null
        val outbound = marker.get("outbound")
        if (id == null || id !in 1..ROOT_PORT_RANGE_OUTBOUND_MAX_RULE_ID || !ids.add(id) ||
            ranges == null || parseRootClosedPortRanges(ranges.joinToString(",")) != ranges ||
            outbound == null || !outbound.isJsonPrimitive || !outbound.asJsonPrimitive.isString ||
            outbound.asString.isBlank() || outbound.asString.trim() != outbound.asString || outbound.asString == "block" ||
            outbound.asString.length > 256 || outbound.asString.any { it.isISOControl() || it == '/' || it == '\\' }) {
            throw IOException("root_port_range_outbound_invalid")
        }
        marker.addProperty("index", index)
        found = true
    }
    return found
}
