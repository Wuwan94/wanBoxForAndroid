// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.internal.BalancerBean

/**
 * B7a/O7 混搭来源成员解析层（OwnBox 8.12 BalancerMemberResolver 的 wanBox
 * 本地等价实现；wanBox 原先无该解析层，成员展开逻辑散在 ConfigBuilder
 * 的 buildChain 里）。
 *
 * 来源语义（按 balancerType）：
 * - TYPE_LIST：只用散装节点（proxies），按列表顺序去重。
 * - TYPE_GROUP：只用目标分组，名称正则 include/exclude 只作用于分组来源。
 * - TYPE_MIXED：分组（正则过滤后）在前、散装节点在后，去重；正则不过滤散装节点
 *   （散装节点是用户显式点选的，显式选择优先于过滤）。
 *
 * 正则失效时不抛错、不静默丢成员：错误记进 [BalancerMemberResolution] 供设置页
 * 展示并阻止保存；配置生成沿用既有行为（失效的过滤条件按未设置处理），
 * 保证导入/备份里的脏正则不会让核心起不来。
 *
 * 成员上限 [BalancerBean.maxMembers]：0 = 不限；>0 按“分组在前、散装在后”
 * 的解析顺序截取前 N 个唯一成员；负值由 initializeDefaultValues 归一化为 0。
 */
data class BalancerMemberRef(val id: Long, val name: String)

data class BalancerMemberResolution(
    val memberIds: List<Long>,
    /** 分组来源正则过滤后的引用数（去重前；跨分组重复计入 duplicatesRemoved）。 */
    val groupMatched: Int,
    /** 散装来源实际追加的数量（已在分组内的不计）。 */
    val looseMatched: Int,
    /** 跨来源/分组内重复而被去重的 id 个数。 */
    val duplicatesRemoved: Int,
    /** nameInclude 正则语法错误信息（无错为 null）。 */
    val includeRegexError: String?,
    /** nameExclude 正则语法错误信息（无错为 null）。 */
    val excludeRegexError: String?,
) {
    val totalMatched: Int get() = memberIds.size
    val hasRegexError: Boolean get() = includeRegexError != null || excludeRegexError != null
}

/** 编译一条名称正则：空/空白视为未设置（null）；语法错误返回错误信息（不抛错）。 */
fun balancerRegexError(pattern: String?): String? {
    if (pattern.isNullOrBlank()) return null
    return runCatching { Regex(pattern) }.exceptionOrNull()?.message
}

private fun compileBalancerRegex(pattern: String?): Regex? {
    if (pattern.isNullOrBlank()) return null
    return runCatching { Regex(pattern) }.getOrNull()
}

fun resolveBalancerMembers(
    bean: BalancerBean,
    groupMembers: (Long) -> List<BalancerMemberRef>,
): BalancerMemberResolution {
    val includeRegex = compileBalancerRegex(bean.nameInclude)
    val excludeRegex = compileBalancerRegex(bean.nameExclude)
    val includeRegexError = if (!bean.nameInclude.isNullOrBlank() && includeRegex == null) {
        balancerRegexError(bean.nameInclude)
    } else null
    val excludeRegexError = if (!bean.nameExclude.isNullOrBlank() && excludeRegex == null) {
        balancerRegexError(bean.nameExclude)
    } else null

    val targetGids = when {
        bean.targetGroupIds != null && bean.targetGroupIds.isNotEmpty() -> bean.targetGroupIds
        bean.targetGroupId > 0L -> listOf(bean.targetGroupId)
        else -> emptyList()
    }

    var duplicatesRemoved = 0
    val seen = LinkedHashSet<Long>()
    val ordered = ArrayList<Long>()

    fun append(id: Long) {
        if (seen.add(id)) {
            ordered.add(id)
        } else {
            duplicatesRemoved++
        }
    }

    var groupMatched = 0
    if (bean.balancerType == BalancerBean.TYPE_GROUP || bean.balancerType == BalancerBean.TYPE_MIXED) {
        var groupRefs = targetGids.flatMap { gid -> groupMembers(gid) }
        if (includeRegex != null) {
            groupRefs = groupRefs.filter { includeRegex.containsMatchIn(it.name) }
        }
        if (excludeRegex != null) {
            groupRefs = groupRefs.filterNot { excludeRegex.containsMatchIn(it.name) }
        }
        groupMatched = groupRefs.size
        groupRefs.forEach { append(it.id) }
    }

    var looseMatched = 0
    if (bean.balancerType == BalancerBean.TYPE_LIST || bean.balancerType == BalancerBean.TYPE_MIXED) {
        val looseIds = bean.proxies ?: emptyList()
        val before = ordered.size
        looseIds.forEach { append(it) }
        looseMatched = ordered.size - before
    }

    // 未知类型（老数据里的非法值）沿用既有 ConfigBuilder 行为：按散装列表处理，
    // 保证非法 type 不会静默变成空组。
    if (bean.balancerType != BalancerBean.TYPE_GROUP &&
        bean.balancerType != BalancerBean.TYPE_MIXED &&
        bean.balancerType != BalancerBean.TYPE_LIST
    ) {
        (bean.proxies ?: emptyList()).forEach { append(it) }
        looseMatched = ordered.size
    }

    val capped = if (bean.maxMembers > 0 && ordered.size > bean.maxMembers) {
        ordered.take(bean.maxMembers)
    } else {
        ordered
    }

    return BalancerMemberResolution(
        memberIds = capped,
        groupMatched = groupMatched,
        looseMatched = looseMatched,
        duplicatesRemoved = duplicatesRemoved,
        includeRegexError = includeRegexError,
        excludeRegexError = excludeRegexError,
    )
}
