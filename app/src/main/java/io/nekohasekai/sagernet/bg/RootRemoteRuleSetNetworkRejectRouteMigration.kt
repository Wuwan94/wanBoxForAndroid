// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal const val ROOT_REMOTE_RULE_SET_NETWORK_REJECT_ROUTE_MARKER = "_wanbox_root_remote_ruleset_network_reject_v72"

/** Move only migrated binary rule-set AND TCP/UDP reject conjunctions into Root. */
internal fun projectRootRemoteRuleSetNetworkRejectRoutes(config: JsonObject): Boolean {
    val route = config.getAsJsonObject("route") ?: return false
    val definitions = route.getAsJsonArray("rule_set") ?: return false
    val migratedTags = definitions.mapNotNull { item ->
        if (!item.isJsonObject) return@mapNotNull null
        item.asJsonObject.getAsJsonObject(ROOT_REMOTE_RULE_SET_MARKER)
            ?.get("tag")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }.toSet()
    if (migratedTags.isEmpty()) return false
    val rules = route.getAsJsonArray("rules") ?: return false
    var moved = false
    for (index in 0 until rules.size()) {
        val raw = rules[index]
        if (!raw.isJsonObject) continue
        val rule = raw.asJsonObject
        if (rule.has(ROOT_REMOTE_RULE_SET_NETWORK_REJECT_ROUTE_MARKER) ||
            rule.keySet().any { it !in setOf("rule_set", "network", "action", "invert") }) continue
        val tagsElement = rule.get("rule_set") ?: continue
        val networksElement = rule.get("network") ?: continue
        val action = rule.get("action") ?: continue
        if (!tagsElement.isJsonArray || tagsElement.asJsonArray.size() == 0 ||
            !networksElement.isJsonArray || networksElement.asJsonArray.size() == 0 ||
            !action.isJsonPrimitive || !action.asJsonPrimitive.isString || action.asString != "reject") continue
        val tags = tagsElement.asJsonArray.map { tag ->
            if (!tag.isJsonPrimitive || !tag.asJsonPrimitive.isString) return@map null
            tag.asString.takeIf(String::isNotBlank)
        }
        if (tags.any { it == null } || tags.filterNotNull().any { it !in migratedTags } ||
            tags.filterNotNull().distinct().size != tags.size || tags.size > 256) continue
        val networks = networksElement.asJsonArray.map { network ->
            if (!network.isJsonPrimitive || !network.asJsonPrimitive.isString) return@map null
            network.asString.takeIf { it == "tcp" || it == "udp" }
        }
        if (networks.any { it == null } || networks.filterNotNull().distinct().size != networks.size || networks.size > 2) continue
        val invert = rule.get("invert")
        if (invert != null && (!invert.isJsonPrimitive || !invert.asJsonPrimitive.isBoolean)) continue
        rules.set(index, JsonObject().apply {
            add(ROOT_REMOTE_RULE_SET_NETWORK_REJECT_ROUTE_MARKER, JsonObject().apply {
                addProperty("index", index)
                add("tags", JsonArray().apply { tags.filterNotNull().forEach(::add) })
                add("networks", JsonArray().apply { networks.filterNotNull().forEach(::add) })
                addProperty("invert", invert?.asBoolean ?: false)
            })
        })
        moved = true
    }
    return moved
}

internal fun hasRootRemoteRuleSetNetworkRejectRouteMarker(config: JsonObject): Boolean =
    config.getAsJsonObject("route")?.getAsJsonArray("rules")?.any {
        it.isJsonObject && it.asJsonObject.has(ROOT_REMOTE_RULE_SET_NETWORK_REJECT_ROUTE_MARKER)
    } == true
