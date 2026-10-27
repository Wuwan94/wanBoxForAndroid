// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.RootModuleClient
import io.nekohasekai.sagernet.bg.RootModuleReadQuery
import io.nekohasekai.sagernet.bg.RootModuleStatusCache
import org.junit.Assert.*
import org.junit.Test

class RootModuleStatusCacheTest {
    private fun status(revision: String, phase: String = "connected") =
        RootModuleClient.Status(phase, revision, revision, 7L, "fixture", null)

    @Test fun statusAndDashboardViewsAreCachedSeparately() {
        var now = 100L
        val cache = RootModuleStatusCache(2_000L) { now }
        val status = status("status-rev")
        val dashboard = status("dashboard-rev")

        assertNull(cache.get(RootModuleReadQuery.STATUS))
        assertNull(cache.get(RootModuleReadQuery.DASHBOARD))
        cache.putIfGeneration(RootModuleReadQuery.STATUS, status, cache.generation())
        cache.putIfGeneration(RootModuleReadQuery.DASHBOARD, dashboard, cache.generation())

        assertSame(status, cache.get(RootModuleReadQuery.STATUS))
        assertSame(dashboard, cache.get(RootModuleReadQuery.DASHBOARD))
        assertNotSame(cache.get(RootModuleReadQuery.STATUS), cache.get(RootModuleReadQuery.DASHBOARD))
    }


    @Test fun staleInFlightReadCannotRefillAfterInvalidation() {
        var now = 4_000L
        val cache = RootModuleStatusCache(2_000L) { now }
        val readGeneration = cache.generation()

        cache.invalidate()
        assertFalse(cache.putIfGeneration(RootModuleReadQuery.STATUS, status("stale"), readGeneration))
        assertNull(cache.get(RootModuleReadQuery.STATUS))

        val freshGeneration = cache.generation()
        assertTrue(cache.putIfGeneration(RootModuleReadQuery.STATUS, status("fresh"), freshGeneration))
        assertEquals("fresh", cache.get(RootModuleReadQuery.STATUS)?.revision)
    }

    @Test fun entriesExpireAtTtlAndMutationClassificationKeepsReadOnlyCalls() {
        var now = 1_000L
        val cache = RootModuleStatusCache(2_000L) { now }
        val status = status("rev")
        cache.putIfGeneration(RootModuleReadQuery.STATUS, status, cache.generation())
        cache.putIfGeneration(RootModuleReadQuery.DASHBOARD, status("dash-rev"), cache.generation())

        now += 1_999L
        assertSame(status, cache.get(RootModuleReadQuery.STATUS))
        now += 1L
        assertNull(cache.get(RootModuleReadQuery.STATUS))
        assertNull(cache.get(RootModuleReadQuery.DASHBOARD))

        cache.putIfGeneration(RootModuleReadQuery.STATUS, status, cache.generation())
        cache.putIfGeneration(RootModuleReadQuery.DASHBOARD, status("dash-rev"), cache.generation())
        assertTrue(RootModuleClient.mutatesModule("config apply"))
        assertTrue(RootModuleClient.mutatesModule("node select 7"))
        assertTrue(RootModuleClient.mutatesModule("data selection-finish 0123456789abcdef0123456789abcdef"))
        assertFalse(RootModuleClient.mutatesModule("status"))
        assertFalse(RootModuleClient.mutatesModule("dashboard"))
        assertFalse(RootModuleClient.mutatesModule("config validate"))

        cache.invalidate()
        assertNull(cache.get(RootModuleReadQuery.STATUS))
        assertNull(cache.get(RootModuleReadQuery.DASHBOARD))
    }
}
