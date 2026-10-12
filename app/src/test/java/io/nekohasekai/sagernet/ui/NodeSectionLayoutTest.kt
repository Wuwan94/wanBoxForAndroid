package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class NodeSectionLayoutTest {
    @Test fun oddSizedGroupsNeverShareAGridRow() {
        for (columns in 1..3) {
            val groups = listOf(1L, 1L, 1L, 2L, 3L, 3L, 3L, 3L, 3L)
            val layout = NodeSectionLayout().apply { update(groups) }
            var occupied = 0
            groups.indices.forEach { position ->
                if (position > 0 && groups[position] != groups[position - 1]) assertEquals(0, occupied)
                val span = layout.span(position, columns)
                assertTrue(span in 1..columns)
                occupied = (occupied + span) % columns
            }
            assertEquals(0, occupied)
        }
    }
    @Test fun filteringAndEmptyReloadsRebuildHeaders() {
        val layout = NodeSectionLayout()
        layout.update(listOf(1L, 1L, 2L, 2L))
        assertEquals(NodeSectionLayout.Item(2L, 0, 2), layout.item(2))
        layout.update(listOf(2L))
        assertEquals(NodeSectionLayout.Item(2L, 0, 1), layout.item(0))
        assertEquals(3, layout.span(0, 3))
        assertNull(layout.item(1))
        layout.update(emptyList())
        assertNull(layout.item(0))
        assertEquals(1, layout.span(0, 2))
    }
}
