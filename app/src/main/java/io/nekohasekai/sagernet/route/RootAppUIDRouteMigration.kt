// @author 雾晚
package io.nekohasekai.sagernet.route

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal const val ROOT_APP_UID_ROUTE_MARKER = "_wanbox_root_route_app_uid_v45"

/** Projects only package-identity-only route markers; all richer predicates stay in ConfigBuilder. */
internal fun projectRootRouteAppUIDRules(config: JsonObject): JsonObject? {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return null
    val projected = JsonArray()
    for (index in 0 until rules.size()) {
        val rule = rules[index]
        if (!rule.isJsonObject || !rule.asJsonObject.has(ROOT_APP_UID_ROUTE_MARKER)) continue
        val markerRule = rule.asJsonObject
        require(markerRule.keySet() == setOf(ROOT_APP_UID_ROUTE_MARKER)) { "root_route_app_uid_invalid" }
        val marker = markerRule.getAsJsonObject(ROOT_APP_UID_ROUTE_MARKER)
        require(marker.keySet() == setOf("index", "packages", "uids", "outbound")) { "root_route_app_uid_invalid" }
        val sourceIndex = marker.get("index")
        require(sourceIndex != null && sourceIndex.isJsonPrimitive && sourceIndex.asJsonPrimitive.isNumber &&
            sourceIndex.asInt >= index && sourceIndex.asInt <= (1 shl 20)) { "root_route_app_uid_invalid" }
        // Earlier Root projections can remove preceding generated rules. The protocol index
        // must describe the marker's position in this exact snapshot, not ConfigBuilder's
        // pre-projection coordinate; the Go validator checks it against the remaining array.
        projected.add(marker.deepCopy().apply { addProperty("index", index) })
    }
    return if (projected.size() == 0) null else JsonObject().apply { add("rules", projected) }
}
