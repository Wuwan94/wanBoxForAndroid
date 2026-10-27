// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.domain

import io.nekohasekai.sagernet.core.contracts.CoreResult
import io.nekohasekai.sagernet.core.contracts.ModuleError
import io.nekohasekai.sagernet.core.contracts.SubscriptionPort
import io.nekohasekai.sagernet.core.contracts.SubscriptionResult

/**
 * U-07 订阅解析与节点展开。
 *
 * 调度（WorkManager / 定时）属适配层；这里只表达"解析一个组"的业务语义。
 * 部分失败不吞掉成功条目：返回 Success，同时把失败码写进状态供界面提示。
 */
class ImportSubscriptionUseCase(
    private val subscriptions: SubscriptionPort,
    private val engine: CoreEngine,
) {
    suspend operator fun invoke(groupId: Long): CoreResult<SubscriptionResult> = try {
        val result = subscriptions.resolve(groupId)
        if (result.failed > 0) {
            engine.onFailure(PARTIAL_FAILURE)
        }
        CoreResult.Success(result)
    } catch (error: ModuleError) {
        engine.onFailure(error.code)
        CoreResult.Failure(error.code, "订阅解析失败：${error.code}")
    } catch (error: Exception) {
        engine.onFailure(SUBSCRIPTION_UNAVAILABLE)
        CoreResult.Failure(SUBSCRIPTION_UNAVAILABLE, error.message ?: "订阅服务不可用")
    }

    private companion object {
        const val PARTIAL_FAILURE = "subscription_partial_failure"
        const val SUBSCRIPTION_UNAVAILABLE = "subscription_unavailable"
    }
}
