// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.domain

import io.nekohasekai.sagernet.core.contracts.CoreResult
import io.nekohasekai.sagernet.core.contracts.ModuleError
import io.nekohasekai.sagernet.core.contracts.ModuleEvent
import io.nekohasekai.sagernet.core.contracts.ModuleStatus
import io.nekohasekai.sagernet.core.contracts.Selection
import io.nekohasekai.sagernet.core.contracts.TrafficData
import io.nekohasekai.sagernet.core.contracts.TrafficSnapshot
import io.nekohasekai.sagernet.core.testing.FakeRootModulePort
import io.nekohasekai.sagernet.core.testing.FakeSettingsPort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** U-15 / U-16 / U-18 与状态归约。 */
class ServiceControlTest {

    private fun running() = ModuleStatus(
        running = true,
        revision = "rev-1",
        selection = Selection(3L, 1L),
        backend = "TUN",
        storeReady = true,
    )

    @Test
    fun `自启开启时模块与偏好同时更新`() = runBlocking {
        val root = FakeRootModulePort()
        val settings = FakeSettingsPort()
        val engine = CoreEngine()

        val result = SetAutoStartUseCase(root, settings, engine)(true)

        assertTrue(result is CoreResult.Success)
        assertEquals(listOf(true), root.autoStartCalls)
        assertTrue(settings.current.autoStart)
        assertEquals(1, settings.updates.size)
    }

    @Test
    fun `模块拒绝自启时不写偏好`() = runBlocking {
        val root = FakeRootModulePort()
        root.failure = ModuleError("autostart_rejected")
        val settings = FakeSettingsPort()
        val engine = CoreEngine()

        val result = SetAutoStartUseCase(root, settings, engine)(true)

        assertTrue(result is CoreResult.Failure)
        assertFalse(settings.current.autoStart)
        assertTrue(settings.updates.isEmpty())
        assertEquals("autostart_rejected", engine.state.value.lastErrorCode)
    }

    @Test
    fun `启停走模块端口并刷新状态`() = runBlocking {
        val root = FakeRootModulePort()
        val engine = CoreEngine()
        val useCase = ToggleServiceUseCase(root, engine)

        useCase.start()
        assertTrue(engine.state.value.status?.running == true)

        useCase.stop()
        assertFalse(engine.state.value.status?.running ?: true)
        assertEquals(1, root.startCalls)
        assertEquals(1, root.stopCalls)
    }

    @Test
    fun `状态事件更新快照`() {
        val engine = CoreEngine()

        engine.onEvent(ModuleEvent.StateChanged(running()))

        assertEquals("rev-1", engine.state.value.status?.revision)
        assertEquals(3L, engine.state.value.selection?.profileId)
    }

    @Test
    fun `流量事件只改流量字段`() {
        val engine = CoreEngine()
        engine.onEvent(ModuleEvent.StateChanged(running()))
        val traffic = TrafficSnapshot(TrafficData(rx = 100, tx = 50), perUid = emptyMap())

        engine.onEvent(ModuleEvent.Traffic(traffic))

        assertEquals(100L, engine.state.value.traffic?.total?.rx)
        assertEquals("rev-1", engine.state.value.status?.revision)
    }

    @Test
    fun `失败事件记录错误码且不影响已有选择`() {
        val engine = CoreEngine()
        engine.onEvent(ModuleEvent.StateChanged(running()))

        engine.onEvent(ModuleEvent.Failed(ModuleError("module_timeout")))

        assertEquals("module_timeout", engine.state.value.lastErrorCode)
        assertEquals(3L, engine.state.value.selection?.profileId)
    }
}
