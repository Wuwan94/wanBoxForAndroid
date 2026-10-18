// @author 雾晚
package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import io.nekohasekai.sagernet.ui.PendingProfileSelection
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Rapid UI clicks remain ordered and never cancel an atomic core apply. @author 雾晚 */
class ProfileSelectionTest {
    @Test fun rapidReturnToOriginalNodeWinsOverIntermediateClicks() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>(); val saved = mutableListOf<Long>()
        try {
            val selection = ProfileSelection(owner, save = { id ->
                saved += id
                if (saved.size == 1) { first.complete(Unit); release.await() } else done.complete(Unit)
            }, onError = { throw it })
            selection.request(1); withTimeout(2000) { first.await() }
            selection.request(2); selection.request(3); selection.request(1)
            release.complete(Unit); withTimeout(2000) { done.await() }
            assertEquals(listOf(1L, 1L), saved)
        } finally { owner.cancel(); owner.coroutineContext[Job]!!.join() }
    }
    @Test fun failedSelectionDoesNotKillQueueAndCancelledOwnerRejectsNewClicks() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val failed = CompletableDeferred<Unit>(); val recovered = CompletableDeferred<Unit>()
        val selection = ProfileSelection(owner, save = { id ->
            if (id == 1L) throw java.io.IOException("fixture_failure") else recovered.complete(Unit)
        }, onError = { failed.complete(Unit) })
        selection.request(1); withTimeout(2000) { failed.await() }
        assertFalse(selection.request(0)); selection.request(2); withTimeout(2000) { recovered.await() }
        owner.cancel(); owner.coroutineContext[Job]!!.join(); assertFalse(selection.request(3))
    }
    @Test fun selectionUsesModuleTruthAndAcknowledgementsDoNotUndoNewerIntent() {
        val app = File("src/main/java/io/nekohasekai/sagernet/SagerNet.kt").readText()
        // 失败时保持旧连接，不调用无意义的全量重载
        assertTrue(app.contains("keeping old connection"))
        val ui = File("src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
            .substringAfter("private fun selectProfile(proxyEntity: ProxyEntity)").substringBefore("private fun removeProfile")
        assertTrue(ui.contains("pf.selectProfileManually(proxyEntity.id)"))
        val manual = File("src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
            .substringAfter("private fun selectProfileManually(").substringBefore("private fun startProfileStateActor")
        assertTrue(manual.contains("cancelViewTests()"))
        assertTrue(manual.contains("SagerNet.selectProfile(profileId)"))
        assertTrue(manual.contains("pendingSelection.complete(request)"))
        assertFalse(manual.contains("DataStore.selectedProxy ="))
        assertFalse(ui.contains("reloadAccess")); assertFalse(ui.contains("BaseService.State.Stopped"))
        val observer = File("src/main/java/io/nekohasekai/sagernet/bg/RootTunService.kt").readText()
        assertTrue(observer.contains("if (changedProfile) data.binder.broadcast { it.cbSelectorUpdate(status.profileId) }"))
        assertTrue(observer.contains("before.profileId != DataStore.selectedProxy"))
        val callback = File("src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt").readText()
            .substringAfter("override fun cbSelectorUpdate(id: Long)").substringBefore("override fun onPreferenceDataStoreChanged")
        assertTrue(callback.contains("if (DataStore.serviceMode != Key.MODE_ROOT) DataStore.selectedProxy = id"))
        val dialog = File("src/main/java/io/nekohasekai/sagernet/ui/NodeSelectDialogActivity.kt").readText()
            .substringAfter("private fun selectNode(").substringBefore("private fun finishWithFade")
        assertTrue(dialog.contains("SagerNet.selectProfile(proxy.id)"))
        assertFalse(dialog.contains("DataStore.currentProfile ="))
        assertFalse(dialog.contains("DataStore.serviceState.started"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun slowRequestRemainsPendingUntilActualCompletion() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val state = PendingProfileSelection()
        var confirmed = 9L
        var completions = 0
        try {
            val selection = ProfileSelection(owner, save = { delay(6000); confirmed = it }, onError = { throw it })
            val request = state.begin(1)
            assertTrue(selection.request(1) { result ->
                assertTrue(result.isSuccess)
                completions++
                state.complete(request)
            })
            runCurrent()
            advanceTimeBy(4500); runCurrent()
            assertEquals(0, completions)
            assertEquals(9L, confirmed)
            assertEquals(1L, state.selectedOr(confirmed))
            advanceUntilIdle()
            assertEquals(1, completions)
            assertEquals(1L, state.selectedOr(confirmed))
        } finally { owner.cancel() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun failedAAndConflatedBDoNotCompleteLatestAIntent() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        val state = PendingProfileSelection()
        val saved = ArrayList<Long>()
        val completed = ArrayList<String>()
        var errors = 0
        try {
            val selection = ProfileSelection(owner, save = {
                saved += it
                if (saved.size == 1) { release.await(); throw java.io.IOException("fixture_failure") }
            }, onError = { errors++ })
            val first = state.begin(1)
            selection.request(1) {
                assertTrue(it.isFailure)
                assertFalse(state.complete(first))
                completed += "first-failed"
            }
            runCurrent()
            val middle = state.begin(2)
            selection.request(2) {
                assertTrue(it.exceptionOrNull() is CancellationException)
                assertFalse(state.complete(middle))
                completed += "middle-superseded"
            }
            val latest = state.begin(1)
            selection.request(1) {
                assertTrue(it.isSuccess)
                assertTrue(state.complete(latest))
                completed += "latest-success"
            }
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(1L, 1L), saved)
            assertEquals(listOf("middle-superseded", "first-failed", "latest-success"), completed)
            assertEquals(1, errors)
        } finally { owner.cancel() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun failedRequestReportsResultAndQueueCanRecover() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val results = ArrayList<Boolean>()
        try {
            val selection = ProfileSelection(owner, save = { if (it == 1L) error("fixture") }, onError = {})
            selection.request(1) { results += it.isSuccess }
            runCurrent()
            selection.request(2) { results += it.isSuccess }
            advanceUntilIdle()
            assertEquals(listOf(false, true), results)
            owner.cancel(); runCurrent()
            assertFalse(selection.request(3) { fail("Rejected request completed") })
        } finally { owner.cancel() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun externalClickCompletesSupersededUiRequestWithoutRunningIt() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        val state = PendingProfileSelection()
        val saved = ArrayList<Long>()
        try {
            val selection = ProfileSelection(owner, save = {
                saved += it
                if (it == 1L) release.await()
            }, onError = { throw it })
            selection.request(1)
            runCurrent()
            val ui = state.begin(2)
            selection.request(2) {
                assertTrue(it.exceptionOrNull() is CancellationException)
                assertTrue(state.complete(ui))
            }
            selection.request(3)
            assertEquals(9L, state.selectedOr(9))
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(1L, 3L), saved)
        } finally { owner.cancel() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun ownerCancellationCompletesActiveRequestWithoutReportingModuleFailure() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var completed = false
        var errors = 0
        val selection = ProfileSelection(owner, save = { awaitCancellation() }, onError = { errors++ })
        selection.request(1) { completed = it.exceptionOrNull() is CancellationException }
        runCurrent()
        owner.cancel()
        advanceUntilIdle()
        assertTrue(completed)
        assertEquals(0, errors)
        assertFalse(selection.request(2))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun observerExceptionDoesNotKillSelectionQueue() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val saved = ArrayList<Long>()
        var errors = 0
        try {
            val selection = ProfileSelection(owner, save = { saved += it }, onError = { errors++ })
            selection.request(1) { error("fixture observer failure") }
            runCurrent()
            selection.request(2)
            advanceUntilIdle()
            assertEquals(listOf(1L, 2L), saved)
            assertEquals(1, errors)
        } finally { owner.cancel() }
    }
}
