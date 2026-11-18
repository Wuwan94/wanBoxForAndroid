// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.proto.BalancerMemberStats
import io.nekohasekai.sagernet.fmt.BalancerLockState
import io.nekohasekai.sagernet.fmt.lockedMemberTag
import io.nekohasekai.sagernet.fmt.resolveSnapshotMemberId
import io.nekohasekai.sagernet.fmt.snapshotTagForMember
import io.nekohasekai.sagernet.ui.BalancerBannerMode
import io.nekohasekai.sagernet.ui.buildBalancerBanner
import org.junit.Assert.*
import org.junit.Test

/** B7b/O7 严格手动锁定 + 策略统计 + 主页横幅测试。@author 雾晚 */
class BalancerManualLockTest {

    // ---- 6.2 严格手动锁定：断连/超时不自动切，只有「恢复自动」解除 ----

    @Test fun lockThenDisconnectAndTimeoutStayLocked() {
        val locked = BalancerLockState.empty().lock(7L, 42L)
        assertTrue(locked.isLocked(7L))
        assertFalse(locked.shouldAutoSwitch(7L))
        assertEquals(42L, locked.lockedMember(7L)?.memberId)
        // 断连一次、超时一次：锁保持、失败计入统计、不自动切换。
        val afterDisconnect = locked.onFailure(7L)
        assertTrue(afterDisconnect.staysLocked)
        assertTrue(afterDisconnect.failureCounted)
        assertFalse(afterDisconnect.autoSwitchTriggered)
        val afterTimeout = locked.onFailure(7L)
        assertTrue(afterTimeout.staysLocked)
        assertFalse(afterTimeout.autoSwitchTriggered)
        assertTrue(locked.isLocked(7L))
        assertFalse(locked.shouldAutoSwitch(7L))
    }

    @Test fun resumeAutoRestoresAutomatic() {
        val locked = BalancerLockState.empty().lock(7L, 42L)
        val resumed = locked.resumeAuto(7L)
        assertFalse(resumed.isLocked(7L))
        assertTrue(resumed.shouldAutoSwitch(7L))
        assertNull(resumed.lockedMember(7L))
        // 恢复自动后失败照常允许自动切换（由核心 urltest/loadbalance 执行）。
        val outcome = resumed.onFailure(7L)
        assertFalse(outcome.staysLocked)
        assertTrue(outcome.failureCounted)
        assertTrue(outcome.autoSwitchTriggered)
    }

    @Test fun resumeAutoOnlyAffectsOneGroup() {
        val state = BalancerLockState.empty().lock(7L, 42L).lock(8L, 43L)
        val resumed = state.resumeAuto(7L)
        assertFalse(resumed.isLocked(7L))
        assertTrue(resumed.isLocked(8L))
        assertEquals(43L, resumed.lockedMember(8L)?.memberId)
        // 对未锁定的组点恢复自动是 no-op，不得影响其他组。
        assertEquals(2, state.resumeAuto(99L).locks.size)
    }

    @Test fun lockReplacesPreviousMember() {
        val state = BalancerLockState.empty().lock(7L, 42L).lock(7L, 43L)
        assertEquals(43L, state.lockedMember(7L)?.memberId)
        assertEquals(1, state.locks.size)
    }

    @Test fun invalidIdsIgnored() {
        val state = BalancerLockState.empty().lock(0L, 5L).lock(7L, 0L).lock(-1L, -2L)
        assertTrue(state.locks.isEmpty())
        assertEquals("", state.encode())
    }

    // ---- 锁定持久化编解码 ----

    @Test fun encodeDecodeRoundTrip() {
        val state = BalancerLockState.empty().lock(7L, 42L).lock(8L, 43L)
        assertEquals("7:42;8:43", state.encode())
        val restored = BalancerLockState.decode(state.encode())
        assertEquals(42L, restored.lockedMember(7L)?.memberId)
        assertEquals(43L, restored.lockedMember(8L)?.memberId)
        assertTrue(BalancerLockState.decode("").locks.isEmpty())
        assertTrue(BalancerLockState.decode(null).locks.isEmpty())
    }

    @Test fun decodeSkipsMalformedEntries() {
        val state = BalancerLockState.decode("7:42;bad;3:;4:x;5:6:7;;:;0:9;10:0; 11 : 12 ")
        assertEquals(42L, state.lockedMember(7L)?.memberId)
        assertEquals(12L, state.lockedMember(11L)?.memberId)
        assertEquals(2, state.locks.size)
    }

    @Test fun decodeDuplicateBalancerIdLastWins() {
        val state = BalancerLockState.decode("7:42;7:43")
        assertEquals(43L, state.lockedMember(7L)?.memberId)
        assertEquals(1, state.locks.size)
    }

    // ---- 6.2 锁定期间的失败 MUST 计入统计 ----

    @Test fun failuresCountedDuringLockAndResetOnSuccess() {
        val stats = BalancerMemberStats()
        val locked = BalancerLockState.empty().lock(7L, 42L)
        // 锁定期间两次失败：锁不动，统计累计。
        stats.recordFailure("g1", "HK-1", "timeout", nowMs = 1000L)
        assertTrue(locked.onFailure(7L).staysLocked)
        stats.recordFailure("g1", "HK-1", "timeout", nowMs = 2000L)
        assertEquals(2, stats.failures("g1", "HK-1"))
        assertEquals("timeout", stats.stat("g1", "HK-1")?.lastError)
        assertEquals(2000L, stats.stat("g1", "HK-1")?.lastCheckAtMs)
        // 无报错文本的失败不得清空上一次报错。
        stats.recordFailure("g1", "HK-1", null, nowMs = 3000L)
        assertEquals(3, stats.failures("g1", "HK-1"))
        assertEquals("timeout", stats.stat("g1", "HK-1")?.lastError)
        // 成功后清零失败计数、清掉旧报错、记下延迟。
        stats.recordSuccess("g1", "HK-1", 123, nowMs = 4000L)
        assertEquals(0, stats.failures("g1", "HK-1"))
        assertNull(stats.stat("g1", "HK-1")?.lastError)
        assertEquals(123, stats.stat("g1", "HK-1")?.lastLatencyMs)
        // 从未统计过的成员失败数为 0。
        assertEquals(0, stats.failures("g1", "no-such-node"))
        assertNull(stats.stat("g1", "no-such-node"))
    }

    @Test fun statsSnapshotIsPerGroup() {
        val stats = BalancerMemberStats()
        stats.recordFailure("g1", "B", "x", nowMs = 1L)
        stats.recordFailure("g1", "A", "x", nowMs = 1L)
        stats.recordFailure("g2", "A", "x", nowMs = 1L)
        val snapshot = stats.snapshot("g1")
        assertEquals(listOf("A", "B"), snapshot.map { it.member })
        assertEquals(1, stats.snapshot("g2").size)
        assertTrue(stats.snapshot("g3").isEmpty())
        stats.clear("g1")
        assertTrue(stats.snapshot("g1").isEmpty())
        assertEquals(1, stats.snapshot("g2").size)
    }

    // ---- 配置生成层：锁定成员取 tag（回落自动） ----

    @Test fun lockedMemberTagFoundAndMissingFallsBack() {
        val pairs = listOf(41L to "HK-1", 42L to "HK-2", 43L to "JP-1")
        val locked = BalancerLockState.empty().lock(7L, 42L)
        assertEquals("HK-2", lockedMemberTag(pairs, locked, 7L))
        // 锁定成员不在成员列表里（已删/改名）：返回 null，调用方回落自动生成，
        // 锁仍保留（横幅红字提示锁定节点不可用）。
        val stale = BalancerLockState.empty().lock(7L, 99L)
        assertNull(lockedMemberTag(pairs, stale, 7L))
        assertTrue(stale.isLocked(7L))
        // 未锁定：无 tag。
        assertNull(lockedMemberTag(pairs, BalancerLockState.empty(), 7L))
        // 另一组的锁不得串到本组。
        assertNull(lockedMemberTag(pairs, BalancerLockState.empty().lock(8L, 42L), 7L))
    }

    // ---- 快照 tag ↔ profile id 解析（统计页锁定按钮与横幅共用） ----

    @Test fun resolveSnapshotMemberIdPrefersProfileTagsThenName() {
        val tagToId = mapOf("HK-1" to 41L, "HK-2" to 42L)
        val names = mapOf(41L to "HK-1", 42L to "HK-2", 43L to "JP-1")
        // profileTags 反查优先（含重名加后缀的情况）。
        assertEquals(41L, resolveSnapshotMemberId("HK-1", tagToId, names))
        // 隔离链成员不在 profileTags 里：展示名精确匹配回退。
        assertEquals(43L, resolveSnapshotMemberId("JP-1", tagToId, names))
        // 两条都找不到：不得猜 id。
        assertNull(resolveSnapshotMemberId("no-such", tagToId, names))
        // 展示名重名（两个 id 同名）：不得猜，返回 null。
        val dupNames = mapOf(41L to "HK", 42L to "HK")
        assertNull(resolveSnapshotMemberId("HK", emptyMap(), dupNames))
    }

    @Test fun snapshotTagForMemberRequiresPresenceInGroup() {
        val tagsById = mapOf(41L to "HK-1", 42L to "HK-2")
        val names = mapOf(41L to "HK-1", 42L to "HK-2", 43L to "JP-1")
        val members = listOf("HK-1", "HK-2", "JP-1")
        assertEquals("HK-1", snapshotTagForMember(41L, tagsById, names, members))
        // 隔离链成员：profileTags 的 tag 不在本组时，用展示名回退。
        assertEquals("JP-1", snapshotTagForMember(43L, tagsById, names, members))
        // 锁定成员已不在本组：返回 null，调用方按“锁定节点不可用”处理。
        assertNull(snapshotTagForMember(41L, tagsById, names, listOf("HK-2")))
        assertNull(snapshotTagForMember(99L, tagsById, names, members))
    }

    // ---- 6.3 主页横幅：模式 / 承载节点 / 延迟，锁定不可用时红字标记 ----

    @Test fun bannerAutoShowsCarrierAndLatency() {
        val state = buildBalancerBanner(
            connected = true, isBalancer = true, strategy = "leastPing",
            lockedMemberName = null, currentMemberName = "HK-1", currentLatencyMs = 123,
            lockedAvailable = true, groupTag = "g1",
        )
        assertTrue(state.visible)
        assertEquals(BalancerBannerMode.AUTO, state.mode)
        assertEquals("leastPing", state.strategy)
        assertEquals("HK-1", state.carrierName)
        assertEquals(123, state.latencyMs)
        assertFalse(state.lockedUnavailable)
        assertEquals("g1", state.groupTag)
    }

    @Test fun bannerLockedShowsLockedCarrierNotCurrent() {
        val state = buildBalancerBanner(
            connected = true, isBalancer = true, strategy = "leastPing",
            lockedMemberName = "JP-1", currentMemberName = "HK-1", currentLatencyMs = 456,
            lockedAvailable = true, groupTag = "g1",
        )
        assertTrue(state.visible)
        assertEquals(BalancerBannerMode.LOCKED, state.mode)
        // 锁定时承载节点是锁定成员，不是快照当前最优。
        assertEquals("JP-1", state.carrierName)
        assertEquals(456, state.latencyMs)
        assertFalse(state.lockedUnavailable)
    }

    @Test fun bannerLockedUnavailableFlagsRed() {
        val state = buildBalancerBanner(
            connected = true, isBalancer = true, strategy = "leastPing",
            lockedMemberName = "JP-1", currentMemberName = "HK-1", currentLatencyMs = null,
            lockedAvailable = false, groupTag = "g1",
        )
        assertTrue(state.visible)
        assertEquals(BalancerBannerMode.LOCKED, state.mode)
        assertTrue(state.lockedUnavailable)
        // 未锁定时 lockedAvailable=false 不得误标红。
        val auto = buildBalancerBanner(
            connected = true, isBalancer = true, strategy = "leastPing",
            lockedMemberName = null, currentMemberName = "HK-1", currentLatencyMs = 123,
            lockedAvailable = false, groupTag = "g1",
        )
        assertFalse(auto.lockedUnavailable)
    }

    @Test fun bannerHiddenWhenNotConnectedOrNotBalancer() {
        val notConnected = buildBalancerBanner(
            connected = false, isBalancer = true, strategy = "leastPing",
            lockedMemberName = "JP-1", currentMemberName = "HK-1", currentLatencyMs = 123,
            lockedAvailable = true, groupTag = "g1",
        )
        assertFalse(notConnected.visible)
        val notBalancer = buildBalancerBanner(
            connected = true, isBalancer = false, strategy = null,
            lockedMemberName = null, currentMemberName = "HK-1", currentLatencyMs = 123,
            lockedAvailable = true, groupTag = null,
        )
        assertFalse(notBalancer.visible)
    }

    // ---- 接线（源码级契约，完整行为需 CI/真机验证） ----

    @Test fun configBuilderUsesLockedSelectorBranch() {
        val source = java.io.File(
            "src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt"
        ).readText()
        // 锁定时该策略组生成 selector（default = 锁定成员），核心不再自动切换；
        // 导出/测试配置不带运行时锁定（与 useAutoSelect 同一守卫）。
        assertTrue(source.contains("BalancerManualLock.decode(DataStore.balancerManualLock)"))
        assertTrue(source.contains("lockedMemberTag("))
        assertTrue(source.contains("if (lockedTag != null) {"))
        assertTrue(source.contains("buildSelectorOutbound(defaultTag = lockedTag"))
        // 锁定与层级回切 MUST NOT 默认断开现有连接：全仓不得出现把
        // interrupt_exist_connections 写死为 true 的字面量。
        assertFalse(source.contains("interrupt_exist_connections = true"))
    }

    @Test fun dataStorePersistsLockState() {
        val source = java.io.File(
            "src/main/java/io/nekohasekai/sagernet/database/DataStore.kt"
        ).readText()
        assertTrue(source.contains("balancerManualLock"))
        assertTrue(source.contains("configurationStore.string(\"balancerManualLock\")"))
    }

    @Test fun statsPageWiresLockStatsAndCheckAll() {
        val source = java.io.File(
            "src/main/java/io/nekohasekai/sagernet/ui/PolicyGroupsActivity.kt"
        ).readText()
        // 一键全检：整组测速的每次结果都记入统计（成功记延迟、失败记失败数与报错）。
        assertTrue(source.contains("R.string.policy_check_all"))
        assertTrue(source.contains("BalancerStats.store.recordSuccess"))
        assertTrue(source.contains("BalancerStats.store.recordFailure"))
        // 成员行展示失败数与最近报错；锁定行提供「恢复自动」。
        assertTrue(source.contains("R.string.policy_stats_failures"))
        assertTrue(source.contains("R.string.policy_stats_last_error"))
        assertTrue(source.contains("R.string.balancer_resume_auto"))
        // 锁定/解锁走 BalancerLockState，落盘后重建配置生效。
        assertTrue(source.contains("BalancerLockState.decode(DataStore.balancerManualLock)"))
        assertTrue(source.contains("SagerNet.reloadService()"))
        // 锁定成员不可用时红字提示。
        assertTrue(source.contains("R.string.balancer_locked_unavailable"))
        assertTrue(source.contains("R.color.connect_error_fill"))
    }

    @Test fun dashboardWiresBannerToStatsPage() {
        val source = java.io.File(
            "src/main/java/io/nekohasekai/sagernet/ui/DashboardFragment.kt"
        ).readText()
        assertTrue(source.contains("buildBalancerBanner("))
        assertTrue(source.contains("balancerBanner"))
        assertTrue(source.contains("PolicyGroupsActivity::class.java"))
        // 锁定不可用时横幅红字高亮。
        assertTrue(source.contains("R.color.connect_error_fill"))
    }

    @Test fun lockStringsExistInDefaultAndChinese() {
        val en = java.io.File("src/main/res/values/strings.xml").readText()
        val zh = java.io.File("src/main/res/values-zh-rCN/strings.xml").readText()
        for (name in listOf(
            "balancer_resume_auto",
            "balancer_lock_note",
            "balancer_lock_hint",
            "balancer_lock_member",
            "balancer_lock_confirm",
            "balancer_locked_summary",
            "balancer_locked_unavailable",
            "balancer_locked_short",
            "balancer_banner_auto",
            "balancer_banner_locked",
            "policy_check_all",
            "policy_stats_failures",
            "policy_stats_last_error",
            "policy_lock_applied",
            "policy_resume_applied",
        )) {
            assertTrue("missing string $name in values/strings.xml", en.contains("name=\"$name\""))
            assertTrue("missing string $name in values-zh-rCN/strings.xml", zh.contains("name=\"$name\""))
        }
    }
}
