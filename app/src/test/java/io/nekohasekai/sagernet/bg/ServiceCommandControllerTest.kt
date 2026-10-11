// @author 雾晚
package io.nekohasekai.sagernet.bg

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
            // @author 雾晚：fake 真实返回 starting（非终态）
            // requestStart 必须自行校验 connected，不通过则 Error
            override suspend fun start(): RootModuleClient.Status {
                val s = fakeStatus(connected = false, phase = "starting")
                // 模拟 RealBackend 的校验逻辑
                if (!s.connected) throw IllegalStateException("start_not_confirmed")
                return s
            }
            override suspend fun stop(): RootModuleClient.Status {
                val s = fakeStatus(false, "starting")
                if (s.phase != "stopped") throw IllegalStateException("stop_not_confirmed")
                return s
            }
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
