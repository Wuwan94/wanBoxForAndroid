// @author 雾晚
package io.nekohasekai.sagernet.utils

import kotlinx.coroutines.*

/** Main-thread confined, one operation per view. The supplied view scope owns cancellation. */
internal class UpdateTask {
    private var running: Job? = null
    val isRunning: Boolean get() = running?.isActive == true

    fun launch(scope: CoroutineScope, action: suspend CoroutineScope.() -> Unit): Boolean {
        if (isRunning || !scope.isActive) return false
        // Install the new job before it can execute, so even an immediate dispatcher
        // cannot finish before the slot has been assigned.
        val job = scope.launch(start = CoroutineStart.LAZY, block = action)
        running = job
        job.start()
        return true
    }

    fun cancel() {
        val current = running
        running = null
        current?.cancel()
    }
}
