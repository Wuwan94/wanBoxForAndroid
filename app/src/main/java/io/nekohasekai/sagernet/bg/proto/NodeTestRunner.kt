// @author 雾晚
package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/**
 * Limits entire probe lifetimes, including suspended DNS/startup/retry/cleanup.
 * limitedParallelism alone limits executing threads, not suspended probe counts.
 * @author 雾晚
 */
@OptIn(ExperimentalCoroutinesApi::class)
object NodeTestRunner {
    const val DEFAULT_URL = "http://connectivitycheck.gstatic.com/generate_204"
    const val CONCURRENCY = 4
    /** 与高级设置「测速超时时间」同一份设置，单节点与批量延迟测试不再各用一套。 */
    const val DEFAULT_TIMEOUT_MS = 8000
    /** 仅覆盖启动临时核心/插件等准备阶段，不计入用户配置的网络预算。 */
    private const val PREPARE_BUDGET_MS = 5000L
    private val dispatcher = Dispatchers.IO.limitedParallelism(CONCURRENCY)
    private val permits = Semaphore(CONCURRENCY)

    /**
     * 读取唯一超时设置 `speedTestTimeoutMs`。
     * JVM 单测没有 Application 上下文时回退到默认值，避免测试依赖 Android 运行时。
     * @author 雾晚
     */
    fun resolveTimeoutMs(): Int = runCatching { DataStore.speedTestTimeoutMs }
        .getOrDefault(DEFAULT_TIMEOUT_MS)
        .coerceIn(1_000, 60_000)

    suspend fun measure(
        url: String = DEFAULT_URL,
        timeoutMs: Int = resolveTimeoutMs(),
        retryable: (Exception) -> Boolean = { true },
        attempt: suspend (String, Int) -> Int,
    ): Int = permits.withPermit {
        withContext(dispatcher) {
            try {
                measured(url, timeoutMs, attempt)
            } catch (cancel: CancellationException) {
                // Parent cancellation is terminal; a temporary plugin's child-scope failure can retry.
                currentCoroutineContext().ensureActive()
                val cause = cancel.cause as? Exception ?: throw cancel
                if (!retryable(cause)) throw cause
                delay(300)
                measured(url, timeoutMs, attempt)
            } catch (error: Exception) {
                if (!retryable(error)) throw error
                delay(300)
                measured(url, timeoutMs, attempt)
            }
        }
    }

    private suspend fun measured(
        url: String,
        timeoutMs: Int,
        attempt: suspend (String, Int) -> Int,
    ): Int {
        // Convert only our own per-attempt timeout to a retryable failure; preserve parent cancellation.
        val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMs + PREPARE_BUDGET_MS) {
            attempt(url, timeoutMs)
        } ?: throw IOException("节点测速准备或请求超时")
        if (result <= 0) throw IOException("节点测速未返回有效响应")
        return result
    }
}
