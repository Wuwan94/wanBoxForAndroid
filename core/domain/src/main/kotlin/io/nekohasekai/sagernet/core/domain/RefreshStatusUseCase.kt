// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.domain

import io.nekohasekai.sagernet.core.contracts.CoreResult
import io.nekohasekai.sagernet.core.contracts.ModuleError
import io.nekohasekai.sagernet.core.contracts.ModuleStatus
import io.nekohasekai.sagernet.core.contracts.RootModulePort

/** U-17 状态查询：进入界面或收到唤醒时拉一次，结果统一进唯一状态源。 */
class RefreshStatusUseCase(
    private val root: RootModulePort,
    private val engine: CoreEngine,
) {
    suspend operator fun invoke(): CoreResult<ModuleStatus> = try {
        val status = root.status()
        engine.onStatus(status)
        CoreResult.Success(status)
    } catch (error: ModuleError) {
        engine.onFailure(error.code)
        CoreResult.Failure(error.code, "状态查询失败：${error.code}")
    } catch (error: Exception) {
        engine.onFailure(ROOT_UNAVAILABLE)
        CoreResult.Failure(ROOT_UNAVAILABLE, error.message ?: "模块不可用")
    }

    private companion object {
        const val ROOT_UNAVAILABLE = "root_unavailable"
    }
}
