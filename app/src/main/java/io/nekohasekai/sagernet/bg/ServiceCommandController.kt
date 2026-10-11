// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * @author 雾晚：应用级串行命令控制器。
 * 替代 Fragment 局部门闩 + 5s 延时。
 *
 * 特性：
 * - 串行执行：重复点击拒绝/合并，不并发
 * - 可观察状态：pending/confirmed/error，UI 只读渲染
 * - 未终态回调不提前解锁：仅 confirmed/error 解锁
 * - 页面销毁不丢失：控制器属于 Application 生命周期
 * - 模式/节点/启停共用入口
 * - 取消异常重新抛出，不被吞掉
 */
object ServiceCommandController {

    /** 命令状态 */
    sealed class CommandState {
        /** 空闲 */
        data object Idle : CommandState()
        /** 执行中 */
        data class Pending(val kind: CommandKind, val generation: Long) : CommandState()
        /** 已确认 */
        data class Confirmed(val kind: CommandKind, val generation: Long) : CommandState()
        /** 失败 */
        data class Error(val kind: CommandKind, val generation: Long, val message: String) : CommandState()
    }

    /** 命令类型 */
    enum class CommandKind { START, STOP, MODE, NODE }

    private val mutex = Mutex()
    private var generation = 0L
    private var currentJob: Job? = null

    private val _state = MutableStateFlow<CommandState>(CommandState.Idle)
    /** UI 只读观察 */
    val state: StateFlow<CommandState> = _state.asStateFlow()

    /**
     * 提交命令。串行执行，重复命令合并。
     * @param kind 命令类型
     * @param action 实际执行逻辑，返回 true 表示后台已确认
     * @return 是否已提交（false 表示有命令正在执行，已拒绝）
     */
    suspend fun submit(kind: CommandKind, action: suspend (generation: Long) -> Boolean): Boolean {
        // 快速检查：有命令在执行则拒绝
        if (mutex.isLocked) return false
        return mutex.withLock {
            // 双重检查
            val current = _state.value
            if (current is CommandState.Pending) return@withLock false

            generation++
            val gen = generation
            _state.value = CommandState.Pending(kind, gen)
            try {
                val confirmed = action(gen)
                // 仅当 generation 未变化时提交（防旧回调覆盖）
                if (generation == gen) {
                    _state.value = if (confirmed) {
                        CommandState.Confirmed(kind, gen)
                    } else {
                        CommandState.Error(kind, gen, "not_confirmed")
                    }
                }
                // 返回是否确认（用于调用方判断）
                confirmed && generation == gen
            } catch (e: CancellationException) {
                // @author 雾晚：取消必须重新抛出
                throw e
            } catch (e: Exception) {
                if (generation == gen) {
                    _state.value = CommandState.Error(kind, gen, e.message ?: "unknown")
                }
                false
            } finally {
                // 延迟重置为 Idle，让 UI 有机会显示 Confirmed/Error
                // 不立即重置，避免闪烁
            }
        }
    }

    /** 重置为空闲（UI 已处理完结果后调用） */
    fun acknowledge() {
        val current = _state.value
        if (current is CommandState.Confirmed || current is CommandState.Error) {
            _state.value = CommandState.Idle
        }
    }

    /** 是否有命令执行中 */
    fun isBusy(): Boolean = _state.value is CommandState.Pending
}
