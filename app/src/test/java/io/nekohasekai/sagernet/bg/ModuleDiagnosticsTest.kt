package io.nekohasekai.sagernet.bg

import org.junit.Assert.*
import org.junit.Test

class ModuleDiagnosticsTest {
    private fun status() = RootModuleClient.Status("stopped", "", "", 0, "", null)

    @Test fun allFieldsHaveStableOrderAndPreserveCodes() {
        val lines = ModuleDiagnostics.format(status().copy(
            health = "degraded", lastReason = "core_exit", lastExit = "exit:2",
            lastExitAt = 1730000000L, retryCount = 3,
            events = listOf(RootModuleClient.ModuleEvent(1730000001L, "core_restart"))
        ))
        assertEquals(ModuleDiagnostics.Kind.values().toList(), lines.map { it.kind })
        assertEquals(listOf("degraded", "core_exit", "exit:2", "", "3", "core_restart"), lines.map { it.value })
        assertEquals(1730000000L, lines[3].epochSeconds)
        assertEquals(1730000001L, lines[5].epochSeconds)
    }

    @Test fun missingFieldsAreHiddenIndependently() {
        assertEquals(listOf(ModuleDiagnostics.Line(ModuleDiagnostics.Kind.EXIT_REASON, "core_exit")),
            ModuleDiagnostics.format(status().copy(lastReason = "core_exit")))
        assertEquals(listOf(ModuleDiagnostics.Line(ModuleDiagnostics.Kind.EXIT_TIME, epochSeconds = 42L)),
            ModuleDiagnostics.format(status().copy(lastExitAt = 42L)))
        assertTrue(ModuleDiagnostics.format(status().copy(health = "  ", retryCount = -1)).isEmpty())
    }

    @Test fun eventsAreSortedByTimeAndLimitedToNewestFive() {
        val events = listOf(3L, 8L, 1L, 6L, 2L, 7L, 4L, 5L)
            .map { RootModuleClient.ModuleEvent(it, "event:$it") }
        val lines = ModuleDiagnostics.format(status().copy(events = events))
        assertEquals(listOf(8L, 7L, 6L, 5L, 4L), lines.map { it.epochSeconds })
        assertEquals(listOf("event:8", "event:7", "event:6", "event:5", "event:4"), lines.map { it.value })
        assertTrue(lines.all { it.kind == ModuleDiagnostics.Kind.EVENT })
    }

    @Test fun sameSecondEventsKeepLatestFiveInReverseSourceOrder() {
        val events = listOf("A", "B", "C", "D", "E", "F")
            .map { RootModuleClient.ModuleEvent(1730000000L, it) }
        val lines = ModuleDiagnostics.format(status().copy(events = events))
        assertEquals(listOf("F", "E", "D", "C", "B"), lines.map { it.value })
    }

    @Test fun oldModuleReturnsNoLines() {
        assertTrue(ModuleDiagnostics.format(status()).isEmpty())
    }
}
