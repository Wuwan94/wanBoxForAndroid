// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.fmt

import java.util.Base64

/**
 * B7c/O7 「按优先级顺序」多层级调度：纯模型（无 Android 依赖，可进 JVM 单测）。
 *
 * 层级定义：每条 [BalancerTierRule] 是一条名称正则（包含/排除），按顺序匹配
 * 已解析成员（[BalancerMemberRef] 的展示名）；首条匹配的层级获胜，未匹配任何
 * 规则的成员自动归入保底层级（序号 = 规则数）。层级支持上移/下移/删除——
 * 只是规则列表的顺序操作（见 [moveTierRule] / [removeTierRule]）。
 *
 * 选路语义（与 Go 侧 `libcore/protocol/urltest` 的嵌套 urltest 实现同一策略，
 * 运行时真正选路在核心侧，见 design.md B7c 实施记录）：
 * - 只在当前最低健康层级内选路：高层只要还有一个健康成员，低层成员不得被选中
 *   （流量分配也只在该层内分散）；
 * - 层内按实测延迟 + 切换容差择优（与 Go `selectLocked` 同一口径：挑战者须比
 *   当前值快出容差以上才切换）；
 * - 高层全灭时立即回退下一层；恢复后受控回切：高层须连续通过
 *   [TIER_SWITCH_BACK_MIN_CHECKS] 次检查、且距上次失败满
 *   [TIER_SWITCH_BACK_MIN_FAILURE_FREE_MS] 毫秒才允许切回（[TierRecoveryGate]），
 *   未达门槛时留在当前层。
 * - 层级回切 MUST NOT 默认断开现有连接：本模型只决定新连接的选路，
 *   配置生成层始终发 `interrupt_exist_connections = false`。
 *
 * **选路运行时唯一实现在 Go 侧**：本文件的 [TierRecoveryGate]、
 * [selectTieredMember]、[activeTierMemberIds]（连同 [BalancerMemberHealth]、
 * [TieredSelection]）已标注 @Deprecated 且无生产引用，只有单元测试在喂——
 * 它们保留下来只是历史模型与规则编解码的参照，不代表运行中的行为。
 * 真实选路/回切门只存在于 `libcore/protocol/urltest` 的 tier gate。
 *
 * 持久化：层级规则以编码串存在 `BalancerBean.tierRules`（v7 字段），
 * 编解码见 [encodeBalancerTierRules] / [decodeBalancerTierRules]；
 * 每条正则用 URL 安全 Base64（无填充）编码，规则内含/排他以 `:` 分隔，
 * 规则之间以 `;` 分隔——正则本身可含任意字符，编解码不歧义。
 * 解码容错：脏条目直接跳过（不抛错、不丢整组规则）；规则数上限
 * [BALANCER_MAX_TIERS]，超出的在解码时截断。
 */

/** 层级规则数上限（防手误建出超长规则表；上游 8.18 删的是节点上限，不是层级上限）。 */
const val BALANCER_MAX_TIERS = 10

/** 回切门槛：高层连续健康检查次数（“检查次数”门槛）。 */
const val TIER_SWITCH_BACK_MIN_CHECKS = 3

/** 回切门槛：高层距上次失败的最短无失败持续时间（毫秒，“无失败持续时间”门槛）。 */
const val TIER_SWITCH_BACK_MIN_FAILURE_FREE_MS = 60_000L

/** 一条层级规则：包含正则（空 = 匹配全部）与排除正则（空 = 不排除）。 */
data class BalancerTierRule(val includeRegex: String, val excludeRegex: String)

private fun encodeTierRegex(raw: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))

private fun decodeTierRegex(encoded: String): String? = runCatching {
    String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
}.getOrNull()

/** 层级规则表编为 `BalancerBean.tierRules` 的持久化串；空表编为空串。 */
fun encodeBalancerTierRules(rules: List<BalancerTierRule>): String =
    rules.take(BALANCER_MAX_TIERS).joinToString(";") { rule ->
        "${encodeTierRegex(rule.includeRegex)}:${encodeTierRegex(rule.excludeRegex)}"
    }

/** 解码持久化串；脏条目跳过、超限截断，永不抛错。空/空白串解为空表（只有保底层级）。 */
fun decodeBalancerTierRules(raw: String?): List<BalancerTierRule> {
    if (raw.isNullOrBlank()) return emptyList()
    val rules = ArrayList<BalancerTierRule>()
    for (entry in raw.split(";")) {
        if (rules.size >= BALANCER_MAX_TIERS) break
        val parts = entry.split(":")
        if (parts.size != 2) continue
        val include = decodeTierRegex(parts[0]) ?: continue
        val exclude = decodeTierRegex(parts[1]) ?: continue
        // 双空规则（":"）无匹配意义，视为脏条目跳过；仅 include 为空仍合法
        // （匹配全部、再用 exclude 排除）。
        if (include.isEmpty() && exclude.isEmpty()) continue
        rules.add(BalancerTierRule(include, exclude))
    }
    return rules
}

/** 单条规则的正则语法错误信息（无错为 null；与 B7a 的 `balancerRegexError` 同口径）。 */
fun balancerTierRuleError(rule: BalancerTierRule): String? =
    balancerRegexError(rule.includeRegex) ?: balancerRegexError(rule.excludeRegex)

/** 规则表里任一正则语法错误即返回首条错误信息（保存拦截用），无错为 null。 */
fun balancerTierRulesError(rules: List<BalancerTierRule>): String? {
    for (rule in rules) {
        balancerTierRuleError(rule)?.let { return it }
    }
    return null
}

/** 层级总数 = 显式规则数 + 1 个保底层级。 */
fun balancerTierCount(rules: List<BalancerTierRule>): Int = rules.size + 1

/** 保底层级的序号（= 规则数）。 */
fun balancerFallbackTierIndex(rules: List<BalancerTierRule>): Int = rules.size

private fun compileTierRegex(pattern: String): Regex? {
    if (pattern.isBlank()) return null
    return runCatching { Regex(pattern) }.getOrNull()
}

/**
 * 成员归属哪条规则：空包含视为匹配全部；排除命中则该规则不匹配，继续看下一条。
 * 规则失效（语法错误）按未设置处理——与 B7a 的名称过滤同口径，脏规则不让
 * 核心起不来（设置页保存时拦截，见 BalancerSettingsActivity）。
 */
fun matchBalancerTierRule(name: String, rule: BalancerTierRule): Boolean {
    val include = compileTierRegex(rule.includeRegex)
    if (include != null && !include.containsMatchIn(name)) return false
    val exclude = compileTierRegex(rule.excludeRegex)
    if (exclude != null && exclude.containsMatchIn(name)) return false
    return true
}

/**
 * 给每个成员定层级（与 [members] 等长，元素为层级序号 0..rules.size）：
 * 首条匹配的规则获胜；未匹配任何规则的进保底层级。
 */
fun assignBalancerTiers(
    members: List<BalancerMemberRef>,
    rules: List<BalancerTierRule>,
): List<Int> {
    if (rules.isEmpty()) return List(members.size) { rules.size }
    return members.map { member ->
        val matched = rules.indexOfFirst { rule -> matchBalancerTierRule(member.name, rule) }
        if (matched >= 0) matched else rules.size
    }
}

/** 层级上移：把序号 [index] 的规则与上一条交换；越界/首条返回原表。 */
fun moveTierRuleUp(rules: List<BalancerTierRule>, index: Int): List<BalancerTierRule> {
    if (index <= 0 || index >= rules.size) return rules
    return moveTierRule(rules, index, index - 1)
}

/** 层级下移：把序号 [index] 的规则与下一条交换；越界/末条返回原表。 */
fun moveTierRuleDown(rules: List<BalancerTierRule>, index: Int): List<BalancerTierRule> {
    if (index < 0 || index >= rules.size - 1) return rules
    return moveTierRule(rules, index, index + 1)
}

/** 层级拖拽调序：把序号 [from] 的规则移到 [to]；越界返回原表。 */
fun moveTierRule(rules: List<BalancerTierRule>, from: Int, to: Int): List<BalancerTierRule> {
    if (from !in rules.indices || to !in rules.indices || from == to) return rules
    val mutable = rules.toMutableList()
    val rule = mutable.removeAt(from)
    mutable.add(to, rule)
    return mutable
}

/** 删除序号 [index] 的规则；越界返回原表。删除后原保底成员的层级序号自动前移一位。 */
fun removeTierRule(rules: List<BalancerTierRule>, index: Int): List<BalancerTierRule> {
    if (index !in rules.indices) return rules
    return rules.filterIndexed { i, _ -> i != index }
}

/** 层级展示名：层级 1、层级 2…、保底（序号 = 规则数）。 */
fun balancerTierDisplayName(tierIndex: Int, rules: List<BalancerTierRule>): String =
    if (tierIndex >= rules.size) "保底层级" else "层级 ${tierIndex + 1}"

/**
 * 层级恢复门（历史模型）：记录每层最近一次检查轮次的结果。
 *
 * @Deprecated 运行时唯一实现是 Go 侧 `libcore/protocol/urltest` 的 tier gate；
 * 本类无生产引用（只有单测），不要在新代码里使用。
 */
@Deprecated("Go 侧 tier gate (libcore/protocol/urltest) 是唯一运行时实现；本类仅历史模型/单测在用")
class TierRecoveryGate {
    private val consecutiveHealthy = HashMap<Int, Int>()
    private val lastFailureMs = HashMap<Int, Long>()

    /** 一轮检查结束：该层健康则连续计数 +1，否则清零并记下失败时间。 */
    fun recordRound(tierIndex: Int, healthy: Boolean, nowMs: Long) {
        if (healthy) {
            consecutiveHealthy[tierIndex] = (consecutiveHealthy[tierIndex] ?: 0) + 1
        } else {
            consecutiveHealthy[tierIndex] = 0
            lastFailureMs[tierIndex] = nowMs
        }
    }

    /** 记录一次明确的失败（与 recordRound(tier, false, now) 等价）。 */
    fun recordFailure(tierIndex: Int, nowMs: Long) = recordRound(tierIndex, false, nowMs)

    /** 该层连续健康检查次数（测试与展示用）。 */
    fun healthyChecks(tierIndex: Int): Int = consecutiveHealthy[tierIndex] ?: 0

    /**
     * 是否允许切回该层：连续健康检查次数达标，且距上次失败已满无失败持续时间。
     * 从未失败过的层只看检查次数（无失败时间可查即视为已满）。
     */
    fun canSwitchBack(
        tierIndex: Int,
        nowMs: Long,
        minChecks: Int = TIER_SWITCH_BACK_MIN_CHECKS,
        minFailureFreeMs: Long = TIER_SWITCH_BACK_MIN_FAILURE_FREE_MS,
    ): Boolean {
        if ((consecutiveHealthy[tierIndex] ?: 0) < minChecks) return false
        val lastFailure = lastFailureMs[tierIndex] ?: return true
        return nowMs - lastFailure >= minFailureFreeMs
    }
}

/** 成员健康快照（历史模型）。@Deprecated 见 [TierRecoveryGate]。 */
@Deprecated("Go 侧 tier gate 是唯一运行时实现；本快照仅历史模型/单测在用")
data class BalancerMemberHealth(val delayMs: Int?)

/** 层级选路结果（历史模型）。@Deprecated 见 [TierRecoveryGate]。 */
@Deprecated("Go 侧 tier gate 是唯一运行时实现；本结果仅历史模型/单测在用")
data class TieredSelection(val memberId: Long?, val tierIndex: Int, val healthy: Boolean)

/**
 * 在已按优先级排好序的成员上选路（历史模型；运行时唯一实现是 Go 侧
 * `libcore/protocol/urltest` 的 `selectLocked`）：
 * 1. 取最低健康层级（该层至少一个成员有新鲜延迟）；
 * 2. 回切受门控：候选层高于当前层（序号更小）且门未通过、当前层仍健康时，
 *    留在当前层，不来回跳；
 * 3. 层内按延迟 + 容差择优：挑战者须比当前值快出容差以上才切换；
 *    当前选中成员（[currentMemberId]）若在本层且健康，优先作为比较起点
 *    （与 Go 的 stored-selection 粘性一致）；
 * 4. 全层无健康成员时回落列表首位（healthy=false），调用方按既有行为处理。
 *
 * @param orderedMembers 已按优先级顺序排列的成员（层级内顺序即同延迟时的优先顺序）
 * @param tiers 与 [orderedMembers] 等长的层级序号（[assignBalancerTiers] 的结果）
 * @param health 成员 id → 健康快照（缺失视为无健康样本）
 * @param toleranceMs 切换容差（毫秒，取 `BalancerBean.calculateToleranceMs()`）
 * @param currentMemberId 当前选中成员（可为 null，即首次选择）
 * @param gate 恢复门（可为 null，即不做回切门控；首次选择时传 null）
 */
@Deprecated("Go 侧 tier gate 是唯一运行时实现；本函数仅历史模型/单测在用")
fun selectTieredMember(
    orderedMembers: List<BalancerMemberRef>,
    tiers: List<Int>,
    health: Map<Long, BalancerMemberHealth>,
    toleranceMs: Int,
    currentMemberId: Long?,
    gate: TierRecoveryGate?,
    nowMs: Long,
): TieredSelection {
    if (orderedMembers.isEmpty()) return TieredSelection(null, 0, false)
    val memberTiers = if (tiers.size == orderedMembers.size) tiers else List(orderedMembers.size) { 0 }

    fun delayOf(member: BalancerMemberRef): Int? = health[member.id]?.delayMs
    fun tierHealthy(tierIndex: Int): Boolean =
        orderedMembers.indices.any { i -> memberTiers[i] == tierIndex && delayOf(orderedMembers[i]) != null }

    val tierOrder = memberTiers.distinct().sorted()
    val candidateTier = tierOrder.firstOrNull { tierHealthy(it) }
        ?: return TieredSelection(orderedMembers.first().id, memberTiers.first(), false)

    val currentTier = currentMemberId?.let { id ->
        orderedMembers.indices.firstOrNull { orderedMembers[it].id == id }?.let { memberTiers[it] }
    }
    var effectiveTier = candidateTier
    if (gate != null && currentTier != null && candidateTier < currentTier &&
        !gate.canSwitchBack(candidateTier, nowMs) && tierHealthy(currentTier)
    ) {
        effectiveTier = currentTier
    }

    val tierMembers = orderedMembers.indices
        .filter { memberTiers[it] == effectiveTier }
        .map { orderedMembers[it] }
    // 当前选中成员在本层且健康时作为比较起点（粘性），否则取本层首个健康成员。
    var minMember: BalancerMemberRef? = null
    var minDelay = 0
    val current = currentMemberId?.let { id -> tierMembers.firstOrNull { it.id == id } }
    val currentDelay = current?.let { delayOf(it) }
    if (current != null && currentDelay != null) {
        minMember = current
        minDelay = currentDelay
    }
    for (member in tierMembers) {
        val delay = delayOf(member) ?: continue
        if (minMember == null || minDelay > delay + toleranceMs) {
            minMember = member
            minDelay = delay
        }
    }
    val selected = minMember ?: tierMembers.first()
    return TieredSelection(selected.id, effectiveTier, true)
}

/**
 * 当前最低健康层级的成员 id（历史模型；运行时唯一实现是 Go 侧 tier gate）。
 * 全层无健康成员时返回全部成员（退化兜底，与 selectTieredMember 的
 * healthy=false 回落一致）。
 */
@Deprecated("Go 侧 tier gate 是唯一运行时实现；本函数仅历史模型/单测在用")
fun activeTierMemberIds(
    orderedMembers: List<BalancerMemberRef>,
    tiers: List<Int>,
    health: Map<Long, BalancerMemberHealth>,
): List<Long> {
    if (orderedMembers.isEmpty()) return emptyList()
    val memberTiers = if (tiers.size == orderedMembers.size) tiers else List(orderedMembers.size) { 0 }
    val tierOrder = memberTiers.distinct().sorted()
    val activeTier = tierOrder.firstOrNull { tier ->
        orderedMembers.indices.any { i ->
            memberTiers[i] == tier && health[orderedMembers[i].id]?.delayMs != null
        }
    } ?: return orderedMembers.map { it.id }
    return orderedMembers.indices
        .filter { memberTiers[it] == activeTier }
        .map { orderedMembers[it].id }
}
