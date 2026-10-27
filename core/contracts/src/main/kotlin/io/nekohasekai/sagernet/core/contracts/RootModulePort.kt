// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.contracts

import kotlinx.coroutines.flow.Flow

/**
 * 与 root 模块管理面的端口。
 *
 * 实现可先是 wanboxctl CLI 适配器，后续切换到回环 HTTPS 管理面
 * （Bearer token + 证书钉扎 + SSE），上层用例不感知差异。
 */
interface RootModulePort {
    suspend fun status(): ModuleStatus

    /** 对应声明中的 SSE 事件流；CLI 适配器可用轮询转成 Flow。 */
    fun events(): Flow<ModuleEvent>

    suspend fun startOrReload(startIfStopped: Boolean = true): ModuleStatus

    suspend fun stop(): ModuleStatus

    /** 对应 Clash API 选择器，节点热切换不经界面。 */
    suspend fun selectNode(profileId: Long): ModuleStatus

    suspend fun setAutoStart(enabled: Boolean): ModuleStatus

    suspend fun validateConfig(config: String): ModuleStatus

    suspend fun applyConfig(config: String, expectedRevision: String): ModuleStatus

    suspend fun dataUpdate(action: DataUpdateAction): ModuleStatus

    suspend fun logs(): List<ModuleLogLine>
}
