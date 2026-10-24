// @author 雾晚
package io.nekohasekai.sagernet.route

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_APP_UID_RULESET_OUTBOUND_MARKER = "_wanbox_root_app_uid_ruleset_outbound_v83"
internal const val ROOT_APP_UID_RULESET_OUTBOUND_MAX_RULE_ID = 9_007_199_254_740_991L

internal data class RootAppUIDRuleSetOutboundInput(
    val ruleId: Long, val packages: List<String>, val uids: List<Int>,
    val ruleSetTags: List<String>, val outbound: String, val invert: Boolean
)

/** Root rebuilds App's package AND UID AND homogeneous rule_set outbound matcher. */
internal fun rootAppUIDRuleSetOutboundInput(
    rule: RuleEntity, outbound: String, uids: List<Int>, ruleSetTags: List<Pair<String, Boolean>>
): RootAppUIDRuleSetOutboundInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_APP_UID_RULESET_OUTBOUND_MAX_RULE_ID ||
        rule.packages.isEmpty() || rule.domains.isNotBlank() || rule.ip.isNotBlank() || rule.ruleset.isBlank() ||
        ruleSetTags.isEmpty() || ruleSetTags.size > 256 || ruleSetTags.map { it.second }.distinct().size != 1 ||
        rule.port.isNotBlank() || rule.sourcePort.isNotBlank() || rule.network.isNotBlank() ||
        rule.source.isNotBlank() || rule.protocol.isNotBlank() || outbound.isBlank() || outbound == "block" || rule.outbound == -2L) return null
    val custom = RouteRuleEditor.json(rule.config)
    if (custom.keys().asSequence().any { it != "invert" } ||
        custom.has("invert") && custom.opt("invert") !is Boolean) return null
    val packages = rule.packages.toList().sorted()
    if (packages.size > 256 || packages.toSet().size != packages.size || packages.any { !validAppIdentityDNSPackage(it) }) return null
    val resolvedUIDs = uids.distinct().sorted()
    if (resolvedUIDs.size > 4096 || resolvedUIDs.any { it < 0 }) return null
    val tags = ruleSetTags.map { it.first }
    if (tags.any { it.isBlank() || it.length > 256 || it.any { c -> c.isWhitespace() || c == '\\' || c == '/' || c == '\u0000' } }) return null
    return RootAppUIDRuleSetOutboundInput(rule.id, packages, resolvedUIDs, tags, outbound, custom.optBoolean("invert"))
}

internal fun projectRootAppUIDRuleSetOutboundRules(config: JsonObject): JsonObject? {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return null
    val projected = JsonArray()
    rules.forEachIndexed { index, element ->
        if (!element.isJsonObject || !element.asJsonObject.has(ROOT_APP_UID_RULESET_OUTBOUND_MARKER)) return@forEachIndexed
        val rule = element.asJsonObject
        require(rule.keySet() == setOf(ROOT_APP_UID_RULESET_OUTBOUND_MARKER)) { "root_app_uid_ruleset_outbound_invalid" }
        val marker = rule.getAsJsonObject(ROOT_APP_UID_RULESET_OUTBOUND_MARKER)
        require(marker.keySet() == setOf("index", "ruleId", "packages", "uids", "ruleSetTags", "outbound", "invert")) { "root_app_uid_ruleset_outbound_invalid" }
        val markerIndex = marker.get("index")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        require(markerIndex != null && markerIndex >= index && markerIndex <= (1 shl 20)) { "root_app_uid_ruleset_outbound_invalid" }
        projected.add(marker.deepCopy().apply { addProperty("index", index) })
    }
    return if (projected.size() == 0) null else JsonObject().apply { add("rules", projected) }
}

internal const val ROOT_APP_UID_DOMAIN_RULESET_OUTBOUND_MARKER = "_wanbox_root_app_uid_domain_ruleset_outbound_v84"

internal data class RootAppUIDDomainRuleSetOutboundInput(
    val ruleId: Long, val packages: List<String>, val uids: List<Int>, val domainText: String,
    val ruleSetTags: List<String>, val ruleSetKinds: List<String>, val outbound: String, val invert: Boolean
)

/** Root rebuilds only explicit supported domains AND domain-kind remote sets with App-resolved identity. */
internal fun rootAppUIDDomainRuleSetOutboundInput(
    rule: RuleEntity, outbound: String, uids: List<Int>, ruleSetTags: List<Pair<String, Boolean>>
): RootAppUIDDomainRuleSetOutboundInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_APP_UID_RULESET_OUTBOUND_MAX_RULE_ID ||
        rule.packages.isEmpty() || rule.domains.isBlank() || rule.ip.isNotBlank() || rule.ruleset.isBlank() ||
        ruleSetTags.isEmpty() || ruleSetTags.size > 256 || ruleSetTags.any { it.second } ||
        rule.port.isNotBlank() || rule.sourcePort.isNotBlank() || rule.network.isNotBlank() ||
        rule.source.isNotBlank() || rule.protocol.isNotBlank() || outbound.isBlank() || outbound == "block" ||
        rule.outbound == -2L) return null
    val custom = RouteRuleEditor.json(rule.config)
    if (custom.keys().asSequence().any { it != "invert" } ||
        custom.has("invert") && custom.opt("invert") !is Boolean ||
        !parseRootDNSDomainMatchers(rule.domains)) return null
    val packages = rule.packages.toList().sorted()
    if (packages.size > 256 || packages.toSet().size != packages.size || packages.any { !validAppIdentityDNSPackage(it) }) return null
    val resolvedUIDs = uids.distinct().sorted()
    if (resolvedUIDs.size > 4096 || resolvedUIDs.any { it < 0 }) return null
    val tags = ruleSetTags.map { it.first }
    if (tags.any { it.isBlank() || it.length > 256 || it.any { c -> c.isWhitespace() || c == '\\' || c == '/' || c == '\u0000' } }) return null
    return RootAppUIDDomainRuleSetOutboundInput(
        rule.id, packages, resolvedUIDs, rule.domains, tags, List(tags.size) { "domain" }, outbound, custom.optBoolean("invert")
    )
}

internal fun projectRootAppUIDDomainRuleSetOutboundRules(config: JsonObject): JsonObject? {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return null
    val projected = JsonArray()
    val ids = hashSetOf<Long>()
    rules.forEachIndexed { index, element ->
        if (!element.isJsonObject || !element.asJsonObject.has(ROOT_APP_UID_DOMAIN_RULESET_OUTBOUND_MARKER)) return@forEachIndexed
        val rule = element.asJsonObject
        require(rule.keySet() == setOf(ROOT_APP_UID_DOMAIN_RULESET_OUTBOUND_MARKER)) { "root_app_uid_domain_ruleset_outbound_invalid" }
        val marker = rule.getAsJsonObject(ROOT_APP_UID_DOMAIN_RULESET_OUTBOUND_MARKER)
        require(marker.keySet() == setOf("index", "ruleId", "packages", "uids", "domainText", "ruleSetTags", "ruleSetKinds", "outbound", "invert")) {
            "root_app_uid_domain_ruleset_outbound_invalid"
        }
        val markerIndex = marker.get("index")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        val ruleId = marker.get("ruleId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toLongOrNull()
        val packagesValue = marker.getAsJsonArray("packages")
        val packages = packagesValue.mapNotNull { it.takeIf { v -> v.isJsonPrimitive && v.asJsonPrimitive.isString }?.asString }
        val uidsValue = marker.getAsJsonArray("uids")
        val uids = uidsValue.mapNotNull { it.takeIf { v -> v.isJsonPrimitive && v.asJsonPrimitive.isNumber }?.asString?.toIntOrNull() }
        val domainText = marker.get("domainText")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val tagsValue = marker.getAsJsonArray("ruleSetTags")
        val tags = tagsValue.mapNotNull { it.takeIf { v -> v.isJsonPrimitive && v.asJsonPrimitive.isString }?.asString }
        val kindsValue = marker.getAsJsonArray("ruleSetKinds")
        val kinds = kindsValue.mapNotNull { it.takeIf { v -> v.isJsonPrimitive && v.asJsonPrimitive.isString }?.asString }
        val outbound = marker.get("outbound")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val invert = marker.get("invert")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        require(markerIndex != null && markerIndex >= index && markerIndex <= (1 shl 20) &&
            ruleId != null && ruleId in 1..ROOT_APP_UID_RULESET_OUTBOUND_MAX_RULE_ID && ids.add(ruleId) &&
            packagesValue.size() in 1..256 && packages.size == packagesValue.size() && packages.toSet().size == packages.size && packages.all(::validAppIdentityDNSPackage) &&
            uids.size == uidsValue.size() && uids.size <= 4096 && uids.all { it >= 0 } &&
            domainText != null && parseRootDNSDomainMatchers(domainText) &&
            tagsValue.size() in 1..256 && tags.size == tagsValue.size() && tags.toSet().size == tags.size &&
            kinds.size == kindsValue.size() && kinds == List(tags.size) { "domain" } &&
            outbound != null && outbound.isNotBlank() && outbound != "block" && invert != null) {
            "root_app_uid_domain_ruleset_outbound_invalid"
        }
        projected.add(marker.deepCopy().apply { addProperty("index", index) })
    }
    return if (projected.size() == 0) null else JsonObject().apply { add("rules", projected) }
}
