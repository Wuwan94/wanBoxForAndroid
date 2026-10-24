// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal const val ROOT_REMOTE_RULE_SET_REFERENCE_MARKER = "_wanbox_root_remote_rule_set_reference_v47"

/**
 * Move route/DNS rule objects whose complete rule_set dependency set has already been
 * transferred as generated remote binary definitions. Keep the whole rule tree so logical
 * OR/NOT groups, action, DNS server choice, and source ordering survive byte-for-byte JSON
 * structure. Rules containing domain_regex or any untransferred set stay on the legacy path.
 */
internal fun projectRootRemoteRuleSetReferences(config: JsonObject): Boolean {
    val route = config.getAsJsonObject("route") ?: return false
    val definitions = route.getAsJsonArray("rule_set") ?: return false
    val migratedTags = definitions.mapNotNull { entry ->
        if (!entry.isJsonObject) return@mapNotNull null
        val marker = entry.asJsonObject.getAsJsonObject(ROOT_REMOTE_RULE_SET_MARKER) ?: return@mapNotNull null
        marker.get("tag")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }.toSet()
    if (migratedTags.isEmpty()) return false
    var moved = false
    fun project(array: JsonArray?) {
        if (array == null) return
        for (index in 0 until array.size()) {
            val raw = array[index]
            if (!raw.isJsonObject || raw.asJsonObject.has(ROOT_REMOTE_RULE_SET_REFERENCE_MARKER)) continue
            val tags = linkedSetOf<String>()
            var invalid = false
            var hasRegex = false
            fun visit(value: JsonElement) {
                when {
                    value.isJsonObject -> {
                        value.asJsonObject.entrySet().forEach { (key, child) ->
                            if (key == "domain_regex") hasRegex = true
                            if (key == "rule_set") {
                                if (!child.isJsonArray || child.asJsonArray.size() == 0) invalid = true
                                else child.asJsonArray.forEach { item ->
                                    if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString || item.asString.isBlank()) invalid = true
                                    else tags += item.asString
                                }
                            } else visit(child)
                        }
                    }
                    value.isJsonArray -> value.asJsonArray.forEach(::visit)
                }
            }
            visit(raw)
            if (invalid || hasRegex || tags.isEmpty() || tags.any { it !in migratedTags }) continue
            array.set(index, JsonObject().apply {
                add(ROOT_REMOTE_RULE_SET_REFERENCE_MARKER, JsonObject().apply {
                    addProperty("index", index)
                    add("rule", raw.deepCopy())
                })
            })
            moved = true
        }
    }
    project(route.getAsJsonArray("rules"))
    project(config.getAsJsonObject("dns")?.getAsJsonArray("rules"))
    return moved
}
