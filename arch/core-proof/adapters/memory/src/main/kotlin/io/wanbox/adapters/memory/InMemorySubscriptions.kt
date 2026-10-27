package io.wanbox.adapters.memory

import io.wanbox.contracts.SubscriptionGateway
import io.wanbox.contracts.dto.ProfileSpec
import io.wanbox.contracts.dto.SubscriptionInfo
import io.wanbox.contracts.dto.SubscriptionUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 内存订阅网关。
 * - [enqueue] 预置下次 update 的结果，用来模拟"拉取成功/返回 500"。
 * - 成功时向 [nodes] 写入对应数量的节点；失败时一个节点都不动——这条就是 UC-SUB-02 的
 *   "失败保留旧数据" 在适配层的行为契约。
 */
class InMemorySubscriptions(
    initial: List<SubscriptionInfo> = emptyList(),
    private val nodes: InMemoryProfileRepository? = null,
) : SubscriptionGateway {

    private val items = MutableStateFlow(initial)
    private val scripted = ArrayDeque<SubscriptionUpdate>()

    /** 每次 update 的 (groupId, byUser)，用于断言"该拉的时候才拉"。 */
    val updateCalls = mutableListOf<Pair<Long, Boolean>>()

    val all: List<SubscriptionInfo> get() = items.value

    fun enqueue(vararg results: SubscriptionUpdate) {
        results.forEach { scripted.addLast(it) }
    }

    override fun observe(): Flow<List<SubscriptionInfo>> = items.asStateFlow()

    override suspend fun add(url: String, name: String, autoUpdate: Boolean, at: Long): SubscriptionInfo {
        val info = SubscriptionInfo(
            groupId = (items.value.maxOfOrNull { it.groupId } ?: 0L) + 1L,
            name = name,
            url = url,
            lastUpdated = at,
            autoUpdate = autoUpdate,
            updateIntervalMinutes = 60,
        )
        items.value = items.value + info
        return info
    }

    override suspend fun update(groupId: Long, byUser: Boolean, at: Long): SubscriptionUpdate {
        updateCalls += groupId to byUser
        val result = scripted.removeFirstOrNull()
            ?: SubscriptionUpdate(groupId, error = "no scripted result")
        if (result.ok && nodes != null) {
            repeat(result.added) { index ->
                nodes.create(
                    ProfileSpec(
                        protocol = "vmess",
                        name = "g$groupId-node${index + 1}",
                        fields = mapOf(
                            "server" to "g$groupId-n$index.example",
                            "port" to "443",
                            "uuid" to "$groupId-$index",
                        ),
                        groupId = groupId,
                    )
                )
            }
        }
        return result
    }

    override suspend fun remove(groupId: Long) {
        items.value = items.value.filterNot { it.groupId == groupId }
    }

    override suspend fun recordResult(groupId: Long, error: String?, nextRetryAt: Long, at: Long) {
        items.value = items.value.map { info ->
            if (info.groupId != groupId) info
            else info.copy(
                lastUpdated = if (error == null) at else info.lastUpdated,
                consecutiveFailures = if (error == null) 0 else info.consecutiveFailures + 1,
                lastError = error,
                nextRetryAt = nextRetryAt,
            )
        }
    }
}
