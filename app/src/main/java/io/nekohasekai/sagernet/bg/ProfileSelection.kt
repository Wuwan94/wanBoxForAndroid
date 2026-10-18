// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Click order is owned by one bounded queue, never racing per-row coroutines. @author 雾晚 */
internal class ProfileSelection(scope: CoroutineScope, save: suspend (Long) -> Unit, private val onError: (Exception) -> Unit) {
    private class Request(val id: Long, val onComplete: ((Result<Unit>) -> Unit)?)
    private val requests = Channel<Request>(Channel.CONFLATED, onUndeliveredElement = { request ->
        complete(request, Result.failure(CancellationException("Selection superseded or owner stopped")))
    })
    private val worker = scope.launch {
        for (request in requests) {
            val result = try { save(request.id); Result.success(Unit) }
            catch (e: CancellationException) {
                complete(request, Result.failure(e))
                throw e
            }
            catch (e: Exception) { onError(e); Result.failure(e) }
            complete(request, result)
        }
    }.also { job -> job.invokeOnCompletion { requests.cancel() } }
    fun request(id: Long, onComplete: ((Result<Unit>) -> Unit)? = null): Boolean =
        id > 0 && worker.isActive && requests.trySend(Request(id, onComplete)).isSuccess

    private fun complete(request: Request, result: Result<Unit>) {
        try { request.onComplete?.invoke(result) }
        catch (e: Exception) { onError(e) }
    }
}
