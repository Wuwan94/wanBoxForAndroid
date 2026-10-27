// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.domain

import io.nekohasekai.sagernet.core.contracts.CoreResult
import io.nekohasekai.sagernet.core.contracts.ModuleError
import io.nekohasekai.sagernet.core.contracts.ModuleStatus
import io.nekohasekai.sagernet.core.contracts.Selection
import io.nekohasekai.sagernet.core.contracts.SubscriptionResult
import io.nekohasekai.sagernet.core.testing.FakeRootModulePort
import io.nekohasekai.sagernet.core.testing.FakeSubscriptionPort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** U-17 状态查询、U-07 订阅解析。 */
class RefreshAndSubscriptionTest {

    private fun status(revision: String) = ModuleStatus(
        running = true,
        revision = revision,
        selection = Selection(9L, 2L),
        backend = "eBPF",
        storeReady = true,
    )

    @Test
    fun `状态查询成功后写入快照`() = runBlocking {
        val root = FakeRootModulePort(status("rev-42"))
        val engine = CoreEngine()

        val result = RefreshStatusUseCase(root, engine)()

        assertTrue(result is CoreResult.Success)
        assertEquals("rev-42", engine.state.value.status?.revision)
        assertEquals(9L, engine.state.value.selection?.profileId)
        assertEquals(null, engine.state.value.lastErrorCode)
    }

    @Test
    fun `状态查询失败时保留旧快照并记录错误`() = runBlocking {
        val root = FakeRootModulePort(status("rev-42"))
        val engine = CoreEngine()
        engine.onStatus(status("rev-42"))

        root.failure = ModuleError("module_timeout")
        val result = RefreshStatusUseCase(root, engine)()

        assertTrue(result is CoreResult.Failure)
        assertEquals("module_timeout", (result as CoreResult.Failure).code)
        assertEquals("rev-42", engine.state.value.status?.revision)
        assertEquals("module_timeout", engine.state.value.lastErrorCode)
    }

    @Test
    fun `订阅解析成功返回统计`() = runBlocking {
        val port = FakeSubscriptionPort()
        port.setNext(SubscriptionResult(updated = 12, failed = 0, skipped = 3, errors = emptyList()))
        val engine = CoreEngine()

        val result = ImportSubscriptionUseCase(port, engine)(5L)

        assertTrue(result is CoreResult.Success)
        assertEquals(12, (result as CoreResult.Success).value.updated)
        assertEquals(listOf(5L), port.resolvedGroups)
        assertEquals(null, engine.state.value.lastErrorCode)
    }

    @Test
    fun `订阅部分失败仍返回成功但记录错误码`() = runBlocking {
        val port = FakeSubscriptionPort()
        port.setNext(SubscriptionResult(updated = 8, failed = 2, skipped = 0, errors = emptyList()))
        val engine = CoreEngine()

        val result = ImportSubscriptionUseCase(port, engine)(6L)

        assertTrue(result is CoreResult.Success)
        assertEquals(8, (result as CoreResult.Success).value.updated)
        assertEquals("subscription_partial_failure", engine.state.value.lastErrorCode)
    }

    @Test
    fun `订阅整体失败映射为失败结果`() = runBlocking {
        val port = FakeSubscriptionPort()
        port.failure = ModuleError("subscription_parse_failed")
        val engine = CoreEngine()

        val result = ImportSubscriptionUseCase(port, engine)(7L)

        assertTrue(result is CoreResult.Failure)
        assertEquals("subscription_parse_failed", (result as CoreResult.Failure).code)
        assertEquals("subscription_parse_failed", engine.state.value.lastErrorCode)
    }
}
