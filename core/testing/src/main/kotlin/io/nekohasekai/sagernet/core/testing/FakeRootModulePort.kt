// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.testing

import io.nekohasekai.sagernet.core.contracts.ModuleError
import io.nekohasekai.sagernet.core.contracts.ModuleEvent
import io.nekohasekai.sagernet.core.contracts.ModuleStatus
import io.nekohasekai.sagernet.core.contracts.RootModulePort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** 可脚本化的模块端口：记录调用、可注入失败、可手动投递事件。 */
class FakeRootModulePort(
    private var current: ModuleStatus = ModuleStatus(
        running = false,
        revision = null,
        selection = null,
        backend = null,
        storeReady = false,
    ),
) : RootModulePort {

    val selectedNodes = mutableListOf<Long>()
    val autoStartCalls = mutableListOf<Boolean>()
    var startCalls = 0
        private set
    var stopCalls = 0
        private set

    /** 非空时下一次调用抛出该错误，用于验证错误映射。 */
    var failure: ModuleError? = null

    private val eventFlow = MutableSharedFlow<ModuleEvent>(extraBufferCapacity = 16)

    suspend fun emit(event: ModuleEvent) {
        eventFlow.emit(event)
    }

    fun setStatus(status: ModuleStatus) {
        current = status
    }

    override suspend fun status(): ModuleStatus = current

    override fun events(): Flow<ModuleEvent> = eventFlow

    override suspend fun startOrReload(startIfStopped: Boolean): ModuleStatus {
        startCalls++
        throwIfNeeded()
        current = current.copy(running = true)
        return current
    }

    override suspend fun stop(): ModuleStatus {
        stopCalls++
        throwIfNeeded()
        current = current.copy(running = false)
        return current
    }

    override suspend fun selectNode(profileId: Long): ModuleStatus {
        throwIfNeeded()
        selectedNodes += profileId
        current = current.copy(
            running = true,
            selection = io.nekohasekai.sagernet.core.contracts.Selection(profileId, null),
        )
        return current
    }

    override suspend fun setAutoStart(enabled: Boolean): ModuleStatus {
        throwIfNeeded()
        autoStartCalls += enabled
        return current
    }

    override suspend fun validateConfig(config: String): ModuleStatus = current

    override suspend fun applyConfig(config: String, expectedRevision: String): ModuleStatus = current

    override suspend fun dataUpdate(action: io.nekohasekai.sagernet.core.contracts.DataUpdateAction): ModuleStatus = current

    override suspend fun logs(): List<io.nekohasekai.sagernet.core.contracts.ModuleLogLine> = emptyList()

    private fun throwIfNeeded() {
        failure?.let { throw it }
    }
}
