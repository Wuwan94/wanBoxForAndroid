// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.route.ROOT_IP_VERSION_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_IP_VERSION_OUTBOUND_MAX_RULE_ID
import java.io.IOException

/** Rebase standalone ip_version markers after earlier projections remove route slots. */
internal fun rebaseRootIPVersionOutboundMarkers(config: JsonObject): Boolean {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return false
    val ids = hashSetOf<Long>()
    var found = false
    rules.forEachIndexed { index, element ->
        if (!element.isJsonObject) return@forEachIndexed
        val rule = element.asJsonObject
        if (!rule.has(ROOT_IP_VERSION_OUTBOUND_ROUTE_MARKER)) return@forEachIndexed
        if (rule.keySet() != setOf(ROOT_IP_VERSION_OUTBOUND_ROUTE_MARKER)) throw IOException("root_ip_version_outbound_invalid")
        val marker = rule.getAsJsonObject(ROOT_IP_VERSION_OUTBOUND_ROUTE_MARKER)
            ?: throw IOException("root_ip_version_outbound_invalid")
        if (marker.keySet() != setOf("index", "ruleId", "ipVersion", "outbound")) throw IOException("root_ip_version_outbound_invalid")
        val idElement = marker.get("ruleId")
        val id = if (idElement != null && idElement.isJsonPrimitive && idElement.asJsonPrimitive.isNumber) idElement.asString.toLongOrNull() else null
        val versionElement = marker.get("ipVersion")
        val version = if (versionElement != null && versionElement.isJsonPrimitive && versionElement.asJsonPrimitive.isNumber) versionElement.asInt else null
        val outbound = marker.get("outbound")
        if (id == null || id !in 1..ROOT_IP_VERSION_OUTBOUND_MAX_RULE_ID || !ids.add(id) || version !in setOf(4, 6) ||
            outbound == null || !outbound.isJsonPrimitive || !outbound.asJsonPrimitive.isString || outbound.asString.isBlank() || outbound.asString == "block") {
            throw IOException("root_ip_version_outbound_invalid")
        }
        marker.addProperty("index", index)
        found = true
    }
    return found
}
