// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

internal const val ROOT_DOMAIN_KEYWORD_REJECT_ROUTE_MARKER = "_wanbox_root_route_domain_keyword_reject_v42"
internal const val ROOT_DOMAIN_KEYWORD_REJECT_MAX_RULE_ID = 9_007_199_254_740_991L
internal data class RootDomainKeywordRejectRouteInput(val ruleId: Long, val domainText: String)

/** Module owns only enabled reject rules using explicit keyword: matchers. */
internal fun rootDomainKeywordRejectRouteInput(rule: RuleEntity): RootDomainKeywordRejectRouteInput? {
    if (!rule.enabled || rule.id !in 1..ROOT_DOMAIN_KEYWORD_REJECT_MAX_RULE_ID || rule.outbound != -2L ||
        rule.config.isNotBlank() || rule.domains.isBlank() || rule.ip.isNotBlank() || rule.port.isNotBlank() ||
        rule.sourcePort.isNotBlank() || rule.network.isNotBlank() || rule.source.isNotBlank() ||
        rule.protocol.isNotBlank() || rule.ruleset.isNotBlank() || rule.packages.isNotEmpty()) return null
    if (parseRootDomainKeywordRejectKeywords(rule.domains).isNullOrEmpty()) return null
    return RootDomainKeywordRejectRouteInput(rule.id, rule.domains)
}

/** Accept only explicit keyword: tokens in a conservative hostname-character subset; all else stays legacy. */
internal fun parseRootDomainKeywordRejectKeywords(raw: String): List<String>? {
    if (raw.length > 4096) return null
    val tokens = raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (tokens.isEmpty() || tokens.size > 256) return null
    val keywords = ArrayList<String>(tokens.size)
    for (token in tokens) {
        if (!token.startsWith("keyword:")) return null
        val keyword = token.removePrefix("keyword:")
        if (keyword.length !in 1..253 || keyword.any {
                !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '.' || it == '_')
            }) return null
        keywords.add(keyword.lowercase())
    }
    return keywords
}
