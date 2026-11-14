// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * @author 雾晚：应用级串行命令控制器。
 *
 * - 接收 applicationScope，在其中 launch 执行，页面销毁不取消
 * - 原子 tryLock：第二次提交直接拒绝，不排队
 * - Pending 赋值与加锁原子完成
 * - finally 解锁，取消时重置为 Idle（可继续提交）
 * - UI 只调用 requestX，不捕获 Fragment/views/context
 * - 直接 await RootModuleClient，不经 SagerNet.startService (它只 launch 返回)
 * - STOP 明确检查 phase==stopped，starting/failed 不算已停止
 * - 结果携带确认的 Status，UI 更新真实状态
 */
class ServiceCommandController(
    private val applicationScope: CoroutineScope,
    private val backend: Backend = RealBackend(),
) {
    /** 命令类型 */
    enum class CommandKind { START, STOP, MODE, NODE }

    /** 命令状态 */
    sealed class CommandState {
        data object Idle : CommandState()
        data class Pending(val kind: CommandKind, val generation: Long) : CommandState()
        data class ConfirmationRequired(val generation: Long, val warning: OutboundReferenceWarning) : CommandState()
        data class Confirmed(val kind: CommandKind, val generation: Long, val status: RootModuleClient.Status) : CommandState()
        data class Error(val kind: CommandKind, val generation: Long, val message: String) : CommandState()
        data object Cancelled : CommandState()
    }

    /** 后端接口，可注入 fake 测试 */
    interface Backend {
        suspend fun start(): RootModuleClient.Status
        suspend fun stop(): RootModuleClient.Status
        suspend fun setMode(mode: String): RootModuleClient.Status
        suspend fun selectNode(id: Long): RootModuleClient.Status
        suspend fun queryStatus(): RootModuleClient.Status
        suspend fun continueOutboundConfirmation(
            requestToken: String,
            startIfStopped: Boolean,
        ): RootModuleClient.Status = throw UnsupportedOperationException("outbound_confirmation_not_implemented")
        fun cancelOutboundConfirmation(requestToken: String) {}
    }

    /** 真实后端 */
    class RealBackend : Backend {
        override suspend fun start(): RootModuleClient.Status {
            RootModuleClient.startOrReload(startIfStopped = true)
            val s = waitFor({ it.connected }, 10000)
            // @author 雾晚：requestStart 自行校验 connected，不依赖 fake 抛异常
            if (!s.connected) throw IllegalStateException("start_not_confirmed")
            return s
        }
        override suspend fun continueOutboundConfirmation(requestToken: String, startIfStopped: Boolean): RootModuleClient.Status {
            RootModuleClient.continueOutboundWarning(requestToken, startIfStopped)
            return if (startIfStopped) waitFor({ it.connected }, 10000) else queryStatus()
        }
        override fun cancelOutboundConfirmation(requestToken: String) = RootModuleClient.cancelOutboundWarning(requestToken)

        override suspend fun stop(): RootModuleClient.Status {
            RootModuleClient.stop()
            // @author 雾晚：STOP 明确检查 phase==stopped，starting/failed 不算
            val s = waitFor({ it.phase == "stopped" }, 10000)
            if (s.phase != "stopped") throw IllegalStateException("stop_not_confirmed")
            return s
        }
        override suspend fun setMode(mode: String): RootModuleClient.Status {
            // @author 雾晚：通过模块 CLI 读取运行配置 secret，PATCH+GET 确认
            // 不用 UI 本地 secret，避免与后台失配
            val ok = patchClashModeViaModule(mode)
            if (!ok) throw IllegalStateException("mode_patch_failed")
            // 回读真实模式确认
            val s = queryStatus()
            return s
        }
        /**
         * @author 雾晚：经 Clash API 执行 PATCH /configs 并 GET 确认
         * 使用 DataStore.clashApiSecret（与配置生成时的 secret 一致），
         * 仅访问 127.0.0.1:9090，5s 超时。返回 true 仅当 PATCH 成功且 GET 确认模式一致。
         */
        private suspend fun patchClashModeViaModule(mode: String): Boolean {
            return try {
                val secret = io.nekohasekai.sagernet.database.DataStore.clashApiSecret
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(false)
                    .build()
                // PATCH 设置模式
                val body = "{\"mode\":\"$mode\"}"
                    .toRequestBody("application/json".toMediaType())
                val patchReq = okhttp3.Request.Builder()
                    .url("http://127.0.0.1:9090/configs")
                    .patch(body)
                    .header("Authorization", "Bearer $secret")
                    .build()
                val patchOk = client.newCall(patchReq).execute().use { it.isSuccessful }
                if (!patchOk) return false
                // GET 确认模式已生效（读回确认）
                val getReq = okhttp3.Request.Builder()
                    .url("http://127.0.0.1:9090/configs")
                    .header("Authorization", "Bearer $secret")
                    .build()
                val confirmedMode = client.newCall(getReq).execute().use { resp ->
                    if (!resp.isSuccessful) return false
                    // @author 雾晚：流式限额读取，防止大响应体 OOM
                    val bodyStr = resp.body?.source()?.let { source ->
                        val buffer = okio.Buffer()
                        var total = 0L
                        while (total < 4096) {
                            val read = source.read(buffer, 4096 - total)
                            if (read == -1L) break
                            total += read
                        }
                        buffer.readUtf8()
                    } ?: return false
                    org.json.JSONObject(bodyStr).optString("mode", null)
                }
                // 代次一致性：确认返回的模式与目标一致
                confirmedMode.equals(mode, ignoreCase = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        override suspend fun selectNode(id: Long): RootModuleClient.Status {
            val s = RootModuleClient.selectNode(id)
            // @author 雾晚：确认 connected && profileId==目标
            if (!s.connected || s.profileId != id) {
                throw IllegalStateException("node_not_confirmed")
            }
            return s
        }
        override suspend fun queryStatus(): RootModuleClient.Status {
            return RootModuleClient.call("status")
        }
        internal suspend fun waitFor(
            predicate: (RootModuleClient.Status) -> Boolean,
            timeoutMs: Long,
            readStatus: suspend () -> RootModuleClient.Status = { RootModuleClient.call("status") },
        ): RootModuleClient.Status {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                delay(500)
                val s = try {
                    readStatus()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } ?: continue
                if (predicate(s)) return s
                if (s.phase in setOf("failed", "cleanup_required", "disabled")) {
                    throw IllegalStateException(s.error.ifBlank {
                        if (s.phase == "disabled") "module_disabled_or_missing" else s.phase
                    })
                }
            }
            // @author 雾晚：超时直接 throw，不返回未确认的 last
            throw IllegalStateException("status_timeout")
        }
    }

    private val mutex = Mutex()
    private var generation = 0L

    private val _state = MutableStateFlow<CommandState>(CommandState.Idle)
    val state: StateFlow<CommandState> = _state.asStateFlow()

    init {
        applicationScope.coroutineContext[Job]?.invokeOnCompletion {
            val waiting = _state.value as? CommandState.ConfirmationRequired
            if (waiting != null) cancelOutboundConfirmation(waiting.warning.requestToken)
        }
    }

    /**
     * 尝试提交。若有命令执行中，直接返回 false（不排队）。
     * 任务在 applicationScope 中执行，调用方取消不影响。
     */
    fun trySubmit(kind: CommandKind, action: suspend Backend.() -> RootModuleClient.Status): Boolean {
        // 原子 tryLock
        if (!mutex.tryLock()) return false
        generation++
        val gen = generation
        _state.value = CommandState.Pending(kind, gen)

        var retainLock = false
        applicationScope.launch {
            try {
                val status = backend.action()
                // 仅当 generation 未变时提交（防旧回调覆盖）
                if (generation == gen) {
                    _state.value = CommandState.Confirmed(kind, gen, status)
                }
            } catch (e: RootModuleClient.OutboundReferenceWarningRequired) {
                retainLock = true
                _state.value = CommandState.ConfirmationRequired(gen, e.warning)
            } catch (e: CancellationException) {
                // @author 雾晚：取消时重置为 Idle，可继续提交
                if (generation == gen) {
                    _state.value = CommandState.Cancelled
                    // 延迟后回到 Idle
                    _state.value = CommandState.Idle
                }
                throw e
            } catch (e: Exception) {
                if (generation == gen) {
                    _state.value = CommandState.Error(kind, gen, e.message ?: "unknown")
                }
            }
        }.invokeOnCompletion { cause ->
            if (!retainLock) {
                if (cause is CancellationException && generation == gen) _state.value = CommandState.Idle
                mutex.unlock()
            }
        }
        return true
    }

    fun requestOutboundConfirmation(warning: OutboundReferenceWarning): Boolean {
        if (warning.requestToken.isBlank() || warning.issues.isEmpty()) return false
        if (!mutex.tryLock()) return false
        generation++
        _state.value = CommandState.ConfirmationRequired(generation, warning)
        return true
    }

    fun continueOutboundConfirmation(requestToken: String): Boolean {
        val waiting = _state.value as? CommandState.ConfirmationRequired ?: return false
        if (waiting.warning.requestToken != requestToken) return false
        val gen = waiting.generation
        val kind = CommandKind.START
        val pending = CommandState.Pending(kind, gen)
        if (!_state.compareAndSet(waiting, pending)) return false

        var retainLock = false
        applicationScope.launch {
            try {
                val status = backend.continueOutboundConfirmation(requestToken, waiting.warning.startIfStopped)
                if (generation == gen) {
                    _state.compareAndSet(pending, CommandState.Confirmed(kind, gen, status))
                }
            } catch (e: RootModuleClient.OutboundReferenceWarningRequired) {
                retainLock = true
                _state.compareAndSet(pending, CommandState.ConfirmationRequired(gen, e.warning))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == gen) {
                    _state.compareAndSet(pending, CommandState.Error(kind, gen, "outbound_confirmation_failed"))
                }
            }
        }.invokeOnCompletion { cause ->
            // Also runs when the scope was cancelled before the coroutine body started.
            if (cause is CancellationException && generation == gen) {
                _state.compareAndSet(pending, CommandState.Idle)
            }
            if (!retainLock) {
                try {
                    backend.cancelOutboundConfirmation(requestToken)
                } finally {
                    mutex.unlock()
                }
            }
        }
        return true
    }

    fun cancelOutboundConfirmation(requestToken: String): Boolean {
        val waiting = _state.value as? CommandState.ConfirmationRequired ?: return false
        if (waiting.warning.requestToken != requestToken) return false
        if (!_state.compareAndSet(waiting, CommandState.Idle)) return false
        try {
            backend.cancelOutboundConfirmation(requestToken)
        } finally {
            mutex.unlock()
        }
        return true
    }

    /** UI 请求启动（不捕获 Fragment/views） */
    fun requestStart(): Boolean = trySubmit(CommandKind.START) {
        backend.start().also { if (!it.connected) throw IllegalStateException(it.error.ifBlank { "start_not_confirmed" }) }
    }

    /** UI 请求停止 */
    fun requestStop(): Boolean = trySubmit(CommandKind.STOP) {
        backend.stop().also { if (it.phase != "stopped") throw IllegalStateException(it.error.ifBlank { "stop_not_confirmed" }) }
    }

    /** UI 请求切换模式：经 RealBackend.setMode 做 PATCH+GET 确认 */
    fun requestMode(mode: String): Boolean =
        trySubmit(CommandKind.MODE) {
            backend.setMode(mode)
        }

    /** UI 请求切换节点：确认返回 connected && profileId==目标 */
    fun requestNode(id: Long): Boolean = trySubmit(CommandKind.NODE) {
        val s = backend.selectNode(id)
        if (!s.connected || s.profileId != id) {
            throw IllegalStateException("node_not_confirmed")
        }
        s
    }

    /** 确认已处理，回到 Idle */
    fun acknowledge() {
        val s = _state.value
        if (s is CommandState.Confirmed || s is CommandState.Error || s is CommandState.Cancelled) {
            _state.value = CommandState.Idle
        }
    }

    fun isBusy(): Boolean = when (_state.value) {
        is CommandState.Pending, is CommandState.ConfirmationRequired -> true
        else -> false
    }
}
