// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal const val ROOT_REMOTE_RULE_SET_PORT_RANGE_OUTBOUND_ROUTE_MARKER = "_wanbox_root_remote_ruleset_port_range_outbound_v76"

/**
 * Generate a migrated remote rule_set conjunction with port/source-port numeric and
 * range matchers. Only exact route-only shapes are projected; unknown predicates or
 * malformed matcher values remain in the App-generated rule.
 */
internal fun projectRootRemoteRuleSetPortRangeOutboundRoutes(config: JsonObject): Boolean {
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
        if (rule.has(ROOT_REMOTE_RULE_SET_PORT_RANGE_OUTBOUND_ROUTE_MARKER)) continue
        if (rule.keySet().any { it !in setOf("rule_set", "port", "port_range", "source_port", "source_port_range", "outbound", "invert") }) continue
        val tagsElement = rule.get("rule_set") ?: continue
        val outboundElement = rule.get("outbound") ?: continue
        if (!tagsElement.isJsonArray || tagsElement.asJsonArray.size() == 0) continue
        val tags = tagsElement.asJsonArray.map { tag ->
            if (!tag.isJsonPrimitive || !tag.asJsonPrimitive.isString) return@map null
            tag.asString.takeIf(String::isNotBlank)
        }
        if (tags.any { it == null } || tags.filterNotNull().any { it !in migratedTags } || tags.filterNotNull().distinct().size != tags.size) continue
        fun numbers(key: String): List<Int>? {
            val element = rule.get(key) ?: return emptyList()
            if (!element.isJsonArray || element.asJsonArray.size() > 256) return null
            val values = mutableListOf<Int>()
            for (value in element.asJsonArray) {
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
                val number = value.asJsonPrimitive.asString.toIntOrNull() ?: return null
                if (number !in 0..65535) return null
                values += number
            }
            return values
        }
        fun ranges(key: String): List<String>? {
            val element = rule.get(key) ?: return emptyList()
            if (!element.isJsonArray || element.asJsonArray.size() > 256) return null
            val values = mutableListOf<String>()
            for (value in element.asJsonArray) {
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
                val text = value.asString
                if (text.trim() != text) return null
                val bounds = text.split(':')
                if (bounds.size != 2) return null
                val first = bounds[0].toIntOrNull() ?: return null
                val last = bounds[1].toIntOrNull() ?: return null
                if (first !in 1..65535 || last !in first..65535) return null
                values += text
            }
            return values
        }
        val ports = numbers("port") ?: continue
        val sourcePorts = numbers("source_port") ?: continue
        val portRanges = ranges("port_range") ?: continue
        val sourcePortRanges = ranges("source_port_range") ?: continue
        if (portRanges.isEmpty() && sourcePortRanges.isEmpty()) continue
        if (ports.isEmpty() && sourcePorts.isEmpty() && portRanges.isEmpty() && sourcePortRanges.isEmpty()) continue
        if (!outboundElement.isJsonPrimitive || !outboundElement.asJsonPrimitive.isString || outboundElement.asString.isBlank()) continue
        val invert = rule.get("invert")
        if (invert != null && (!invert.isJsonPrimitive || !invert.asJsonPrimitive.isBoolean)) continue
        rules.set(index, JsonObject().apply {
            add(ROOT_REMOTE_RULE_SET_PORT_RANGE_OUTBOUND_ROUTE_MARKER, JsonObject().apply {
                addProperty("index", index)
                add("tags", JsonArray().apply { tags.filterNotNull().forEach(::add) })
                add("ports", JsonArray().apply { ports.forEach(::add) })
                add("portRanges", JsonArray().apply { portRanges.forEach(::add) })
                add("sourcePorts", JsonArray().apply { sourcePorts.forEach(::add) })
                add("sourcePortRanges", JsonArray().apply { sourcePortRanges.forEach(::add) })
                addProperty("outbound", outboundElement.asString)
                addProperty("invert", invert?.asBoolean ?: false)
            })
        })
        moved = true
    }
    return moved
}
