package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

/** Pure validation over caller-supplied snapshots; no database access or persistence. */
object OutboundReferenceValidator {
    enum class ReferenceKind { NODE_OR_STRATEGY_GROUP }

    /** Diagnostic identity only: never retain the rule, config, or outbound entity. */
    data class Issue(
        val ruleId: Long,
        val ruleName: String,
        val kind: ReferenceKind,
        val missingId: Long,
    )

    /**
     * [existingOutboundIds] includes both node and strategy-group/balancer ProxyEntity IDs.
     * Checks only each rule's own outbound ID, without inspecting nested group references.
     * Rule action semantics come from RuleEntity.config, with a default route action.
     */
    fun validate(
        rules: List<RuleEntity>,
        existingOutboundIds: Set<Long>,
    ): List<Issue> = rules.mapNotNull { rule ->
        if (rule.outbound <= 0L || rule.outbound in existingOutboundIds) return@mapNotNull null
        val needsOutbound = try {
            RouteRuleEditor.needsOutbound(rule.config, rule.outbound)
        } catch (_: Exception) {
            true
        }
        if (!needsOutbound) return@mapNotNull null
        Issue(rule.id, rule.displayName(), ReferenceKind.NODE_OR_STRATEGY_GROUP, rule.outbound)
    }
}
