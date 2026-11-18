// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg.proto

/**
 * B7b/O7 策略组成员统计：失败计数与最近报错/延迟/检查时间。
 * 进程内内存态（与 UrlTestCache 同级）：统计只服务于统计页展示与横幅红字提示，
 * 不进持久化；进程重启后由下一次检查重建。锁定期间的失败同样记入此处
 * （测速/探测失败不分锁定与否都调 recordFailure）。
 * failures 为“自上次成功以来的连续失败次数”：成功后清零并记延迟、清掉旧报错。
 */
data class BalancerMemberStat(
    val groupTag: String,
    val member: String,
    val failures: Int,
    val lastError: String?,
    val lastLatencyMs: Int?,
    val lastCheckAtMs: Long,
)

class BalancerMemberStats {
    private val guard = Any()
    private val stats = HashMap<Pair<String, String>, BalancerMemberStat>()

    fun recordFailure(
        groupTag: String,
        member: String,
        error: String?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(guard) {
            val key = groupTag to member
            val prev = stats[key]
            stats[key] = BalancerMemberStat(
                groupTag = groupTag,
                member = member,
                failures = (prev?.failures ?: 0) + 1,
                lastError = error?.takeIf { it.isNotBlank() } ?: prev?.lastError,
                lastLatencyMs = prev?.lastLatencyMs,
                lastCheckAtMs = nowMs,
            )
        }
    }

    fun recordSuccess(
        groupTag: String,
        member: String,
        latencyMs: Int?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(guard) {
            val key = groupTag to member
            val prev = stats[key]
            stats[key] = BalancerMemberStat(
                groupTag = groupTag,
                member = member,
                failures = 0,
                lastError = null,
                lastLatencyMs = latencyMs ?: prev?.lastLatencyMs,
                lastCheckAtMs = nowMs,
            )
        }
    }

    fun stat(groupTag: String, member: String): BalancerMemberStat? =
        synchronized(guard) { stats[groupTag to member] }

    fun failures(groupTag: String, member: String): Int =
        stat(groupTag, member)?.failures ?: 0

    fun snapshot(groupTag: String): List<BalancerMemberStat> = synchronized(guard) {
        stats.values.filter { it.groupTag == groupTag }.sortedBy { it.member }
    }

    fun clear(groupTag: String) {
        synchronized(guard) { stats.keys.removeAll { it.first == groupTag } }
    }

    fun clearAll() {
        synchronized(guard) { stats.clear() }
    }
}

/** 统计页与主页横幅共用的进程内单例（测试请直接 new BalancerMemberStats()）。 */
object BalancerStats {
    val store = BalancerMemberStats()
}
