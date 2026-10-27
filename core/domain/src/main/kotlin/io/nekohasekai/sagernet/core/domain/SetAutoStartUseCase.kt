// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.domain

import io.nekohasekai.sagernet.core.contracts.CoreResult
import io.nekohasekai.sagernet.core.contracts.ModuleError
import io.nekohasekai.sagernet.core.contracts.ModuleStatus
import io.nekohasekai.sagernet.core.contracts.RootModulePort
import io.nekohasekai.sagernet.core.contracts.SettingsPort
import kotlinx.coroutines.flow.first

/** U-18 自启开关：偏好与模块状态必须同时更新，避免两处真值不一致。 */
class SetAutoStartUseCase(
    private val root: RootModulePort,
    private val settings: SettingsPort,
    private val engine: CoreEngine,
) {
    suspend operator fun invoke(enabled: Boolean): CoreResult<ModuleStatus> {
        val status = try {
            root.setAutoStart(enabled)
        } catch (error: ModuleError) {
            engine.onFailure(error.code)
            return CoreResult.Failure(error.code, "模块拒绝修改自启：${error.code}")
        } catch (error: Exception) {
            engine.onFailure(ROOT_UNAVAILABLE)
            return CoreResult.Failure(ROOT_UNAVAILABLE, error.message ?: "模块调用失败")
        }

        // 只在模块接受后才落偏好，避免界面显示已开而模块未开。
        val latest = settings.observeSettings().first()
        settings.update(latest.copy(autoStart = enabled))
        engine.onStatus(status)
        return CoreResult.Success(status)
    }

    private companion object {
        const val ROOT_UNAVAILABLE = "root_unavailable"
    }
}
