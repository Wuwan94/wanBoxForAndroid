package io.nekohasekai.sagernet.ui

import androidx.recyclerview.widget.DiffUtil

internal data class NodeSearchEntry(val id: Long, val name: String, val type: String, val address: String)

internal object NodeContentChanged

internal fun trafficLayoutChanged(previousMiddleRow: Boolean?, oldTx: Long, oldRx: Long, tx: Long, rx: Long): Boolean {
    val previouslyVisible = oldTx != Long.MIN_VALUE && oldRx != Long.MIN_VALUE && oldTx + oldRx != 0L
    return previousMiddleRow == null || previouslyVisible != (tx + rx != 0L)
}

internal fun matchingNodeIds(entries: List<NodeSearchEntry>, query: String): List<Long> {
    val text = query.trim()
    return entries.filter {
        text.isEmpty() || it.name.contains(text, ignoreCase = true) ||
            it.type.contains(text, ignoreCase = true) || it.address.contains(text, ignoreCase = true)
    }.map { it.id }
}

internal class NodeIdDiff(
    oldIds: List<Long>,
    newIds: List<Long>,
    changedIds: Set<Long> = emptySet(),
) : DiffUtil.Callback() {
    private val before = oldIds.toList()
    private val after = newIds.toList()
    private val changedIds = changedIds.toSet()

    override fun getOldListSize(): Int = before.size
    override fun getNewListSize(): Int = after.size
    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
        before[oldItemPosition] == after[newItemPosition]
    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
        areItemsTheSame(oldItemPosition, newItemPosition) && after[newItemPosition] !in changedIds
    override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any = NodeContentChanged
}
