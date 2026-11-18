// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.fmt.BalancerMemberRef
import io.nekohasekai.sagernet.fmt.BalancerTierRule
import io.nekohasekai.sagernet.fmt.TierRecoveryGate
import io.nekohasekai.sagernet.fmt.BalancerMemberHealth
import io.nekohasekai.sagernet.fmt.activeTierMemberIds
import io.nekohasekai.sagernet.fmt.applyPriorityOrder
import io.nekohasekai.sagernet.fmt.assignBalancerTiers
import io.nekohasekai.sagernet.fmt.balancerFallbackTierIndex
import io.nekohasekai.sagernet.fmt.balancerTierCount
import io.nekohasekai.sagernet.fmt.balancerTierDisplayName
import io.nekohasekai.sagernet.fmt.balancerTierRuleError
import io.nekohasekai.sagernet.fmt.balancerTierRulesError
import io.nekohasekai.sagernet.fmt.decodeBalancerTierRules
import io.nekohasekai.sagernet.fmt.decodePriorityOrderIds
import io.nekohasekai.sagernet.fmt.encodeBalancerTierRules
import io.nekohasekai.sagernet.fmt.encodePriorityOrderIds
import io.nekohasekai.sagernet.fmt.formatPriorityNumber
import io.nekohasekai.sagernet.fmt.movePriorityOrder
import io.nekohasekai.sagernet.fmt.moveTierRule
import io.nekohasekai.sagernet.fmt.moveTierRuleDown
import io.nekohasekai.sagernet.fmt.moveTierRuleUp
import io.nekohasekai.sagernet.fmt.reconcilePriorityOrder
import io.nekohasekai.sagernet.fmt.removePriorityOrder
import io.nekohasekai.sagernet.fmt.removeTierRule
import io.nekohasekai.sagernet.fmt.replacePriorityOrder
import io.nekohasekai.sagernet.fmt.selectTieredMember
import io.nekohasekai.sagernet.fmt.sortPriorityOrderByLatency
import io.nekohasekai.sagernet.fmt.sortPriorityOrderByLatencyWithinTiers
import org.junit.Assert.*
import org.junit.Test

/** B7c/O7 「按优先级顺序」多层级调度 + 节点优先级页纯模型测试。@author 雾晚 */
@Suppress("DEPRECATION") // 历史模型（Go 侧 tier gate 才是运行时实现），见 BalancerTierScheduler.kt
class BalancerTierSchedulerTest {

    private fun members(vararg pairs: Pair<Long, String>) =
        pairs.map { BalancerMemberRef(it.first, it.second) }

    private val rules = listOf(
        BalancerTierRule("^HK", ""),
        BalancerTierRule("^JP", "2$"),
    )

    // ---- 7.1 分组/正则定层级 ----

    @Test fun tiersAssignedByRegexFirstMatchWinsFallbackLast() {
        val assigned = assignBalancerTiers(
            members(1L to "HK-1", 2L to "HK-2", 3L to "JP-1", 4L to "JP-2", 5L to "SG-1"),
            rules,
        )
        // 层级 1：^HK；层级 2：^JP 且不以 2 结尾；其余进保底（序号 2）。
        assertEquals(listOf(0, 0, 1, 2, 2), assigned)
        assertEquals(3, balancerTierCount(rules))
        assertEquals(2, balancerFallbackTierIndex(rules))
        assertEquals("层级 1", balancerTierDisplayName(0, rules))
        assertEquals("层级 2", balancerTierDisplayName(1, rules))
        assertEquals("保底层级", balancerTierDisplayName(2, rules))
    }

    @Test fun emptyRulesMeanSingleFallbackTier() {
        val assigned = assignBalancerTiers(members(1L to "HK-1", 2L to "SG-1"), emptyList())
        assertEquals(listOf(0, 0), assigned)
        assertEquals(1, balancerTierCount(emptyList()))
        assertEquals("保底层级", balancerTierDisplayName(0, emptyList()))
    }

    @Test fun blankIncludeMatchesAllAndExcludeNarrows() {
        val assigned = assignBalancerTiers(
            members(1L to "HK-1", 2L to "HK-2"),
            listOf(BalancerTierRule("", "2$")),
        )
        assertEquals(listOf(0, 1), assigned)
    }

    @Test fun invalidRegexFallsBackToUnset() {
        // 脏正则按未设置处理（与 B7a 名称过滤同口径），不丢成员、不抛错。
        val assigned = assignBalancerTiers(
            members(1L to "HK-1"),
            listOf(BalancerTierRule("[invalid", "")),
        )
        assertEquals(listOf(0), assigned)
        assertNotNull(balancerTierRuleError(BalancerTierRule("[invalid", "")))
        assertNull(balancerTierRuleError(BalancerTierRule("^HK", "")))
        assertNotNull(balancerTierRulesError(listOf(BalancerTierRule("", "[bad"))))
        assertNull(balancerTierRulesError(rules))
    }

    // ---- 7.1 层内按延迟 + 容差择优 ----

    @Test fun inTierSelectionUsesLatencyAndTolerance() {
        val ms = members(1L to "HK-1", 2L to "HK-2", 3L to "JP-1")
        val tiers = assignBalancerTiers(ms, rules)
        val health = mapOf(
            1L to BalancerMemberHealth(500),
            2L to BalancerMemberHealth(120),
            3L to BalancerMemberHealth(50),
        )
        // 层级 1 内：120ms 比 500ms 快出容差（300ms）→ 选中 2 号；
        // 层级 2 的 50ms 再快也不得越层。
        val selection = selectTieredMember(ms, tiers, health, 300, null, null, 0L)
        assertEquals(2L, selection.memberId)
        assertEquals(0, selection.tierIndex)
        assertTrue(selection.healthy)

        // 容差内不切换：当前 120ms，挑战者 100ms 只快 20ms（容差 300）→ 留守。
        val stickyHealth = mapOf(
            1L to BalancerMemberHealth(100),
            2L to BalancerMemberHealth(120),
            3L to BalancerMemberHealth(50),
        )
        val sticky = selectTieredMember(ms, tiers, stickyHealth, 300, 2L, null, 0L)
        assertEquals(2L, sticky.memberId)
        // 容差外切换：挑战者 100ms，当前 500ms，快出容差 → 切走。
        val health2 = mapOf(
            1L to BalancerMemberHealth(100),
            2L to BalancerMemberHealth(500),
            3L to BalancerMemberHealth(50),
        )
        val switched = selectTieredMember(ms, tiers, health2, 300, 2L, null, 0L)
        assertEquals(1L, switched.memberId)
    }

    // ---- 7.1 高层全灭回退下一层 ----

    @Test fun fullTierOutageFallsBackToNextTier() {
        val ms = members(1L to "HK-1", 2L to "HK-2", 3L to "JP-1", 4L to "SG-1")
        val tiers = assignBalancerTiers(ms, rules)
        // 层级 1 全灭（无健康样本），层级 2 有健康成员 → 回退层级 2。
        val health = mapOf(
            1L to BalancerMemberHealth(null),
            2L to BalancerMemberHealth(null),
            3L to BalancerMemberHealth(80),
            4L to BalancerMemberHealth(40),
        )
        val selection = selectTieredMember(ms, tiers, health, 300, 1L, null, 0L)
        assertEquals(3L, selection.memberId)
        assertEquals(1, selection.tierIndex)

        // 全层全灭：回落列表首位，healthy=false（调用方按既有行为处理）。
        val allDead = mapOf<Long, BalancerMemberHealth>(
            1L to BalancerMemberHealth(null),
            2L to BalancerMemberHealth(null),
            3L to BalancerMemberHealth(null),
            4L to BalancerMemberHealth(null),
        )
        val fallback = selectTieredMember(ms, tiers, allDead, 300, 1L, null, 0L)
        assertEquals(1L, fallback.memberId)
        assertFalse(fallback.healthy)
    }

    // ---- 7.1 恢复后双重门槛受控回切 ----

    @Test fun switchBackNeedsBothGates() {
        val ms = members(1L to "HK-1", 2L to "JP-1")
        val tiers = assignBalancerTiers(ms, rules)
        val health = mapOf(
            1L to BalancerMemberHealth(100),
            2L to BalancerMemberHealth(90),
        )
        val gate = TierRecoveryGate()
        // 层级 1 在 t=0 失败，当前留在层级 2；恢复后只过 1 轮检查 → 不回切。
        gate.recordFailure(0, 0L)
        gate.recordRound(0, true, 10_000L)
        val held = selectTieredMember(ms, tiers, health, 300, 2L, gate, 10_000L)
        assertEquals(2L, held.memberId)
        assertEquals(1, held.tierIndex)

        // 再过 2 轮检查（共 3 轮），但距失败仅 50 秒（< 60 秒）→ 仍不回切。
        gate.recordRound(0, true, 30_000L)
        gate.recordRound(0, true, 50_000L)
        assertEquals(3, gate.healthyChecks(0))
        val held2 = selectTieredMember(ms, tiers, health, 300, 2L, gate, 50_000L)
        assertEquals(2L, held2.memberId)

        // 距失败满 60 秒且 3 轮健康 → 回切层级 1。
        val back = selectTieredMember(ms, tiers, health, 300, 2L, gate, 60_000L)
        assertEquals(1L, back.memberId)
        assertEquals(0, back.tierIndex)

        // 当前层也全灭时门控不得把流量留在死层：直接回退到有健康成员的层。
        val health2 = mapOf(
            1L to BalancerMemberHealth(100),
            2L to BalancerMemberHealth(null),
        )
        val gate2 = TierRecoveryGate().apply { recordFailure(0, 0L) }
        val forced = selectTieredMember(ms, tiers, health2, 300, 2L, gate2, 10_000L)
        assertEquals(1L, forced.memberId)
        assertEquals(0, forced.tierIndex)
    }

    @Test fun gateCountsOnlyConsecutiveHealthyRounds() {
        val gate = TierRecoveryGate()
        gate.recordRound(0, true, 0L)
        gate.recordRound(0, true, 1_000L)
        assertEquals(2, gate.healthyChecks(0))
        // 中间一次失败即清零重计。
        gate.recordRound(0, false, 2_000L)
        assertEquals(0, gate.healthyChecks(0))
        gate.recordRound(0, true, 3_000L)
        assertEquals(1, gate.healthyChecks(0))
        assertFalse(gate.canSwitchBack(0, 70_000L))
    }

    // ---- 7.1 流量分配只在当前最低健康层级内分散 ----

    @Test fun trafficConfinedToLowestHealthyTier() {
        val ms = members(1L to "HK-1", 2L to "HK-2", 3L to "JP-1", 4L to "SG-1")
        val tiers = assignBalancerTiers(ms, rules)
        val health = mapOf(
            1L to BalancerMemberHealth(100),
            2L to BalancerMemberHealth(120),
            3L to BalancerMemberHealth(50),
            4L to BalancerMemberHealth(40),
        )
        // 层级 1 健康：候选集只有层级 1 的两个成员，低层再快也不进候选集。
        assertEquals(listOf(1L, 2L), activeTierMemberIds(ms, tiers, health))

        // 层级 1 全灭：候选集下移到层级 2。
        val health2 = mapOf(
            1L to BalancerMemberHealth(null),
            2L to BalancerMemberHealth(null),
            3L to BalancerMemberHealth(50),
            4L to BalancerMemberHealth(40),
        )
        assertEquals(listOf(3L), activeTierMemberIds(ms, tiers, health2))

        // 全灭：退化兜底返回全部成员（流量仍能走）。
        val allDead = mapOf<Long, BalancerMemberHealth>(
            1L to BalancerMemberHealth(null),
            2L to BalancerMemberHealth(null),
            3L to BalancerMemberHealth(null),
            4L to BalancerMemberHealth(null),
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), activeTierMemberIds(ms, tiers, allDead))
    }

    // ---- 7.1 层级规则持久化编解码 ----

    @Test fun tierRulesEncodeDecodeRoundTrip() {
        val encoded = encodeBalancerTierRules(rules)
        assertEquals(rules, decodeBalancerTierRules(encoded))
        assertEquals("", encodeBalancerTierRules(emptyList()))
        assertEquals(emptyList<BalancerTierRule>(), decodeBalancerTierRules(""))
        assertEquals(emptyList<BalancerTierRule>(), decodeBalancerTierRules(null))
        // 正则含分隔符也不歧义（Base64 编码后不含 : 与 ;）。
        val tricky = listOf(BalancerTierRule("^HK:1;2$", "a;b:c"))
        assertEquals(tricky, decodeBalancerTierRules(encodeBalancerTierRules(tricky)))
    }

    @Test fun tierRulesDecodeToleratesDirtyEntriesAndCapsCount() {
        // 脏条目跳过、好条目保留；超过 10 条截断。
        val dirty = encodeBalancerTierRules(rules) + ";garbage;:" + ";" +
            encodeBalancerTierRules(listOf(BalancerTierRule("^SG", "")))
        val decoded = decodeBalancerTierRules(dirty)
        assertEquals(3, decoded.size)
        assertEquals("^SG", decoded[2].includeRegex)

        val many = (1..12).map { BalancerTierRule("^T$it", "") }
        assertEquals(10, decodeBalancerTierRules(encodeBalancerTierRules(many)).size)
        assertEquals(10, encodeBalancerTierRules(many).split(";").size)
    }

    @Test fun tierRuleMoveUpDownDelete() {
        val three = listOf(
            BalancerTierRule("^HK", ""),
            BalancerTierRule("^JP", ""),
            BalancerTierRule("^SG", ""),
        )
        assertEquals(listOf("^JP", "^HK", "^SG"), moveTierRuleUp(three, 1).map { it.includeRegex })
        assertEquals(three, moveTierRuleUp(three, 0))
        assertEquals(listOf("^HK", "^SG", "^JP"), moveTierRuleDown(three, 1).map { it.includeRegex })
        assertEquals(three, moveTierRuleDown(three, 2))
        assertEquals(listOf("^JP", "^HK", "^SG"), moveTierRule(three, 1, 0).map { it.includeRegex })
        assertEquals(three, moveTierRule(three, 5, 0))
        assertEquals(listOf("^HK", "^SG"), removeTierRule(three, 1).map { it.includeRegex })
        assertEquals(three, removeTierRule(three, 9))
        // 删除后保底成员的层级序号自动前移（规则数 3→2，保底序号 3→2）。
        val ms = members(1L to "US-1")
        assertEquals(listOf(3), assignBalancerTiers(ms, three))
        assertEquals(listOf(2), assignBalancerTiers(ms, removeTierRule(three, 1)))
    }

    // ---- 7.1 节点优先级页：编号/拖拽/移除/替换/按延迟排序 ----

    @Test fun priorityNumberingIsCompact() {
        assertEquals("1", formatPriorityNumber(0))
        assertEquals("2", formatPriorityNumber(1))
        assertEquals("5", formatPriorityNumber(4))
    }

    @Test fun priorityOrderMoveRemoveReplace() {
        val order = listOf(1L, 2L, 3L, 4L, 5L)
        // 拖拽：第 5 位移到第 1 位。
        assertEquals(listOf(5L, 1L, 2L, 3L, 4L), movePriorityOrder(order, 4, 0))
        assertEquals(order, movePriorityOrder(order, 9, 0))
        assertEquals(order, movePriorityOrder(order, 0, 0))
        // 移除。
        assertEquals(listOf(1L, 2L, 4L, 5L), removePriorityOrder(order, 3L))
        assertEquals(order, removePriorityOrder(order, 99L))
        // 替换：原地换位，顺位不变。
        assertEquals(listOf(1L, 9L, 3L, 4L, 5L), replacePriorityOrder(order, 2L, 9L))
        // 替换成已在表里的成员：先去重再替换，不产生重复。
        assertEquals(listOf(1L, 4L, 3L, 5L), replacePriorityOrder(order, 2L, 4L))
        // 替换不存在的成员：追加在末尾。
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 9L), replacePriorityOrder(order, 99L, 9L))
        assertEquals(order, replacePriorityOrder(order, 2L, 2L))
    }

    @Test fun priorityOrderReconcileAndApply() {
        // 存量顺序只留仍在当前成员里的，未在存量里的按解析顺序追加；排除的不留。
        assertEquals(
            listOf(2L, 1L, 3L, 4L),
            reconcilePriorityOrder(listOf(2L, 1L, 99L), listOf(1L, 2L, 3L, 4L)),
        )
        assertEquals(
            listOf(1L, 3L),
            reconcilePriorityOrder(listOf(1L, 2L, 3L), listOf(1L, 2L, 3L), setOf(2L)),
        )
        assertEquals(emptyList<Long>(), reconcilePriorityOrder(emptyList(), emptyList()))
        // 应用：表内在前按表序，表外按原序追加。
        assertEquals(
            listOf(3L, 1L, 2L, 4L),
            applyPriorityOrder(listOf(1L, 2L, 3L, 4L), listOf(3L, 1L)),
        )
        assertEquals(listOf(1L, 2L), applyPriorityOrder(listOf(1L, 2L), emptyList()))
    }

    @Test fun priorityOrderSortByLatency() {
        val order = listOf(1L, 2L, 3L, 4L)
        val delay = mapOf(1L to 300, 2L to 50, 3L to null, 4L to 120)
        // 延迟升序，未知延迟排最后。
        assertEquals(listOf(2L, 4L, 1L, 3L), sortPriorityOrderByLatency(order, delay))
        // 全部未知：保持原顺序（稳定排序）。
        assertEquals(order, sortPriorityOrderByLatency(order, emptyMap()))
    }

    @Test fun priorityOrderSortByLatencyWithinTiers() {
        val order = listOf(1L, 2L, 3L, 4L)
        val tiers = mapOf(1L to 0, 2L to 0, 3L to 1, 4L to 1)
        val delay = mapOf(1L to 300, 2L to 50, 3L to 400, 4L to 10)
        // 有层级时层内排序：层级 1 内 50ms 在前，层级 2 内 10ms 在前；
        // 跨层不比较延迟，高层成员永远在前。
        assertEquals(listOf(2L, 1L, 4L, 3L), sortPriorityOrderByLatencyWithinTiers(order, tiers, delay))
    }

    @Test fun priorityOrderIdsEncodeDecode() {
        assertEquals("3,1,2", encodePriorityOrderIds(listOf(3L, 1L, 2L)))
        assertEquals("", encodePriorityOrderIds(emptyList()))
        assertEquals(listOf(3L, 1L, 2L), decodePriorityOrderIds("3,1,2"))
        assertEquals(emptyList<Long>(), decodePriorityOrderIds(""))
        assertEquals(emptyList<Long>(), decodePriorityOrderIds(null))
        // 脏段跳过、非法 id 丢弃、重复去重（保留首次顺序）。
        assertEquals(listOf(3L, 1L), decodePriorityOrderIds("3, x, 1, -5, 0, 3"))
    }

    // ---- 配置生成层与设置页接线（源码级契约，完整 buildChain 需 DB，待 CI 单测验证） ----

    @Test fun configBuilderGeneratesTieredUrlTestGroups() {
        val source = java.io.File(
            "src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt"
        ).readText()
        // 优先级模式：按层级生成嵌套 urltest（层内一组，外层一组），
        // 层级 tag 带 [T<n>] / [FB] 后缀供 Go 侧解析；单层时不嵌套。
        assertTrue(source.contains("STRATEGY_PRIORITY_ORDER"))
        assertTrue(source.contains("assignBalancerTiers("))
        assertTrue(source.contains("tierGroupTag("))
        // tierGroupTag 的实现是 "$balancerTag [T${...}]" / "$balancerTag [FB]"，
        // 字面量里 [T 与 [FB 前是 $balancerTag 而不是引号，按实际写法断言。
        assertTrue(source.contains(" [T\${"))
        assertTrue(source.contains(" [FB]\""))
        // 排除名单只在优先级模式过滤成员；层级回切不断现有连接。
        assertTrue(source.contains("priorityExcluded"))
        assertTrue(source.contains("interruptExist = false"))
        // 探测间隔仍钳制 ≥10 秒，不为层级检查新增更密的探测。
        assertTrue(source.contains("coerceAtLeast(10L)"))
    }

    @Test fun settingsActivityWiresTierEditingAndPriorityPage() {
        val source = java.io.File(
            "src/main/java/io/nekohasekai/sagernet/ui/profile/BalancerSettingsActivity.kt"
        ).readText()
        // 层级编辑：添加层级、层级行（上移/下移/删除）、正则校验拦截保存。
        assertTrue(source.contains("balancerAddTier"))
        assertTrue(source.contains("updateTierRows"))
        assertTrue(source.contains("balancerTierRulesError("))
        // 节点优先级二级页入口 + 摘要（数量 + 前 3 名预览）。
        assertTrue(source.contains("balancerPriority"))
        assertTrue(source.contains("BalancerPriorityActivity"))
        assertTrue(source.contains("updatePrioritySummary"))
        // 「最快最稳」（leastPing）模式隐藏层级相关入口。
        assertTrue(source.contains("updateStrategyVisibility"))
        assertTrue(source.contains("STRATEGY_PRIORITY_ORDER"))
        // 内嵌节点顺序列表：优先级模式下显示紧凑编号。
        assertTrue(source.contains("formatPriorityNumber("))
    }

    @Test fun priorityActivitySupportsOrderOperations() {
        val source = java.io.File(
            "src/main/java/io/nekohasekai/sagernet/ui/profile/BalancerPriorityActivity.kt"
        ).readText()
        assertTrue(source.contains("movePriorityOrder("))
        assertTrue(source.contains("removePriorityOrder("))
        assertTrue(source.contains("replacePriorityOrder("))
        assertTrue(source.contains("sortPriorityOrderByLatencyWithinTiers("))
        assertTrue(source.contains("reconcilePriorityOrder("))
        // 返回自动生效：结果随 intent 回传，设置页应用后标记 dirty。
        assertTrue(source.contains("EXTRA_PRIORITY_ORDER"))
        assertTrue(source.contains("setResult("))
    }

    @Test fun strategyListHasPriorityOrderEntry() {
        val arrays = java.io.File("src/main/res/values/arrays.xml").readText()
        assertTrue(arrays.contains("priorityOrder"))
        val en = java.io.File("src/main/res/values/strings.xml").readText()
        val zh = java.io.File("src/main/res/values-zh-rCN/strings.xml").readText()
        for (name in listOf(
            "balancer_strategy_priority_order",
            "balancer_add_tier",
            "balancer_tiers",
            "balancer_tier",
            "balancer_tier_fallback",
            "balancer_tier_include",
            "balancer_tier_exclude",
            "balancer_tier_move_up",
            "balancer_tier_move_down",
            "balancer_tier_delete",
            "balancer_node_priority",
            "balancer_priority_summary",
            "balancer_priority_sort_by_latency",
            "balancer_priority_add_node",
            "balancer_priority_empty",
            "balancer_tier_invalid_message",
        )) {
            assertTrue("missing string $name in values/strings.xml", en.contains("name=\"$name\""))
            assertTrue("missing string $name in values-zh-rCN/strings.xml", zh.contains("name=\"$name\""))
        }
    }
}
