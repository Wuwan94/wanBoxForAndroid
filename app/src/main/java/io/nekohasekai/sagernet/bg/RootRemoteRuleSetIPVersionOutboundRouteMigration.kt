// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal const val ROOT_REMOTE_RULE_SET_IP_VERSION_OUTBOUND_ROUTE_MARKER = "_wanbox_root_remote_ruleset_ip_version_outbound_v65"

/** Generate a remote rule-set membership AND ip_version outbound route in Root. */
internal fun projectRootRemoteRuleSetIPVersionOutboundRoutes(config: JsonObject): Boolean {
    val route = config.getAsJsonObject("route") ?: return false
    val definitions = route.getAsJsonArray("rule_set") ?: return false
    val migratedTags = definitions.mapNotNull { item ->
        if (!item.isJsonObject) return@mapNotNull null
        val marker = item.asJsonObject.getAsJsonObject(ROOT_REMOTE_RULE_SET_MARKER) ?: return@mapNotNull null
        marker.get("tag")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }.toSet()
    if (migratedTags.isEmpty()) return false
    val rules = route.getAsJsonArray("rules") ?: return false
    var moved = false
    for (index in 0 until rules.size()) {
        val raw = rules[index]
        if (!raw.isJsonObject) continue
        val rule = raw.asJsonObject
        if (rule.has(ROOT_REMOTE_RULE_SET_IP_VERSION_OUTBOUND_ROUTE_MARKER)) continue
        if (rule.keySet().any { it !in setOf("rule_set", "ip_version", "outbound", "invert") }) continue
        val tagsElement = rule.get("rule_set") ?: continue
        val versionElement = rule.get("ip_version") ?: continue
        val outboundElement = rule.get("outbound") ?: continue
        if (!tagsElement.isJsonArray || tagsElement.asJsonArray.size() == 0) continue
        val tags = tagsElement.asJsonArray.map { tag ->
            if (!tag.isJsonPrimitive || !tag.asJsonPrimitive.isString) return@map null
            tag.asString.takeIf(String::isNotBlank)
        }
        if (tags.any { it == null } || tags.filterNotNull().any { it !in migratedTags } || tags.filterNotNull().distinct().size != tags.size) continue
        if (!versionElement.isJsonPrimitive || !versionElement.asJsonPrimitive.isNumber) continue
        val version = versionElement.asJsonPrimitive.asString.toIntOrNull()?.takeIf { it == 4 || it == 6 } ?: continue
        if (!outboundElement.isJsonPrimitive || !outboundElement.asJsonPrimitive.isString || outboundElement.asString.isBlank()) continue
        val invert = rule.get("invert")
        if (invert != null && (!invert.isJsonPrimitive || !invert.asJsonPrimitive.isBoolean)) continue
        rules.set(index, JsonObject().apply {
            add(ROOT_REMOTE_RULE_SET_IP_VERSION_OUTBOUND_ROUTE_MARKER, JsonObject().apply {
                addProperty("index", index)
                add("tags", JsonArray().apply { tags.filterNotNull().forEach(::add) })
                addProperty("ipVersion", version)
                addProperty("outbound", outboundElement.asString)
                addProperty("invert", invert?.asBoolean ?: false)
            })
        })
        moved = true
    }
    return moved
}
