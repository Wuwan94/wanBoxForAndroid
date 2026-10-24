// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonObject

internal const val ROOT_IP_OUTBOUND_ROUTE_MARKER = "_wanbox_root_ip_outbound_v52"

/** Rebase typed indices after earlier Root-only projections remove route entries. */
internal fun rebaseRootIPOutboundMarkers(config: JsonObject): Boolean {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return false
    var found = false
    rules.forEachIndexed { index, element ->
        if (!element.isJsonObject) return@forEachIndexed
        val marker = element.asJsonObject.getAsJsonObject(ROOT_IP_OUTBOUND_ROUTE_MARKER) ?: return@forEachIndexed
        marker.addProperty("index", index)
        found = true
    }
    return found
}
