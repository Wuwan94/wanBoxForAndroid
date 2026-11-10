package io.nekohasekai.sagernet.bg

import android.service.quicksettings.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(Tile.STATE_ACTIVE, TileService.displayState(BaseService.State.Connecting))
        assertEquals(Tile.STATE_ACTIVE, TileService.displayState(BaseService.State.Connected))
        assertEquals(Tile.STATE_UNAVAILABLE, TileService.displayState(BaseService.State.Stopping))
        assertEquals(Tile.STATE_UNAVAILABLE, TileService.displayState(null))
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
