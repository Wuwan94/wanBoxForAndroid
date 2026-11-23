// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页 UI 门的契约（v3.0.18-preview.3 复审 P1 的补齐）：只有「当前且可见的视图」
 * 才能改开关状态与摘要。
 *
 * 之前契约里写着 `AutoStartSyncUiGateTest` 覆盖了 Fragment 分离、视图/Activity 销毁
 * 与乱序结果，但仓库里根本没有这个文件——UI 门当时是 `collectLatest` 里的局部函数。
 * 现在判定抽成 [AutoStartUiGate]，每条判据各有一条独立断言，另有一条“全部满足时
 * 放行”，防止把门写成恒 false 也能过关。
 */
class AutoStartSyncUiGateTest {
    private val finished = AutoStartSync.State.Finished(7L, AutoStartSync.Outcome.Confirmed(true))
    private val pending = AutoStartSync.State.Pending(8L, false)

    private fun accepts(
        attached: Boolean = true,
        hasView: Boolean = true,
        activityFinishing: Boolean = false,
        activityDestroyed: Boolean = false,
        viewStarted: Boolean = true,
        emitted: AutoStartSync.State = finished,
        current: AutoStartSync.State = finished,
    ): Boolean = AutoStartUiGate.accepts(
        attached = attached,
        hasView = hasView,
        activityFinishing = activityFinishing,
        activityDestroyed = activityDestroyed,
        viewStarted = viewStarted,
        emitted = emitted,
        current = current,
    )

    @Test
    fun `a started attached view may render the current result`() {
        assertTrue(accepts())
    }

    @Test
    fun `a detached fragment cannot touch the settings page`() {
        assertFalse(accepts(attached = false))
    }

    @Test
    fun `a destroyed view cannot touch the settings page`() {
        assertFalse(accepts(hasView = false))
    }

    @Test
    fun `an activity that is finishing cannot touch the settings page`() {
        assertFalse(accepts(activityFinishing = true))
    }

    @Test
    fun `a destroyed activity cannot touch the settings page`() {
        assertFalse(accepts(activityDestroyed = true))
    }

    @Test
    fun `a view below started cannot touch the settings page`() {
        assertFalse(accepts(viewStarted = false))
    }

    @Test
    fun `a stale result cannot repaint over a newer request`() {
        assertFalse(accepts(emitted = finished, current = pending))
    }

    @Test
    fun `pending is replayed to the same view that may render a result`() {
        assertTrue(accepts(emitted = pending, current = pending))
        assertFalse(accepts(emitted = pending, current = AutoStartSync.State.Idle))
    }
}
