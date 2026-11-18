// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity

/**
 * N6 路由规则指定策略组：纯判定，不读库、不写库、不碰 Android 运行时。
 *
 * 引用用稳定实体 ID（ProxyGroup.id）；运行时 tag 在生成时由当前组名派生，
 * 因此已提交的改名不会使引用失效（tag 随新名重算），只有删除组才会悬空。
 * 组名空白时无法派生 tag，同样视为不可解析。
 */
object RouteGroupTarget {

    /** 被删除组阻断时定位到的组与引用它的首条规则。 */
    data class BlockingReference(val groupId: Long, val rule: RuleEntity)

    /**
     * 策略组运行时 tag 契约：主组固定 `proxy`，非主组为 trim 后组名。
     * 非主组组名空白时返回 null（调用方按缺失引用处理，不得回退主代理）。
     */
    fun runtimeTag(groupName: String?, isCurrentGroup: Boolean): String? {
        if (isCurrentGroup) return "proxy"
        return groupName?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 由稳定实体 ID 解析规则目标的运行时 tag；组不存在或组名空白时返回 null。 */
    fun resolveTag(rule: RuleEntity, groups: List<ProxyGroup>, currentGroupId: Long): String? {
        val targetId = rule.policyGroupId ?: return null
        val group = groups.firstOrNull { it.id == targetId } ?: return null
        return runtimeTag(group.name, targetId == currentGroupId)
    }

    /**
     * 节点与组目标互斥：组目标存在时，节点出站必须保持未选（0），
     * 且规则动作必须是需要出站的路由动作；直连/拦截/非路由动作与组目标同存即违规。
     */
    fun mutualExclusionViolation(rule: RuleEntity): Boolean {
        if (rule.policyGroupId == null) return false
        if (rule.outbound != 0L) return true
        return !RouteRuleEditor.needsOutbound(rule.config, rule.outbound)
    }

    /**
     * 单条件表单能否设置策略组目标：多条件（`rules` 数组）/logical/invert
     * 与其他无法确认插入位置的情况（自定义配置可能改写 route.rules）一律走 JSON 编辑器。
     * 其他原生字段（如 route-options）保留原样通过，不强制走 JSON 编辑器。
     */
    fun canUseForm(rule: RuleEntity, hasCustomConfig: Boolean): Boolean {
        if (hasCustomConfig) return false
        val obj = runCatching { RouteRuleEditor.json(rule.config) }.getOrNull() ?: return false
        if (obj.has("rules")) return false
        if (obj.optString("type") == "logical") return false
        if (obj.optBoolean("invert")) return false
        return true
    }

    fun referencedGroupIds(rules: List<RuleEntity>): Set<Long> =
        rules.mapNotNull { it.policyGroupId }.toSet()

    /**
     * 借鉴上游 `validateRemovedRouteGroups` 的行为，按 wanBox 稳定 ID 引用重写：
     * 被删除的组只要仍被任意规则（无论是否启用）引用就阻断，返回首个阻断项。
     * 改名不列入阻断：引用按 ID 解析，tag 在生成时重算，改名本身不使引用失效；
     * 组名改空白在组设置保存时已有非空回退，不会落到此处。
     */
    fun firstBlockingReference(
        rules: List<RuleEntity>,
        removedGroupIds: Set<Long>,
    ): BlockingReference? {
        if (removedGroupIds.isEmpty()) return null
        for (rule in rules) {
            val target = rule.policyGroupId ?: continue
            if (target in removedGroupIds) return BlockingReference(target, rule)
        }
        return null
    }
}
