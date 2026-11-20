// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开机自启一致性的行为契约：模块回读确认之后才落库；失败/超时/结果未知都保持
 * 原值；快速连续切换由最后一次选择落地，旧请求的迟到结果不得覆盖它。
 */
class AutoStartSyncTest {
    private class Host(
        var moduleValue: Boolean = false,
        var confirmedValue: Boolean = false,
    ) : AutoStartSync.Host {
        val writes = mutableListOf<Boolean>()
        var failWrites = 0
        var failReads = 0

        /** 命令成功但模块没有真的写入（模拟“结果未知/未生效”）。 */
        var ignoreWrites = false

        /** 老模块没有回读字段。 */
        var noReadBack = false
        var beforeWrite: (suspend () -> Unit)? = null

        override suspend fun writeModule(enabled: Boolean) {
            beforeWrite?.invoke()
            if (failWrites > 0) {
                failWrites--
                throw IOException("root_required")
            }
            writes += enabled
            if (!ignoreWrites) moduleValue = enabled
        }

        override suspend fun readModule(): Boolean? {
            if (noReadBack) return null
            if (failReads > 0) {
                failReads--
                throw IOException("module_status_invalid")
            }
            return moduleValue
        }

        override fun confirmed(): Boolean = confirmedValue

        override fun persist(enabled: Boolean) {
            confirmedValue = enabled
        }
    }

    @Test
    fun `module confirmation is required before persisting`() = runTest {
        val host = Host()
        val sync = AutoStartSync(host)
        val outcome = sync.request(true)
        assertEquals(AutoStartSync.Outcome.Confirmed(true), outcome)
        assertEquals(listOf(true), host.writes)
        assertTrue(host.confirmedValue)
        assertEquals(true, host.moduleValue)
    }

    @Test
    fun `command failure keeps the confirmed value and reports the code`() = runTest {
        val host = Host(failWrites = 2)
        val sync = AutoStartSync(host)
        val outcome = sync.request(true)
        assertEquals(AutoStartSync.Outcome.Failed("root_required"), outcome)
        assertEquals(false, host.confirmedValue)
        assertEquals(false, host.moduleValue)
        assertEquals(0, host.writes.size)
    }

    @Test
    fun `a retry recovers a transient failure`() = runTest {
        val host = Host(failWrites = 1)
        val sync = AutoStartSync(host)
        val outcome = sync.request(true)
        assertEquals(AutoStartSync.Outcome.Confirmed(true), outcome)
        assertTrue(host.confirmedValue)
    }

    @Test
    fun `module that keeps the old value is not treated as applied`() = runTest {
        val host = Host(ignoreWrites = true)
        val sync = AutoStartSync(host)
        val outcome = sync.request(true)
        assertEquals(AutoStartSync.Outcome.Failed(AutoStartSync.CODE_NOT_APPLIED), outcome)
        assertEquals(false, host.confirmedValue)
        assertEquals(2, host.writes.size)
    }

    @Test
    fun `read-back failure is retried and then reported`() = runTest {
        val host = Host(failReads = 2)
        val sync = AutoStartSync(host)
        val outcome = sync.request(true)
        assertEquals(AutoStartSync.Outcome.Failed("module_status_invalid"), outcome)
        assertEquals(false, host.confirmedValue)
    }

    @Test
    fun `older module without read-back is applied on command success`() = runTest {
        val host = Host(noReadBack = true)
        val sync = AutoStartSync(host)
        val outcome = sync.request(true)
        assertEquals(AutoStartSync.Outcome.Confirmed(true), outcome)
        assertTrue(host.confirmedValue)
    }

    @Test
    fun `rapid toggles apply only the last choice`() = runTest {
        val host = Host()
        val gate = CompletableDeferred<Unit>()
        host.beforeWrite = { gate.await() }
        val sync = AutoStartSync(host)

        // UNDISPATCHED：两个请求都先同步跑到第一次挂起，顺序确定
        // （第一个卡在下发上，第二个登记新选择后排在锁上）。
        val first = async(start = CoroutineStart.UNDISPATCHED) { sync.request(true) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { sync.request(false) }
        gate.complete(Unit)

        assertTrue(first.await() is AutoStartSync.Outcome.Superseded)
        assertEquals(AutoStartSync.Outcome.Confirmed(false), second.await())
        assertEquals(false, host.confirmedValue)
        assertEquals(false, host.moduleValue)
        // 两次下发都发生了，但只有最后的选择被落库。
        assertEquals(listOf(true, false), host.writes)
    }

    @Test
    fun `check reports a module that diverged from the app`() = runTest {
        val host = Host(moduleValue = true, confirmedValue = false)
        val sync = AutoStartSync(host)
        assertEquals(AutoStartSync.Mismatch(true), sync.check())

        host.moduleValue = false
        assertNull(sync.check())

        host.noReadBack = true
        assertNull("older modules cannot be reconciled, not a mismatch", sync.check())
    }

    @Test
    fun `failed request leaves a diverged module visible instead of hiding it`() = runTest {
        val host = Host(moduleValue = true, confirmedValue = false)
        val sync = AutoStartSync(host)
        // 用户关掉自启：模块写入失败（root 被拒），App 侧保持 false。
        host.failWrites = 2
        val outcome = sync.request(false)
        assertEquals(AutoStartSync.Outcome.Failed("root_required"), outcome)
        assertEquals(false, host.confirmedValue)
        assertEquals(true, host.moduleValue)
        // 下一次打开设置页会显示“待同步”，而不是假装已经关掉。
        assertEquals(AutoStartSync.Mismatch(true), sync.check())
    }
}
