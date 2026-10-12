package io.nekohasekai.sagernet.ui

/** Row boundaries for contiguous groups, without inserting fake profile IDs into the adapter. */
internal class NodeSectionLayout {
    data class Item(val groupId: Long, val offset: Int, val count: Int)
    private var items: List<Item> = emptyList()
    fun update(groups: List<Long>) {
        val result = ArrayList<Item>(groups.size)
        var start = 0
        while (start < groups.size) {
            var end = start + 1
            while (end < groups.size && groups[end] == groups[start]) end++
            for (index in start until end) result.add(Item(groups[start], index - start, end - start))
            start = end
        }
        items = result
    }
    fun item(position: Int) = items.getOrNull(position)
    fun span(position: Int, columns: Int): Int {
        val item = item(position) ?: return 1
        return if (item.offset == item.count - 1) columns - item.offset % columns else 1
    }
}
