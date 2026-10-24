// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonObject

internal const val ROOT_COMPOSITE_DOMAIN_IP_OUTBOUND_MARKER = "_wanbox_root_composite_domain_ip_outbound_v51"

/** Rebase typed slot metadata after earlier Root-only projections remove other entries. */
internal fun rebaseRootCompositeDomainIPOutboundMarkers(config: JsonObject): Boolean {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return false
    var found = false
    rules.forEachIndexed { index, element ->
        if (!element.isJsonObject) return@forEachIndexed
        val marker = element.asJsonObject.getAsJsonObject(ROOT_COMPOSITE_DOMAIN_IP_OUTBOUND_MARKER) ?: return@forEachIndexed
        marker.addProperty("index", index)
        found = true
    }
    return found
}
