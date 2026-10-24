// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal const val ROOT_NETWORK_OUTBOUND_ROUTE_MARKER = "_wanbox_root_route_network_outbound_v44"

/**
 * Move only exact generated network->outbound rules into the Root schema.
 * Domain-regex rules remain in the original generated configuration until semantic parity
 * with the pinned core is demonstrated; they do not gate this independent projection.
 */
internal fun projectRootRouteNetworkOutboundRules(
    config: JsonObject,
    domainRegexProjected: Boolean,
): JsonObject? {
    if (domainRegexProjected) return null
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return null
    val projected = JsonArray()
    for (index in 0 until rules.size()) {
        val element = rules[index]
        if (!element.isJsonObject) continue
        val rule = element.asJsonObject
        if (rule.keySet() != setOf("network", "outbound")) continue
        val network = rule.get("network")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
        if (network.size() !in 1..256 || network.any {
                !it.isJsonPrimitive || !it.asJsonPrimitive.isString || it.asString !in setOf("tcp", "udp")
            }) continue
        val outbound = rule.get("outbound")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: continue
        if (outbound.isBlank() || outbound.length > 256 || outbound.trim() != outbound ||
            outbound.any { it == '\\' || it == '/' || it.isISOControl() }) continue

        projected.add(JsonObject().apply {
            addProperty("index", index)
            add("network", network.deepCopy())
            addProperty("outbound", outbound)
        })
        rules.set(index, JsonObject().apply {
            add(ROOT_NETWORK_OUTBOUND_ROUTE_MARKER, JsonObject().apply { addProperty("index", index) })
        })
    }
    if (projected.size() == 0) return null
    return JsonObject().apply { add("rules", projected) }
}
