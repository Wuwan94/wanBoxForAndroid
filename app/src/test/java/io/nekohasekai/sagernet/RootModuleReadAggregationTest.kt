// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.RootModuleClient
import io.nekohasekai.sagernet.bg.RootModuleReadQuery
import io.nekohasekai.sagernet.bg.RootModuleStatusCache
import org.junit.Assert.*
import org.junit.Test

/**
 * Aggregated polling in RootModuleClient refreshes a sibling protocol view
 * only when a previous consumer left a stale entry behind; these tests pin
 * the staleness signal that decision relies on.
 */
class RootModuleReadAggregationTest {
    private fun status(revision: String) =
        RootModuleClient.Status("connected", revision, revision, 7L, "fixture", null)

    @Test fun missingEntryIsNotStale() {
        val cache = RootModuleStatusCache(4_000L) { 0L }
        assertFalse(cache.hasStaleEntry(RootModuleReadQuery.STATUS))
        assertFalse(cache.hasStaleEntry(RootModuleReadQuery.DASHBOARD))
    }

    @Test fun entryTurnsStaleExactlyAtTtlWithoutBeingEvicted() {
        var now = 1_000L
        val cache = RootModuleStatusCache(4_000L) { now }
        cache.putIfGeneration(RootModuleReadQuery.STATUS, status("rev"), cache.generation())

        now += 3_999L
        assertFalse(cache.hasStaleEntry(RootModuleReadQuery.STATUS))
        now += 1L
        assertTrue(cache.hasStaleEntry(RootModuleReadQuery.STATUS))
        // Non-destructive: the demand signal survives repeated checks until a
        // normal get() evicts the entry, after which the demand is gone.
        assertTrue(cache.hasStaleEntry(RootModuleReadQuery.STATUS))
        assertNull(cache.get(RootModuleReadQuery.STATUS))
        assertFalse(cache.hasStaleEntry(RootModuleReadQuery.STATUS))
    }

    @Test fun stalenessIsTrackedPerView() {
        var now = 0L
        val cache = RootModuleStatusCache(4_000L) { now }
        cache.putIfGeneration(RootModuleReadQuery.STATUS, status("s"), cache.generation())
        now += 4_000L
        cache.putIfGeneration(RootModuleReadQuery.DASHBOARD, status("d"), cache.generation())

        assertTrue(cache.hasStaleEntry(RootModuleReadQuery.STATUS))
        assertFalse(cache.hasStaleEntry(RootModuleReadQuery.DASHBOARD))
    }

    @Test fun invalidationClearsDemandAndBackwardsClockCountsAsStale() {
        var now = 10_000L
        val cache = RootModuleStatusCache(4_000L) { now }
        cache.putIfGeneration(RootModuleReadQuery.STATUS, status("rev"), cache.generation())
        now += 4_000L
        assertTrue(cache.hasStaleEntry(RootModuleReadQuery.STATUS))

        cache.invalidate()
        assertFalse(cache.hasStaleEntry(RootModuleReadQuery.STATUS))

        // Same rule as get(): a clock step back makes the entry unusable, so
        // it counts as stale demand rather than fresh data.
        cache.putIfGeneration(RootModuleReadQuery.STATUS, status("rev"), cache.generation())
        now -= 1L
        assertTrue(cache.hasStaleEntry(RootModuleReadQuery.STATUS))
    }
}
