// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal const val ROOT_ROUTE_RAW_RULE_MARKER = "_wanbox_root_route_raw_rule_v48"

/**
 * Transfer exact ConfigBuilder-produced route/DNS rule objects that earlier typed projections
 * deliberately left in the config. No matcher or action is rewritten. Unsupported regex rules
 * and already-projected Root markers stay on their existing paths.
 */
internal fun projectRootRouteRawRules(config: JsonObject): Boolean {
    var moved = false
    fun project(array: JsonArray?) {
        if (array == null) return
        for (index in 0 until array.size()) {
            val raw = array[index]
            if (!raw.isJsonObject) continue
            val rule = raw.asJsonObject
            if (rule.keySet().any { it.startsWith("_wanbox_root_") }) continue
            var hasRegex = false
            fun visit(value: JsonElement) {
                when {
                    value.isJsonObject -> value.asJsonObject.entrySet().forEach { (key, child) ->
                        if (key == "domain_regex") hasRegex = true
                        visit(child)
                    }
                    value.isJsonArray -> value.asJsonArray.forEach(::visit)
                }
            }
            visit(rule)
            if (hasRegex) continue
            array.set(index, JsonObject().apply {
                add(ROOT_ROUTE_RAW_RULE_MARKER, JsonObject().apply {
                    addProperty("index", index)
                    add("rule", rule.deepCopy())
                })
            })
            moved = true
        }
    }
    val route = config.getAsJsonObject("route")
    project(route?.getAsJsonArray("rules"))
    project(config.getAsJsonObject("dns")?.getAsJsonArray("rules"))
    return moved
}
