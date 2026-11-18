// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.DomesticRoutingPreset
import io.nekohasekai.sagernet.route.OutboundReferenceValidator
import io.nekohasekai.sagernet.route.OutboundReferenceValidator.Issue
import io.nekohasekai.sagernet.route.OutboundReferenceValidator.ReferenceKind
import io.nekohasekai.sagernet.route.RouteGroupTarget
import io.nekohasekai.sagernet.route.RouteRulePriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** N6（B7）红测：规则目标策略组。实现前以下测试应失败。 */
class RouteGroupTargetTest {

    // 运行时 tag 契约：主组固定 proxy，非主组为 trim 后组名。

    @Test fun runtimeTagOfCurrentGroupIsAlwaysProxy() {
        assertEquals("proxy", RouteGroupTarget.runtimeTag("streaming", isCurrentGroup = true))
        assertEquals("proxy", RouteGroupTarget.runtimeTag(null, isCurrentGroup = true))
        assertEquals("proxy", RouteGroupTarget.runtimeTag("   ", isCurrentGroup = true))
    }

    @Test fun runtimeTagOfOtherGroupIsTrimmedName() {
        assertEquals("streaming", RouteGroupTarget.runtimeTag("  streaming  ", isCurrentGroup = false))
    }

    @Test fun runtimeTagOfOtherGroupWithoutNameIsUnresolvable() {
        assertNull(RouteGroupTarget.runtimeTag(null, isCurrentGroup = false))
        assertNull(RouteGroupTarget.runtimeTag("   ", isCurrentGroup = false))
    }

    @Test fun resolveTagUsesStableGroupId() {
        val groups = listOf(
            ProxyGroup(id = 1L, name = "main"),
            ProxyGroup(id = 7L, name = "  streaming  "),
        )
        assertEquals("streaming", RouteGroupTarget.resolveTag(
            RuleEntity(id = 1, policyGroupId = 7L), groups, currentGroupId = 1L))
        assertEquals("proxy", RouteGroupTarget.resolveTag(
            RuleEntity(id = 2, policyGroupId = 1L), groups, currentGroupId = 1L))
        assertNull(RouteGroupTarget.resolveTag(
            RuleEntity(id = 3, policyGroupId = 99L), groups, currentGroupId = 1L))
        assertNull(RouteGroupTarget.resolveTag(
            RuleEntity(id = 4, policyGroupId = null), groups, currentGroupId = 1L))
    }

    // 节点与组目标互斥：组目标存在时节点出站必须保持未选（0）且动作为路由动作。

    @Test fun mutualExclusionRejectsNodeAndGroupTogether() {
        assertFalse(RouteGroupTarget.mutualExclusionViolation(
            RuleEntity(id = 1, outbound = 0L, policyGroupId = 7L)))
        assertTrue(RouteGroupTarget.mutualExclusionViolation(
            RuleEntity(id = 2, outbound = 42L, policyGroupId = 7L)))
        assertTrue(RouteGroupTarget.mutualExclusionViolation(
            RuleEntity(id = 3, outbound = -1L, policyGroupId = 7L)))
        assertTrue(RouteGroupTarget.mutualExclusionViolation(
            RuleEntity(id = 4, outbound = -2L, policyGroupId = 7L)))
        assertTrue(RouteGroupTarget.mutualExclusionViolation(
            RuleEntity(id = 5, outbound = 0L, policyGroupId = 7L,
                config = """{"action":"reject"}""")))
        assertFalse(RouteGroupTarget.mutualExclusionViolation(
            RuleEntity(id = 6, outbound = 42L, policyGroupId = null)))
    }

    // 插入位置无法确认时走 JSON 编辑器：多条件/logical/invert 与自定义配置覆盖。

    @Test fun formPlacementAllowsPlainSingleConditionRule() {
        val rule = RuleEntity(id = 1, domains = "example.com", policyGroupId = 7L)
        assertTrue(RouteGroupTarget.canUseForm(rule, hasCustomConfig = false))
    }

    @Test fun formPlacementRefusesMultiConditionAndLogicalRules() {
        val nested = RuleEntity(id = 2, policyGroupId = 7L,
            config = """{"rules":[{"domain":["example.com"]}]}""")
        assertFalse(RouteGroupTarget.canUseForm(nested, hasCustomConfig = false))
        val logical = RuleEntity(id = 3, policyGroupId = 7L,
            config = """{"type":"logical","mode":"or","rules":[]}""")
        assertFalse(RouteGroupTarget.canUseForm(logical, hasCustomConfig = false))
    }

    @Test fun formPlacementRefusesInvertedRules() {
        val rule = RuleEntity(id = 4, domains = "example.com", policyGroupId = 7L,
            config = """{"invert":true}""")
        assertFalse(RouteGroupTarget.canUseForm(rule, hasCustomConfig = false))
    }

    @Test fun formPlacementRefusesWhenCustomConfigMayReorderRoutes() {
        val rule = RuleEntity(id = 5, domains = "example.com", policyGroupId = 7L)
        assertFalse(RouteGroupTarget.canUseForm(rule, hasCustomConfig = true))
    }

    @Test fun formPlacementRefusesBrokenJson() {
        val rule = RuleEntity(id = 6, policyGroupId = 7L, config = "{not json")
        assertFalse(RouteGroupTarget.canUseForm(rule, hasCustomConfig = false))
    }

    @Test fun formPlacementKeepsOtherNativeFields() {
        // 其他原生字段（如 route-options）保留原样，不强制走 JSON 编辑器。
        val rule = RuleEntity(id = 7, domains = "example.com", policyGroupId = 7L,
            config = """{"action":"route-options","override_port":8080}""")
        assertTrue(RouteGroupTarget.canUseForm(rule, hasCustomConfig = false))
    }

    // 插入位置：显式组目标规则排在内置站点分流（fallback）之前。

    @Test fun groupRuleOrdersBeforeBuiltInSiteSplit() {
        val groupRule = RuleEntity(id = 1, name = "Streaming", domains = "example.com",
            outbound = 0L, policyGroupId = 7L, enabled = true)
        val preset = DomesticRoutingPreset.rules("domains", "IPs")
        val ordered = RouteRulePriority.order(listOf(preset[0], groupRule, preset[1]))
        assertEquals(groupRule, ordered.first())
        assertTrue(ordered.indexOf(groupRule) < ordered.indexOf(preset[0]))
        assertTrue(ordered.indexOf(groupRule) < ordered.indexOf(preset[1]))
    }

    // 缺失引用预检：规则引用的策略组不存在时报告 POLICY_GROUP 缺失。

    @Test fun validatorReportsMissingPolicyGroup() {
        val rule = RuleEntity(id = 12, name = "Streaming", domains = "example.com",
            outbound = 0L, policyGroupId = 7L)
        assertEquals(
            listOf(Issue(12L, "Streaming", ReferenceKind.POLICY_GROUP, 7L)),
            OutboundReferenceValidator.validate(listOf(rule), existingOutboundIds = emptySet(),
                existingGroupIds = emptySet()),
        )
    }

    @Test fun validatorAcceptsExistingPolicyGroup() {
        val rule = RuleEntity(id = 13, name = "Streaming", domains = "example.com",
            outbound = 0L, policyGroupId = 7L)
        assertEquals(emptyList<Issue>(), OutboundReferenceValidator.validate(
            listOf(rule), existingOutboundIds = emptySet(), existingGroupIds = setOf(7L)))
    }

    @Test fun validatorKeepsNodeAndGroupIssuesSeparate() {
        val rule = RuleEntity(id = 14, name = "Both", outbound = 42L, policyGroupId = 7L)
        // 节点存在、组缺失：只报组缺失，不把节点误报。
        assertEquals(
            listOf(Issue(14L, "Both", ReferenceKind.POLICY_GROUP, 7L)),
            OutboundReferenceValidator.validate(listOf(rule),
                existingOutboundIds = setOf(42L), existingGroupIds = emptySet()),
        )
    }

    @Test fun validatorSkipsGroupTargetForNonRoutingActions() {
        val rule = RuleEntity(id = 15, name = "Reject", outbound = 0L, policyGroupId = 7L,
            config = """{"action":"reject"}""")
        assertEquals(emptyList<Issue>(), OutboundReferenceValidator.validate(
            listOf(rule), existingOutboundIds = emptySet(), existingGroupIds = emptySet()))
    }

    @Test fun validatorIssueOmitsSensitiveRuleContent() {
        val rule = RuleEntity(id = 16, name = "Work", outbound = 0L, policyGroupId = 7L,
            config = """{"action":"route","password":"fixture-password","server":"private.fixture.invalid"}""",
            domains = "private.fixture.invalid")
        val issues = OutboundReferenceValidator.validate(
            listOf(rule), existingOutboundIds = emptySet(), existingGroupIds = emptySet())
        assertEquals(1, issues.size)
        for (sensitive in listOf("fixture-password", "private.fixture.invalid", "\"action\"")) {
            assertFalse("Diagnostic must omit $sensitive", issues.toString().contains(sensitive))
        }
    }

    // 删除被引用组阻断：返回首个被引用的组与引用它的规则。

    @Test fun deleteGuardBlocksReferencedGroup() {
        val rule = RuleEntity(id = 5, name = "Streaming rule", policyGroupId = 7L, enabled = true)
        val blocking = RouteGroupTarget.firstBlockingReference(listOf(rule), removedGroupIds = setOf(7L))
        assertEquals(7L, blocking?.groupId)
        assertEquals(rule, blocking?.rule)
    }

    @Test fun deleteGuardAllowsUnreferencedGroup() {
        val rule = RuleEntity(id = 5, name = "Streaming rule", policyGroupId = 7L, enabled = true)
        assertNull(RouteGroupTarget.firstBlockingReference(listOf(rule), removedGroupIds = setOf(8L)))
        assertNull(RouteGroupTarget.firstBlockingReference(listOf(rule), removedGroupIds = emptySet()))
    }

    @Test fun deleteGuardBlocksEvenForDisabledRules() {
        // 已停用规则仍持有引用：删组后重新启用会立刻悬空，故同样阻断。
        val rule = RuleEntity(id = 6, name = "Off rule", policyGroupId = 7L, enabled = false)
        val blocking = RouteGroupTarget.firstBlockingReference(listOf(rule), removedGroupIds = setOf(7L))
        assertEquals(7L, blocking?.groupId)
    }

    @Test fun referencedGroupIdsCollectsTargets() {
        val rules = listOf(
            RuleEntity(id = 1, policyGroupId = 7L),
            RuleEntity(id = 2, policyGroupId = null),
            RuleEntity(id = 3, policyGroupId = 7L),
            RuleEntity(id = 4, policyGroupId = 9L),
        )
        assertEquals(setOf(7L, 9L), RouteGroupTarget.referencedGroupIds(rules))
    }

    // 备份兼容：新字段随 JSON 备份往返；旧备份缺该字段时读为 null，不丢规则。

    @Test fun backupJsonRoundTripsPolicyGroupId() {
        val obj = org.json.JSONObject().apply {
            put("id", 1)
            put("name", "Streaming")
            put("config", "")
            put("userOrder", 1)
            put("enabled", true)
            put("domains", "example.com")
            put("ip", "")
            put("port", "")
            put("sourcePort", "")
            put("network", "")
            put("source", "")
            put("protocol", "")
            put("ruleset", "")
            put("outbound", 0)
            put("policyGroupId", 7)
            put("packages", org.json.JSONArray())
        }
        assertEquals(7L, io.nekohasekai.sagernet.database.PortableBackup.parseRuleObject(obj).policyGroupId)
    }

    @Test fun backupJsonWithoutPolicyGroupIdReadsAsNull() {
        val obj = org.json.JSONObject().apply {
            put("id", 2)
            put("name", "Plain")
            put("config", "")
            put("userOrder", 2)
            put("enabled", true)
            put("domains", "example.com")
            put("ip", "")
            put("port", "")
            put("sourcePort", "")
            put("network", "")
            put("source", "")
            put("protocol", "")
            put("ruleset", "")
            put("outbound", 0)
            put("packages", org.json.JSONArray())
        }
        assertNull(io.nekohasekai.sagernet.database.PortableBackup.parseRuleObject(obj).policyGroupId)
    }
}
