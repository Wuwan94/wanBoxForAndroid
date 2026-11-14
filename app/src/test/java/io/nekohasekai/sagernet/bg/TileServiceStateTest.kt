package io.nekohasekai.sagernet.bg

import android.service.quicksettings.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileServiceStateTest {
    @Test
    fun binderStateMappingAcceptsKnownValuesAndRejectsInvalidValues() {
        assertEquals(BaseService.State.Idle, TileService.stateFromBinderValue(BaseService.State.Idle.ordinal))
        assertEquals(BaseService.State.Connected, TileService.stateFromBinderValue(BaseService.State.Connected.ordinal))
        assertNull(TileService.stateFromBinderValue(-1))
        assertNull(TileService.stateFromBinderValue(BaseService.State.values().size))
        assertNull(TileService.stateFromBinderValue(Int.MAX_VALUE))
    }

    @Test
    fun displayStatePreciselyRepresentsServiceLifecycle() {
        assertEquals(Tile.STATE_INACTIVE, TileService.displayState(BaseService.State.Idle))
        assertEquals(Tile.STATE_INACTIVE, TileService.displayState(BaseService.State.Stopped))
        assertEquals(Tile.STATE_INACTIVE, TileService.displayState(BaseService.State.Suspended))
        assertEquals(Tile.STATE_ACTIVE, TileService.displayState(BaseService.State.Connecting))
        assertEquals(Tile.STATE_ACTIVE, TileService.displayState(BaseService.State.Connected))
        assertEquals(Tile.STATE_UNAVAILABLE, TileService.displayState(BaseService.State.Stopping))
        assertEquals(Tile.STATE_UNAVAILABLE, TileService.displayState(null))
    }

    @Test
    fun tileToggleIsBlockedOnlyWhileServiceIsTransitioning() {
        assertTrue(isTransitionalServiceState(BaseService.State.Connecting))
        assertTrue(isTransitionalServiceState(BaseService.State.Stopping))
        assertFalse(isTransitionalServiceState(BaseService.State.Idle))
        assertFalse(isTransitionalServiceState(BaseService.State.Stopped))
        assertFalse(isTransitionalServiceState(BaseService.State.Connected))
        assertFalse(isTransitionalServiceState(null))
    }

    @Test
    fun toggleActionIgnoresTransitionsAndPreservesStableStateBehavior() {
        assertEquals(ServiceToggleAction.IGNORE, serviceToggleAction(BaseService.State.Connecting, -1L, 7L))
        assertEquals(ServiceToggleAction.IGNORE, serviceToggleAction(BaseService.State.Stopping, 9L, 7L))
        assertEquals(ServiceToggleAction.START, serviceToggleAction(BaseService.State.Idle, -1L, 7L))
        assertEquals(ServiceToggleAction.START, serviceToggleAction(BaseService.State.Stopped, -1L, 7L))
        assertEquals(ServiceToggleAction.STOP, serviceToggleAction(BaseService.State.Connected, -1L, 7L))
        assertEquals(ServiceToggleAction.STOP, serviceToggleAction(BaseService.State.Connected, 7L, 7L))
        assertEquals(ServiceToggleAction.START, serviceToggleAction(BaseService.State.Connected, 9L, 7L))
    }

    @Test
    fun profileNameNormalizationIsNullAndWhitespaceSafe() {
        assertEquals("Office", TileService.normalizeProfileName("  Office  "))
        assertNull(TileService.normalizeProfileName(null))
        assertNull(TileService.normalizeProfileName("   "))
        assertNull(TileService.normalizeProfileName("null"))
        assertNull(TileService.normalizeProfileName(" NULL "))
    }
}
