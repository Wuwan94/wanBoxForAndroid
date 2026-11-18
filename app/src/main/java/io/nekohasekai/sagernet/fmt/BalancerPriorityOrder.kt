// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.fmt

/**
 * B7c/O7 节点优先级顺序：纯模型（无 Android 依赖，可进 JVM 单测）。
 * 节点优先级二级页（`ui/profile/BalancerPriorityActivity`）与设置页内嵌
 * 节点顺序列表共用同一模型；持久化在 `BalancerBean.priorityOrder`（v7 字段，
 * 显式顺序的成员 id 列表）与 `BalancerBean.priorityExcluded`（v7 字段，
 * 从优先级页移除的分组来源成员 id——分组来源成员无法从源分组删除，
 * 排除名单是让“移除”真正生效的唯一记录）。
 *
 * 语义：
 * - 顺序列表是“显式顺序”：在表里的成员按表内顺序排，未在表里的成员
 *   按解析顺序（[reconcilePriorityOrder] 的 currentMembers 顺序）追加在后；
 * - 移除：散装成员同时从散装列表（proxies）删除（真正离组）；分组来源成员
 *   进排除名单（解析与配置生成时过滤，不再出现）；
 * - 替换：原地替换，保持顺位；新成员已在表里时先去重再替换；
 * - 按延迟排序：延迟未知（null）的排最后，同层内排序见
 *   [sortPriorityOrderByLatencyWithinTiers]；
 * - 编号：展示顺位 = 列表下标 + 1（[formatPriorityNumber]）。
 */
object BalancerPriorityOrder {
    /** 展示顺位编号：下标 0 → "1"，下标 1 → "2"……（紧凑编号 `1, 2, 3...`）。 */
    fun formatNumber(index: Int): String = "${index + 1}"
}

/** 展示顺位编号（顶层便捷函数，与 [BalancerPriorityOrder.formatNumber] 同值）。 */
fun formatPriorityNumber(index: Int): String = BalancerPriorityOrder.formatNumber(index)

/**
 * 把存量顺序与当前解析成员对齐：保留存量顺序（只留仍在当前成员里的），
 * 再把未在存量顺序里的当前成员按解析顺序追加在后；排除名单里的成员
 * 两边都不留。存量顺序里的脏 id（已不在当前成员里）直接丢弃。
 */
fun reconcilePriorityOrder(
    stored: List<Long>,
    currentMembers: List<Long>,
    excluded: Set<Long> = emptySet(),
): List<Long> {
    val currentSet = currentMembers.toSet()
    val kept = stored.filter { it in currentSet && it !in excluded }.distinct()
    val keptSet = kept.toSet()
    val appended = currentMembers.filter { it !in keptSet && it !in excluded }
    return kept + appended
}

/**
 * 把解析顺序按显式顺序重排：在表里的成员按表内顺序在前，未在表里的
 * 按原顺序追加在后（排除名单里的成员已在解析层过滤，此处不再处理）。
 */
fun applyPriorityOrder(memberIds: List<Long>, priorityOrder: List<Long>): List<Long> {
    val orderIndex = priorityOrder.withIndex().associate { it.value to it.index }
    val listed = memberIds.filter { it in orderIndex }.sortedBy { orderIndex.getValue(it) }
    val unlisted = memberIds.filter { it !in orderIndex }
    return listed + unlisted
}

/** 拖拽调序：把下标 [from] 的成员移到 [to]；越界返回原表。 */
fun movePriorityOrder(order: List<Long>, from: Int, to: Int): List<Long> {
    if (from !in order.indices || to !in order.indices || from == to) return order
    val mutable = order.toMutableList()
    val id = mutable.removeAt(from)
    mutable.add(to, id)
    return mutable
}

/** 移除：从顺序表里删掉 [memberId]（不存在时返回原表）。 */
fun removePriorityOrder(order: List<Long>, memberId: Long): List<Long> =
    if (memberId !in order) order else order.filter { it != memberId }

/**
 * 替换：把 [oldId] 原地换成 [newId]，顺位不变；[newId] 已在表里时先删掉
 * 旧位置再替换（不产生重复）；[oldId] 不在表里时把 [newId] 追加在末尾。
 */
fun replacePriorityOrder(order: List<Long>, oldId: Long, newId: Long): List<Long> {
    if (oldId == newId) return order
    val deduped = order.filter { it != newId }
    val index = deduped.indexOf(oldId)
    if (index < 0) return deduped + newId
    val mutable = deduped.toMutableList()
    mutable[index] = newId
    return mutable
}

/**
 * 按当前延迟排序：延迟升序，未知延迟（null）的排最后；延迟相同保持原顺序
 * （稳定排序，拖拽过的相对顺序不被打乱）。
 */
fun sortPriorityOrderByLatency(
    order: List<Long>,
    delayMs: Map<Long, Int?>,
): List<Long> = order.sortedWith(
    compareBy<Long> { delayMs[it] == null }.thenBy { delayMs[it] ?: Int.MAX_VALUE },
)

/**
 * 有层级时按层级分组内延迟排优：先按层级序号分组，层内按延迟升序
 * （未知延迟排最后），再按层级顺序拼接——跨层不比较延迟，
 * 高层成员永远排在低层成员前面。
 */
fun sortPriorityOrderByLatencyWithinTiers(
    order: List<Long>,
    tiers: Map<Long, Int>,
    delayMs: Map<Long, Int?>,
): List<Long> {
    val tierOf: (Long) -> Int = { tiers[it] ?: Int.MAX_VALUE }
    return order.sortedWith(
        compareBy<Long> { tierOf(it) }
            .thenBy { delayMs[it] == null }
            .thenBy { delayMs[it] ?: Int.MAX_VALUE },
    )
}

/** 显式顺序编为 DataStore 草稿串（逗号分隔的成员 id）；空表编为空串。 */
fun encodePriorityOrderIds(ids: List<Long>): String = ids.joinToString(",")

/**
 * 解码 DataStore 草稿串；脏段跳过、非法 id（<=0）丢弃、重复去重
 * （保留首次出现顺序），永不抛错。
 */
fun decodePriorityOrderIds(raw: String?): List<Long> {
    if (raw.isNullOrBlank()) return emptyList()
    val seen = LinkedHashSet<Long>()
    for (part in raw.split(",")) {
        val id = part.trim().toLongOrNull() ?: continue
        if (id > 0L) seen.add(id)
    }
    return seen.toList()
}
