// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.route.parseRootDNSDomainMatchers

internal const val ROOT_REMOTE_RULE_SET_DOMAIN_OUTBOUND_ROUTE_MARKER = "_wanbox_root_remote_ruleset_domain_outbound_v68"

/**
 * Root materializes a migrated remote rule_set AND supported domain matcher conjunction.
 * Any extra predicate, unmigrated set, or matcher outside the DNS-safe subset stays legacy.
 */
internal fun projectRootRemoteRuleSetDomainOutboundRoutes(config: JsonObject): Boolean {
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
        if (rule.has(ROOT_REMOTE_RULE_SET_DOMAIN_OUTBOUND_ROUTE_MARKER)) continue
        val allowed = setOf("rule_set", "domain", "domain_suffix", "domain_keyword", "outbound", "invert")
        if (rule.keySet().any { it !in allowed }) continue
        val tagsElement = rule.get("rule_set") ?: continue
        val outboundElement = rule.get("outbound") ?: continue
        if (!tagsElement.isJsonArray || tagsElement.asJsonArray.size() == 0 ||
            !outboundElement.isJsonPrimitive || !outboundElement.asJsonPrimitive.isString || outboundElement.asString.isBlank()) continue
        val tags = tagsElement.asJsonArray.map { tag ->
            if (!tag.isJsonPrimitive || !tag.asJsonPrimitive.isString) return@map null
            tag.asString.takeIf(String::isNotBlank)
        }
        if (tags.any { it == null } || tags.filterNotNull().any { it !in migratedTags } ||
            tags.filterNotNull().distinct().size != tags.size) continue
        val domains = stringArray(rule, "domain") ?: if (rule.has("domain")) continue else emptyList()
        val suffixes = stringArray(rule, "domain_suffix") ?: if (rule.has("domain_suffix")) continue else emptyList()
        val keywords = stringArray(rule, "domain_keyword") ?: if (rule.has("domain_keyword")) continue else emptyList()
        if (domains.isEmpty() && suffixes.isEmpty() && keywords.isEmpty()) continue
        val allDomainsValid = domains.all { parseRootDNSDomainMatchers("full:$it") } &&
            suffixes.all { parseRootDNSDomainMatchers("domain:$it") } &&
            keywords.all { parseRootDNSDomainMatchers("keyword:$it") }
        if (!allDomainsValid) continue
        val invert = rule.get("invert")
        if (invert != null && (!invert.isJsonPrimitive || !invert.asJsonPrimitive.isBoolean)) continue
        rules.set(index, JsonObject().apply {
            add(ROOT_REMOTE_RULE_SET_DOMAIN_OUTBOUND_ROUTE_MARKER, JsonObject().apply {
                addProperty("index", index)
                add("tags", JsonArray().apply { tags.filterNotNull().forEach(::add) })
                add("domains", JsonArray().apply { domains.forEach(::add) })
                add("suffixes", JsonArray().apply { suffixes.forEach(::add) })
                add("keywords", JsonArray().apply { keywords.forEach(::add) })
                addProperty("outbound", outboundElement.asString)
                addProperty("invert", invert?.asBoolean ?: false)
            })
        })
        moved = true
    }
    return moved
}

private fun stringArray(rule: JsonObject, key: String): List<String>? {
    val value = rule.get(key) ?: return emptyList()
    if (!value.isJsonArray || value.asJsonArray.size() == 0) return null
    val result = value.asJsonArray.map { item ->
        if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) return null
        val text = item.asString
        if (text.isBlank() || text != text.trim() || text.contains(',') || text.contains('\n')) return null
        text
    }
    return result.takeIf { it.size <= 256 }
}
