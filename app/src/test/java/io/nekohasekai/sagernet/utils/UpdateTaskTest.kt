package io.nekohasekai.sagernet.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateTaskTest {
    @Test fun duplicateClicksRunOnceAndNextOperationCanStart() = runTest {
        val tasks = UpdateTask()
        var started = 0
        val gate = CompletableDeferred<Unit>()
        repeat(5) { tasks.launch(this) { started++; gate.await() } }
        runCurrent()
        assertEquals(1, started)
        gate.complete(Unit); runCurrent()
        tasks.launch(this) { started++ }; runCurrent()
        assertEquals(2, started)
    }
    @Test fun destroyingViewCancelsTaskAndSuppressesLateUiResult() = runTest {
        val viewJob = Job()
        val scope = CoroutineScope(coroutineContext + viewJob)
        var uiCalls = 0
        val started = CompletableDeferred<Unit>()
        val late = CompletableDeferred<Unit>()
        UpdateTask().launch(scope) { started.complete(Unit); late.await(); uiCalls++ }
        runCurrent(); started.await()
        viewJob.cancel(); late.complete(Unit); runCurrent()
        assertEquals(0, uiCalls)
    }
    @Test fun leavingPageCancelsAndSuppressesResultThenAllowsRetry() = runTest {
        val task = UpdateTask()
        var uiCalls = 0
        val gate = CompletableDeferred<Unit>()
        task.launch(this) { gate.await(); uiCalls++ }
        runCurrent()
        task.cancel(); gate.complete(Unit); runCurrent()
        assertEquals(0, uiCalls)
        task.launch(this) { uiCalls++ }; runCurrent()
        assertEquals(1, uiCalls)
    }
    @Test fun alreadyDestroyedViewDoesNotStartWork() = runTest {
        val job = Job().apply { cancel() }
        var calls = 0
        UpdateTask().launch(CoroutineScope(coroutineContext + job)) { calls++ }
        runCurrent(); assertEquals(0, calls)
    }
}
