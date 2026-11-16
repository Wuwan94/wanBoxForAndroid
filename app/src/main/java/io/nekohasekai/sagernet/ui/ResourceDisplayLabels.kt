package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.GroupType

/**
 * Display-layer identity helpers for the unified resource page (batch 4b).
 *
 * Duplicate display names are disambiguated only in the UI: first by a source
 * suffix, and when name and source both collide, by the stable entity ID.
 * Labels are keyed by entity ID; a display name is never used as a database,
 * routing or provider key, and renaming a resource changes only its label.
 */
object ResourceDisplayLabels {

    data class Item(val id: Long, val name: String, val source: String)

    fun labels(items: List<Item>): Map<Long, String> {
        val nameCounts = items.groupingBy { it.name }.eachCount()
        val nameSourceCounts = items.groupingBy { it.name to it.source }.eachCount()
        return items.associate { item ->
            val label = when {
                nameCounts.getValue(item.name) <= 1 -> item.name
                nameSourceCounts.getValue(item.name to item.source) <= 1 ->
                    "${item.name} (${item.source})"
                else -> "${item.name} (${item.source} #${item.id})"
            }
            item.id to label
        }
    }

    enum class UpdateScope { SINGLE, SELECTED, ALL }

    data class GroupRef(val id: Long, val type: Int)

    /**
     * Plans subscription update targets for the resource page. Only
     * subscription groups are ever returned, in group list order; local
     * groups (and local nodes under them) are never update targets.
     */
    fun planUpdateTargets(
        groups: List<GroupRef>,
        scope: UpdateScope,
        currentGroupId: Long,
        selectedGroupIds: Set<Long>,
    ): List<Long> {
        val subscriptions = groups.filter { it.type == GroupType.SUBSCRIPTION }
        return when (scope) {
            UpdateScope.SINGLE -> subscriptions.filter { it.id == currentGroupId }.map { it.id }
            UpdateScope.SELECTED -> subscriptions.filter { it.id in selectedGroupIds }.map { it.id }
            UpdateScope.ALL -> subscriptions.map { it.id }
        }
    }
}
