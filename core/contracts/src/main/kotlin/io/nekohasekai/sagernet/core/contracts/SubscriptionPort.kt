// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.contracts

/** 订阅拉取与解析。调度（WorkManager）属适配层，core 只调用用例。 */
interface SubscriptionPort {
    suspend fun refresh(subscriptionId: Long): SubscriptionResult

    suspend fun resolve(groupId: Long): SubscriptionResult

    suspend fun cleanup(): Int
}
