// @author 雾晚
package io.nekohasekai.sagernet.route

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_APP_IDENTITY_DOMAIN_DNS_RULE_MARKER = "_wanbox_root_app_identity_domain_dns_rule_v80"
internal const val ROOT_APP_IDENTITY_DOMAIN_DNS_RULE_MAX_ID = 9_007_199_254_740_991L

internal data class RootAppIdentityDomainDNSRuleInput(
    val ruleId: Long,
    val packages: List<String>,
    val uids: List<Int>,
    val domainText: String,
    val kind: String,
    val fakeDns: Boolean
)

/** Rebuild only package-identity AND supported-domain DNS effects emitted by ConfigBuilder. */
internal fun rootAppIdentityDomainDNSRuleInput(
    rule: RuleEntity,
    routingAction: String,
    uids: List<Int>,
    useFakeDns: Boolean
): RootAppIdentityDomainDNSRuleInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_APP_IDENTITY_DOMAIN_DNS_RULE_MAX_ID ||
        rule.packages.isEmpty() || rule.domains.isBlank() || rule.ip.isNotBlank() || rule.ruleset.isNotBlank() ||
        rule.port.isNotBlank() || rule.sourcePort.isNotBlank() || rule.network.isNotBlank() ||
        rule.source.isNotBlank() || rule.protocol.isNotBlank() || routingAction !in setOf("route", "reject")) return null
    val options = RouteRuleEditor.json(rule.config)
    if (options.keys().asSequence().any { it != "invert" } || options.has("invert") && options.opt("invert") !is Boolean) return null
    if (!parseRootDNSDomainMatchers(rule.domains)) return null
    val packages = rule.packages.toList()
    if (packages.size > 256 || packages.toSet().size != packages.size || packages.any { !validAppIdentityDNSPackage(it) }) return null
    val resolvedUIDs = uids.toList()
    if (resolvedUIDs.size > 4096 || resolvedUIDs.any { it < 0 }) return null
    val kind = when {
        routingAction == "reject" || rule.outbound == -2L -> "reject"
        rule.outbound == -1L -> "direct"
        else -> "proxy"
    }
    return RootAppIdentityDomainDNSRuleInput(rule.id, packages, resolvedUIDs, rule.domains, kind, useFakeDns && kind == "proxy")
}

/** Validates the typed marker before the App hands the snapshot to Root. */
internal fun projectRootAppIdentityDomainDNSRules(config: JsonObject): Boolean {
    val rules = config.getAsJsonObject("dns")?.getAsJsonArray("rules") ?: return false
    val ids = hashSetOf<Long>()
    var found = false
    rules.forEach { element ->
        if (!element.isJsonObject || !element.asJsonObject.has(ROOT_APP_IDENTITY_DOMAIN_DNS_RULE_MARKER)) return@forEach
        found = true
        val rule = element.asJsonObject
        require(rule.keySet() == setOf(ROOT_APP_IDENTITY_DOMAIN_DNS_RULE_MARKER)) { "root_app_identity_domain_dns_invalid" }
        val marker = rule.getAsJsonObject(ROOT_APP_IDENTITY_DOMAIN_DNS_RULE_MARKER)
        require(marker.keySet() == setOf("ruleId", "packages", "uids", "domainText", "kind", "fakeDns")) { "root_app_identity_domain_dns_invalid" }
        val id = marker.get("ruleId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toLongOrNull()
        val packagesValue = marker.getAsJsonArray("packages")
        val packages = packagesValue.mapNotNull { it.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }?.asString }
        val uidsValue = marker.getAsJsonArray("uids")
        val uids = uidsValue.mapNotNull { value -> value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toIntOrNull() }
        val domainText = marker.get("domainText")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val kind = marker.get("kind")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val fakeDns = marker.get("fakeDns")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        require(id != null && id in 1..ROOT_APP_IDENTITY_DOMAIN_DNS_RULE_MAX_ID && ids.add(id) &&
            packagesValue.size() in 1..256 && packages.size == packagesValue.size() && packages.toSet().size == packages.size && packages.all(::validAppIdentityDNSPackage) &&
            uids.size == uidsValue.size() && uids.size <= 4096 && uids.all { it >= 0 } &&
            domainText != null && parseRootDNSDomainMatchers(domainText) && kind in setOf("direct", "reject", "proxy") &&
            fakeDns != null && (!fakeDns || kind == "proxy")) { "root_app_identity_domain_dns_invalid" }
    }
    return found
}
