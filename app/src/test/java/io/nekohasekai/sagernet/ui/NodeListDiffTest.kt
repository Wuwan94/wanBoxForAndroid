package io.nekohasekai.sagernet.ui

import androidx.recyclerview.widget.ListUpdateCallback
import org.junit.Assert.*
import org.junit.Test

class NodeListDiffTest {
    private class Updates : ListUpdateCallback {
        var inserted = 0
        var removed = 0
        var changed = 0
        var moved = 0
        override fun onInserted(position: Int, count: Int) { inserted += count }
        override fun onRemoved(position: Int, count: Int) { removed += count }
        override fun onChanged(position: Int, count: Int, payload: Any?) { changed += count }
        override fun onMoved(fromPosition: Int, toPosition: Int) { moved++ }
    }

    @Test fun largeListRemovalsAndInsertionsAreIncremental() {
        val old = (1L..1500L).toList()
        val new = (2L..1501L).toList()
        val diff = NodeListDiff.calculate(old, new, emptySet())
        val updates = Updates()
        diff.dispatchUpdatesTo(updates)
        assertEquals(1, updates.inserted)
        assertEquals(1, updates.removed)
        assertEquals(0, updates.changed)
        for (i in 1 until old.size) assertEquals(i - 1, diff.convertOldPositionToNew(i))
    }

    @Test fun largeListContentChangeDoesNotRebindEveryRow() {
        val ids = (1L..2000L).toList()
        val updates = Updates()
        NodeListDiff.calculate(ids, ids, setOf(1001L)).dispatchUpdatesTo(updates)
        assertEquals(1, updates.changed)
        assertEquals(0, updates.inserted + updates.removed + updates.moved)
    }

    @Test fun emptyListTransitionsKeepCorrectCounts() {
        val updates = Updates()
        NodeListDiff.calculate(emptyList(), listOf(1L, 2L), emptySet()).dispatchUpdatesTo(updates)
        assertEquals(2, updates.inserted)
        val cleared = Updates()
        NodeListDiff.calculate(listOf(1L, 2L), emptyList(), emptySet()).dispatchUpdatesTo(cleared)
        assertEquals(2, cleared.removed)
    }

    @Test fun smallListMoveUsesStableIdentity() {
        val updates = Updates()
        NodeListDiff.calculate(listOf(1L, 2L, 3L), listOf(3L, 1L, 2L), emptySet()).dispatchUpdatesTo(updates)
        assertEquals(1, updates.moved)
        assertEquals(0, updates.inserted + updates.removed + updates.changed)
    }

    @Test fun unchangedLargeSnapshotDispatchesNothing() {
        val ids = (1L..5000L).toList()
        val updates = Updates()
        NodeListDiff.calculate(ids, ids, emptySet()).dispatchUpdatesTo(updates)
        assertEquals(0, updates.inserted + updates.removed + updates.changed + updates.moved)
    }
}
