// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.ui

/** B7b/O7 主页策略组横幅的纯展示模型：Fragment 只做字符串装配、着色与点击跳转。 */
enum class BalancerBannerMode { AUTO, LOCKED }

data class BalancerBannerState(
    /** 未连接或当前不是策略组时为 false，横幅隐藏。 */
    val visible: Boolean,
    val mode: BalancerBannerMode,
    /** 策略名原值（leastPing 等），Fragment 再映射到策略文案。 */
    val strategy: String?,
    /** 正在使用/锁定的成员展示名；未知时为空串（横幅仍显示模式）。 */
    val carrierName: String,
    /** 承载节点的实时延迟（毫秒），无数据时为 null，展示为 "—"。 */
    val latencyMs: Int?,
    /** 锁定成员不可用时为 true，横幅 MUST 红字高亮提示。 */
    val lockedUnavailable: Boolean,
    /** 该策略组在运行快照中的 tag（点横幅进统计页时可直接定位），未知时为 null。 */
    val groupTag: String?,
)

/**
 * @param connected 服务是否已连接
 * @param isBalancer 当前节点是否为策略组（BalancerBean）
 * @param strategy 策略名原值
 * @param lockedMemberName 锁定成员展示名，未锁定为 null
 * @param currentMemberName 快照当前承载成员（now）展示名，未知为 null
 * @param currentLatencyMs 承载节点的实时延迟（毫秒），无数据为 null
 * @param lockedAvailable 锁定成员是否可用（调用方判定：在快照成员列表里且无连续失败）
 * @param groupTag 该策略组在运行快照中的 tag，未知为 null
 */
fun buildBalancerBanner(
    connected: Boolean,
    isBalancer: Boolean,
    strategy: String?,
    lockedMemberName: String?,
    currentMemberName: String?,
    currentLatencyMs: Int?,
    lockedAvailable: Boolean,
    groupTag: String?,
): BalancerBannerState {
    if (!connected || !isBalancer) {
        return BalancerBannerState(
            visible = false,
            mode = BalancerBannerMode.AUTO,
            strategy = strategy,
            carrierName = "",
            latencyMs = null,
            lockedUnavailable = false,
            groupTag = null,
        )
    }
    val locked = lockedMemberName != null
    return BalancerBannerState(
        visible = true,
        mode = if (locked) BalancerBannerMode.LOCKED else BalancerBannerMode.AUTO,
        strategy = strategy,
        carrierName = lockedMemberName ?: currentMemberName ?: "",
        latencyMs = currentLatencyMs,
        lockedUnavailable = locked && !lockedAvailable,
        groupTag = groupTag,
    )
}
