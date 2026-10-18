package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class PendingProfileSelectionTest {
    @Test fun lateFailureDoesNotUndoLatestClickEvenOnSameNode() {
        val state = PendingProfileSelection()
        val firstA = state.begin(1)
        val b = state.begin(2)
        val latestA = state.begin(1)
        assertFalse(state.complete(firstA))
        assertFalse(state.complete(b))
        assertEquals(1L, state.selectedOr(9))
        assertTrue(state.complete(latestA))
        assertEquals(9L, state.selectedOr(9))
    }

    @Test fun confirmedOldNodeDoesNotAcknowledgePendingReturn() {
        val state = PendingProfileSelection()
        state.begin(2)
        val returning = state.begin(1)
        assertEquals(1L, state.selectedOr(1))
        assertEquals(1L, state.selectedOr(2))
        assertTrue(state.complete(returning))
        assertEquals(2L, state.selectedOr(2))
    }

    @Test fun destroyingViewInvalidatesCompletionFromItsRequest() {
        val state = PendingProfileSelection()
        val old = state.begin(1)
        state.clear()
        assertEquals(9L, state.selectedOr(9))
        val recreated = state.begin(1)
        assertFalse(state.complete(old))
        assertTrue(state.complete(recreated))
    }

    @Test fun invalidNodeDoesNotReplacePendingIntent() {
        val state = PendingProfileSelection()
        val valid = state.begin(4)
        try { state.begin(0); fail("Invalid ID accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(4L, state.selectedOr(1))
        assertTrue(state.complete(valid))
    }
}
