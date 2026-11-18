package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

/** Pure validation over caller-supplied snapshots; no database access or persistence. */
object OutboundReferenceValidator {
    enum class ReferenceKind { NODE_OR_STRATEGY_GROUP, POLICY_GROUP }

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
     * [existingGroupIds] holds ProxyGroup IDs for N6 policy-group targets; a rule whose
     * [RuleEntity.policyGroupId] is absent from it reports a [ReferenceKind.POLICY_GROUP] issue.
     */
    fun validate(
        rules: List<RuleEntity>,
        existingOutboundIds: Set<Long>,
        existingGroupIds: Set<Long> = emptySet(),
    ): List<Issue> = rules.flatMap { rule ->
        val issues = mutableListOf<Issue>()
        if (rule.outbound > 0L && rule.outbound !in existingOutboundIds) {
            val needsOutbound = try {
                RouteRuleEditor.needsOutbound(rule.config, rule.outbound)
            } catch (_: Exception) {
                true
            }
            if (needsOutbound) {
                issues += Issue(rule.id, rule.displayName(), ReferenceKind.NODE_OR_STRATEGY_GROUP, rule.outbound)
            }
        }
        val groupId = rule.policyGroupId
        if (groupId != null && groupId !in existingGroupIds) {
            val needsOutbound = try {
                RouteRuleEditor.needsOutbound(rule.config, rule.outbound)
            } catch (_: Exception) {
                true
            }
            if (needsOutbound) {
                issues += Issue(rule.id, rule.displayName(), ReferenceKind.POLICY_GROUP, groupId)
            }
        }
        issues
    }
}
