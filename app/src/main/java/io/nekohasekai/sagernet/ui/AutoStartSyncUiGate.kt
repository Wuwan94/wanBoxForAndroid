// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.bg.AutoStartTapState

/**
 * 自启同步结果落地到界面之前的边界判定。
 *
 * 模块写入是应用级的，离开页面也不能丢；这里只决定「这条结果能不能碰界面」。
 * 请求乱序由 [AutoStartTapState.finish] 裁决，必须在判定界面前无条件执行一次，
 * 否则快速连续切换后 pending 会残留并影响下一次选择。
 */
internal class AutoStartSyncUiGate(
    private val uiAvailable: () -> Boolean,
) {
    /** 当前是否允许更新已销毁/已分离的界面。 */
    fun canTouchUi(): Boolean = uiAvailable()

    /** 返回 true 仅当结果仍属于当前选择且界面可用。 */
    fun finishResult(sequence: Long, taps: AutoStartTapState): Boolean {
        val fresh = taps.finish(sequence)
        return fresh && canTouchUi()
    }
}
