package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class SmartAutoStopControllerTest {
    @Test fun cancellationPropagatesWithoutChangingNotification() = runBlocking {
        for (action in listOf("resume", "stop")) for (cancelAction in listOf(true, false)) {
            val cancellation = CancellationException("cancelled")
            val calls = mutableListOf<String>()
            val operations = object : SmartAutoStopOperations {
                override suspend fun resume() { calls += "resume"; if (cancelAction) throw cancellation }
                override suspend fun stop() { calls += "stop"; if (cancelAction) throw cancellation }
                override suspend fun status(): String { calls += "status"; throw cancellation }
            }
            val shown = mutableListOf<String>()
            val controller = SmartAutoStopController(operations, shown::add)
            controller.onPhase("suspended")
            try {
                controller.onAction(action)
                fail("Cancellation must propagate")
            } catch (e: CancellationException) {
                assertSame(cancellation, e)
            }
            assertEquals(if (cancelAction) listOf(action) else listOf(action, "status"), calls)
            assertEquals(listOf("suspended"), shown)
        }
    }

    private class Operations : SmartAutoStopOperations {
        val calls = mutableListOf<String>()
        var phase = "suspended"
        var actionFails = false
        var statusFails = false
        override suspend fun resume() { calls += "resume"; if (actionFails) error("offline") }
        override suspend fun stop() { calls += "stop"; if (actionFails) error("offline") }
        override suspend fun status(): String { calls += "status"; if (statusFails) error("offline"); return phase }
    }

    @Test fun fixedPhasesReconcileAndUnknownInputPreservesNotification() = runBlocking {
        val ops = Operations()
        val shown = mutableListOf<String>()
        val controller = SmartAutoStopController(ops, shown::add)
        for (phase in listOf("suspended", "connected", "stopped", "failed", "disabled")) controller.onPhase(phase)
        controller.onPhase("unknown")
        controller.onPhase(null)
        controller.onAction("unknown")
        controller.onAction(null)
        assertEquals(listOf("suspended", "connected", "stopped", "failed", "disabled"), shown)
        assertEquals(emptyList<String>(), ops.calls)
    }

    @Test fun actionsReadAuthoritativeStatusEvenWhenRootActionFails() = runBlocking {
        for (action in listOf("resume", "stop")) for (fails in listOf(false, true)) for (status in
            listOf("suspended", "connected", "stopped", "failed", "disabled")) {
            val ops = Operations().apply { actionFails = fails; phase = status }
            val shown = mutableListOf<String>()
            val controller = SmartAutoStopController(ops, shown::add)
            controller.onPhase("suspended")
            controller.onAction(action)
            assertEquals(listOf(action, "status"), ops.calls)
            assertEquals(listOf("suspended", status), shown)
        }
    }

    @Test fun unreadableOrUnknownStatusPreservesLastSuspendedNotification() = runBlocking {
        for (action in listOf("resume", "stop")) for (fails in listOf(false, true)) for (unreadable in listOf(false, true)) {
            val ops = Operations().apply { actionFails = fails; statusFails = unreadable; phase = "unknown" }
            val shown = mutableListOf<String>()
            val controller = SmartAutoStopController(ops, shown::add)
            controller.onPhase("suspended")
            controller.onAction(action)
            assertEquals(listOf(action, "status"), ops.calls)
            assertEquals(listOf("suspended"), shown)
        }
    }
}
