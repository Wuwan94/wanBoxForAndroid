// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 开机自启开关的一致性协调器。
 *
 * 设置有两处持久点，只有同时一致才有意义：
 *  - 模块 `boot.json`（`wanboxctl autostart on/off`）决定开机时是否拉起核心；
 *  - `DataStore.persistAcrossReboot`（`isAutoConnect`）决定 App 的 BOOT_COMPLETED
 *    接收器与界面显示。
 *
 * 顺序固定为「下发 → 回读确认 → 落库」：命令退出码不是生效证据；回读不一致按
 * 失败处理并重试，最终失败时把已确认值写回、返回错误码，由界面恢复原状并给出
 * 中文提示，而不是留下“看起来已开启、模块里其实没开”的假象。
 *
 * 快速连续切换：每次请求先登记序号与新目标值；早到的请求在完成一次尝试后发现
 * 序号已变，就把落地权让给最新请求（Superseded），绝不拿旧请求的迟到结果覆盖
 * 用户最后的选择。
 *
 * 状态发布（`State`）只装 id / 目标值 / 结论，不含 View、Activity 与回调：离开
 * 设置页不会拖住视图，新视图也能重放进行中与最终状态。视图呈现完之后由
 * [consume] 清回 Idle，避免过期的失败一直挂在设置页。
 */
class AutoStartSync(
    private val host: Host,
    private val attempts: Int = 2,
) {
    /** 模块侧回读的三种结局：有效值 / 配置损坏不可用 / 老模块不支持回读。 */
    sealed class ModuleState {
        data class Value(val enabled: Boolean) : ModuleState()

        /** boot.json 损坏或读不出来：模块本次开机不会自启，这次回读没有可用值。 */
        data class Unavailable(val code: String) : ModuleState()

        /** 老模块没有 autoStart 字段：只能按命令成功处理。 */
        object Unsupported : ModuleState()
    }

    interface Host {
        /** 向模块下发设置（幂等，模块未运行时也应生效）；失败抛异常，错误码走 [RootModuleClient.safeError]。 */
        suspend fun writeModule(enabled: Boolean)

        /** 回读模块实际生效值。 */
        suspend fun readModule(): ModuleState

        /** App 侧已确认值（DataStore）。 */
        fun confirmed(): Boolean

        /** 把确认值落库（DataStore + BootReceiver），是 UI 与接收器的唯一数据源。 */
        fun persist(enabled: Boolean)
    }

    sealed class Outcome {
        data class Confirmed(val enabled: Boolean) : Outcome()

        /** 设置未生效：已回滚到原值，[code] 是稳定的错误码。 */
        data class Failed(val code: String) : Outcome()

        /** 更新的切换已接管，本次结果不落库也不改界面。 */
        object Superseded : Outcome()
    }

    /** 回到设置页的核对结果：不一致就提示待同步；模块配置损坏就提示待处理。 */
    sealed class Check {
        /** 回读到的模块值与 App 已确认值不一致：可见的“待同步”状态。 */
        data class Mismatch(val moduleValue: Boolean) : Check()

        /** 模块侧的 boot 配置损坏/不可读：本次开机不会自启，开关状态不可信。 */
        data class Unavailable(val code: String) : Check()
    }

    private val mutex = Mutex()
    private val registration = Any()
    private var sequence = 0L

    // Replayed to the current settings view; contains no reference to a view or callback.
    internal sealed interface State {
        data object Idle : State
        data class Pending(val id: Long, val enabled: Boolean) : State
        data class Finished(val id: Long, val outcome: Outcome) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    internal val state = mutableState.asStateFlow()

    internal data class Ticket(val id: Long, val enabled: Boolean)

    // Register at the caller, before IO dispatch can reorder two taps.
    internal fun register(enabled: Boolean): Ticket {
        val ticket = synchronized(registration) { Ticket(++sequence, enabled) }
        // 状态发布放在锁外：Main.immediate 的收集器可能在同一栈里内联跑 UI。
        mutableState.value = State.Pending(ticket.id, ticket.enabled)
        return ticket
    }

    /**
     * 登记一次「反向切换」。
     *
     * 锁内只留内存态（pending 与序号）：`host.confirmed()` 会走 Room，MUST 在锁外读——
     * 否则主线程的点击要排在 IO 线程的写事务后面（点击 → 掉帧）。点击只有主线程发起，
     * pending 在这里读一次就够。
     */
    internal fun toggle(): Ticket {
        val pending = mutableState.value as? State.Pending
        val enabled = if (pending != null) !pending.enabled else !host.confirmed()
        return register(enabled)
    }

    private fun isLatest(id: Long): Boolean = synchronized(registration) { sequence == id }

    /** 应用一次切换；结果由调用方在主线程消费。 */
    suspend fun request(enabled: Boolean): Outcome = apply(register(enabled))

    internal suspend fun apply(ticket: Ticket): Outcome {
        val (myId, target) = ticket
        return mutex.withLock {
            // 排队期间已有更新的选择：本次不落地，交给最新请求。
            if (!isLatest(myId)) return@withLock Outcome.Superseded
            var lastCode: String? = null
            var confirmedValue: Boolean? = null
            var remaining = attempts.coerceAtLeast(1)
            while (remaining > 0) {
                remaining--
                try {
                    host.writeModule(target)
                    when (val actual = host.readModule()) {
                        is ModuleState.Value -> if (actual.enabled == target) {
                            confirmedValue = target
                            break
                        } else {
                            lastCode = CODE_NOT_APPLIED
                        }
                        // 模块侧配置损坏/不可读：这次没有生效，按失败处理（不得落库）。
                        is ModuleState.Unavailable -> lastCode = actual.code
                        // 老模块没有回读字段：能力边界，命令成功即视为已生效。
                        ModuleState.Unsupported -> {
                            confirmedValue = target
                            break
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastCode = RootModuleClient.safeError(e)
                }
                if (!isLatest(myId)) return@withLock Outcome.Superseded
            }
            settle(myId, confirmedValue, lastCode)
        }
    }

    /**
     * 收尾：落库 + 发布结果。
     *
     * 锁只保护「谁还能落地」这一个判断（`sequence`）：`persist()` 会做 Room 写与
     * `setComponentEnabledSetting` 的 IPC，`mutableState.value` 的收集器又可能是
     * `Dispatchers.Main.immediate`（同一个栈里内联跑 UI 更新），两者都 MUST NOT 占着锁。
     *
     * 落库抛异常（磁盘满、Room 损坏）时 MUST 收口成一条可见的失败：界面不能永久停在
     * Pending，异常也不能冒到应用级作用域把进程带走。
     */
    private fun settle(id: Long, confirmedValue: Boolean?, lastCode: String?): Outcome {
        // 锁内只取一个结论：序号是否还属于本次请求。
        if (!isLatest(id)) return Outcome.Superseded
        val outcome = try {
            if (confirmedValue != null) {
                host.persist(confirmedValue)
                Outcome.Confirmed(confirmedValue)
            } else {
                // 失败/未知都保持 App 侧原来的确认值。
                host.persist(host.confirmed())
                Outcome.Failed(lastCode ?: CODE_UNCONFIRMED)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failed(RootModuleClient.safeError(e))
        }
        publish(id, outcome)
        return outcome
    }

    /**
     * 发布结果。用 StateFlow 自己的原子 update，而不是占着登记锁去写：只有「界面
     * 还停在这条请求的 Pending 上」才落地，所以落库期间被新点击接管的结果不会覆盖
     * 新的 pending，收集器也不会在锁内被叫醒。
     */
    private fun publish(id: Long, outcome: Outcome) {
        mutableState.update { current ->
            if ((current as? State.Pending)?.id == id) State.Finished(id, outcome) else current
        }
    }

    /**
     * 当前视图已经呈现过这条最终结果：回到 Idle。
     *
     * `Finished` 原来没有任何消费点，于是任何一次再进设置页都会把上一次的失败摘要
     * 当成现状显示（第二天也一样）。只消费匹配 id 的结果；更新的请求已经接管时不动它。
     */
    internal fun consume(id: Long) {
        mutableState.update { current ->
            if ((current as? State.Finished)?.id == id) State.Idle else current
        }
    }

    /**
     * 打开设置页或回到前台时核对模块与 App：返回非空表示需要提示——模块侧设置与
     * 用户已确认值不同（待同步），或模块侧 boot 配置损坏/不可读（待处理）。
     */
    suspend fun check(): Check? {
        val moduleState = try {
            host.readModule()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return when (moduleState) {
            is ModuleState.Value ->
                if (moduleState.enabled != host.confirmed()) Check.Mismatch(moduleState.enabled) else null
            is ModuleState.Unavailable -> Check.Unavailable(moduleState.code)
            ModuleState.Unsupported -> null
        }
    }

    companion object {
        /** 命令成功但回读不一致：模块还是旧值。 */
        const val CODE_NOT_APPLIED = "module_autostart_not_applied"

        /** 命令没有给出可用结论（超时/进程中断/协议不符）。 */
        const val CODE_UNCONFIRMED = "module_autostart_unconfirmed"
    }
}
