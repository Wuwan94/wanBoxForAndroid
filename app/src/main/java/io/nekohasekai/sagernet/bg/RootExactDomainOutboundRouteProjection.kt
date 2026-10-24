// @author 雾晚
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.route.ROOT_DOMAIN_REJECT_MAX_RULE_ID
import io.nekohasekai.sagernet.route.ROOT_EXACT_DOMAIN_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.parseRootDomainRejectDomains
import java.io.IOException

/** Validate the private typed marker and keep it in place for Root-side rule generation. */
internal fun hasRootExactDomainOutboundRouteMarker(config: JsonObject): Boolean {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return false
    val seen = hashSetOf<Long>()
    var found = false
    for (index in 0 until rules.size()) {
        val element = rules[index]
        if (!element.isJsonObject || !element.asJsonObject.has(ROOT_EXACT_DOMAIN_OUTBOUND_ROUTE_MARKER)) continue
        val rule = element.asJsonObject
        if (rule.keySet() != setOf(ROOT_EXACT_DOMAIN_OUTBOUND_ROUTE_MARKER)) throw IOException("root_exact_domain_outbound_invalid")
        val marker = rule.get(ROOT_EXACT_DOMAIN_OUTBOUND_ROUTE_MARKER)
        if (!marker.isJsonObject || marker.asJsonObject.keySet() != setOf("index", "ruleId", "domainText", "outbound")) throw IOException("root_exact_domain_outbound_invalid")
        val obj = marker.asJsonObject
        val sourceIndex = obj.get("index")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        val id = obj.get("ruleId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toLongOrNull()
        val domain = obj.get("domainText")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val outbound = obj.get("outbound")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        if (sourceIndex != index || id == null || id !in 1..ROOT_DOMAIN_REJECT_MAX_RULE_ID || !seen.add(id) ||
            domain == null || parseRootDomainRejectDomains(domain).isNullOrEmpty() || outbound.isNullOrBlank()) {
            throw IOException("root_exact_domain_outbound_invalid")
        }
        found = true
    }
    return found
}
