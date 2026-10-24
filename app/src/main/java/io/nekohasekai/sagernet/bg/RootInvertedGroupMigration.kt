// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal const val ROOT_INVERTED_GROUP_MARKER = "_wanbox_root_route_inverted_group_v49"

/**
 * Move construction of ConfigBuilder's NOT(domain OR IP) wrapper into Root.
 * Only accept the exact logical-group shape emitted by RouteRuleEditor.invertedGroup;
 * children and terminal action are retained without reinterpretation.
 */
internal fun projectRootInvertedGroups(config: JsonObject): Boolean {
    val route = config.getAsJsonObject("route") ?: return false
    val rules = route.getAsJsonArray("rules") ?: return false
    var moved = false
    val actionKeys = setOf("action", "outbound", "method", "no_drop", "strategy", "sniffer", "timeout", "override_address", "override_port")
    for (index in 0 until rules.size()) {
        val raw = rules[index]
        if (!raw.isJsonObject) continue
        val entry = raw.asJsonObject
        if (entry.has(ROOT_INVERTED_GROUP_MARKER)) continue
        val hasRemoteReference = entry.has(ROOT_REMOTE_RULE_SET_REFERENCE_MARKER)
        if (!hasRemoteReference && entry.keySet().any { it.startsWith("_wanbox_root_") }) continue
        val group = if (hasRemoteReference) {
            val marker = entry.getAsJsonObject(ROOT_REMOTE_RULE_SET_REFERENCE_MARKER)
            marker.get("rule")?.takeIf { it.isJsonObject }?.asJsonObject
        } else entry
        if (group == null || group.get("type")?.asString != "logical" || group.get("mode")?.asString != "or" ||
            group.get("invert")?.asBoolean != true || group.get("rules")?.isJsonArray != true) continue
        val children = group.getAsJsonArray("rules")
        if (children.size() < 2 || children.any(::containsRootDomainRegex) ||
            group.keySet().any { it !in setOf("type", "mode", "invert", "rules") && it !in actionKeys }) continue
        val action = JsonObject()
        group.entrySet().filter { it.key in actionKeys }.forEach { (key, value) -> action.add(key, value.deepCopy()) }
        rules.set(index, JsonObject().apply {
            add(ROOT_INVERTED_GROUP_MARKER, JsonObject().apply {
                addProperty("index", index)
                add("rules", children.deepCopy())
                add("action", action)
            })
        })
        moved = true
    }
    return moved
}

private fun containsRootDomainRegex(value: JsonElement): Boolean {
    if (value.isJsonObject) {
        val obj = value.asJsonObject
        if (obj.has("domain_regex")) return true
        return obj.entrySet().any { containsRootDomainRegex(it.value) }
    }
    if (value.isJsonArray) return value.asJsonArray.any(::containsRootDomainRegex)
    return false
}
