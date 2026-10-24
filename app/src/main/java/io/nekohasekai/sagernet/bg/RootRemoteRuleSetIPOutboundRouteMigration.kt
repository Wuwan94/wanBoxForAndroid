// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.InetAddress

internal const val ROOT_REMOTE_RULE_SET_IP_OUTBOUND_ROUTE_MARKER = "_wanbox_root_remote_ruleset_ip_outbound_v69"

/** Generate an exact remote rule_set AND destination-CIDR conjunction in Root. */
internal fun projectRootRemoteRuleSetIPOutboundRoutes(config: JsonObject): Boolean {
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
        if (rule.has(ROOT_REMOTE_RULE_SET_IP_OUTBOUND_ROUTE_MARKER)) continue
        if (rule.keySet().any { it !in setOf("rule_set", "ip_cidr", "outbound", "invert") }) continue
        val tagsElement = rule.get("rule_set") ?: continue
        val cidrsElement = rule.get("ip_cidr") ?: continue
        val outboundElement = rule.get("outbound") ?: continue
        if (!tagsElement.isJsonArray || tagsElement.asJsonArray.size() == 0 ||
            !cidrsElement.isJsonArray || cidrsElement.asJsonArray.size() == 0) continue
        val tags = tagsElement.asJsonArray.map { tag ->
            if (!tag.isJsonPrimitive || !tag.asJsonPrimitive.isString) return@map null
            tag.asString.takeIf(String::isNotBlank)
        }
        if (tags.any { it == null } || tags.filterNotNull().any { it !in migratedTags } || tags.filterNotNull().distinct().size != tags.size) continue
        val cidrs = cidrsElement.asJsonArray.map { item ->
            if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) return@map null
            item.asString.takeIf { value ->
                value.isNotBlank() && value == value.trim() && !value.contains(',') && !value.contains('\n') && validCIDR(value)
            }
        }
        if (cidrs.any { it == null } || cidrs.filterNotNull().distinct().size != cidrs.size || cidrs.size > 256) continue
        if (!outboundElement.isJsonPrimitive || !outboundElement.asJsonPrimitive.isString || outboundElement.asString.isBlank()) continue
        val invert = rule.get("invert")
        if (invert != null && (!invert.isJsonPrimitive || !invert.asJsonPrimitive.isBoolean)) continue
        rules.set(index, JsonObject().apply {
            add(ROOT_REMOTE_RULE_SET_IP_OUTBOUND_ROUTE_MARKER, JsonObject().apply {
                addProperty("index", index)
                add("tags", JsonArray().apply { tags.filterNotNull().forEach(::add) })
                add("cidrs", JsonArray().apply { cidrs.filterNotNull().forEach(::add) })
                addProperty("outbound", outboundElement.asString)
                addProperty("invert", invert?.asBoolean ?: false)
            })
        })
        moved = true
    }
    return moved
}

private fun validCIDR(value: String): Boolean {
    return try {
        val slash = value.lastIndexOf('/')
        if (slash <= 0 || slash == value.lastIndex) return false
        val bits = value.substring(slash + 1).toIntOrNull() ?: return false
        val addressText = value.substring(0, slash)
        if (!(addressText.matches(Regex("[0-9.]+")) || (addressText.contains(':') && addressText.matches(Regex("[0-9a-fA-F:.]+"))))) return false
        val address = InetAddress.getByName(addressText)
        bits in 0..(if (address.address.size == 4) 32 else 128)
    } catch (_: Exception) { false }
}
