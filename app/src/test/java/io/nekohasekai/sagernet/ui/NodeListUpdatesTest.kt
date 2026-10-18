package io.nekohasekai.sagernet.ui

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import org.junit.Assert.*
import org.junit.Test

class NodeListUpdatesTest {
    private val entries = listOf(
        NodeSearchEntry(3, "Japan", "Shadowsocks", "jp.invalid"),
        NodeSearchEntry(1, "Hong Kong", "Trojan", "hk.invalid"),
        NodeSearchEntry(8, "Home", "SOCKS", "192.0.2.1"),
    )

    @Test fun searchesAllDisplayFieldsIgnoringCaseAndTrimsQuery() {
        assertEquals(listOf(1L), matchingNodeIds(entries, "  HONG  "))
        assertEquals(listOf(3L, 8L), matchingNodeIds(entries, "SOCKS"))
        assertEquals(listOf(8L), matchingNodeIds(entries, "192.0.2"))
        assertTrue(matchingNodeIds(entries, "missing").isEmpty())
    }

    @Test fun clearingSearchPreservesCanonicalOrderAndMembership() {
        assertEquals(listOf(3L, 1L, 8L), matchingNodeIds(entries, "  "))
        assertEquals(listOf(3L, 8L), matchingNodeIds(entries.filter { it.id != 1L }, ""))
    }

    @Test fun changedNameInvalidatesCapturedSearchInputEvenWithSameIds() {
        val updated = entries.map { if (it.id == 1L) it.copy(name = "Singapore") else it }
        assertNotEquals(entries, updated)
        assertEquals(listOf(1L), matchingNodeIds(entries, "Hong"))
        assertTrue(matchingNodeIds(updated, "Hong").isEmpty())
        assertEquals(listOf(1L), matchingNodeIds(updated, "Singapore"))
    }

    @Test fun diffMapsStableIdsAcrossSearchDeletionAndReorder() {
        val diff = DiffUtil.calculateDiff(NodeIdDiff(listOf(1, 2, 3), listOf(3, 1, 4)))
        assertEquals(1, diff.convertOldPositionToNew(0))
        assertEquals(DiffUtil.DiffResult.NO_POSITION, diff.convertOldPositionToNew(1))
        assertEquals(0, diff.convertOldPositionToNew(2))
        assertEquals(DiffUtil.DiffResult.NO_POSITION, diff.convertNewPositionToOld(2))
        val events = RecordingUpdates()
        diff.dispatchUpdatesTo(events)
        assertEquals(1, events.inserted)
        assertEquals(1, events.removed)
        assertTrue(events.moved > 0)
        assertTrue(events.changes.isEmpty())
    }

    @Test fun sameIdContentReloadProducesFullContentPayload() {
        val diff = DiffUtil.calculateDiff(NodeIdDiff(listOf(1, 2), listOf(1, 2), setOf(2)))
        val events = RecordingUpdates()
        diff.dispatchUpdatesTo(events)
        assertEquals(listOf(Triple(1, 1, NodeContentChanged)), events.changes)
        assertEquals(0, events.inserted + events.removed + events.moved)
    }

    @Test fun diffOwnsImmutableCopiesWhileAdapterListsChange() {
        val before = mutableListOf(1L, 2L)
        val after = mutableListOf(2L)
        val changed = mutableSetOf(2L)
        val callback = NodeIdDiff(before, after, changed)
        before.clear(); after.clear(); changed.clear()
        assertEquals(2, callback.oldListSize)
        assertEquals(1, callback.newListSize)
        assertTrue(callback.areItemsTheSame(1, 0))
        assertFalse(callback.areContentsTheSame(1, 0))
    }

    @Test fun trafficAppearingAndClearingRequiresFullLayoutBinding() {
        assertTrue(trafficLayoutChanged(false, 0, 0, 1, 0))
        assertTrue(trafficLayoutChanged(true, 1, 1, 0, 0))
    }

    @Test fun recycledOrUnboundTrafficRowRequiresFullLayoutBinding() {
        assertTrue(trafficLayoutChanged(null, Long.MIN_VALUE, Long.MIN_VALUE, 0, 0))
        assertTrue(trafficLayoutChanged(null, 100, 200, 100, 200))
    }

    @Test fun ongoingTrafficOnlyNeedsTextBinding() {
        assertFalse(trafficLayoutChanged(true, 100, 200, 200, 300))
        assertFalse(trafficLayoutChanged(false, 0, 0, 0, 0))
    }

    private class RecordingUpdates : ListUpdateCallback {
        var inserted = 0
        var removed = 0
        var moved = 0
        val changes = ArrayList<Triple<Int, Int, Any?>>()
        override fun onInserted(position: Int, count: Int) { inserted += count }
        override fun onRemoved(position: Int, count: Int) { removed += count }
        override fun onMoved(fromPosition: Int, toPosition: Int) { moved++ }
        override fun onChanged(position: Int, count: Int, payload: Any?) { changes += Triple(position, count, payload) }
    }
}
