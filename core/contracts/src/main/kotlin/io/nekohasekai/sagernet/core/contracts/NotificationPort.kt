// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.contracts

import kotlinx.coroutines.flow.Flow

/** 通知、磁贴与桌面小组件：全部由快照驱动，界面不自行缓存业务状态。 */
interface NotificationPort {
    fun observeServiceState(): Flow<ServiceState>

    suspend fun updateNotification(state: ServiceState, traffic: TrafficSnapshot?)

    suspend fun setAutoStart(enabled: Boolean)
}
