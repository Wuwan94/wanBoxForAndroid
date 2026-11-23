// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.bg.AutoStartSync
import io.nekohasekai.sagernet.bg.AutoStartTapState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面边界回归：Activity 仍存活但 Fragment 已分离/视图已销毁时，失败回调不得
 * 触碰旧界面（当前实现会在失败分支调用 Fragment.getString() 抛 IllegalStateException）。
 *
 * 这里用可以模拟 attached/view/activity 三种状态的宿主替身，在「能否落地到界面」
 * 这一边界断言，而不是只验证内部函数是否被调用。
 */
class AutoStartSyncUiGateTest {
    private class FakeUi {
        var attached = true
        var viewAlive = true
        var activityAlive = true
        val applied = mutableListOf<AutoStartSync.Outcome>()

        fun available(): Boolean = attached && viewAlive && activityAlive

        fun apply(outcome: AutoStartSync.Outcome) {
            check(available()) { "UI touched while Fragment detached, view destroyed or activity dead" }
            applied += outcome
        }
    }

    private fun gateFor(ui: FakeUi) = AutoStartSyncUiGate { ui.available() }

    @Test
    fun `detached fragment with live activity must not touch ui`() {
        val ui = FakeUi().apply { attached = false }
        assertFalse(gateFor(ui).canTouchUi())
    }

    @Test
    fun `destroyed view or activity must not touch ui`() {
        val ui = FakeUi().apply { viewAlive = false }
        assertFalse(gateFor(ui).canTouchUi())
        ui.viewAlive = true
        ui.activityAlive = false
        assertFalse(gateFor(ui).canTouchUi())
    }

    @Test
    fun `attached fragment with live view and activity may touch ui`() {
        assertTrue(gateFor(FakeUi()).canTouchUi())
    }

    @Test
    fun `failure result after leaving page is dropped but tap state is finished`() {
        val ui = FakeUi()
        val gate = gateFor(ui)
        val taps = AutoStartTapState()
        val target = taps.next(true)
        val generation = taps.generation
        ui.attached = false

        val applied = gate.finishResult(generation, taps)
        assertFalse(applied)
        if (applied) ui.apply(AutoStartSync.Outcome.Failed("root_required"))
        assertTrue(ui.applied.isEmpty())

        // 结果已裁决：pending 被清理，再切一次按当前用户动作取值，不被旧结果反转。
        assertEquals(true, taps.next(true))
    }

    @Test
    fun `stale result never applies even when ui is available`() {
        val ui = FakeUi()
        val gate = gateFor(ui)
        val taps = AutoStartTapState()
        taps.next(true)
        val oldGeneration = taps.generation
        taps.next(true)

        assertFalse(gate.finishResult(oldGeneration, taps))
        assertTrue(ui.applied.isEmpty())
    }

    @Test
    fun `current failure result applies on a live page`() {
        val ui = FakeUi()
        val gate = gateFor(ui)
        val taps = AutoStartTapState()
        taps.next(true)
        val generation = taps.generation

        assertTrue(gate.finishResult(generation, taps))
        ui.apply(AutoStartSync.Outcome.Failed("root_required"))
        assertEquals(listOf(AutoStartSync.Outcome.Failed("root_required")), ui.applied)
    }
}
