// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开机自启一致性的行为契约：模块回读确认之后才落库；失败/超时/结果未知都保持
 * 原值；快速连续切换由最后一次选择落地，旧请求的迟到结果不得覆盖它。
 *
 * 第二轮复审（P2）新增：模块侧 boot 配置损坏/不可读时回读拿不到可用值。此时
 * 既不能落库，也不能显示为已生效；回到设置页要显示“待处理”，而不是开关状态。
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

        /** 模块侧 boot 配置损坏/不可读：回读没有可用值。 */
        var bootConfigError: String? = null
        var beforeWrite: (suspend () -> Unit)? = null

        /** 落库失败的注入点（磁盘满、Room 损坏）。 */
        var persistError: Exception? = null

        /** 落库前的挂钩：用来把“写库”卡住，验证点击不会被它拖住。 */
        var beforePersist: (() -> Unit)? = null

        override suspend fun writeModule(enabled: Boolean) {
            beforeWrite?.invoke()
            if (failWrites > 0) {
                failWrites--
                throw IOException("root_required")
            }
            writes += enabled
            if (!ignoreWrites) moduleValue = enabled
            // 真正写入成功意味着配置被重写，损坏状态随之消失。
            if (!ignoreWrites) bootConfigError = null
        }

        override suspend fun readModule(): AutoStartSync.ModuleState {
            if (failReads > 0) {
                failReads--
                throw IOException("module_status_invalid")
            }
            bootConfigError?.let { return AutoStartSync.ModuleState.Unavailable(it) }
            if (noReadBack) return AutoStartSync.ModuleState.Unsupported
            return AutoStartSync.ModuleState.Value(moduleValue)
        }

        override fun confirmed(): Boolean = confirmedValue

        override fun persist(enabled: Boolean) {
            beforePersist?.invoke()
            persistError?.let { throw it }
            confirmedValue = enabled
        }
    }

    @Test
    fun `a replacement settings observer sees pending and the confirmed value after detached completion`() = runTest {
        val host = Host()
        val writeArrived = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        host.beforeWrite = {
            writeArrived.complete(Unit)
            finishWrite.await()
        }
        val sync = AutoStartSync(host)
        val oldStates = mutableListOf<AutoStartSync.State>()
        val oldObserver = launch(start = CoroutineStart.UNDISPATCHED) { sync.state.toList(oldStates) }
        val ticket = sync.toggle()
        val operation = async { sync.apply(ticket) }
        writeArrived.await()
        oldObserver.cancelAndJoin()
        val newObserver = async(start = CoroutineStart.UNDISPATCHED) { sync.state.take(2).toList() }
        assertEquals(AutoStartSync.State.Pending(ticket.id, true), sync.state.value)
        assertEquals(false, host.confirmedValue)

        finishWrite.complete(Unit)
        val settled = AutoStartSync.State.Finished(ticket.id, AutoStartSync.Outcome.Confirmed(true))
        assertEquals(AutoStartSync.Outcome.Confirmed(true), operation.await())
        assertEquals(listOf(AutoStartSync.State.Pending(ticket.id, true), settled), newObserver.await())
        assertEquals(settled, sync.state.value)
        assertEquals(false, oldStates.any { it is AutoStartSync.State.Finished })
        assertEquals(true, host.confirmedValue)
        assertNull(sync.check())
    }

    @Test
    fun `while unobserved a failed request retains its result and clears the pending toggle`() = runTest {
        val host = Host().apply { failWrites = 2 }
        val sync = AutoStartSync(host)
        val ticket = sync.toggle()
        val result = sync.apply(ticket)
        assertTrue(result is AutoStartSync.Outcome.Failed)
        assertEquals(AutoStartSync.State.Finished(ticket.id, result), sync.state.value)
        assertEquals(false, host.confirmedValue)
        assertEquals(true, sync.toggle().enabled)
    }

    @Test
    fun `a return and rapid second tap invert shared pending intent without publishing the old result`() = runTest {
        val host = Host()
        val sync = AutoStartSync(host)
        val first = sync.toggle()
        val last = sync.toggle()
        assertEquals(true, first.enabled)
        assertEquals(false, last.enabled)
        assertEquals(AutoStartSync.Outcome.Superseded, sync.apply(first))
        assertEquals(AutoStartSync.State.Pending(last.id, false), sync.state.value)
        val outcome = sync.apply(last)
        assertEquals(AutoStartSync.Outcome.Confirmed(false), outcome)
        assertEquals(AutoStartSync.State.Finished(last.id, outcome), sync.state.value)
        assertEquals(listOf(false), host.writes)
    }

    @Test
    fun `registering before dispatch preserves tap order when IO starts in reverse`() = runTest {
        val host = Host()
        val sync = AutoStartSync(host)
        val first = sync.register(true)
        val last = sync.register(false)
        assertEquals(AutoStartSync.Outcome.Confirmed(false), sync.apply(last))
        assertEquals(AutoStartSync.Outcome.Superseded, sync.apply(first))
        assertEquals(listOf(false), host.writes)
        assertEquals(false, host.confirmedValue)
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
        val host = Host().apply { failWrites = 2 }
        val sync = AutoStartSync(host)
        val outcome = sync.request(true)
        assertEquals(AutoStartSync.Outcome.Failed("root_required"), outcome)
        assertEquals(false, host.confirmedValue)
        assertEquals(false, host.moduleValue)
        assertEquals(0, host.writes.size)
    }

    @Test
    fun `a retry recovers a transient failure`() = runTest {
        val host = Host().apply { failWrites = 1 }
        val sync = AutoStartSync(host)
        val outcome = sync.request(true)
        assertEquals(AutoStartSync.Outcome.Confirmed(true), outcome)
        assertTrue(host.confirmedValue)
    }

    @Test
    fun `module that keeps the old value is not treated as applied`() = runTest {
        val host = Host().apply { ignoreWrites = true }
        val sync = AutoStartSync(host)
        val outcome = sync.request(true)
        assertEquals(AutoStartSync.Outcome.Failed(AutoStartSync.CODE_NOT_APPLIED), outcome)
        assertEquals(false, host.confirmedValue)
        assertEquals(2, host.writes.size)
    }

    @Test
    fun `read-back failure is retried and then reported`() = runTest {
        val host = Host().apply { failReads = 2 }
        val sync = AutoStartSync(host)
        val outcome = sync.request(true)
        assertEquals(AutoStartSync.Outcome.Failed("module_status_invalid"), outcome)
        assertEquals(false, host.confirmedValue)
    }

    @Test
    fun `older module without read-back is applied on command success`() = runTest {
        val host = Host().apply { noReadBack = true }
        val sync = AutoStartSync(host)
        val outcome = sync.request(true)
        assertEquals(AutoStartSync.Outcome.Confirmed(true), outcome)
        assertTrue(host.confirmedValue)
    }

    /** 复审 P2：模块配置损坏时命令“成功”也不得落库成已生效。 */
    @Test
    fun `corrupt module boot setting is never persisted as applied`() = runTest {
        val host = Host().apply {
            bootConfigError = RootModuleErrors.BootSettingsInvalid
            ignoreWrites = true
        }
        val sync = AutoStartSync(host)
        val outcome = sync.request(true)
        assertEquals(AutoStartSync.Outcome.Failed(RootModuleErrors.BootSettingsInvalid), outcome)
        assertEquals(false, host.confirmedValue)
        assertEquals(2, host.writes.size)
    }

    /** 用户再切一次开关就是修复路径：写成功后回读拿到可用值并落库。 */
    @Test
    fun `toggling again repairs a corrupt module setting`() = runTest {
        val host = Host().apply { bootConfigError = RootModuleErrors.BootSettingsUnreadable }
        val sync = AutoStartSync(host)
        assertEquals(AutoStartSync.Outcome.Confirmed(true), sync.request(true))
        assertTrue(host.confirmedValue)
        assertNull(host.bootConfigError)
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
        assertEquals(AutoStartSync.Check.Mismatch(true), sync.check())

        host.moduleValue = false
        assertNull(sync.check())

        host.noReadBack = true
        assertNull("older modules cannot be reconciled, not a mismatch", sync.check())
    }

    /** 复审 P2：配置损坏不是“待同步”，而是“待处理”——本次开机会不自启。 */
    @Test
    fun `check surfaces a corrupt module boot setting`() = runTest {
        val host = Host(moduleValue = true, confirmedValue = true)
            .apply { bootConfigError = RootModuleErrors.BootSettingsInvalid }
        val sync = AutoStartSync(host)
        assertEquals(
            AutoStartSync.Check.Unavailable(RootModuleErrors.BootSettingsInvalid),
            sync.check()
        )
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
        assertEquals(AutoStartSync.Check.Mismatch(true), sync.check())
    }

    /**
     * 复审 P2：落库（磁盘满、Room 损坏）抛异常时不能把界面永久停在 Pending，也不能
     * 让异常冒到应用级作用域把进程带走——必须收口成一条可见的失败。
     */
    @Test
    fun `a failed store write reports a visible failure instead of a permanent pending`() = runTest {
        val host = Host().apply { persistError = IOException("disk_full") }
        val sync = AutoStartSync(host)
        val ticket = sync.toggle()
        val outcome = sync.apply(ticket)
        assertEquals(AutoStartSync.Outcome.Failed("disk_full"), outcome)
        assertEquals(AutoStartSync.State.Finished(ticket.id, outcome), sync.state.value)
        assertEquals(false, host.confirmedValue)
    }

    /**
     * 复审 P2：结果被视图呈现过后必须回到 Idle，否则下次进入设置页会把上一次的失败
     * 摘要当成现状（第二天也一样）。
     */
    @Test
    fun `a displayed result is consumed and does not outlive its view`() = runTest {
        val host = Host().apply { failWrites = 2 }
        val sync = AutoStartSync(host)
        val ticket = sync.toggle()
        sync.apply(ticket)
        assertTrue(sync.state.value is AutoStartSync.State.Finished)
        sync.consume(ticket.id)
        assertEquals(AutoStartSync.State.Idle, sync.state.value)
    }

    /** 只消费自己那条：旧视图的清屏动作不得干掉已经接管的新结果。 */
    @Test
    fun `consuming a stale result leaves the current one in place`() = runTest {
        val host = Host()
        val sync = AutoStartSync(host)
        val first = sync.toggle()
        assertEquals(AutoStartSync.Outcome.Confirmed(true), sync.apply(first))
        val second = sync.toggle()
        assertEquals(AutoStartSync.Outcome.Confirmed(false), sync.apply(second))
        sync.consume(first.id)
        assertEquals(
            AutoStartSync.State.Finished(second.id, AutoStartSync.Outcome.Confirmed(false)),
            sync.state.value,
        )
    }

    /**
     * 复审 P2：落库会做 Room 写与 `setComponentEnabledSetting` 的 IPC，MUST NOT 占着
     * 登记锁——否则主线程点一下要等 IO 线程把库写完（点击 → 掉帧）。
     */
    @Test
    fun `a tap never waits for the store write an in-flight apply is doing`() = runBlocking {
        val host = Host()
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        host.beforePersist = {
            writeEntered.countDown()
            releaseWrite.await(5, TimeUnit.SECONDS)
        }
        val sync = AutoStartSync(host)
        val ticket = sync.toggle()
        val applied = async(Dispatchers.Default) { sync.apply(ticket) }
        assertTrue("apply never reached the store write", writeEntered.await(5, TimeUnit.SECONDS))
        val tap = async(Dispatchers.Default) { sync.toggle() }
        val tapped = try {
            withTimeoutOrNull(2_000L) { tap.await() }
        } finally {
            releaseWrite.countDown()
        }
        assertNotNull("a tap must not queue behind the store write", tapped)
        applied.await()
    }
}
