// @author 雾晚
package io.nekohasekai.sagernet.utils

import kotlinx.coroutines.*

/** Main-thread confined, one operation per view. The supplied view scope owns cancellation. */
internal class UpdateTask {
    private var running: Job? = null
    fun launch(scope: CoroutineScope, action: suspend CoroutineScope.() -> Unit) {
        if (running?.isCompleted == false) return
        running = scope.launch(block = action)
    }
    fun cancel() { running?.cancel() }
}
