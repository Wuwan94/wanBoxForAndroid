// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal const val ROOT_REMOTE_RULE_SET_DNS_EFFECT_MARKER = "_wanbox_root_remote_ruleset_dns_effect_v56"

/**
 * Turn only the App's plain one-ruleset DNS outbound effects into typed Root inputs.
 * The rule stays in its original slot; unsupported/custom DNS rules remain untouched.
 */
internal fun projectRootRemoteRuleSetDNSEffects(config: JsonObject): Boolean {
    val route = config.getAsJsonObject("route") ?: return false
    val definitions = route.getAsJsonArray("rule_set") ?: return false
    val migratedTags = definitions.mapNotNull { entry ->
        if (!entry.isJsonObject) return@mapNotNull null
        val marker = entry.asJsonObject.getAsJsonObject(ROOT_REMOTE_RULE_SET_MARKER) ?: return@mapNotNull null
        marker.get("tag")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }.toSet()
    if (migratedTags.isEmpty()) return false
    val rules = config.getAsJsonObject("dns")?.getAsJsonArray("rules") ?: return false
    var moved = false
    for (index in 0 until rules.size()) {
        val raw = rules[index]
        if (!raw.isJsonObject) continue
        val rule = raw.asJsonObject
        if (rule.has(ROOT_REMOTE_RULE_SET_DNS_EFFECT_MARKER)) continue
        val tagsElement = rule.get("rule_set") ?: continue
        if (!tagsElement.isJsonArray || tagsElement.asJsonArray.size() != 1) continue
        val tagElement = tagsElement.asJsonArray[0]
        if (!tagElement.isJsonPrimitive || !tagElement.asJsonPrimitive.isString) continue
        val tag = tagElement.asString
        if (tag !in migratedTags) continue
        val kind = when {
            rule.keySet() == setOf("rule_set", "action") && rule.get("action")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true &&
                rule.get("action").asString == "reject" -> "reject"
            rule.keySet() == setOf("rule_set", "server") && rule.get("server")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true -> when (rule.get("server").asString) {
                "dns-direct" -> "direct"
                "dns-remote" -> "proxy"
                else -> null
            }
            rule.keySet() == setOf("rule_set", "server", "inbound", "query_type") &&
                rule.get("server")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true && rule.get("server").asString == "dns-fake" &&
                rule.get("inbound")?.isJsonArray == true && rule.getAsJsonArray("inbound").size() == 1 &&
                rule.getAsJsonArray("inbound")[0].let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == "tun-in" } &&
                rule.get("query_type")?.isJsonArray == true && rule.getAsJsonArray("query_type").all { it.isJsonPrimitive && it.asJsonPrimitive.isString } && rule.getAsJsonArray("query_type").map { it.asString } == listOf("A", "AAAA") -> "fake"
            else -> null
        } ?: continue
        rules.set(index, JsonObject().apply {
            add(ROOT_REMOTE_RULE_SET_DNS_EFFECT_MARKER, JsonObject().apply {
                addProperty("tag", tag)
                addProperty("kind", kind)
            })
        })
        moved = true
    }
    return moved
}

internal fun hasRootRemoteRuleSetDNSEffectMarker(config: JsonObject): Boolean =
    config.getAsJsonObject("dns")?.getAsJsonArray("rules")?.any { it.isJsonObject && it.asJsonObject.has(ROOT_REMOTE_RULE_SET_DNS_EFFECT_MARKER) } == true
