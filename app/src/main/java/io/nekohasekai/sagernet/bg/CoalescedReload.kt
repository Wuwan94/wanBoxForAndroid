// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/** One bounded pending edit; an in-flight atomic apply is never cancelled by another edit. @author 雾晚 */
internal class CoalescedReload(
    scope: CoroutineScope,
    private val windowMs: Long = 400,
    private val apply: suspend () -> Unit,
    private val onError: (Exception) -> Unit,
) {
    private val pending = PendingReloadBatch(onError)
    private val worker: Job
    init {
        require(windowMs > 0)
        worker = scope.launch {
            try {
                while (pending.receive()) {
                    // Quiet time starts again after every edit. No timer runs when idle.
                    while (!pending.takeUrgency()) {
                        if (withTimeoutOrNull(windowMs) { pending.receive() } == null) break
                    }
                    val batchOnError = pending.closeBatch()
                    try { apply() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { batchOnError(e) }
                }
            } finally { pending.cancel() }
        }
        // Cancellation may happen before the coroutine body starts (and its finally
        // runs). Close the channel on Job completion as well; reject orphan requests.
        worker.invokeOnCompletion { pending.cancel() }
    }

    fun request(immediate: Boolean = false, onError: ((Exception) -> Unit)? = null): Boolean {
        if (!worker.isActive) return false
        return pending.request(immediate, onError)
    }
}
