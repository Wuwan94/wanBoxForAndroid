// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.fmt

/**
 * B7b/O7 严格手动锁定：策略组成员被手动锁定后，断连或超时 MUST NOT 自动切换，
 * 只有用户点「恢复自动」才能解除；锁定期间的失败 MUST 计入统计（调用方记入
 * BalancerMemberStats），MUST NOT 触发自动切换。
 *
 * 持久化：`DataStore.balancerManualLock`（configurationStore，运行时状态，
 * 不是 BalancerBean 记录字段——锁是运行时选择，不参与记录升级；
 * B7c 的层级字段仍按计划升 v7，本批不占 v7）。
 * 编码："<balancerId>:<memberId>;<balancerId>:<memberId>"，空串 = 全部自动。
 * 锁的身份是 (balancerId, memberId)；成员 tag 在配置生成/渲染时由成员列表
 * 现场解析，不进编码（tag 会随重命名/重分配变化，id 不会）。
 */
data class BalancerManualLock(val balancerId: Long, val memberId: Long)

/** 锁定期间一次失败/断连/超时的处理结果（纯契约，不代替核心的实际选路）。 */
data class LockFailureOutcome(
    /** 锁是否保持（锁定期间 MUST 为 true）。 */
    val staysLocked: Boolean,
    /** 该失败是否计入统计（MUST 为 true，调用方记入 BalancerMemberStats）。 */
    val failureCounted: Boolean,
    /** 是否触发自动切换（锁定期间 MUST 为 false）。 */
    val autoSwitchTriggered: Boolean,
)

class BalancerLockState(val locks: Map<Long, BalancerManualLock> = emptyMap()) {

    /** 锁定某策略组的指定成员（替换该组此前的锁；非法 id 直接忽略）。 */
    fun lock(balancerId: Long, memberId: Long): BalancerLockState {
        if (balancerId <= 0L || memberId <= 0L) return this
        return BalancerLockState(locks + (balancerId to BalancerManualLock(balancerId, memberId)))
    }

    /** 用户点「恢复自动」：只解除该组的锁，其余组不动。 */
    fun resumeAuto(balancerId: Long): BalancerLockState {
        if (balancerId !in locks) return this
        return BalancerLockState(locks - balancerId)
    }

    fun lockedMember(balancerId: Long): BalancerManualLock? = locks[balancerId]

    fun isLocked(balancerId: Long): Boolean = locks.containsKey(balancerId)

    /** 该组当前是否允许自动切换：锁定期间 MUST NOT。 */
    fun shouldAutoSwitch(balancerId: Long): Boolean = !isLocked(balancerId)

    /**
     * 断连/超时/探测失败发生时的处理：锁保持、失败计入统计、不自动切换。
     * 未锁定时失败照常允许自动切换（由核心 urltest/loadbalance 执行）。
     */
    fun onFailure(balancerId: Long): LockFailureOutcome =
        if (isLocked(balancerId)) {
            LockFailureOutcome(staysLocked = true, failureCounted = true, autoSwitchTriggered = false)
        } else {
            LockFailureOutcome(staysLocked = false, failureCounted = true, autoSwitchTriggered = true)
        }

    fun encode(): String =
        locks.entries.sortedBy { it.key }.joinToString(";") { (balancerId, lock) ->
            "$balancerId:${lock.memberId}"
        }

    companion object {
        fun decode(raw: String?): BalancerLockState {
            if (raw.isNullOrBlank()) return BalancerLockState()
            val parsed = LinkedHashMap<Long, BalancerManualLock>()
            for (entry in raw.split(";")) {
                val parts = entry.split(":")
                if (parts.size != 2) continue
                val balancerId = parts[0].trim().toLongOrNull() ?: continue
                val memberId = parts[1].trim().toLongOrNull() ?: continue
                if (balancerId <= 0L || memberId <= 0L) continue
                parsed[balancerId] = BalancerManualLock(balancerId, memberId)
            }
            return BalancerLockState(parsed)
        }

        fun empty(): BalancerLockState = BalancerLockState()
    }
}

/**
 * 配置生成层取锁定成员的链 tag：锁定成员在成员列表里才返回其 tag，
 * 不在时返回 null（调用方回落自动生成，锁仍保留、横幅红字提示锁定节点不可用）。
 * memberTags 必须是 (memberId, chainTag) 对照，与 buildChain 的成员顺序一致。
 */
fun lockedMemberTag(
    memberTags: List<Pair<Long, String>>,
    state: BalancerLockState,
    balancerId: Long,
): String? {
    val lock = state.lockedMember(balancerId) ?: return null
    return memberTags.firstOrNull { it.first == lock.memberId }?.second
}

/**
 * 快照成员 tag → profile id 的解析（统计页锁定按钮与横幅共用）：
 * 1. profileTags 反查（`node tags` 的 id→tag，覆盖非隔离链成员，含重名加后缀的情况）；
 * 2. 展示名精确匹配（隔离链成员不在 profileTagMap 里，但单跳成员的链 tag
 *    就是 readableTag(displayName)，无重名时与展示名相同；重名时第 1 条已覆盖）。
 * 两条都找不到时返回 null——该成员行不可锁定，不得猜 id。
 *
 * @param memberNamesById 该策略组已解析成员的 id→展示名（BalancerMemberResolver 结果 + DB）。
 */
fun resolveSnapshotMemberId(
    memberTag: String,
    tagToProfileId: Map<String, Long>,
    memberNamesById: Map<Long, String>,
): Long? {
    tagToProfileId[memberTag]?.let { return it }
    val matches = memberNamesById.filter { it.value == memberTag }.keys
    return if (matches.size == 1) matches.single() else null
}

/**
 * 锁定成员 id → 其在运行快照中的 tag（横幅“锁定成员不可用”判定共用）：
 * 1. profileTags 正查，且该 tag 确实在本组成员列表里；
 * 2. 展示名，且该展示名确在本组成员列表里（隔离链成员的回退）。
 * 找不到时返回 null——调用方按“锁定成员不可用”处理，不得猜 tag。
 */
fun snapshotTagForMember(
    memberId: Long,
    tagsById: Map<Long, String>,
    memberNamesById: Map<Long, String>,
    groupMembers: List<String>,
): String? {
    val byTag = tagsById[memberId]
    if (byTag != null && byTag in groupMembers) return byTag
    val byName = memberNamesById[memberId]
    if (byName != null && byName in groupMembers) return byName
    return null
}
