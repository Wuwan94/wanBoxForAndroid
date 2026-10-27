// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.domain

import io.nekohasekai.sagernet.core.contracts.CoreSnapshot
import io.nekohasekai.sagernet.core.contracts.ModuleEvent
import io.nekohasekai.sagernet.core.contracts.ModuleStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 唯一状态源。界面、通知、磁贴都只订阅 [state]，不各自缓存业务状态。
 *
 * 不持有协程作用域：事件由适配层收集后投递（[onEvent]），
 * 这样 core 的用例可以在 JVM 单测里同步驱动，不需要 Android 生命周期。
 */
class CoreEngine {

    private val mutableState = MutableStateFlow(
        CoreSnapshot(status = null, selection = null, traffic = null, lastErrorCode = null),
    )

    val state: StateFlow<CoreSnapshot> = mutableState.asStateFlow()

    fun onStatus(status: ModuleStatus) {
        mutableState.update {
            it.copy(status = status, selection = status.selection, lastErrorCode = null)
        }
    }

    fun onEvent(event: ModuleEvent) {
        when (event) {
            is ModuleEvent.StateChanged -> onStatus(event.status)

            is ModuleEvent.SelectionChanged -> mutableState.update {
                it.copy(selection = event.selection)
            }

            is ModuleEvent.Traffic -> mutableState.update {
                it.copy(traffic = event.snapshot)
            }

            is ModuleEvent.Failed -> onFailure(event.error.code)
        }
    }

    fun onFailure(code: String) {
        mutableState.update { it.copy(lastErrorCode = code) }
    }
}
