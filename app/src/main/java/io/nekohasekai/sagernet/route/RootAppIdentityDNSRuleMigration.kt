// @author 雾晚
package io.nekohasekai.sagernet.route

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_APP_IDENTITY_DNS_RULE_MARKER = "_wanbox_root_app_identity_dns_rule_v79"
internal const val ROOT_APP_IDENTITY_DNS_RULE_MAX_ID = 9_007_199_254_740_991L

internal data class RootAppIdentityDNSRuleInput(
    val ruleId: Long,
    val packages: List<String>,
    val uids: List<Int>,
    val kind: String,
    val fakeDns: Boolean
)

/** Reconstructs only the DNS side effect ConfigBuilder emits for package-only rules. */
internal fun rootAppIdentityDNSRuleInput(
    rule: RuleEntity,
    routingAction: String,
    uids: List<Int>,
    useFakeDns: Boolean
): RootAppIdentityDNSRuleInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_APP_IDENTITY_DNS_RULE_MAX_ID ||
        rule.packages.isEmpty() || rule.domains.isNotBlank() || rule.ip.isNotBlank() ||
        rule.ruleset.isNotBlank() || rule.port.isNotBlank() || rule.sourcePort.isNotBlank() ||
        rule.network.isNotBlank() || rule.source.isNotBlank() || rule.protocol.isNotBlank() ||
        RouteRuleEditor.json(rule.config).length() != 0 || routingAction !in setOf("route", "reject")) return null
    val packages = rule.packages.toList()
    if (packages.size > 256 || packages.toSet().size != packages.size || packages.any { !validAppIdentityDNSPackage(it) }) return null
    val resolvedUIDs = uids.toList()
    if (resolvedUIDs.size > 4096 || resolvedUIDs.any { it < 0 }) return null
    val kind = when {
        routingAction == "reject" || rule.outbound == -2L -> "reject"
        rule.outbound == -1L -> "direct"
        else -> "proxy"
    }
    return RootAppIdentityDNSRuleInput(rule.id, packages, resolvedUIDs, kind, useFakeDns && kind == "proxy")
}

/** Validates inline typed inputs before they are handed to the Root module. */
internal fun projectRootAppIdentityDNSRules(config: JsonObject): Boolean {
    val rules = config.getAsJsonObject("dns")?.getAsJsonArray("rules") ?: return false
    val ids = hashSetOf<Long>()
    var found = false
    rules.forEach { element ->
        if (!element.isJsonObject || !element.asJsonObject.has(ROOT_APP_IDENTITY_DNS_RULE_MARKER)) return@forEach
        found = true
        val rule = element.asJsonObject
        require(rule.keySet() == setOf(ROOT_APP_IDENTITY_DNS_RULE_MARKER)) { "root_app_identity_dns_invalid" }
        val marker = rule.getAsJsonObject(ROOT_APP_IDENTITY_DNS_RULE_MARKER)
        require(marker.keySet() == setOf("ruleId", "packages", "uids", "kind", "fakeDns")) { "root_app_identity_dns_invalid" }
        val idValue = marker.get("ruleId")
        val id = idValue?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toLongOrNull()
        val packagesValue = marker.getAsJsonArray("packages")
        val packages = packagesValue.mapNotNull { it.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }?.asString }
        val uidsValue = marker.getAsJsonArray("uids")
        val uids = uidsValue.mapNotNull { value ->
            value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toIntOrNull()
        }
        val kind = marker.get("kind")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val fakeDns = marker.get("fakeDns")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        require(id != null && id in 1..ROOT_APP_IDENTITY_DNS_RULE_MAX_ID && ids.add(id) &&
            packagesValue.size() in 1..256 && packages.size == packagesValue.size() && packages.toSet().size == packages.size && packages.all(::validAppIdentityDNSPackage) &&
            uids.size == uidsValue.size() && uids.size <= 4096 && uids.all { it >= 0 } &&
            kind in setOf("direct", "reject", "proxy") && fakeDns != null && (!fakeDns || kind == "proxy")) {
            "root_app_identity_dns_invalid"
        }
    }
    return found
}

internal fun validAppIdentityDNSPackage(value: String): Boolean =
    value.isNotBlank() && value.length <= 256 && value == value.trim() &&
        value.none { it.isWhitespace() || it.isISOControl() || it == '/' || it == '\\' }
