// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
    private val sequence = AtomicLong()
    private val desired = AtomicReference<Boolean?>(null)

    /** 应用一次切换；结果由调用方在主线程消费。 */
    suspend fun request(enabled: Boolean): Outcome {
        val myId = sequence.incrementAndGet()
        desired.set(enabled)
        return mutex.withLock {
            // 排队期间已有更新的选择：本次不落地，交给最新请求。
            if (sequence.get() != myId) return@withLock Outcome.Superseded
            var lastCode: String? = null
            var confirmedValue: Boolean? = null
            var remaining = attempts.coerceAtLeast(1)
            while (remaining > 0) {
                remaining--
                val target = desired.get() ?: enabled
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
                if (sequence.get() != myId) return@withLock Outcome.Superseded
            }
            if (sequence.get() != myId) return@withLock Outcome.Superseded
            if (confirmedValue != null) {
                host.persist(confirmedValue)
                Outcome.Confirmed(confirmedValue)
            } else {
                // 失败 / 超时 / 结果未知：保留已确认值，界面回滚；
                // 下一次连接或下一次切换会按 DataStore 的值重新下发。
                host.persist(host.confirmed())
                Outcome.Failed(lastCode ?: CODE_UNCONFIRMED)
            }
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
