// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.domain

import io.nekohasekai.sagernet.core.contracts.CoreResult
import io.nekohasekai.sagernet.core.contracts.ModuleError
import io.nekohasekai.sagernet.core.contracts.ModuleStatus
import io.nekohasekai.sagernet.core.contracts.RootModulePort

/** U-15 / U-16 启停：eBPF 失败由模块侧回退 TUN，core 只认最终状态。 */
class ToggleServiceUseCase(
    private val root: RootModulePort,
    private val engine: CoreEngine,
) {
    suspend fun start(): CoreResult<ModuleStatus> = call { root.startOrReload() }

    suspend fun stop(): CoreResult<ModuleStatus> = call { root.stop() }

    private suspend fun call(block: suspend () -> ModuleStatus): CoreResult<ModuleStatus> =
        try {
            val status = block()
            engine.onStatus(status)
            CoreResult.Success(status)
        } catch (error: ModuleError) {
            engine.onFailure(error.code)
            CoreResult.Failure(error.code, "模块调用失败：${error.code}")
        } catch (error: Exception) {
            engine.onFailure(ROOT_UNAVAILABLE)
            CoreResult.Failure(ROOT_UNAVAILABLE, error.message ?: "模块不可用")
        }

    private companion object {
        const val ROOT_UNAVAILABLE = "root_unavailable"
    }
}
