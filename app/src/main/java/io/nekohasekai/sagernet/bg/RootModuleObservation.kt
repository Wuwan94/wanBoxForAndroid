// @author 雾晚
package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.*

/** Foreground-bound status sampling must not wait for an Android network callback. @author 雾晚 */
internal object RootModuleObservation {
    /** phase2: delay after [failures] consecutive failed samples: 2s,4s,8s,16s, capped at 30s. */
    fun backoffMs(failures: Int, intervalMs: Long = 2000L): Long =
        if (failures <= 0) intervalMs else minOf(30_000L, intervalMs * (1L shl minOf(failures, 4)))

    suspend fun run(network: suspend () -> Unit, sample: suspend () -> Unit, intervalMs: Long = 2000,
                    isInteractive: () -> Boolean = { true }, failures: () -> Int = { 0 }) = coroutineScope {
        require(intervalMs > 0)
        val networkJob = launch(Dispatchers.IO) {
            try { network() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Module core observes its own physical network. */ }
        }
        try {
            while (isActive) {
                sample()
                // @author 雾晚: screen off -> back off to 10s; bound observers
                // get TTL-cached data, no need for aggressive polling.
                // phase2: consecutive status-read failures back off (2s→4s→8s→16s→30s) instead of
                // hammering su; a success resets to the normal cadence.
                val base = if (isInteractive()) intervalMs else 10000L
                delay(maxOf(base, backoffMs(failures(), intervalMs)))
            }
        } finally { networkJob.cancelAndJoin() }
    }
}
