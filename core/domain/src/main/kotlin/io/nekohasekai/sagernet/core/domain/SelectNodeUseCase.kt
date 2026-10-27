// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.domain

import io.nekohasekai.sagernet.core.contracts.CoreResult
import io.nekohasekai.sagernet.core.contracts.ModuleError
import io.nekohasekai.sagernet.core.contracts.ModuleStatus
import io.nekohasekai.sagernet.core.contracts.ProfileRepository
import io.nekohasekai.sagernet.core.contracts.RootModulePort

/**
 * U-03 切换节点。
 *
 * 规则：节点必须存在；选择经模块端口下发；成功后统一更新唯一状态源。
 * 界面不得自行改本地状态，也不得拼 wanboxctl 命令。
 */
class SelectNodeUseCase(
    private val root: RootModulePort,
    private val profiles: ProfileRepository,
    private val engine: CoreEngine,
) {
    suspend operator fun invoke(profileId: Long): CoreResult<ModuleStatus> {
        val profile = profiles.get(profileId)
            ?: return CoreResult.Failure("profile_not_found", "节点不存在：$profileId")

        val status = try {
            root.selectNode(profile.id)
        } catch (error: ModuleError) {
            engine.onFailure(error.code)
            return CoreResult.Failure(error.code, "模块拒绝切换：${error.code}")
        } catch (error: Exception) {
            engine.onFailure(ROOT_UNAVAILABLE)
            return CoreResult.Failure(ROOT_UNAVAILABLE, error.message ?: "模块调用失败")
        }

        profiles.select(profile.id)
        engine.onStatus(status)
        return CoreResult.Success(status)
    }

    private companion object {
        const val ROOT_UNAVAILABLE = "root_unavailable"
    }
}
