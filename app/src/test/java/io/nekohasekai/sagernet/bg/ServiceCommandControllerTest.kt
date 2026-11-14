// @author 雾晚
package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.route.OutboundReferenceValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * ServiceCommandController 行为测试（可注入 fake backend）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceCommandControllerTest {

    @Test
    fun `outbound warning holds until explicit continue and cancel releases the next command`() = runTest {
        val continuedRequests = mutableListOf<Pair<String, Boolean>>()
        var starts = 0
        val backend = object : ServiceCommandController.Backend {
            override suspend fun start(): RootModuleClient.Status {
                starts++
                return fakeStatus()
            }
            override suspend fun stop() = fakeStatus(false, "stopped")
            override suspend fun setMode(mode: String) = fakeStatus()
            override suspend fun selectNode(id: Long) = fakeStatus(profileId = id)
            override suspend fun queryStatus() = fakeStatus()
            override suspend fun continueOutboundConfirmation(
                requestToken: String,
                startIfStopped: Boolean,
            ): RootModuleClient.Status {
                continuedRequests += requestToken to startIfStopped
                return fakeStatus()
            }
        }
        val controller = ServiceCommandController(this, backend)
        val warning = ServiceCommandController.OutboundReferenceWarning(
            requestToken = "cancelled-request",
            startIfStopped = true,
            issues = listOf(OutboundReferenceValidator.Issue(
                ruleId = 12L,
                ruleName = "Video",
                kind = OutboundReferenceValidator.ReferenceKind.NODE_OR_STRATEGY_GROUP,
                missingId = 99L,
            )),
        )

        assertTrue("warning request should be accepted", controller.requestOutboundConfirmation(warning))
        testScheduler.advanceUntilIdle()
        assertEquals(
            ServiceCommandController.CommandState.ConfirmationRequired(1L, warning),
            controller.state.value,
        )
        assertTrue(controller.isBusy())
        assertFalse(controller.requestStart())
        assertEquals(0, starts)
        assertEquals(emptyList<Pair<String, Boolean>>(), continuedRequests)

        assertTrue(controller.cancelOutboundConfirmation("cancelled-request"))
        testScheduler.advanceUntilIdle()
        assertEquals(ServiceCommandController.CommandState.Idle, controller.state.value)
        assertFalse(controller.isBusy())
        assertFalse(controller.continueOutboundConfirmation("cancelled-request"))
        assertEquals(emptyList<Pair<String, Boolean>>(), continuedRequests)
        assertEquals(0, starts)

        assertTrue(controller.requestStart())
        testScheduler.advanceUntilIdle()
        assertEquals(1, starts)
        controller.acknowledge()

        assertTrue(controller.requestOutboundConfirmation(warning.copy(
            requestToken = "continued-request",
            startIfStopped = false,
        )))
        testScheduler.advanceUntilIdle()
        assertTrue(controller.state.value is ServiceCommandController.CommandState.ConfirmationRequired)
        assertEquals(emptyList<Pair<String, Boolean>>(), continuedRequests)
        assertTrue(controller.continueOutboundConfirmation("continued-request"))
        assertFalse(controller.continueOutboundConfirmation("continued-request"))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("continued-request" to false), continuedRequests)
        assertEquals(1, starts)
        assertTrue(controller.state.value is ServiceCommandController.CommandState.Confirmed)
        assertFalse(controller.isBusy())
        assertFalse(controller.continueOutboundConfirmation("continued-request"))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("continued-request" to false), continuedRequests)
    }

    private fun fakeStatus(connected: Boolean = true, phase: String = "connected", profileId: Long = 1L) =
        RootModuleClient.Status(
            phase = phase,
            revision = "rev",
            runningRevision = "rev",
            profileId = profileId,
            profileName = "test",
            stats = null,
        )

    @Test
    fun `two simultaneous submits only execute once`() = runTest {
        val scope = this
        var execCount = 0
        val backend = object : ServiceCommandController.Backend {
            override suspend fun start(): RootModuleClient.Status {
                execCount++
                delay(100)
                return fakeStatus()
            }
            override suspend fun stop() = fakeStatus(false, "stopped")
            override suspend fun setMode(mode: String) = fakeStatus()
            override suspend fun selectNode(id: Long) = fakeStatus(profileId = id)
            override suspend fun queryStatus() = fakeStatus()
        }
        val controller = ServiceCommandController(scope, backend)

        val r1 = controller.requestStart()
        val r2 = controller.requestStart()

        assertTrue(r1)
        assertFalse(r2)

        scope.testScheduler.advanceUntilIdle()
        assertEquals(1, execCount)
    }

    @Test
    fun `cancellation releases pending and allows resubmit`() = runTest {
        val scope = this
        var firstCall = true
        var execCount = 0
        val backend = object : ServiceCommandController.Backend {
            override suspend fun start(): RootModuleClient.Status {
                if (firstCall) {
                    firstCall = false
                    throw CancellationException("test cancel")
                }
                execCount++
                return fakeStatus()
            }
            override suspend fun stop() = fakeStatus(false, "stopped")
            override suspend fun setMode(mode: String) = fakeStatus()
            override suspend fun selectNode(id: Long) = fakeStatus(profileId = id)
            override suspend fun queryStatus() = fakeStatus()
        }
        val controller = ServiceCommandController(scope, backend)

        // 第一次：抛 CancellationException
        assertTrue(controller.requestStart())
        scope.testScheduler.advanceUntilIdle()

        // 取消后状态应为 Cancelled/Idle，锁已释放
        val s1 = controller.state.value
        assertTrue(
            s1 is ServiceCommandController.CommandState.Cancelled ||
            s1 is ServiceCommandController.CommandState.Idle
        )
        controller.acknowledge()
        assertFalse(controller.isBusy())

        // 第二次：正常执行，验证锁已释放
        assertTrue(controller.requestStart())
        scope.testScheduler.advanceUntilIdle()
        assertEquals(1, execCount)
    }

    @Test
    fun `observer cancellation does not cancel application task`() = runTest {
        val scope = this
        var execCompleted = false
        val backend = object : ServiceCommandController.Backend {
            override suspend fun start(): RootModuleClient.Status {
                delay(100)
                execCompleted = true
                return fakeStatus()
            }
            override suspend fun stop() = fakeStatus(false, "stopped")
            override suspend fun setMode(mode: String) = fakeStatus()
            override suspend fun selectNode(id: Long) = fakeStatus(profileId = id)
            override suspend fun queryStatus() = fakeStatus()
        }
        val controller = ServiceCommandController(scope, backend)

        assertTrue(controller.requestStart())

        // 独立 observerJob 观察状态，然后取消它
        val observerJob = scope.launch {
            controller.state.collect { }
        }
        scope.testScheduler.advanceTimeBy(10)
        observerJob.cancelAndJoin()

        // 应用任务应继续完成（不受 observer 取消影响）
        scope.testScheduler.advanceUntilIdle()
        assertTrue(execCompleted)
        val s = controller.state.value
        assertTrue(s is ServiceCommandController.CommandState.Confirmed)
    }

    @Test
    fun `non-terminal starting returns error not confirmed`() = runTest {
        val scope = this
        val backend = object : ServiceCommandController.Backend {
            override suspend fun start() = fakeStatus(connected = false, phase = "starting")
            override suspend fun stop() = fakeStatus(false, "starting")
            override suspend fun setMode(mode: String) = fakeStatus()
            override suspend fun selectNode(id: Long) = fakeStatus(profileId = id)
            override suspend fun queryStatus() = fakeStatus()
        }
        val controller = ServiceCommandController(scope, backend)

        assertTrue(controller.requestStart())
        scope.testScheduler.advanceUntilIdle()

        // @author 雾晚：非终态必须返回 Error，不允许 Confirmed 掩盖
        val state = controller.state.value
        assertTrue("expected Error for non-terminal starting, got $state",
            state is ServiceCommandController.CommandState.Error)

        controller.acknowledge()
        assertTrue(controller.requestStop())
        scope.testScheduler.advanceUntilIdle()
        assertTrue(controller.state.value is ServiceCommandController.CommandState.Error)
        assertEquals("stop_not_confirmed", (controller.state.value as ServiceCommandController.CommandState.Error).message)
    }

    @Test
    fun `module terminal failure keeps its diagnostic code`() = runTest {
        val backend = ServiceCommandController.RealBackend()
        for (phase in listOf("failed", "cleanup_required", "disabled")) {
            try {
                backend.waitFor({ it.connected }, 10_000L) {
                    fakeStatus(false, phase).copy(error = "core_start_failed")
                }
                fail("terminal failure was accepted")
            } catch (error: IllegalStateException) {
                assertEquals("core_start_failed", error.message)
            }
        }
    }

    @Test
    fun `status read failure can recover and cancellation still propagates`() = runTest {
        val backend = ServiceCommandController.RealBackend()
        var reads = 0
        val expected = fakeStatus()
        val actual = backend.waitFor({ it.connected }, 10_000L) {
            if (++reads == 1) throw java.io.IOException("module_timeout")
            expected
        }
        assertSame(expected, actual)
        assertEquals(2, reads)
        try {
            backend.waitFor({ it.connected }, 10_000L) { throw CancellationException("caller_cancelled") }
            fail("cancellation was swallowed")
        } catch (error: CancellationException) {
            assertEquals("caller_cancelled", error.message)
        }
    }

    @Test
    fun `failure keeps old node and allows resubmit`() = runTest {
        val scope = this
        val backend = object : ServiceCommandController.Backend {
            override suspend fun start() = fakeStatus()
            override suspend fun stop() = fakeStatus(false, "stopped")
            override suspend fun setMode(mode: String) = fakeStatus()
            override suspend fun selectNode(id: Long): RootModuleClient.Status {
                throw IllegalStateException("node_not_found")
            }
            override suspend fun queryStatus() = fakeStatus()
        }
        val controller = ServiceCommandController(scope, backend)

        assertTrue(controller.requestNode(999L))
        scope.testScheduler.advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state is ServiceCommandController.CommandState.Error)
        val error = state as ServiceCommandController.CommandState.Error
        assertEquals(ServiceCommandController.CommandKind.NODE, error.kind)
        // 失败后可继续提交（不永久锁定）
        controller.acknowledge()
        assertFalse(controller.isBusy())
    }

    @Test
    fun `requestNode confirms profileId`() = runTest {
        val scope = this
        val backend = object : ServiceCommandController.Backend {
            override suspend fun start() = fakeStatus()
            override suspend fun stop() = fakeStatus(false, "stopped")
            override suspend fun setMode(mode: String) = fakeStatus()
            // 返回错误的 profileId（模拟未确认）
            override suspend fun selectNode(id: Long) = fakeStatus(profileId = 999L)
            override suspend fun queryStatus() = fakeStatus()
        }
        val controller = ServiceCommandController(scope, backend)

        assertTrue(controller.requestNode(123L))
        scope.testScheduler.advanceUntilIdle()

        // profileId 不匹配应返回 Error
        val state = controller.state.value
        assertTrue("expected Error for profileId mismatch, got $state",
            state is ServiceCommandController.CommandState.Error)
    }
}
