// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.testing

import io.nekohasekai.sagernet.core.contracts.ModuleError
import io.nekohasekai.sagernet.core.contracts.SubscriptionPort
import io.nekohasekai.sagernet.core.contracts.SubscriptionResult

/** 可脚本化的订阅端口：记录被解析的组，可按需返回成功、部分失败或抛错。 */
class FakeSubscriptionPort(
    private var nextResult: SubscriptionResult = SubscriptionResult(
        updated = 0,
        failed = 0,
        skipped = 0,
        errors = emptyList(),
    ),
) : SubscriptionPort {

    val resolvedGroups = mutableListOf<Long>()
    var failure: ModuleError? = null

    fun setNext(result: SubscriptionResult) {
        nextResult = result
    }

    override suspend fun refresh(subscriptionId: Long): SubscriptionResult {
        failure?.let { throw it }
        return nextResult
    }

    override suspend fun resolve(groupId: Long): SubscriptionResult {
        failure?.let { throw it }
        resolvedGroups += groupId
        return nextResult
    }

    override suspend fun cleanup(): Int = 0
}
