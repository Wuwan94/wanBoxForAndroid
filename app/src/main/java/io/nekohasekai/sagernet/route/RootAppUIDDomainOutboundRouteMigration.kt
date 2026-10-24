// @author 雾晚
package io.nekohasekai.sagernet.route

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_APP_UID_DOMAIN_OUTBOUND_ROUTE_MARKER = "_wanbox_root_app_uid_domain_outbound_v81"
internal const val ROOT_APP_UID_DOMAIN_OUTBOUND_MAX_RULE_ID = 9_007_199_254_740_991L

internal data class RootAppUIDDomainOutboundRuleInput(
    val ruleId: Long,
    val packages: List<String>,
    val uids: List<Int>,
    val domainText: String,
    val outbound: String,
    val invert: Boolean
)

/** Root owns the final package/UID AND supported-domain route matcher; App resolves Android UIDs. */
internal fun rootAppUIDDomainOutboundRouteInput(
    rule: RuleEntity,
    outbound: String,
    uids: List<Int>
): RootAppUIDDomainOutboundRuleInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_APP_UID_DOMAIN_OUTBOUND_MAX_RULE_ID ||
        rule.packages.isEmpty() || rule.domains.isBlank() || rule.ip.isNotBlank() || rule.ruleset.isNotBlank() ||
        rule.port.isNotBlank() || rule.sourcePort.isNotBlank() || rule.network.isNotBlank() ||
        rule.source.isNotBlank() || rule.protocol.isNotBlank() || outbound.isBlank() || outbound == "block" ||
        rule.outbound == -2L) return null
    val custom = RouteRuleEditor.json(rule.config)
    if (custom.keys().asSequence().any { it != "invert" } ||
        custom.has("invert") && custom.opt("invert") !is Boolean ||
        !parseRootDNSDomainMatchers(rule.domains)) return null
    val packages = rule.packages.toList().sorted()
    if (packages.size > 256 || packages.toSet().size != packages.size || packages.any { !validAppIdentityDNSPackage(it) }) return null
    val resolvedUIDs = uids.toList().distinct().sorted()
    if (resolvedUIDs.size > 4096 || resolvedUIDs.any { it < 0 }) return null
    return RootAppUIDDomainOutboundRuleInput(rule.id, packages, resolvedUIDs, rule.domains, outbound, custom.optBoolean("invert"))
}

/** Rebase marker positions after preceding Root projections remove their own markers. */
internal fun projectRootAppUIDDomainOutboundRules(config: JsonObject): JsonObject? {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return null
    val projected = JsonArray()
    val ids = hashSetOf<Long>()
    rules.forEachIndexed { index, element ->
        if (!element.isJsonObject || !element.asJsonObject.has(ROOT_APP_UID_DOMAIN_OUTBOUND_ROUTE_MARKER)) return@forEachIndexed
        val rule = element.asJsonObject
        require(rule.keySet() == setOf(ROOT_APP_UID_DOMAIN_OUTBOUND_ROUTE_MARKER)) { "root_app_uid_domain_outbound_invalid" }
        val marker = rule.getAsJsonObject(ROOT_APP_UID_DOMAIN_OUTBOUND_ROUTE_MARKER)
        require(marker.keySet() == setOf("index", "ruleId", "packages", "uids", "domainText", "outbound", "invert")) {
            "root_app_uid_domain_outbound_invalid"
        }
        val markerIndex = marker.get("index")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        val ruleId = marker.get("ruleId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toLongOrNull()
        val packagesValue = marker.getAsJsonArray("packages")
        val packages = packagesValue.mapNotNull { it.takeIf { v -> v.isJsonPrimitive && v.asJsonPrimitive.isString }?.asString }
        val uidsValue = marker.getAsJsonArray("uids")
        val uids = uidsValue.mapNotNull { it.takeIf { v -> v.isJsonPrimitive && v.asJsonPrimitive.isNumber }?.asString?.toIntOrNull() }
        val domainText = marker.get("domainText")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val outbound = marker.get("outbound")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val invert = marker.get("invert")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        require(markerIndex != null && markerIndex >= index && markerIndex <= (1 shl 20) &&
            ruleId != null && ruleId in 1..ROOT_APP_UID_DOMAIN_OUTBOUND_MAX_RULE_ID && ids.add(ruleId) &&
            packagesValue.size() in 1..256 && packages.size == packagesValue.size() && packages.toSet().size == packages.size && packages.all(::validAppIdentityDNSPackage) &&
            uids.size == uidsValue.size() && uids.size <= 4096 && uids.all { it >= 0 } &&
            domainText != null && parseRootDNSDomainMatchers(domainText) &&
            outbound != null && outbound.isNotBlank() && outbound != "block" &&
            invert != null) { "root_app_uid_domain_outbound_invalid" }
        projected.add(marker.deepCopy().apply { addProperty("index", index) })
    }
    return if (projected.size() == 0) null else JsonObject().apply { add("rules", projected) }
}
