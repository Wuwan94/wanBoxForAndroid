// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.contracts

import kotlinx.coroutines.flow.Flow

/** 流量与速率只读流。数据来源（AIDL / 管理面）由适配层决定。 */
interface TrafficPort {
    fun observeTraffic(): Flow<TrafficSnapshot>

    fun observeSpeed(): Flow<SpeedSnapshot>
}
