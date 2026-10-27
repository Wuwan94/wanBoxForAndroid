// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.domain

import io.nekohasekai.sagernet.core.contracts.CoreResult
import io.nekohasekai.sagernet.core.contracts.ModuleError
import io.nekohasekai.sagernet.core.contracts.ModuleStatus
import io.nekohasekai.sagernet.core.contracts.ProfileSummary
import io.nekohasekai.sagernet.core.contracts.ProfileType
import io.nekohasekai.sagernet.core.contracts.Selection
import io.nekohasekai.sagernet.core.testing.FakeProfileRepository
import io.nekohasekai.sagernet.core.testing.FakeRootModulePort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** U-03 切换节点：规则在 core，端口可替换。 */
class SelectNodeUseCaseTest {

    private val profile = ProfileSummary(
        id = 7L,
        name = "香港 01",
        type = ProfileType.SHADOWSOCKS,
        groupId = 1L,
    )

    private fun status(profileId: Long) = ModuleStatus(
        running = true,
        revision = "rev-1",
        selection = Selection(profileId, 1L),
        backend = "eBPF",
        storeReady = true,
    )

    @Test
    fun `选择成功后下发端口并更新唯一状态源`() = runBlocking {
        val root = FakeRootModulePort(status(profile.id))
        val profiles = FakeProfileRepository(listOf(profile))
        val engine = CoreEngine()
        val useCase = SelectNodeUseCase(root, profiles, engine)

        val result = useCase(profile.id)

        assertTrue(result is CoreResult.Success)
        assertEquals(listOf(profile.id), root.selectedNodes)
        assertEquals(listOf(profile.id), profiles.selectedCalls)
        assertEquals(profile.id, engine.state.value.selection?.profileId)
        assertEquals("eBPF", engine.state.value.status?.backend)
    }

    @Test
    fun `节点不存在时不调用模块`() = runBlocking {
        val root = FakeRootModulePort()
        val engine = CoreEngine()
        val useCase = SelectNodeUseCase(root, FakeProfileRepository(), engine)

        val result = useCase(404L)

        assertTrue(result is CoreResult.Failure)
        assertEquals("profile_not_found", (result as CoreResult.Failure).code)
        assertTrue(root.selectedNodes.isEmpty())
        assertEquals(null, engine.state.value.selection)
    }

    @Test
    fun `模块错误码映射到失败结果与状态`() = runBlocking {
        val root = FakeRootModulePort()
        root.failure = ModuleError("node_switch_rejected")
        val engine = CoreEngine()
        val useCase = SelectNodeUseCase(root, FakeProfileRepository(listOf(profile)), engine)

        val result = useCase(profile.id)

        assertTrue(result is CoreResult.Failure)
        assertEquals("node_switch_rejected", (result as CoreResult.Failure).code)
        assertEquals("node_switch_rejected", engine.state.value.lastErrorCode)
    }

    @Test
    fun `成功后会清掉上一次错误`() = runBlocking {
        val root = FakeRootModulePort()
        val engine = CoreEngine()
        val useCase = SelectNodeUseCase(root, FakeProfileRepository(listOf(profile)), engine)
        engine.onFailure("stale_error")

        useCase(profile.id)

        assertEquals(null, engine.state.value.lastErrorCode)
    }
}
