package io.wanbox.domain

import io.wanbox.contracts.dto.ConnectionState
import io.wanbox.contracts.dto.SubscriptionInfo
import io.wanbox.contracts.dto.SubscriptionUpdate
import kotlinx.coroutines.flow.first

// ---------------- UC-SUB-01 添加订阅 ----------------

/** 添加后立即拉取一次（首次拉取），并把结果（含失败退避）记回订阅。 */
suspend fun AppCore.addSubscription(
    url: String,
    name: String,
    autoUpdate: Boolean = true,
): SubscriptionInfo {
    val now = clock.now()
    val info = subscriptions.add(url, name, autoUpdate, now)
    val result = subscriptions.update(info.groupId, byUser = true, at = now)
    val failures = if (result.ok) 0 else 1
    subscriptions.recordResult(
        groupId = info.groupId,
        error = result.error,
        nextRetryAt = if (result.ok) 0L else now + BackoffPolicy.nextDelay(failures),
        at = now,
    )
    if (result.ok) {
        logger.info("core", "subscription ${info.groupId} added, first fetch +${result.added}")
        publish()
    } else {
        logger.warn("core", "subscription ${info.groupId} first fetch failed: ${result.error}")
    }
    return subscriptions.observe().first().firstOrNull { it.groupId == info.groupId } ?: info
}

// ---------------- UC-SUB-02 手动更新 ----------------

/**
 * 更新一次订阅。失败时不改动既有节点（由适配层事务保证），核心只更新退避与错误信息。
 * byUser = true 跳过到期判断（下拉刷新必须能立刻拉）。
 */
suspend fun AppCore.updateSubscription(groupId: Long, byUser: Boolean = true): SubscriptionUpdate {
    val info = subscriptions.observe().first().firstOrNull { it.groupId == groupId }
        ?: return SubscriptionUpdate.skipped(groupId, "订阅不存在")
    val now = clock.now()
    if (!byUser && !SubscriptionPolicy.isDue(info, now)) {
        return SubscriptionUpdate.skipped(groupId, "未到期")
    }

    val result = subscriptions.update(groupId, byUser, now)
    val failures = if (result.ok) 0 else info.consecutiveFailures + 1
    subscriptions.recordResult(
        groupId = groupId,
        error = result.error,
        nextRetryAt = if (result.ok) 0L else now + BackoffPolicy.nextDelay(failures),
        at = now,
    )
    if (result.ok) {
        logger.info("core", "subscription $groupId updated +${result.added} -${result.removed} ~${result.updated}")
    } else {
        logger.warn("core", "subscription $groupId update failed (#$failures): ${result.error}")
    }
    publish()
    return result
}

// ---------------- UC-SUB-03 定时更新 ----------------

/**
 * 定时任务入口。只处理到期且 autoUpdate 的组；开了 updateWhenConnectedOnly 时未连接直接跳过。
 * 返回每个组的处理结果，便于上层写日志或重排下次闹钟。
 */
suspend fun AppCore.runDueSubscriptions(now: Long = clock.now()): List<SubscriptionUpdate> {
    val snap = settings.snapshot().first()
    if (snap.updateWhenConnectedOnly && connectionState.value !is ConnectionState.Connected) {
        logger.info("core", "skip scheduled update: only-when-connected and currently idle")
        return emptyList()
    }
    val due = SubscriptionPolicy.due(subscriptions.observe().first(), now)
    if (due.isEmpty()) {
        logger.info("core", "scheduled update: nothing due")
        return emptyList()
    }
    return due.map { updateSubscription(it, byUser = false) }
}

// ---------------- UC-SUB-04 删除订阅 ----------------

/** 组与组内节点一并删除，避免留下无主节点。 */
suspend fun AppCore.removeSubscription(groupId: Long) {
    subscriptions.remove(groupId)
    val removedNodes = profiles.deleteByGroup(groupId)
    logger.info("core", "subscription $groupId removed with $removedNodes nodes")
    publish()
}

// ---------------- 纯函数策略 ----------------

/** 到期判定：autoUpdate + 已过间隔 + 不在退避窗口内。 */
object SubscriptionPolicy {

    fun isDue(info: SubscriptionInfo, nowMillis: Long): Boolean {
        if (!info.autoUpdate) return false
        if (info.nextRetryAt > 0 && nowMillis < info.nextRetryAt) return false
        val interval = info.updateIntervalMinutes.coerceAtLeast(1) * 60_000L
        return nowMillis - info.lastUpdated >= interval
    }

    fun due(list: List<SubscriptionInfo>, nowMillis: Long): List<Long> =
        list.filter { isDue(it, nowMillis) }.map { it.groupId }
}

/** 指数退避：1 分钟起，每次翻倍，上限 6 小时。 */
object BackoffPolicy {
    const val BASE_MS = 60_000L
    const val MAX_MS = 6 * 60 * 60 * 1000L

    fun nextDelay(failures: Int): Long {
        if (failures <= 0) return 0L
        val shift = (failures - 1).coerceAtMost(12)
        return (BASE_MS shl shift).coerceAtMost(MAX_MS)
    }
}
